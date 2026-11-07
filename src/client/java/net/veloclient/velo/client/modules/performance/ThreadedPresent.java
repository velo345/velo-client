package net.veloclient.velo.client.modules.performance;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Velo Turbo threaded present (Vulkan 26.2+, only together with {@link DecoupledPresent}).
 *
 * <p>At the end of every frame vanilla submits the frame's command buffers and then calls vkQueuePresentKHR on the
 * render thread. Both are driver calls that can take a while (the present also talks to the compositor), and the
 * render thread can't start the next frame until they return. Profiled on the Chalet bench: ~25% of the render
 * thread's time sat in those calls while the CPU work of the next frame waited.
 *
 * <p>Here the frame's last queue submit and its present go to one worker thread, in order, and the render thread goes
 * straight on with the next frame. Vulkan needs the queue and the swapchain externally synchronized, so every other
 * use of them first waits until the worker is empty: other submits (uploads, readbacks), queue waitIdle, swapchain
 * configure/close and the swapchain acquire. The acquire at the start of the next frame is skipped while the worker is
 * still presenting - decoupled present's late acquire right before the blit picks the image up instead, so the whole
 * CPU part of the next frame overlaps with the present.
 *
 * <p>Errors on the worker are rethrown on the render thread at the next hand-off or wait, so a lost device still
 * crashes the same way as in vanilla. Opt-in with {@code -Dvelo.threadedPresent=true}: on a GPU-bound bench it changed
 * nothing (the render thread then just waits for the GPU elsewhere); it can only help when the CPU is the limit.
 */
public final class ThreadedPresent {

	/** Opt-in: measured 2026-11-07 on the Chalet bench (RTX 3060, GPU ~85% busy) = no FPS change, and the worker spins. */
	private static final boolean ON = Boolean.getBoolean("velo.threadedPresent");
	private static final ConcurrentLinkedQueue<Runnable> JOBS = new ConcurrentLinkedQueue<>();
	/** Jobs queued and not finished yet (a job counts until it has run). */
	private static final AtomicInteger PENDING = new AtomicInteger();
	private static volatile Thread worker;
	private static volatile boolean parked;
	private static volatile Throwable failure;
	/** Set by the frame hook around the end-of-frame submit: that submit's queue call may be handed off. */
	public static boolean frameSubmit;
	/** Result of the last handed-off present that the surface hasn't looked at yet, or {@link #NO_RESULT}. */
	private static volatile int presentResult = Integer.MIN_VALUE;
	public static final int NO_RESULT = Integer.MIN_VALUE;

	private ThreadedPresent() {
	}

	/** True when the frame's submit and present go to the worker. */
	public static boolean enabled() {
		return ON && PerformanceBoostModule.turbo && DecoupledPresent.active();
	}

	/** True while the worker still has a submit or present to run. */
	public static boolean busy() {
		return PENDING.get() > 0;
	}

	public static boolean onWorker() {
		return Thread.currentThread() == worker;
	}

	/** Runs a queue job on the worker, after everything handed off before it. */
	public static void handOff(Runnable job) {
		rethrow();
		Thread w = worker;
		if (w == null || !w.isAlive()) {
			w = start();
		}
		PENDING.incrementAndGet();
		JOBS.add(job);
		if (parked) {
			LockSupport.unpark(w);
		}
	}

	/** Waits until every handed-off job has run. Cheap when the worker is empty; never call it on the worker. */
	public static void drain() {
		if (PENDING.get() > 0 && !onWorker()) {
			int spins = 0;
			while (PENDING.get() > 0) {
				Thread w = worker;
				if (w == null || !w.isAlive()) {
					// The worker died: nothing will run what's left - drop it, the failure is rethrown below.
					JOBS.clear();
					PENDING.set(0);
					break;
				}
				if (++spins < 2000) {
					Thread.onSpinWait();
				} else {
					LockSupport.parkNanos(20_000L);
				}
			}
		}
		rethrow();
	}

	/** Stores the VkResult of a present that ran on the worker. */
	public static void presentDone(int result) {
		presentResult = result;
	}

	/** The last present's result for the surface to act on (suboptimal / out of date), once. */
	public static int takePresentResult() {
		int r = presentResult;
		if (r != NO_RESULT) {
			presentResult = NO_RESULT;
		}
		return r;
	}

	private static void rethrow() {
		Throwable t = failure;
		if (t != null) {
			failure = null;
			if (t instanceof RuntimeException e) {
				throw e;
			}
			if (t instanceof Error e) {
				throw e;
			}
			throw new IllegalStateException("Velo present thread failed", t);
		}
	}

	private static synchronized Thread start() {
		Thread w = worker;
		if (w != null && w.isAlive()) {
			return w;
		}
		w = new Thread(ThreadedPresent::work, "Velo Present");
		w.setDaemon(true);
		w.setPriority(Thread.MAX_PRIORITY);
		worker = w;
		w.start();
		return w;
	}

	private static void work() {
		int idle = 0;
		while (true) {
			Runnable job = JOBS.poll();
			if (job == null) {
				// Frames come every few hundred microseconds at high FPS: spin briefly before parking.
				if (++idle < 4000) {
					Thread.onSpinWait();
					continue;
				}
				parked = true;
				if (JOBS.isEmpty()) {
					LockSupport.parkNanos(2_000_000L);
				}
				parked = false;
				idle = 0;
				continue;
			}
			idle = 0;
			try {
				job.run();
			} catch (Throwable t) {
				failure = t;
				JOBS.clear();
				PENDING.set(0);
				continue;
			}
			PENDING.decrementAndGet();
		}
	}
}
