package net.veloclient.velo.client.modules.performance;

/**
 * Decoupled present (Vulkan, 26.2+): renders as fast as the GPU allows and shows the newest
 * finished frame whenever the screen can take one, instead of letting the swapchain set the pace.
 *
 * <p>Vanilla asks the swapchain for its next image with a 5 second timeout at the start of every
 * frame and simply waits until the compositor hands one back. Measured on the benchmark scene
 * (1440p, RTX 3060, XWayland): of a 2.0 ms frame, 1.45 ms was that wait - the CPU was idle and the
 * GPU half empty. With this on, the acquire doesn't wait: when no image is free the frame is still
 * rendered and submitted (the GPU stays busy, the next frame starts right away) but isn't shown,
 * and right before showing a frame the acquire is tried once more, so whenever an image frees up
 * the newest frame goes to the screen. Frames in flight stay capped by vanilla's own submit
 * throttle, so a GPU-bound game waits on the GPU (full utilization) rather than on the display.
 *
 * <p>The FPS counter counts rendered frames (like other clients' counters); {@link #shownFps()} is
 * how many of those reached the screen. Off while VSync is on - VSync means "wait for the display".
 */
public final class DecoupledPresent {

	/** Set by the acquire hook: the swapchain had no free image just now. */
	public static boolean missed;

	private static long windowStart;
	private static int shownThisSecond;
	private static int shownFps;

	private DecoupledPresent() {
	}

	public static boolean active() {
		if (!PerformanceBoostModule.decoupledPresent) {
			return false;
		}
		net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
		return client.options != null && !client.options.getEnableVsync().getValue();
	}

	/** A frame actually went to the screen. */
	public static void onPresent() {
		shownThisSecond++;
		long now = System.nanoTime();
		if (now - windowStart >= 1_000_000_000L) {
			shownFps = shownThisSecond;
			shownThisSecond = 0;
			windowStart = now;
		}
	}

	/** Frames that reached the screen in the last second (the FPS counter shows rendered frames). */
	public static int shownFps() {
		return shownFps;
	}
}
