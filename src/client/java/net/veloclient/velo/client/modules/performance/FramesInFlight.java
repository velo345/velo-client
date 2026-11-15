package net.veloclient.velo.client.modules.performance;

/**
 * Velo Turbo frames in flight (Vulkan 26.2): how many frames the CPU may record ahead of the GPU.
 *
 * <p>Vanilla keeps two command pools and, after submitting frame N, waits until the GPU has finished frame N-1
 * before the next frame starts. When CPU and GPU need about the same time per frame - which is where Velo sits at
 * 3000+ FPS - each side keeps waiting for the other: profiled 2026-11-11, the render thread spent most of its native
 * time in that wait while the GPU was only ~84% busy.
 *
 * <p>The mixin keeps three command pools and a three-frame destruction queue all the time (both safe with either
 * distance below) and asks {@link #distance()} on every submit how far back to wait: 2 = vanilla, 3 = one more frame
 * of slack. Auto uses 3 only while frames are short (one extra frame then costs well under a millisecond of input
 * latency) and only on an IMMEDIATE swapchain with decoupled present (see {@link ThreadedPresent#eligible()}: no
 * VSync, no MAILBOX/FIFO-only driver, not macOS); vanilla's 2 otherwise, so 60-240 FPS play feels exactly as before.
 *
 * <p>{@code -Dvelo.framesInFlight=2|3|auto} (A/B knob, default auto).
 */
public final class FramesInFlight {

	private static final String MODE = System.getProperty("velo.framesInFlight", "auto");
	/** Auto: below this average frame time (2.5 ms = 400 FPS) the third frame is used. */
	private static final long FAST_NANOS = 2_500_000L;
	private static long lastSubmit;
	private static double avgNanos = 16_000_000;

	private FramesInFlight() {
	}

	/** How many command pools / destruction-queue slots to keep. */
	public static int pools() {
		return "2".equals(MODE) ? 2 : 3;
	}

	/** Called once per submit: the submit index distance to wait for (2 = vanilla). */
	public static long distance() {
		long now = System.nanoTime();
		if (lastSubmit != 0) {
			long dt = now - lastSubmit;
			if (dt > 0 && dt < 1_000_000_000L) {
				avgNanos += (dt - avgNanos) * 0.05;
			}
		}
		lastSubmit = now;
		return switch (MODE) {
			case "3" -> 3;
			case "auto" -> ThreadedPresent.eligible() && avgNanos < FAST_NANOS ? 3 : 2;
			default -> 2;
		};
	}
}
