package net.veloclient.velo.client.modules.servertools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.modules.qol.CinematicCameraModule;
import net.veloclient.velo.client.util.ModuleProfiler;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Live radar for nearby entities in your own singleplayer world, so a sudden wall of zombies (or
 * any other mob density spike) has an actual visible "where is this all coming from" instead of
 * just noticing the lag/danger after the fact - a box outline, tracer line to the camera, and a
 * type+distance label per entity, all recomputed every frame since (unlike {@link
 * StorageFinderModule}'s static containers) entities actually move. Detection itself is the same
 * plain {@code world.getEntities()} walk {@link EntityCountOverlayModule} already uses (rather
 * than a bounding-box bulk query) plus a manual distance check - the simplest, already-proven-
 * working way to find what's nearby.
 *
 * <p>Shows other players too (its own toggle, on by default) - the "player finder through walls"
 * concern that would normally rule this out doesn't apply here, since the module is already
 * hard-restricted to singleplayer (same {@link CinematicCameraModule#isActive} reasoning as that
 * module's own restriction): there's no real other player to reveal unless this world is open to
 * LAN with friends actually connected. The local player themselves is still always excluded -
 * same "nothing useful about drawing a box around yourself" reasoning as {@code
 * HitboxVisualizerModule}.
 */
public final class EntityFinderModule extends AbstractModule implements Configurable {

	private static final int SCAN_INTERVAL_TICKS = 1;
	private static final int TRACER_POINT_COUNT = 18;

	private double radius = 32;
	private boolean showHostile = true;
	private boolean showPassive = true;
	private boolean showItems = false;
	private boolean showOther = true;
	private boolean showPlayers = true;
	private boolean showBlockEntities = false;
	private boolean showTracerBeams = true;
	private boolean playSoundOnDensitySpike = true;
	private int densityThreshold = 8;
	private int maxRendered = 100;
	private boolean unlimited = false;

	private List<Entity> trackedEntities = List.of();
	private List<BlockPos> trackedBlockEntities = List.of();
	private int ticksUntilScan;
	private int ticksUntilBlockEntityScan;
	private boolean spikeAnnounced;

	private enum Category {
		HOSTILE(0xFFFF5555), PASSIVE(0xFF55FF55), ITEM(0xFFFFFF55), OTHER(0xFF55D6FF), PLAYER(0xFFFF55FF), BLOCK_ENTITY(0xFFFFAA00);

		final int color;

		Category(int color) {
			this.color = color;
		}
	}

	public EntityFinderModule() {
		super("entity-finder", "Entity Finder",
				"Live radar for nearby entities (and, optionally, block entities like pistons/hoppers) in your "
						+ "own singleplayer world - box outline, tracer line, and type/distance label per entity, so "
						+ "you can actually see where a mob-spawn spike is coming from. Includes other players by "
						+ "default (never the local player - your own view). Singleplayer only.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.CHECK_SERVER_RULES, false);
		ClientTickEvents.END_CLIENT_TICK.register(client ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.TICK, () -> onTick(client)));
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.WORLD_RENDER, () -> onRender(context)));
	}

	@Override
	public void onEnable() {
		trackedEntities = List.of();
		trackedBlockEntities = List.of();
		ticksUntilScan = 0;
		ticksUntilBlockEntityScan = 0;
		spikeAnnounced = false;
		renderErrorLogged = false;
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled() || client.player == null || client.world == null) {
			return;
		}
		if (ticksUntilScan-- <= 0) {
			ticksUntilScan = SCAN_INTERVAL_TICKS;
			scanEntities(client);
		}
		if (showBlockEntities && ticksUntilBlockEntityScan-- <= 0) {
			// Block entities don't move, so this doesn't need the every-tick cadence the live
			// entity scan above does - matches StorageFinderModule's own scan interval reasoning.
			ticksUntilBlockEntityScan = 40;
			scanBlockEntities(client);
		} else if (!showBlockEntities) {
			trackedBlockEntities = List.of();
		}
	}

	private void scanEntities(MinecraftClient client) {
		Box box = expand(client.player.getBoundingBox(), radius);
		List<Entity> found = new ArrayList<>();
		for (Entity entity : entitiesInBox(client.world, box)) {
			if (entity == client.player || entity.isRemoved()) {
				continue;
			}
			Category category = categoryOf(entity);
			if (!isShown(category)) {
				continue;
			}
			found.add(entity);
		}
		found.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(client.player)));
		if (!unlimited && found.size() > maxRendered) {
			found = found.subList(0, maxRendered);
		}
		trackedEntities = found;

		boolean spike = found.size() >= densityThreshold;
		if (spike && !spikeAnnounced && playSoundOnDensitySpike) {
			playDensitySound(client);
		}
		spikeAnnounced = spike;
	}

	private void scanBlockEntities(MinecraftClient client) {
		ClientWorld world = client.world;
		int centerX = client.player.getBlockX() >> 4;
		int centerZ = client.player.getBlockZ() >> 4;
		int chunkRadius = Math.max(1, (int) Math.ceil(radius / 16.0));
		List<BlockPos> found = new ArrayList<>();
		for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
			for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
				int chunkX = centerX + dx;
				int chunkZ = centerZ + dz;
				if (!world.isChunkLoaded(chunkX, chunkZ)) {
					continue;
				}
				for (BlockPos pos : world.getChunk(chunkX, chunkZ).getBlockEntities().keySet()) {
					if (client.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= radius * radius) {
						found.add(immutable(pos));
					}
				}
			}
		}
		found.sort(Comparator.comparingDouble(p -> client.player.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)));
		if (!unlimited && found.size() > maxRendered) {
			found = found.subList(0, maxRendered);
		}
		trackedBlockEntities = found;
	}

	private boolean isShown(Category category) {
		return switch (category) {
			case HOSTILE -> showHostile;
			case PASSIVE -> showPassive;
			case ITEM -> showItems;
			case OTHER -> showOther;
			case PLAYER -> showPlayers;
			case BLOCK_ENTITY -> showBlockEntities;
		};
	}

	private static Category categoryOf(Entity entity) {
		if (entity instanceof PlayerEntity) {
			return Category.PLAYER;
		}
		if (isHostile(entity)) {
			return Category.HOSTILE;
		}
		if (isItem(entity)) {
			return Category.ITEM;
		}
		if (entity instanceof net.minecraft.entity.LivingEntity) {
			return Category.PASSIVE;
		}
		return Category.OTHER;
	}

	/** {@code instanceof HostileEntity} (Yarn) -> {@code instanceof Monster} (Mojmap) - same "counts as a hostile mob" check, diverges by class name/package only. */
	private static boolean isHostile(Entity entity) {
		//? if <26.1 {
		return entity instanceof net.minecraft.entity.mob.HostileEntity;
		//?} else {
		/*return entity instanceof net.minecraft.world.entity.monster.Monster;
		*///?}
	}

	/** {@code net.minecraft.entity.ItemEntity} (Yarn) -> {@code net.minecraft.world.entity.item.ItemEntity} (Mojmap) - same class, package changed only. */
	private static boolean isItem(Entity entity) {
		//? if <26.1 {
		return entity instanceof net.minecraft.entity.ItemEntity;
		//?} else {
		/*return entity instanceof net.minecraft.world.entity.item.ItemEntity;
		*///?}
	}

	/** {@code World#getOtherEntities(Entity, Box, Predicate)} -> {@code Level#getEntities(Entity, AABB, Predicate)} - same shape, diverges by name only (same divergence as {@code ChunkLoadProfilerModule}). */
	private static List<Entity> entitiesInBox(ClientWorld world, Box box) {
		//? if <26.1 {
		return world.getOtherEntities(null, box, entity -> true);
		//?} else {
		/*return world.getEntities((Entity) null, box, entity -> true);
		*///?}
	}

	/** {@code Box#expand(double)} (Yarn) -> {@code AABB#inflate(double)} (Mojmap) - same value, diverges by name only. */
	private static Box expand(Box box, double amount) {
		//? if <26.1 {
		return box.expand(amount);
		//?} else {
		/*return box.inflate(amount);
		*///?}
	}

	/** {@code BlockPos#toImmutable()} (Yarn) -> {@code BlockPos#immutable()} (Mojmap) - same value, diverges by name only. */
	private static BlockPos immutable(BlockPos pos) {
		//? if <26.1 {
		return pos.toImmutable();
		//?} else {
		/*return pos.immutable();
		*///?}
	}

	private static boolean isSingleplayer(MinecraftClient client) {
		//? if <26.1 {
		return client.isInSingleplayer();
		//?} else {
		/*return client.hasSingleplayerServer();
		*///?}
	}

	/** Same mapping divergence/verification as {@code StorageFinderModule#playDiscoverySound}. */
	private static void playDensitySound(MinecraftClient client) {
		//? if <26.1 {
		client.world.playSoundClient(client.player.getX(), client.player.getY(), client.player.getZ(),
				SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.7f, 0.5f, false);
		//?} else {
		/*client.world.playLocalSound(client.player.getX(), client.player.getY(), client.player.getZ(),
				SoundEvents.EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.7f, 0.5f, false);
		*///?}
	}

	// Logged at most once (not every frame) so a real bug is actually visible in the log instead
	// of either being silently swallowed by the render-event dispatcher or spamming it 20x/sec.
	private boolean renderErrorLogged;

	private void onRender(WorldRenderContext context) {
		if (!isEnabled()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) {
			return;
		}
		Vec3d viewerPos = viewerPos(client);
		if (viewerPos == null) {
			return;
		}
		for (Entity entity : trackedEntities) {
			if (entity.isRemoved()) {
				continue;
			}
			try {
				int color = categoryOf(entity).color;
				GizmoDrawing.box(entity.getBoundingBox(), DrawStyle.stroked(color, 2.0f)).ignoreOcclusion();
				String label = entityTypeName(entity);
				double distance = viewerPos.distanceTo(entityPos(entity));
				GizmoDrawing.entityLabel(entity, 0, label, color, labelScale(distance)).ignoreOcclusion();
				if (showTracerBeams) {
					drawTracerBeam(viewerPos, entityPos(entity).add(0, entityHeight(entity) / 2.0, 0), color);
				}
			} catch (RuntimeException e) {
				logRenderErrorOnce("entity", e);
			}
		}
		int blockEntityColor = Category.BLOCK_ENTITY.color;
		for (BlockPos pos : trackedBlockEntities) {
			try {
				Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
				GizmoDrawing.box(box, DrawStyle.stroked(blockEntityColor, 2.0f)).ignoreOcclusion();
				if (showTracerBeams) {
					Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
					drawTracerBeam(viewerPos, center, blockEntityColor);
				}
			} catch (RuntimeException e) {
				logRenderErrorOnce("block entity", e);
			}
		}
	}

	private void logRenderErrorOnce(String what, RuntimeException e) {
		if (renderErrorLogged) {
			return;
		}
		renderErrorLogged = true;
		VeloClient.LOGGER.error("Entity Finder: failed to draw a {} - nothing will be highlighted for it (and possibly nothing else this frame) until the module is toggled off and back on. This is the actual cause, not a rendering fluke:", what, e);
	}

	private static float labelScale(double distance) {
		return (float) Math.min(4.0, 1.0 + distance / 40.0);
	}

	/** {@code Entity#getEntityPos()} (Yarn) -> {@code Entity#position()} (Mojmap) - same real, continuous (not block-snapped) position, diverges by name only (same divergence already documented on {@code TntTimerModule}). */
	private static Vec3d entityPos(Entity entity) {
		//? if <26.1 {
		return entity.getEntityPos();
		//?} else {
		/*return entity.position();
		*///?}
	}

	/** {@code Entity#getHeight()} (Yarn) -> {@code Entity#getBbHeight()} (Mojmap) - same value, diverges by name only (same divergence already documented on {@code TntTimerModule}). */
	private static float entityHeight(Entity entity) {
		//? if <26.1 {
		return entity.getHeight();
		//?} else {
		/*return entity.getBbHeight();
		*///?}
	}

	/** {@code EntityType#getName()} (Yarn, returns {@code Text}) -> {@code EntityType#getDescription()} (Mojmap, returns {@code Component}) - same display name, diverges by method name only; {@code .getString()} is unaffected either way. */
	private static String entityTypeName(Entity entity) {
		//? if <26.1 {
		return entity.getType().getName().getString();
		//?} else {
		/*return entity.getType().getDescription().getString();
		*///?}
	}

	/** Same "one real line straight to the current viewer position" technique as {@code StorageFinderModule#drawTracerBeam} - see that module's javadoc for why 1.21.11 gets a dotted-point fallback instead. */
	private static void drawTracerBeam(Vec3d viewerPos, Vec3d fromPos, int color) {
		Vec3d toViewer = viewerPos.subtract(fromPos);
		double length = toViewer.length();
		if (length <= 0.05) {
			return;
		}
		// A dotted chain of points on every version now, not just 1.21.11 - see
		// StorageFinderModule#drawTracerBeam's own javadoc for why: the real
		// GizmoDrawing.line()/Gizmos.line() primitive only visibly updates while the camera is
		// moving on 26.x too, while point() refreshes correctly every frame regardless.
		Vec3d step = scale(toViewer, 1.0 / TRACER_POINT_COUNT);
		Vec3d pos = fromPos;
		for (int i = 0; i < TRACER_POINT_COUNT; i++) {
			pos = pos.add(step);
			GizmoDrawing.point(pos, color, 3f).ignoreOcclusion();
		}
	}

	/** {@code Vec3d#multiply(double)} (Yarn) -> {@code Vec3#scale(double)} (Mojmap) - same value, diverges by name only. */
	private static Vec3d scale(Vec3d vec, double factor) {
		//? if <26.1 {
		return vec.multiply(factor);
		//?} else {
		/*return vec.scale(factor);
		*///?}
	}

	/** The point tracers/labels should be measured/drawn against - the free-flying cinematic camera when active, the actual render camera otherwise. Same helper shape as {@code StorageFinderModule#viewerPos}. */
	private static Vec3d viewerPos(MinecraftClient client) {
		if (CinematicCameraModule.isActive()) {
			return new Vec3d(CinematicCameraModule.x(), CinematicCameraModule.y(), CinematicCameraModule.z());
		}
		Vec3d pos = interpolatedCameraPos(client);
		// getCameraEntity() should never actually be null while playing, but this used to
		// silently skip drawing EVERYTHING for the whole frame if it ever was - falling back to
		// the player's own raw position keeps the module working instead of going dark.
		return pos != null ? pos : entityPos(client.player);
	}

	/**
	 * The actual partial-tick-interpolated camera position for this exact frame, not the raw,
	 * once-per-tick {@code Entity#getX/getEyeY/getZ()} the near end of the tracer used before.
	 * That mismatch (an endpoint only updating 20x/sec while the on-screen camera itself moves
	 * smoothly every frame) is what made the tracer visibly lag/drift behind the camera while
	 * moving - this always lines up exactly with the crosshair instead.
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

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Radius", 8, 64, () -> radius, v -> radius = v, v -> String.valueOf((int) v)),
				new ConfigField.ToggleField("Hostile Mobs", () -> showHostile, v -> showHostile = v),
				new ConfigField.ToggleField("Passive/Neutral Mobs", () -> showPassive, v -> showPassive = v),
				new ConfigField.ToggleField("Item Entities", () -> showItems, v -> showItems = v),
				new ConfigField.ToggleField("Other (projectiles, minecarts, ...)", () -> showOther, v -> showOther = v),
				new ConfigField.ToggleField("Other Players", () -> showPlayers, v -> showPlayers = v),
				new ConfigField.ToggleField("Block Entities (pistons, hoppers, ...)", () -> showBlockEntities, v -> showBlockEntities = v),
				new ConfigField.ToggleField("Show Tracer Beams", () -> showTracerBeams, v -> showTracerBeams = v),
				new ConfigField.ToggleField("Play Sound on Density Spike", () -> playSoundOnDensitySpike, v -> playSoundOnDensitySpike = v),
				new ConfigField.SliderField("Density Spike Threshold (entity count)", 2, 40,
						() -> densityThreshold, v -> densityThreshold = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Max Rendered At Once", 10, 500,
						() -> maxRendered, v -> maxRendered = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.ToggleField("Unlimited (ignore the max above)", () -> unlimited, v -> unlimited = v));
	}
}
