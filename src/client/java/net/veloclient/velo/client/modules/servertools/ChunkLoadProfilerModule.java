package net.veloclient.velo.client.modules.servertools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.veloclient.velo.client.util.ChunkLoadTracker;
import net.veloclient.velo.client.util.ModuleProfiler;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Highlights how long each incoming chunk actually took the client to process, colored relative
 * to a rolling average for this session - green for normal, sliding to red the slower it was -
 * so a laggy server/world shows you exactly which chunks to go look at instead of just "it's
 * laggy somewhere". A chunk this client has genuinely never received before (a real, persisted
 * per-chunk record, not just guessed from timing) is shown in purple instead, since a brand-new
 * chunk needing real terrain generation server-side is a fundamentally different (and expected)
 * kind of slow than an already-generated chunk that's merely slow to re-send. Raw load timing
 * alone misses a very real, very common lag source that isn't about chunk *generation* at all -
 * a chunk sitting there with a wall of hoppers/pistons/redstone ticks every single game tick
 * whether or not it just loaded - so the number of entities and block entities already in a
 * chunk the moment it loads is folded into the same score, each with its own configurable weight
 * (pistons/hoppers are exactly the kind of block entity this is meant to catch).
 *
 * <p>Purely a visualization of packet-arrival timing and already-loaded block/entity data this
 * client already has - nothing is queried beyond what the server already sent for chunks already
 * in view.
 */
public final class ChunkLoadProfilerModule extends AbstractModule implements Configurable {

	private static final int FLUSH_INTERVAL_TICKS = 100;
	private static final int GREEN = 0x55FF55;
	private static final int RED = 0xFF5555;

	private double slowMultiplier = 2.5;
	private double minSlowMs = 15.0;
	private double entityWeight = 0.15;
	private double blockEntityWeight = 0.3;
	private boolean weighEntities = true;
	private boolean weighBlockEntities = true;
	private boolean playSoundOnSlow = true;
	private boolean markNeverVisited = true;
	private double displaySeconds = 12.0;
	private double pillarThickness = 0.15;
	// Highlighting literally every loaded chunk (including the boring, perfectly normal ones)
	// while exploring or flying meant a screen full of green pillars everywhere - the vast
	// majority of chunks are never actually "notable" for lag diagnosis, so this is on by default.
	private boolean onlyNotable = true;
	private int sampleEveryNth = 1;

	private static volatile boolean moduleEnabled;

	private Set<String> visitedChunks = Set.of();
	private boolean visitedDirty;
	private int ticksUntilFlush;
	private int sampleCounter;

	private record ChunkVisual(int chunkX, int chunkZ, int color, long expiresAtMillis) {
	}

	// Read every frame from onRender, written every tick from onTick - both always on the client
	// thread in practice, but ConcurrentHashMap costs nothing here and removes any doubt.
	private final Map<String, ChunkVisual> visuals = new ConcurrentHashMap<>();

	public ChunkLoadProfilerModule() {
		super("chunk-load-profiler", "Chunk Load Profiler",
				"Highlights how long each chunk took to load relative to others (green = normal, red = "
						+ "slow), flags chunks you've genuinely never loaded before in purple, and factors in "
						+ "entity/block-entity counts (pistons, hoppers, ...) as an extra lag signal, with a ping "
						+ "when a slow chunk shows up.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.CHECK_SERVER_RULES, false);
		ClientTickEvents.END_CLIENT_TICK.register(client ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.TICK, () -> onTick(client)));
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.WORLD_RENDER, () -> onRender(context)));
	}

	@Override
	public void onEnable() {
		visitedChunks = VisitedChunkStore.load();
		visuals.clear();
		visitedDirty = false;
		ticksUntilFlush = FLUSH_INTERVAL_TICKS;
		sampleCounter = 0;
		ChunkLoadTracker.drainPending();
		moduleEnabled = true;
	}

	@Override
	public void onDisable() {
		moduleEnabled = false;
		// Nothing more will ever call drainPending() while disabled, so drop whatever's still
		// queued instead of leaving it to grow unboundedly until re-enabled.
		ChunkLoadTracker.drainPending();
		flushVisitedIfDirty();
	}

	/** Read by {@code ChunkLoadTimingMixin} so the timing packet handler does zero work (not even a queue insert) while this module is off - a mod feature being disabled should mean no cost, not "runs the same, just nobody's looking at the result". */
	public static boolean isRunning() {
		return moduleEnabled;
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled()) {
			return;
		}
		List<ChunkLoadTracker.RawLoad> pending = ChunkLoadTracker.drainPending();
		if (!pending.isEmpty() && client.world != null) {
			double avgMs = Math.max(ChunkLoadTracker.rollingAverageMs(), 0.001);
			String currentDimension = client.world.getRegistryKey().getValue().toString();
			for (ChunkLoadTracker.RawLoad raw : pending) {
				processLoad(client, raw, avgMs, currentDimension);
			}
		}

		long now = System.currentTimeMillis();
		visuals.values().removeIf(v -> v.expiresAtMillis() <= now);

		if (--ticksUntilFlush <= 0) {
			ticksUntilFlush = FLUSH_INTERVAL_TICKS;
			flushVisitedIfDirty();
		}
	}

	private void flushVisitedIfDirty() {
		if (visitedDirty) {
			VisitedChunkStore.save(visitedChunks);
			visitedDirty = false;
		}
	}

	private void processLoad(MinecraftClient client, ChunkLoadTracker.RawLoad raw, double avgMs, String currentDimension) {
		String key = raw.dimension() + "@" + raw.chunkX() + "," + raw.chunkZ();
		boolean firstTime = visitedChunks.add(key);
		if (firstTime) {
			visitedDirty = true;
		}

		double loadMs = raw.nanos() / 1_000_000.0;
		double score = loadMs / avgMs;

		if (raw.dimension().equals(currentDimension) && (weighEntities || weighBlockEntities)) {
			ClientWorld world = client.world;
			if (weighEntities) {
				score += entityCount(world, raw.chunkX(), raw.chunkZ()) * entityWeight;
			}
			if (weighBlockEntities && world.isChunkLoaded(raw.chunkX(), raw.chunkZ())) {
				score += world.getChunk(raw.chunkX(), raw.chunkZ()).getBlockEntities().size() * blockEntityWeight;
			}
		}

		boolean isSlow = loadMs >= minSlowMs && score >= slowMultiplier;
		if (isSlow && playSoundOnSlow) {
			playSlowChunkSound(client);
		}

		boolean isNotable = isSlow || (firstTime && markNeverVisited);
		if (onlyNotable && !isNotable) {
			return;
		}
		// Extra thinning on top of "only notable" for anyone exploring/generating enough new
		// terrain that even the purple never-visited markers alone still felt like too many.
		if (++sampleCounter % Math.max(1, sampleEveryNth) != 0) {
			return;
		}

		int color = (firstTime && markNeverVisited) ? 0xFFB266FF : scoreColor(score);
		long expiresAt = System.currentTimeMillis() + Math.round(displaySeconds * 1000);
		visuals.put(key, new ChunkVisual(raw.chunkX(), raw.chunkZ(), color, expiresAt));
	}

	private static int entityCount(ClientWorld world, int chunkX, int chunkZ) {
		Box box = new Box(chunkX * 16, world.getBottomY(), chunkZ * 16, chunkX * 16 + 16, world.getTopYInclusive() + 1, chunkZ * 16 + 16);
		return entitiesInBox(world, box).size();
	}

	/** {@code World#getOtherEntities(Entity, Box, Predicate)} -> {@code Level#getEntities(Entity, AABB, Predicate)} - same shape, diverges by name only. The excluded-entity param is unused here (no self to exclude), just passed null like a plain box query. */
	private static List<Entity> entitiesInBox(ClientWorld world, Box box) {
		//? if <26.1 {
		return world.getOtherEntities(null, box, entity -> true);
		//?} else {
		/*return world.getEntities((Entity) null, box, entity -> true);
		*///?}
	}

	private static int scoreColor(double score) {
		double t = Math.clamp((score - 1.0), 0.0, 1.5) / 1.5;
		int r = Math.min(255, (int) Math.round(((RED >> 16) & 0xFF) * t + ((GREEN >> 16) & 0xFF) * (1 - t)));
		int g = Math.min(255, (int) Math.round(((RED >> 8) & 0xFF) * t + ((GREEN >> 8) & 0xFF) * (1 - t)));
		int b = Math.min(255, (int) Math.round((RED & 0xFF) * t + (GREEN & 0xFF) * (1 - t)));
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** Same mapping divergence/verification as {@code StorageFinderModule#playDiscoverySound}. */
	private static void playSlowChunkSound(MinecraftClient client) {
		if (client.player == null) {
			return;
		}
		//? if <26.1 {
		client.world.playSoundClient(client.player.getX(), client.player.getY(), client.player.getZ(),
				SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.5f, 0.6f, false);
		//?} else {
		/*client.world.playLocalSound(client.player.getX(), client.player.getY(), client.player.getZ(),
				SoundEvents.EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.5f, 0.6f, false);
		*///?}
	}

	private void onRender(WorldRenderContext context) {
		if (!isEnabled()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		ClientWorld world = client.world;
		if (world == null) {
			return;
		}
		double worldMinY = world.getBottomY();
		double worldMaxY = world.getTopYInclusive() + 1;
		double half = Math.max(0.02, pillarThickness / 2.0);
		for (ChunkVisual visual : visuals.values()) {
			double originX = visual.chunkX() * 16.0;
			double originZ = visual.chunkZ() * 16.0;
			for (double dx = 0; dx <= 16; dx += 16) {
				for (double dz = 0; dz <= 16; dz += 16) {
					Box pillar = new Box(originX + dx - half, worldMinY, originZ + dz - half,
							originX + dx + half, worldMaxY, originZ + dz + half);
					GizmoDrawing.box(pillar, DrawStyle.filled(visual.color())).ignoreOcclusion();
				}
			}
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Slow Threshold (x average)", 1.2, 6.0,
						() -> slowMultiplier, v -> slowMultiplier = v, v -> String.format(java.util.Locale.ROOT, "%.1fx", v)),
				new ConfigField.SliderField("Minimum Slow Time (ms)", 1, 200,
						() -> minSlowMs, v -> minSlowMs = v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("How Long Markers Stay (s)", 2, 60,
						() -> displaySeconds, v -> displaySeconds = v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Pillar Thickness", 0.05, 1.0,
						() -> pillarThickness, v -> pillarThickness = v, v -> String.format(java.util.Locale.ROOT, "%.2f", v)),
				new ConfigField.ToggleField("Only Highlight Notable Chunks (slow or never-visited)", () -> onlyNotable, v -> onlyNotable = v),
				new ConfigField.SliderField("Show Only 1 In Every N Notable Chunks", 1, 10,
						() -> sampleEveryNth, v -> sampleEveryNth = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.ToggleField("Play Sound on Slow Chunk", () -> playSoundOnSlow, v -> playSoundOnSlow = v),
				new ConfigField.ToggleField("Mark Never-Visited Chunks (purple)", () -> markNeverVisited, v -> markNeverVisited = v),
				new ConfigField.ToggleField("Weigh In Entity Count", () -> weighEntities, v -> weighEntities = v),
				new ConfigField.SliderField("Entity Weight", 0.0, 2.0,
						() -> entityWeight, v -> entityWeight = v, v -> String.format(java.util.Locale.ROOT, "%.2f", v)),
				new ConfigField.ToggleField("Weigh In Block Entity Count (pistons, hoppers, ...)", () -> weighBlockEntities, v -> weighBlockEntities = v),
				new ConfigField.SliderField("Block Entity Weight", 0.0, 2.0,
						() -> blockEntityWeight, v -> blockEntityWeight = v, v -> String.format(java.util.Locale.ROOT, "%.2f", v)),
				new ConfigField.ActionButtonField("Forget Visited-Chunk History", () -> {
					visitedChunks = new java.util.HashSet<>();
					VisitedChunkStore.save(visitedChunks);
					visitedDirty = false;
				}));
	}
}
