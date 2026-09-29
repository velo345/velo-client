package net.veloclient.velo.client.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-module processing cost so the Module Profiler debug overlay can show which module is
 * actually expensive, instead of guessing from overall FPS. Renderer-agnostic: every sample is
 * timed with {@link System#nanoTime()} around plain Java module code, so this works identically
 * whether the frame is being drawn through Sodium/OpenGL or a Vulkan backend - the profiler never
 * touches GPU state, it only measures the CPU-side time this mod's own code takes.
 */
public final class ModuleProfiler {

	/** Which event phase a sample was taken in, shown as a column in the overlay. */
	public enum Phase {
		TICK("Tick"),
		WORLD_RENDER("World"),
		HUD_RENDER("HUD");

		private final String label;

		Phase(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/** How long a decaying max is held before it's allowed to reset, so a one-off spike stays visible briefly. */
	private static final long MAX_WINDOW_NANOS = 1_000_000_000L;
	/** Smoothing factor for the rolling average; higher reacts faster, lower is steadier. */
	private static final double EMA_ALPHA = 0.1;

	private static final Map<Key, Sample> SAMPLES = new ConcurrentHashMap<>();

	/**
	 * Only measures while the Module Profiler overlay is on - every tick/render hook in the mod runs
	 * through {@link #time}, and recording (map lookup + synchronized update) on every one of those
	 * calls, every frame, for an overlay nobody is looking at was pure overhead.
	 */
	public static volatile boolean enabled;

	private ModuleProfiler() {
	}

	/** Times {@code action} and records the result under {@code moduleId}/{@code phase}. Exceptions still propagate. */
	public static void time(String moduleId, Phase phase, Runnable action) {
		if (!enabled) {
			action.run();
			return;
		}
		long start = System.nanoTime();
		try {
			action.run();
		} finally {
			record(moduleId, phase, System.nanoTime() - start);
		}
	}

	public static void record(String moduleId, Phase phase, long nanos) {
		SAMPLES.computeIfAbsent(new Key(moduleId, phase), k -> new Sample()).add(nanos);
	}

	/** Snapshot of every module that has recorded at least one sample, sorted most expensive first. */
	public static List<Entry> snapshot() {
		List<Entry> entries = new ArrayList<>(SAMPLES.size());
		long now = System.nanoTime();
		SAMPLES.forEach((key, sample) -> entries.add(new Entry(key.moduleId, key.phase, sample.avgMs(), sample.windowMaxMs(now))));
		entries.sort((a, b) -> Double.compare(b.avgMs, a.avgMs));
		return entries;
	}

	public record Entry(String moduleId, Phase phase, double avgMs, double maxMs) {
	}

	private record Key(String moduleId, Phase phase) {
	}

	/** Rolling average (EMA) plus a max that decays over {@link #MAX_WINDOW_NANOS} instead of an all-time high. */
	private static final class Sample {
		private volatile double emaNanos = -1;
		private volatile long windowMaxNanos = 0;
		private volatile long windowStartNanos = 0;

		synchronized void add(long nanos) {
			emaNanos = emaNanos < 0 ? nanos : emaNanos + EMA_ALPHA * (nanos - emaNanos);
			long now = System.nanoTime();
			if (now - windowStartNanos > MAX_WINDOW_NANOS) {
				windowStartNanos = now;
				windowMaxNanos = nanos;
			} else {
				windowMaxNanos = Math.max(windowMaxNanos, nanos);
			}
		}

		double avgMs() {
			return Math.max(0, emaNanos) / 1_000_000.0;
		}

		double windowMaxMs(long now) {
			return now - windowStartNanos > MAX_WINDOW_NANOS ? avgMs() : windowMaxNanos / 1_000_000.0;
		}
	}
}
