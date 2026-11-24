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

	/**
	 * On by default since 2026-11-11 (static bench +1.2% FPS, +4% 1% lows once Velo was CPU-bound; 2026-11-07 it was
	 * GPU-bound and gained nothing). {@code -Dvelo.threadedPresent=false} turns it off, {@code =true} skips the
	 * adaptive gate below (A/B runs).
	 */
	private static final String MODE = System.getProperty("velo.threadedPresent", "auto");
	private static final boolean ON = !"false".equals(MODE);
	private static final boolean FORCE = "true".equals(MODE);
	private static final boolean STATS = Boolean.getBoolean("velo.presentStats");
	/** Set by the surface's configure hook: the swapchain really runs IMMEDIATE (VSync off and the driver offers it). */
	public static volatile boolean immediate;
	/** The frame's decision, taken once at its start so its submit and present take the same path. */
	private static boolean frameOn;
	// Adaptive gate: the worker only pays off while the CPU is the limit. Over windows of 128+ frames / 0.5+ s, time the render
	// thread spends waiting for the GPU (submit throttle) and for the swapchain (acquire) is compared with the frame
	// time: on stays on while waits <= 20% of it, off turns on again below 10% (hysteresis, so it doesn't flap).
	private static boolean gateOn;
	private static long lastFrame, winFrame, winWait, winAcquire;
	private static int winFrames;
	/** Windows to stay off after the gate switched the worker off (see frameSubmitted). */
	private static int offHold;
	/** Idle spins before the worker parks (A/B knob: -Dvelo.presentSpins). */
	private static final int SPINS = Integer.getInteger("velo.presentSpins", 200);
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

	/** True when the frame's submit and present go to the worker (decided by {@link #beginFrame()}). */
	public static boolean enabled() {
		return frameOn;
	}

	/**
	 * The swapchain runs IMMEDIATE and frames don't wait for the display: the only case where the present can leave the
	 * render thread or the CPU may run further ahead. VSync (FIFO), MAILBOX-only drivers (e.g. some Wayland/Mesa
	 * setups even with VSync off) and macOS keep vanilla's path.
	 */
	public static boolean eligible() {
		return immediate && PerformanceBoostModule.turbo && DecoupledPresent.active();
	}

	/** Called at the start of every frame (before its acquire). */
	public static void beginFrame() {
		// Low Latency Mode's queue reduction waits in the submit: on the worker it would only block the worker
		frameOn = ON && eligible() && (FORCE || gateOn) && !LowLatencyModule.queueReduction();
	}

	/** Render thread time spent waiting for the GPU in the submit throttle. */
	public static void recordGpuWait(long nanos) {
		winWait += nanos;
	}

	/** Render thread time spent in the swapchain acquire. */
	public static void recordAcquire(long nanos) {
		winAcquire += nanos;
	}

	/** Called once per frame submit: closes a 128-frame window and updates the gate. */
	public static void frameSubmitted() {
		long now = System.nanoTime();
		long dt = lastFrame == 0 ? 0 : now - lastFrame;
		lastFrame = now;
		if (dt <= 0 || dt > 250_000_000L || !eligible()) {
			resetWindow();
			return;
		}
		winFrame += dt;
		// At least 128 frames and half a second: at 3000+ FPS 128 frames are only ~40 ms and single GPU-wait spikes
		// flapped the worker off ~10% of the time (bench 2026-11-11, average wait 2%).
		if (++winFrames < 128 || winFrame < 500_000_000L) {
			return;
		}
		boolean was = gateOn;
		if (offHold > 0) {
			// Just switched off: with the worker off the GPU wait moves into the (unmeasured) inline present, so the next
			// window always looks wait-free - re-checking right away flapped on/off every window in GPU-bound farm scenes.
			offHold--;
		} else {
			gateOn = gateOn ? winAcquire + winWait <= winFrame * 0.2 : winWait < winFrame * 0.1;
			if (was && !gateOn) {
				offHold = 6;
			}
		}
		if (STATS || was != gateOn) {
			net.veloclient.velo.VeloClient.LOGGER.info(String.format(java.util.Locale.ROOT,
					"[ThreadedPresent] %.0f us/frame, gpu wait %.1f%%, acquire %.1f%% -> worker %s",
					winFrame / 1e3 / winFrames, 100.0 * winWait / winFrame, 100.0 * winAcquire / winFrame, gateOn ? "on" : "off"));
		}
		resetWindow();
	}

	private static void resetWindow() {
		winFrame = 0;
		winWait = 0;
		winAcquire = 0;
		winFrames = 0;
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
				// Spin only a few microseconds, then park: handOff unparks us. The old 4000 spins (~150 us) covered the
				// whole gap between two frames at 3000+ FPS, so the worker never parked and burned a core that the
				// render, cull and server threads needed (profiled 2026-11-11: 100% busy in queue polling).
				if (++idle < SPINS) {
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
