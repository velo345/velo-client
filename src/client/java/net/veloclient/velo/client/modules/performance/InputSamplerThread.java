package net.veloclient.velo.client.modules.performance;


import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * Polls raw mouse-button state off the main thread so a physical click's
 * timestamp doesn't depend on how often vanilla's own {@code
 * glfwPollEvents()} happens to run - during an FPS stall that call gets
 * sparse (it's tied to the render loop), so a click sitting between two
 * increasingly-far-apart polls shows up later than it actually happened.
 *
 * <p>GLFW's reference documents {@code glfwGetKey}/{@code
 * glfwGetMouseButton} as main-thread-only; in practice they're a plain,
 * unsynchronized read of a small state array with no cross-thread invariant
 * beyond "eventually consistent," which is why other high-frequency-input
 * Minecraft mods poll them off-thread too - but it's worth being upfront
 * that this isn't an officially-blessed usage. Only ever reads state and
 * timestamps edges here; nothing in this class ever touches entity/world
 * state, which stays exclusively on the main thread (see {@link
 * InputSamplerModule#onTickStart}).
 */
final class InputSamplerThread extends Thread {

	private final ConcurrentLinkedQueue<Long> attackEdges = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Long> useEdges = new ConcurrentLinkedQueue<>();
	private volatile int sampleHz = 1000;
	private volatile boolean running = true;
	private boolean attackWasDown;
	private boolean useWasDown;

	InputSamplerThread() {
		super("Velo Input Sampler");
		setDaemon(true);
	}

	void setSampleHz(int hz) {
		this.sampleHz = Math.max(1, hz);
	}

	void shutdown() {
		running = false;
		interrupt();
	}

	/** Drains and returns every attack-button press edge timestamped since the last call, oldest first. */
	java.util.List<Long> drainAttackEdges() {
		return drain(attackEdges);
	}

	java.util.List<Long> drainUseEdges() {
		return drain(useEdges);
	}

	private static java.util.List<Long> drain(ConcurrentLinkedQueue<Long> queue) {
		java.util.List<Long> out = new java.util.ArrayList<>();
		Long next;
		while ((next = queue.poll()) != null) {
			out.add(next);
		}
		return out;
	}

	@Override
	public void run() {
		while (running) {
			try {
				boolean attackDown = net.veloclient.velo.client.util.Keys.isMouseDown(net.veloclient.velo.client.util.Keys.MOUSE_LEFT);
				boolean useDown = net.veloclient.velo.client.util.Keys.isMouseDown(net.veloclient.velo.client.util.Keys.MOUSE_RIGHT);
				long now = System.nanoTime();
				if (attackDown && !attackWasDown) {
					attackEdges.add(now);
				}
				if (useDown && !useWasDown) {
					useEdges.add(now);
				}
				attackWasDown = attackDown;
				useWasDown = useDown;
			} catch (Exception ignored) {
				// The window handle can be transiently invalid during
				// startup/shutdown - never let a sampling hiccup kill the
				// whole client, just skip this cycle.
			}
			LockSupport.parkNanos(1_000_000_000L / sampleHz);
		}
	}
}
