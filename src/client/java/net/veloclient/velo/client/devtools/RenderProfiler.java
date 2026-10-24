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
		frameStart = System.nanoTime();
	}

	public static void frameEnd() {
		if (frameStart == 0) {
			return;
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
					"Velo render profile [fast draw %s] (avg/frame over 600): frame %.3f ms | extract %.3f ms | record terrain %.3f ms (%d draws) | wait acquire %.3f ms | submit+present %.3f ms | %d barriers",
					net.veloclient.velo.client.modules.performance.PerformanceBoostModule.fastDrawPath ? "on" : "off",
					frameNanos / 600e6, extractNanos / 600e6, terrainNanos / 600e6, terrainDraws / 600, acquireNanos / 600e6, presentNanos / 600e6, barriers / 600));
			barriers = 0;
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
		}
	}
}
