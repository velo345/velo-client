package net.veloclient.velo.client.modules.hud;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.SpawnerBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.modules.qol.CinematicCameraModule;
import net.veloclient.velo.client.util.ModuleProfiler;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Remembers where your own chests/barrels/shulker boxes/spawners are so a base you built (and
 * lost track of - buried underground, or just forgotten after a break) is always findable again,
 * populated purely from block entities in chunks the client already has loaded right now (the
 * same data {@link net.veloclient.velo.client.modules.servertools.EntityCountOverlayModule} and
 * the light-level overlay already read - nothing is queried beyond what's already loaded, and
 * nothing is read from disk). Found containers are remembered across sessions so once a chunk has
 * ever been loaded with this on, that container stays listed even after the chunk unloads again -
 * that's what actually solves "I don't remember where my base is": you don't need to already know
 * where to look, you only need to have walked near it once, ever.
 *
 * <p>Every single found position (not just the handful nearest shown in the flat HUD list below)
 * gets a color-coded box outline (color says the type at a glance, and whether a cluster is
 * likely one base or several unrelated ones) plus exactly one real 3D tracer line straight from
 * that block to wherever the camera currently is (see {@link #drawTracerBeam} for why 1.21.11
 * gets a dotted-point fallback instead of the real thing there), so each one stays visible and
 * points the right way in full 3D regardless of where you're looking - not just a 2D HUD icon.
 */
public final class StorageFinderModule extends AbstractModule implements HudModule, Configurable {

	// Caps the small flat HUD text list only (a fixed-size on-screen panel can't show unlimited
	// rows anyway) - the in-world box/label/tracer for every single found container is NOT capped
	// to this, see onWorldRender.
	private static final int MAX_LISTED = 6;
	private static final int TRACER_POINT_COUNT = 18;
	private static final float TRACER_POINT_SIZE = 3f;
	// A full scan of every loaded chunk on every tick is exactly the kind of unthrottled per-tick
	// world walk the performance audit flagged elsewhere in this mod - containers don't move, so
	// re-checking a couple of times a second is already far more often than needed.
	private static final int SCAN_INTERVAL_TICKS = 40;

	private final HudPosition position = new HudPosition(0.02f, 0.5f);

	private boolean includeChests = true;
	private boolean includeBarrels = true;
	private boolean includeShulkers = true;
	private boolean includeEnderChests = false;
	private boolean includeSpawners = true;
	private boolean playSoundOnDiscovery = true;
	private boolean showCompassDirection = true;
	private boolean showTracerBeams = true;
	private boolean onlyShowNonEmpty = false;
	private int scanRadiusChunks = 6;
	private int minScanY = -64;
	private int maxScanY = 320;
	// Rendering box/label/tracer for literally every container ever found (no cap at all) got
	// expensive fast once a world had a few hundred remembered ones - nearest-first, capped, with
	// an explicit opt-out for anyone who really does want everything drawn at once.
	private int maxRendered = 100;
	private boolean unlimitedRendered = false;

	private Map<String, StorageEntry> found = Map.of();
	// Not persisted, not part of StorageEntry - just a live "did the last scan see anything in
	// it" cache, singleplayer-only (see hasContents). Absent key = unknown, treated as non-empty
	// so the "only show non-empty" filter never hides something it couldn't actually check.
	private final Map<String, Boolean> contentsCache = new java.util.HashMap<>();
	private boolean firstScanDone;
	private int ticksUntilScan;

	public StorageFinderModule() {
		super("storage-finder", "Storage Finder",
				"Remembers where your chests/barrels/shulker boxes/spawners are, from chunks you've already "
						+ "loaded, so you can always find your way back to a base you lost track of - color-coded box "
						+ "outlines plus a tracer beam toward the camera, and an optional Y-level range to only flag "
						+ "what's underground.",
				ModuleCategory.HUD, SafetyTag.CHECK_SERVER_RULES, false);
		ClientTickEvents.END_CLIENT_TICK.register(client ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.TICK, () -> onTick(client)));
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.WORLD_RENDER, () -> onWorldRender(context)));
	}

	@Override
	public void onEnable() {
		found = StorageFinderStore.load();
		firstScanDone = false;
		ticksUntilScan = 0;
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled() || client.player == null || client.world == null) {
			return;
		}
		if (ticksUntilScan-- > 0) {
			return;
		}
		ticksUntilScan = SCAN_INTERVAL_TICKS;
		scan(client);
	}

	private void scan(MinecraftClient client) {
		ClientWorld world = client.world;
		String dimension = world.getRegistryKey().getValue().toString();
		int centerX = client.player.getBlockX() >> 4;
		int centerZ = client.player.getBlockZ() >> 4;

		Set<String> confirmed = new HashSet<>();
		Set<Long> scannedChunks = new HashSet<>();
		boolean changed = false;

		for (int dz = -scanRadiusChunks; dz <= scanRadiusChunks; dz++) {
			for (int dx = -scanRadiusChunks; dx <= scanRadiusChunks; dx++) {
				int chunkX = centerX + dx;
				int chunkZ = centerZ + dz;
				if (!world.isChunkLoaded(chunkX, chunkZ)) {
					continue;
				}
				scannedChunks.add(chunkKey(chunkX, chunkZ));
				Map<BlockPos, BlockEntity> blockEntities = world.getChunk(chunkX, chunkZ).getBlockEntities();
				for (BlockPos pos : blockEntities.keySet()) {
					String kind = kindOf(pos, world);
					if (kind == null) {
						continue;
					}
					StorageEntry entry = new StorageEntry(dimension, kind, pos.getX(), pos.getY(), pos.getZ());
					confirmed.add(entry.key());
					if (!found.containsKey(entry.key())) {
						found.put(entry.key(), entry);
						changed = true;
						if (playSoundOnDiscovery && firstScanDone) {
							playDiscoverySound(client);
						}
					}
					if (isInventoryKind(kind)) {
						Boolean nonEmpty = hasContents(client, pos);
						if (nonEmpty != null) {
							contentsCache.put(entry.key(), nonEmpty);
						}
					}
				}
			}
		}

		// Self-healing: a known container whose chunk was actually re-checked this pass but
		// didn't show up anymore was broken/removed since it was last seen - drop it instead of
		// leaving a stale entry that points at nothing forever.
		List<String> stale = new ArrayList<>();
		for (StorageEntry entry : found.values()) {
			if (!entry.dimension().equals(dimension)) {
				continue;
			}
			// Narrowing the Y-level filter must never delete something that's still actually
			// there just because it's now outside the configured range - an entry this pass
			// didn't even consider (because of the Y filter) is left alone, not treated as
			// "confirmed missing".
			if (entry.y() < minScanY || entry.y() > maxScanY) {
				continue;
			}
			if (scannedChunks.contains(chunkKey(entry.x() >> 4, entry.z() >> 4)) && !confirmed.contains(entry.key())) {
				stale.add(entry.key());
			}
		}
		for (String key : stale) {
			found.remove(key);
			changed = true;
		}

		if (changed) {
			StorageFinderStore.save(found);
		}
		firstScanDone = true;
	}

	/** {@code Vec3d.squaredDistanceTo(Vec3d)} -> {@code Vec3.distanceToSqr(Vec3)} - same value, diverges by name only. */
	private static double squaredDistance(Vec3d a, Vec3d b) {
		//? if <26.1 {
		return a.squaredDistanceTo(b);
		//?} else {
		/*return a.distanceToSqr(b);
		*///?}
	}

	/** {@code Vec3d.multiply(double)} -> {@code Vec3.scale(double)} - same value, diverges by name only. */
	private static Vec3d scale(Vec3d vec, double factor) {
		//? if <26.1 {
		return vec.multiply(factor);
		//?} else {
		/*return vec.scale(factor);
		*///?}
	}

	/** {@code ChunkPos.toLong}/{@code pack} - name diverges between Yarn and Mojmap even though the packing itself is identical, so the exact method used doesn't matter beyond being a stable per-chunk key within one call to {@link #scan}. */
	private static long chunkKey(int chunkX, int chunkZ) {
		//? if <26.1 {
		return ChunkPos.toLong(chunkX, chunkZ);
		//?} else {
		/*return ChunkPos.pack(chunkX, chunkZ);
		*///?}
	}

	/**
	 * Both the method name ({@code playSoundClient} -> {@code playLocalSound}) and the sound
	 * constant ({@code ENTITY_EXPERIENCE_ORB_PICKUP} -> {@code EXPERIENCE_ORB_PICKUP}, Mojmap
	 * drops the Yarn category prefix) diverge between mappings here - same reasoning/verification
	 * as {@code KillEffectsModule}'s own duplicated {@code playSound} method for the two branches.
	 */
	private static void playDiscoverySound(MinecraftClient client) {
		//? if <26.1 {
		client.world.playSoundClient(client.player.getX(), client.player.getY(), client.player.getZ(),
				SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.6f, 1.4f, false);
		//?} else {
		/*client.world.playLocalSound(client.player.getX(), client.player.getY(), client.player.getZ(),
				SoundEvents.EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.6f, 1.4f, false);
		*///?}
	}

	// Takes the position and looks the block up itself (rather than a Block-typed parameter) so
	// this file never needs a bare, unqualified reference to the `Block` base class - `Block` is
	// a substring of several other real class names this codebase already FQN-swaps for the
	// 26.1+ Mojmap port (BlockState, BlockEntity, ...), and staying off it here avoids needing to
	// reason about that overlap at all.
	private String kindOf(BlockPos pos, ClientWorld world) {
		if (pos.getY() < minScanY || pos.getY() > maxScanY) {
			return null;
		}
		var block = world.getBlockState(pos).getBlock();
		if (includeChests && block instanceof ChestBlock) {
			return "Chest";
		}
		if (includeBarrels && block instanceof BarrelBlock) {
			return "Barrel";
		}
		if (includeShulkers && block instanceof ShulkerBoxBlock) {
			return "Shulker";
		}
		if (includeEnderChests && block instanceof EnderChestBlock) {
			return "Ender Chest";
		}
		if (includeSpawners && block instanceof SpawnerBlock) {
			return "Spawner";
		}
		return null;
	}

	/** Spawners have no inventory to check - only the container kinds are ever subject to the "only show non-empty" filter. */
	private static boolean isInventoryKind(String kind) {
		return !"Spawner".equals(kind);
	}

	/**
	 * Whether the container at {@code pos} actually has anything in it right now - reads the
	 * REAL, live block entity straight from the integrated server's own world object (same JVM,
	 * singleplayer only), not the client's copy, which vanilla deliberately never syncs
	 * inventory contents into until a player opens the container - exactly the anti-X-ray
	 * measure that makes "see chest contents from a live remote server" impossible for any
	 * legitimate client mod. Returns null (meaning: unknown, don't filter it out) whenever that
	 * real data isn't available - not singleplayer, or the server hasn't got that chunk loaded.
	 */
	private static Boolean hasContents(MinecraftClient client, BlockPos pos) {
		var serverWorld = currentServerWorld(client);
		if (serverWorld == null) {
			return null;
		}
		BlockEntity serverSideEntity = serverWorld.getBlockEntity(pos);
		return isNonEmptyInventory(serverSideEntity);
	}

	/** {@code instanceof Inventory} (Yarn) -> {@code instanceof Container} (Mojmap) - same "has an item inventory and it's non-empty" check, diverges by interface name only. */
	private static Boolean isNonEmptyInventory(BlockEntity blockEntity) {
		if (blockEntity == null) {
			return null;
		}
		//? if <26.1 {
		return blockEntity instanceof net.minecraft.inventory.Inventory inv && !inv.isEmpty();
		//?} else {
		/*return blockEntity instanceof net.minecraft.world.Container inv && !inv.isEmpty();
		*///?}
	}

	/**
	 * The integrated server's own real world object for whatever dimension the client is
	 * currently in - null for anything except singleplayer ({@code getServer()}/{@code
	 * getSingleplayerServer()} only ever returns non-null for the local integrated server, never
	 * a remote one), which is exactly the restriction this feature needs: reading real,
	 * un-synced block entity data only makes sense (and is only technically possible without
	 * inventing new networking) when client and server are the same JVM.
	 */
	//? if <26.1 {
	private static net.minecraft.server.world.ServerWorld currentServerWorld(MinecraftClient client) {
		var server = client.getServer();
		if (server == null || client.world == null) {
			return null;
		}
		return server.getWorld(client.world.getRegistryKey());
	}
	//?} else {
	/*private static net.minecraft.server.level.ServerLevel currentServerWorld(MinecraftClient client) {
		var server = client.getSingleplayerServer();
		if (server == null || client.world == null) {
			return null;
		}
		return server.getLevel(client.world.dimension());
	}
	*///?}

	private void onWorldRender(WorldRenderContext context) {
		if (!isEnabled()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return;
		}
		Vec3d viewerPos = viewerPos(client);
		if (viewerPos == null) {
			return;
		}
		String dimension = client.world.getRegistryKey().getValue().toString();
		// Not capped to MAX_LISTED (that's only for the small flat HUD text list below) - but
		// rendering literally every container ever found, unbounded, got expensive once a world
		// had a few hundred remembered ones, so this has its own, much higher, adjustable cap.
		List<StorageEntry> visible = found.values().stream()
				.filter(e -> e.dimension().equals(dimension) && !isHiddenByContentsFilter(e))
				.sorted(Comparator.comparingDouble(e -> squaredDistance(viewerPos, entryCenter(e))))
				.toList();
		if (!unlimitedRendered && visible.size() > maxRendered) {
			visible = visible.subList(0, maxRendered);
		}
		for (StorageEntry entry : visible) {
			int color = kindColor(entry.kind());
			double distance = viewerPos.distanceTo(entryCenter(entry));
			GizmoDrawing.box(outlineBox(entry, distance), DrawStyle.stroked(color, outlineWidth(distance))).ignoreOcclusion();
			if (showTracerBeams) {
				drawTracerBeam(viewerPos, entry, color);
			}
		}
	}

	private static Vec3d entryCenter(StorageEntry entry) {
		return new Vec3d(entry.x() + 0.5, entry.y() + 0.5, entry.z() + 0.5);
	}

	/** Spawners are never affected by this filter (nothing to be "empty"); a container is hidden only once we've actually confirmed (singleplayer, same dimension) that it's empty - an unknown/uncheckable entry is always shown. */
	private boolean isHiddenByContentsFilter(StorageEntry entry) {
		return onlyShowNonEmpty && isInventoryKind(entry.kind()) && Boolean.FALSE.equals(contentsCache.get(entry.key()));
	}

	/** The point everything (nearest-sorting, tracer beams, labels) should be measured/drawn against - the free-flying camera position while {@link CinematicCameraModule} is active (its whole point is looking around away from the stationary player), the actual render camera otherwise. */
	private static Vec3d viewerPos(MinecraftClient client) {
		if (CinematicCameraModule.isActive()) {
			return new Vec3d(CinematicCameraModule.x(), CinematicCameraModule.y(), CinematicCameraModule.z());
		}
		return interpolatedCameraPos(client);
	}

	/**
	 * The actual partial-tick-interpolated camera position for this exact frame, not the raw,
	 * once-per-tick {@code Entity#getX/getEyeY/getZ()} the box/tracer used before - see {@code
	 * EntityFinderModule#interpolatedCameraPos} for why that mismatch is what made both the
	 * outline and the tracer visibly lag/drift behind the camera while moving.
	 */
	private static Vec3d interpolatedCameraPos(MinecraftClient client) {
		Entity camera = client.getCameraEntity();
		return camera == null ? null : cameraPosVec(camera, tickProgress(client));
	}

	/** {@code MinecraftClient#getRenderTickCounter()#getTickProgress(boolean)} (Yarn) -> {@code Minecraft#getDeltaTracker()#getGameTimeDeltaPartialTick(boolean)} (Mojmap) - same value, diverges by name only. */
	private static float tickProgress(MinecraftClient client) {
		//? if <26.1 {
		return client.getRenderTickCounter().getTickProgress(true);
		//?} else {
		/*return client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		*///?}
	}

	/** {@code Entity#getCameraPosVec(float)} (Yarn) -> {@code Entity#getEyePosition(float)} (Mojmap) - same value, diverges by name only. */
	private static Vec3d cameraPosVec(Entity camera, float tickDelta) {
		//? if <26.1 {
		return camera.getCameraPosVec(tickDelta);
		//?} else {
		/*return camera.getEyePosition(tickDelta);
		*///?}
	}

	/**
	 * Perspective naturally shrinks a fixed-size label as distance grows, which is backwards for
	 * this use case - the whole point is spotting something far away, not something you're
	 * already standing next to. Scaling up with distance instead keeps far containers actually
	 * legible/findable, capped so an extremely distant one doesn't take over the screen.
	 */
	private static float labelScale(double distance) {
		return (float) Math.min(4.0, 1.0 + distance / 40.0);
	}

	/**
	 * The outline itself needs the exact same "grows with distance instead of shrinking with
	 * perspective" treatment as {@link #labelScale} for the same reason - a fixed-size box gets
	 * visually smaller/harder to spot the farther away it is, backwards for something you're
	 * trying to find. Padding the box outward (rather than just thickening the line) is what
	 * actually reads as "bigger" at a glance instead of just "bolder".
	 */
	private static Box outlineBox(StorageEntry entry, double distance) {
		double margin = (labelScale(distance) - 1.0) * 0.4;
		return new Box(entry.x() - margin, entry.y() - margin, entry.z() - margin,
				entry.x() + 1 + margin, entry.y() + 1 + margin, entry.z() + 1 + margin);
	}

	private static float outlineWidth(double distance) {
		return (float) (1.5 + (labelScale(distance) - 1.0) * 1.5);
	}

	/**
	 * Exactly one real {@code GizmoDrawing.line(...)} per block, straight from the container's
	 * center to the exact current viewer position (the flying cinematic camera when that's
	 * active, your own eye otherwise) - recomputed fresh every frame, so it always ends precisely
	 * where you're looking from right now, not a stale/offset position. That method exists with
	 * the identical signature on every supported version (verified via javap), but is
	 * deliberately NOT used on 1.21.11, which has a confirmed rendering-drift bug on that same
	 * underlying primitive (see {@link net.veloclient.velo.client.modules.servertools.ChunkBorderOverlayModule}'s
	 * own javadoc) - a chain of small points (the same primitive {@link WaypointsModule} already
	 * uses safely for its own marker) is used there instead, at the cost of looking like a dotted
	 * trail rather than one clean line. Drawn without occlusion either way, same reasoning as
	 * {@link WaypointsModule}'s own marker point - this is a position you've already discovered
	 * and this mod already remembers, not a live scan of anything you don't already know about.
	 */
	private static void drawTracerBeam(Vec3d viewerPos, StorageEntry entry, int color) {
		Vec3d blockCenter = entryCenter(entry);
		Vec3d toViewer = viewerPos.subtract(blockCenter);
		double length = toViewer.length();
		if (length <= 0.05) {
			return;
		}
		// A dotted chain of points on every version now, not just 1.21.11 - confirmed (2026-09-16)
		// that the real GizmoDrawing.line()/Gizmos.line() primitive only visibly updates while the
		// camera is moving on 26.x too (a second, different quirk from 1.21.11's documented
		// rendering-drift one), while point() - the same primitive WaypointsModule's own marker
		// already uses safely - refreshes correctly every frame regardless.
		Vec3d step = scale(toViewer, 1.0 / TRACER_POINT_COUNT);
		Vec3d pos = blockCenter;
		for (int i = 0; i < TRACER_POINT_COUNT; i++) {
			pos = pos.add(step);
			GizmoDrawing.point(pos, color, TRACER_POINT_SIZE).ignoreOcclusion();
		}
	}

	/** Distinct color per container type, so a glance at the outline (or the HUD list row) says what it is without reading the label - and, at a spot with several containers close together, whether it's likely the same base (same colors clustered) or a different one you don't remember (an unexpected type/color mix). */
	private static int kindColor(String kind) {
		return switch (kind) {
			case "Chest" -> 0xFFFFAA00;
			case "Barrel" -> 0xFF9C6B30;
			case "Shulker" -> 0xFFC77DFF;
			case "Ender Chest" -> 0xFF00E5C8;
			case "Spawner" -> 0xFFFF3333;
			default -> 0xFF55D6FF;
		};
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) {
			return;
		}
		Vec3d viewerPos = viewerPos(client);
		if (viewerPos == null) {
			return;
		}
		String dimension = client.world.getRegistryKey().getValue().toString();
		// The cinematic camera's own yaw while it's active, so "N" still means "turn the camera
		// to face north" from wherever it's currently flying, not from the frozen player's facing.
		float viewerYaw = CinematicCameraModule.isActive() ? CinematicCameraModule.yaw() : client.player.getYaw();

		List<StorageEntry> nearby = found.values().stream()
				.filter(e -> e.dimension().equals(dimension) && !isHiddenByContentsFilter(e))
				.sorted(Comparator.comparingDouble(e -> squaredDistance(viewerPos, entryCenter(e))))
				.limit(MAX_LISTED)
				.toList();

		int lineHeight = client.textRenderer.fontHeight + 1;
		int rowY = y;
		context.drawTextWithShadow(client.textRenderer, "Storage Finder", x, rowY, 0xFFFFFFFF);
		rowY += lineHeight;
		if (nearby.isEmpty()) {
			context.drawTextWithShadow(client.textRenderer, "None found yet - explore around.", x, rowY, 0xFFC8C8C8);
			return;
		}
		for (StorageEntry entry : nearby) {
			double distance = viewerPos.distanceTo(entryCenter(entry));
			String line = showCompassDirection
					? String.format(Locale.ROOT, "%s: %.0fm %s", entry.kind(), distance, compassBearing(viewerPos, entry, viewerYaw))
					: String.format(Locale.ROOT, "%s: %.0fm", entry.kind(), distance);
			context.drawTextWithShadow(client.textRenderer, line, x, rowY, kindColor(entry.kind()));
			rowY += lineHeight;
		}
	}

	/** Compass direction relative to where the viewer is currently facing, so "N" always means "turn to face north," not a fixed on-screen side. */
	private static String compassBearing(Vec3d viewerPos, StorageEntry entry, float viewerYaw) {
		Vec3d center = entryCenter(entry);
		double dx = center.x - viewerPos.x;
		double dz = center.z - viewerPos.z;
		double worldBearing = Math.toDegrees(Math.atan2(-dx, dz));
		double relative = worldBearing - viewerYaw;
		relative = ((relative % 360) + 360) % 360;
		String[] labels = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
		int index = (int) Math.round(relative / 45.0) % 8;
		return labels[index];
	}

	@Override
	public int width() {
		return 170;
	}

	@Override
	public int height() {
		return (1 + Math.max(1, Math.min(MAX_LISTED, found.size()))) * (MinecraftClient.getInstance().textRenderer.fontHeight + 1);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Chests", () -> includeChests, v -> includeChests = v),
				new ConfigField.ToggleField("Barrels", () -> includeBarrels, v -> includeBarrels = v),
				new ConfigField.ToggleField("Shulkers", () -> includeShulkers, v -> includeShulkers = v),
				new ConfigField.ToggleField("Ender Chests", () -> includeEnderChests, v -> includeEnderChests = v),
				new ConfigField.ToggleField("Spawners", () -> includeSpawners, v -> includeSpawners = v),
				new ConfigField.ToggleField("Play Sound on New Discovery", () -> playSoundOnDiscovery, v -> playSoundOnDiscovery = v),
				new ConfigField.ToggleField("Show Compass Direction in List", () -> showCompassDirection, v -> showCompassDirection = v),
				new ConfigField.ToggleField("Show Tracer Beams", () -> showTracerBeams, v -> showTracerBeams = v),
				new ConfigField.ToggleField("Only Show Non-Empty Containers (Singleplayer)", () -> onlyShowNonEmpty, v -> onlyShowNonEmpty = v),
				new ConfigField.SliderField("Max Rendered At Once", 10, 500,
						() -> maxRendered, v -> maxRendered = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.ToggleField("Unlimited (ignore the max above)", () -> unlimitedRendered, v -> unlimitedRendered = v),
				new ConfigField.SliderField("Scan Radius (chunks)", 2, 16,
						() -> scanRadiusChunks, v -> scanRadiusChunks = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Min Y", -64, 320,
						() -> minScanY, v -> minScanY = (int) Math.min(v, maxScanY), v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Max Y (e.g. lower this to only flag underground)", -64, 320,
						() -> maxScanY, v -> maxScanY = (int) Math.max(v, minScanY), v -> String.valueOf((int) v)),
				new ConfigField.ActionButtonField("Forget All Found Containers", () -> {
					found = new LinkedHashMap<>();
					StorageFinderStore.save(found);
				}));
	}
}
