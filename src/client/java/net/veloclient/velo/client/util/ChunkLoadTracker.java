package net.veloclient.velo.client.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thin, thread-safe recorder for how long each incoming chunk packet actually took to process
 * (written from {@code ChunkLoadTimingMixin}, on whatever thread the packet handler itself runs
 * on) - deliberately does no rendering, scoring, or disk I/O itself. {@link
 * net.veloclient.velo.client.modules.servertools.ChunkLoadProfilerModule} drains {@link
 * #drainPending()} once per client tick and does the heavier per-chunk work (entity/block-entity
 * counts, persisted "ever visited" lookups, scoring) there instead, off the packet-handling path.
 */
public final class ChunkLoadTracker {

	/** One raw timing sample, not yet scored or checked against "ever visited". */
	public record RawLoad(String dimension, int chunkX, int chunkZ, long nanos) {
	}

	private static final Queue<RawLoad> PENDING = new ConcurrentLinkedQueue<>();
	private static volatile double rollingAverageNanos = -1;

	private ChunkLoadTracker() {
	}

	public static void recordLoad(String dimension, int chunkX, int chunkZ, long nanos) {
		PENDING.add(new RawLoad(dimension, chunkX, chunkZ, nanos));
		rollingAverageNanos = rollingAverageNanos < 0 ? nanos : rollingAverageNanos * 0.9 + nanos * 0.1;
	}

	/** Everything recorded since the last drain, oldest first - the queue is empty again after this returns. */
	public static List<RawLoad> drainPending() {
		List<RawLoad> drained = new ArrayList<>();
		RawLoad next;
		while ((next = PENDING.poll()) != null) {
			drained.add(next);
		}
		return drained;
	}

	/** A slow-moving baseline across every chunk load so far this session, in milliseconds - the "relative to others" yardstick the profiler colors against. */
	public static double rollingAverageMs() {
		return rollingAverageNanos < 0 ? 0 : rollingAverageNanos / 1_000_000.0;
	}
}
