package net.veloclient.velo.client.modules.performance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Velo Turbo - recycled Sodium cull trees. Every cull pass (Sodium's "Async Cull Thread", up to once per frame while the
 * camera moves) builds three section trees plus a portal forest for its ray occlusion test, each tree a fresh 32 KB
 * {@code long[4096]}. Profiled 2026-11-21 (26.2, flying): 17% of ALL bytes the game allocated were these arrays, and
 * 39 of the 41 frames over 8 ms were garbage-collector pauses - this was the biggest single source of that garbage.
 *
 * <p>The arrays are now reused:
 * <ul>
 * <li>portal trees ({@code RayOcclusionSectionTree$FlatTree}) only serve the ray test inside the cull pass - their
 * arrays go back the moment the pass returns;</li>
 * <li>result trees stay with Sodium as long as it keeps them ({@code cullResults} / {@code renderTree} of the
 * RenderSectionManager, read on the render thread). A pass's arrays go back only after one of its trees was seen in use
 * and then none of them is in use any more - checked on the render thread, the only thread that reads them.</li>
 * </ul>
 * Only trees built inside a cull pass are pooled; everything else Sodium allocates as before. Off with
 * {@code -Dvelo.sodiumTreePool=false} (or Turbo off).
 */
public final class SodiumTreePool {

	public static final boolean ON = !"false".equals(System.getProperty("velo.sodiumTreePool"));
	public static final int SIZE = 4096;
	private static final int MAX_FREE = 64;
	/** Results that never get used (a pass Sodium threw away) are forgotten after this many sweeps (not recycled). */
	private static final int MAX_UNPUBLISHED_SWEEPS = 600;

	private static final ConcurrentLinkedQueue<long[]> FREE = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger FREE_COUNT = new AtomicInteger();
	private static final ThreadLocal<Batch> CURRENT = new ThreadLocal<>();
	private static final ConcurrentLinkedQueue<Batch> FINISHED = new ConcurrentLinkedQueue<>();

	// render thread only
	private static final ArrayList<Batch> TRACKED = new ArrayList<>();
	private static Object trackedOwner;
	private static final boolean PROFILE = Boolean.getBoolean("velo.renderProfile");
	private static long lastLog;

	/** Counters for the bench log. */
	public static final AtomicInteger REUSED = new AtomicInteger();
	public static final AtomicInteger FRESH = new AtomicInteger();

	private SodiumTreePool() {
	}

	private static final class Batch {
		final ArrayList<long[]> flat = new ArrayList<>(8);
		final ArrayList<long[]> results = new ArrayList<>(8);
		Object[] owners;
		boolean published;
		int sweeps;
	}

	// ---------------------------------------------------------------- cull thread

	/** A cull pass starts on this thread. */
	public static void beginPass() {
		CURRENT.set(ON && PerformanceBoostModule.turbo ? new Batch() : null);
	}

	/** True while a cull pass runs on this thread: the next tree takes its array from {@link #take}. */
	public static boolean pooling() {
		return CURRENT.get() != null;
	}

	/** The array for a tree built inside the cull pass (all zeros, like a new one). */
	public static long[] take(boolean portal) {
		Batch batch = CURRENT.get();
		long[] array = FREE.poll();
		if (array != null) {
			FREE_COUNT.decrementAndGet();
			Arrays.fill(array, 0L);
			REUSED.incrementAndGet();
		} else {
			array = new long[SIZE];
			FRESH.incrementAndGet();
		}
		if (batch != null) {
			(portal ? batch.flat : batch.results).add(array);
		}
		return array;
	}

	/** The pass returned its result: portal arrays are free now, the result trees are handed to the render thread. */
	public static void endPass(Object wide, Object regular, Object local, boolean known) {
		Batch batch = CURRENT.get();
		CURRENT.remove();
		if (batch == null) {
			return;
		}
		release(batch.flat);
		batch.flat.clear();
		if (batch.results.isEmpty() || !known) {
			return; // unknown trees: the result arrays stay with them (garbage collector), never recycled
		}
		batch.owners = new Object[] {wide, regular, local};
		FINISHED.add(batch);
	}

	// ---------------------------------------------------------------- render thread

	/**
	 * Once per frame after Sodium picked its trees: passes whose trees were in use and no longer are give their arrays
	 * back. {@code manager} identifies the world's renderer (a new one = old passes are simply forgotten).
	 */
	public static void sweep(Object manager, Collection<?> live, Object renderTree) {
		if (manager != trackedOwner) {
			TRACKED.clear();
			FINISHED.clear();
			trackedOwner = manager;
		}
		for (Batch b; (b = FINISHED.poll()) != null; ) {
			TRACKED.add(b);
		}
		if (PROFILE) {
			long now = System.nanoTime();
			if (now - lastLog > 10_000_000_000L) {
				lastLog = now;
				net.veloclient.velo.VeloClient.LOGGER.info("Velo Sodium tree pool: {} reused, {} new arrays, {} passes held, {} free",
						REUSED.getAndSet(0), FRESH.getAndSet(0), TRACKED.size(), FREE_COUNT.get());
			}
		}
		for (int i = TRACKED.size() - 1; i >= 0; i--) {
			Batch b = TRACKED.get(i);
			if (inUse(b, live, renderTree)) {
				b.published = true;
			} else if (b.published) {
				release(b.results);
				TRACKED.remove(i);
			} else if (++b.sweeps > MAX_UNPUBLISHED_SWEEPS) {
				TRACKED.remove(i); // never used: left to the garbage collector, never recycled
			}
		}
	}

	private static boolean inUse(Batch b, Collection<?> live, Object renderTree) {
		for (Object owner : b.owners) {
			if (owner == null) {
				continue;
			}
			if (owner == renderTree) {
				return true;
			}
			for (Object l : live) {
				if (l == owner) {
					return true;
				}
			}
		}
		return false;
	}

	private static void release(ArrayList<long[]> arrays) {
		for (long[] a : arrays) {
			if (a.length == SIZE && FREE_COUNT.get() < MAX_FREE) {
				FREE.add(a);
				FREE_COUNT.incrementAndGet();
			}
		}
	}
}
