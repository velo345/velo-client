package net.veloclient.velo.client.devtools;

import net.veloclient.velo.VeloClient;

/**
 * Dev-only frame-section timer ({@code -Dvelo.renderProfile=true}): measures how much render-thread
 * time goes into preparing and recording terrain draws versus the whole frame, to decide where a
 * renderer optimization actually pays off. Logs an average every 600 frames.
 */
public final class RenderProfiler {

	public static final boolean ENABLED = Boolean.getBoolean("velo.renderProfile");
	/** Draw calls seen since {@link #begin()} - counted at the Vulkan draw calls on 26.3, where the old per-layer draw lists are gone. */
	public static int drawCalls;
	/**
	 * Descriptor work per draw (VulkanDrawStateMixin, Vulkan): full = vanilla re-pushed the whole set, partial = only the
	 * changed uniform buffers went out. Together with the skipped vertex/index binds this says how much per-draw CPU work
	 * is left besides the draw call itself - if nearly every push is already partial and terrain still costs a lot, only
	 * fewer draws (merging sections) can make it cheaper.
	 */
	public static long fullPushes;
	public static long partialPushes;
	public static long skippedBinds;
	public static long binds;
	/** Vanilla full memory barriers recorded (VulkanBarrierProbeMixin), reset with each log line. */
	public static long barriers;
	private static final java.util.Map<String, Integer> BARRIER_FROM = new java.util.TreeMap<>();

	/** One frame's encoder operations in order, captured every 600 frames (dev profiling only). */
	private static final java.util.List<String> SEQUENCE = new java.util.ArrayList<>();
	private static boolean capturing;

	public static void op(String what) {
		if (capturing) {
			SEQUENCE.add(what);
		}
	}

	public static void barrierFrom(String method) {
		BARRIER_FROM.merge(method, 1, Integer::sum);
	}
	/** With {@code -Dvelo.renderProfileAB=true}, flips the Fast Draw Path toggle every block for a same-scene A/B. */
	private static final boolean AB = Boolean.getBoolean("velo.renderProfileAB");
	/** {@code -Dvelo.renderProfileAB=particles}: flips Velo Turbo's particle section culling instead. */
	private static final boolean AB_PARTICLES = "particles".equals(System.getProperty("velo.renderProfileAB"));
	/** {@code -Dvelo.renderProfileAB=turbo}: flips the Velo Turbo master switch. */
	private static final boolean AB_TURBO = "turbo".equals(System.getProperty("velo.renderProfileAB"));

	private static long frameStart;
	private static long frames;
	private static long frameNanos;
	private static long extractNanos;
	private static long prepareNanos;
	private static long terrainNanos;
	private static long terrainDraws;
	private static long sectionStart;
	private static long waitStart;
	private static long acquireNanos;
	private static long presentNanos;

	public static void waitBegin() {
		waitStart = System.nanoTime();
	}

	public static void waitEnd(String what) {
		long elapsed = System.nanoTime() - waitStart;
		if (what.equals("acquire")) {
			acquireNanos += elapsed;
		} else {
			presentNanos += elapsed;
		}
	}

	private RenderProfiler() {
	}

	public static void begin() {
		drawCalls = 0;
		sectionStart = System.nanoTime();
	}

	public static void endExtract() {
		extractNanos += System.nanoTime() - sectionStart;
	}

	public static void endPrepare() {
		prepareNanos += System.nanoTime() - sectionStart;
	}

	public static void endTerrain(int draws) {
		terrainNanos += System.nanoTime() - sectionStart;
		terrainDraws += draws;
	}

	public static void frameBegin() {
		MoveBench.frameStart();
		frameStart = System.nanoTime();
	}

	/** -Dvelo.hitchLog=true: every frame over 25 ms is logged with the screen that was open (opening the Store, the Locker...). */
	private static final boolean HITCH_LOG = Boolean.getBoolean("velo.hitchLog");

	public static void frameEnd() {
		GpuPassTimer.endFrame();
		MoveBench.frame(System.nanoTime());
		if (frameStart == 0) {
			return;
		}
		if (HITCH_LOG) {
			long ms = (System.nanoTime() - frameStart) / 1_000_000;
			if (ms > 25) {
				//? if <26.1 {
				var screen = net.minecraft.client.MinecraftClient.getInstance().currentScreen;
				//?} else if <26.2 {
				/*var screen = net.minecraft.client.Minecraft.getInstance().screen;
				*///?} else {
				/*var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
				*///?}
				VeloClient.LOGGER.info("Velo hitch: " + ms + " ms frame, screen " + (screen == null ? "none" : screen.getClass().getSimpleName()));
			}
		}
		frameNanos += System.nanoTime() - frameStart;
		if (frames == 0) {
			// GPU time is only measured while vanilla's GPU-utilization debug entry is on.
			net.veloclient.velo.module.ModuleRegistry.get("gpu-utilization").ifPresent(m -> m.setEnabled(true));
		}
		if (capturing) {
			capturing = false;
			VeloClient.LOGGER.info("Velo render profile frame ops: " + String.join(" | ", SEQUENCE));
			SEQUENCE.clear();
		}
		if (frames % 600 == 599) {
			capturing = true;
		}
		if (++frames % 600 == 0) {
			net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
			VeloClient.LOGGER.info(String.format(java.util.Locale.ROOT, "Velo render profile: GPU busy %.0f%% of the frame (%.0f FPS rendered, %d shown)",
					client.getGpuUtilizationPercentage(), client.getCurrentFps() * 1.0,
					net.veloclient.velo.client.modules.performance.DecoupledPresent.shownFps()));
			VeloClient.LOGGER.info(String.format(java.util.Locale.ROOT,
					"Velo render profile [fast draw %s, particle cull %s, turbo %s] (avg/frame over 600): frame %.3f ms | extract %.3f ms | record terrain %.3f ms (%d draws) | wait acquire %.3f ms | submit+present %.3f ms | %d barriers",
					net.veloclient.velo.client.modules.performance.PerformanceBoostModule.fastDrawPath ? "on" : "off",
					net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turboParticleCull ? "on" : "off",
					net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo ? "on" : "off",
					frameNanos / 600e6, extractNanos / 600e6, terrainNanos / 600e6, terrainDraws / 600, acquireNanos / 600e6, presentNanos / 600e6, barriers / 600));
			barriers = 0;
			long pushes = fullPushes + partialPushes;
			if (pushes + binds + skippedBinds > 0) {
				VeloClient.LOGGER.info(String.format(java.util.Locale.ROOT,
						"Velo render profile descriptors/frame: %.0f full pushes, %.0f partial (%.0f%% partial) | buffer binds %.0f done, %.0f skipped",
						fullPushes / 600.0, partialPushes / 600.0, pushes == 0 ? 0.0 : 100.0 * partialPushes / pushes, binds / 600.0, skippedBinds / 600.0));
			}
			fullPushes = partialPushes = skippedBinds = binds = 0;
			if (!BARRIER_FROM.isEmpty()) {
				StringBuilder sb = new StringBuilder("Velo render profile barriers/frame by caller:");
				BARRIER_FROM.forEach((k, v) -> sb.append(' ').append(k).append('=').append(String.format(java.util.Locale.ROOT, "%.1f", v / 600.0)));
				VeloClient.LOGGER.info(sb.toString());
				BARRIER_FROM.clear();
			}
			frameNanos = extractNanos = prepareNanos = terrainNanos = terrainDraws = acquireNanos = presentNanos = 0;
			if (AB) {
				net.veloclient.velo.client.modules.performance.PerformanceBoostModule.fastDrawPath ^= true;
			}
			VeloClient.LOGGER.info("Velo render profile HUD cache: " + net.veloclient.velo.client.hud.HudFrameCache.replays + " replays, "
					+ net.veloclient.velo.client.hud.HudFrameCache.records + " records");
			net.veloclient.velo.client.hud.HudFrameCache.replays = 0;
			net.veloclient.velo.client.hud.HudFrameCache.records = 0;
			if (AB_TURBO) {
				net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo ^= true;
			}
			if (AB_PARTICLES) {
				net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turboParticleCull ^= true;
			}
		}
	}
}
