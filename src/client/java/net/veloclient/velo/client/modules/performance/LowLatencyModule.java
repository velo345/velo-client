package net.veloclient.velo.client.modules.performance;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Low Latency Mode (opt-in, off by default): trades frames you never see for a fresher picture.
 *
 * <p><b>Reduce Render Queue</b> - when the GPU is the slower side, vanilla lets the CPU record up to two frames ahead
 * of it; every queued frame shows input that is one frame older. With this on, each frame waits until the GPU has
 * finished it before the next one samples mouse and keyboard, so what you see is at most one frame old (the idea
 * behind GPU vendors' low-latency modes). OpenGL: a fence after the buffer swap; Vulkan 26.2: the submit throttle
 * waits for the frame just submitted ({@link FramesInFlight#distance()} 1) and threaded present stays off. 26.3's
 * Vulkan renderer keeps its own queue (not hooked yet). Costs FPS - the CPU and GPU no longer overlap.
 *
 * <p><b>Frame Cap</b> - a precise limiter that replaces Max Framerate while playing, sleeping before the next frame
 * samples input (never between input and display). "Monitor Refresh" stops drawing frames the screen can't show;
 * "Refresh - 3" keeps G-Sync/FreeSync screens inside their range so VSync never queues frames. Vanilla's own throttles
 * (menus, AFK, minimized, hidden window) still win.
 *
 * <p>Off by default because both cost the FPS number (and Reduce Render Queue can cost real frames on a GPU-bound PC).
 */
public final class LowLatencyModule extends AbstractModule implements Configurable {

	private static final List<String> CAPS = List.of("Off", "Monitor Refresh", "Refresh - 3 (G-Sync/FreeSync)", "Custom");
	private static volatile LowLatencyModule instance;

	private String cap = "Monitor Refresh";
	private int customFps = 240;
	private boolean reduceQueue = true;

	private static long nextFrameNanos;

	public LowLatencyModule() {
		super("low-latency", "Low Latency Mode",
				"Shows a fresher picture: frames wait for the GPU instead of queueing, and an optional precise frame cap stops drawing frames your screen never shows. Lowers the FPS number, lowers input lag.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
	}

	@Override
	public void onDisable() {
		nextFrameNanos = 0;
	}

	/** Reduce Render Queue is on (the frame waits for the GPU before the next input sample). */
	public static boolean queueReduction() {
		LowLatencyModule m = instance;
		return m != null && m.isEnabled() && m.reduceQueue;
	}

	/** The module's frame cap in FPS, 0 = no cap. */
	public static int capFps() {
		LowLatencyModule m = instance;
		if (m == null || !m.isEnabled()) {
			return 0;
		}
		return switch (m.cap) {
			case "Monitor Refresh" -> Math.max(30, net.veloclient.velo.client.util.MenuFps.refreshRate());
			case "Refresh - 3 (G-Sync/FreeSync)" -> Math.max(30, net.veloclient.velo.client.util.MenuFps.refreshRate() - 3);
			case "Custom" -> m.customFps;
			default -> 0;
		};
	}

	/**
	 * Vanilla's frame limit hook (FrameCapMixin): while this module caps and vanilla isn't throttling (the limit is the
	 * player's own Max Framerate), vanilla's limiter steps aside (260 = unlimited) and {@link #frameEnd()} paces instead.
	 */
	public static int vanillaLimit(int limit) {
		return capFps() > 0 && limit == configuredMaxFps() ? 260 : limit;
	}

	private static int configuredMaxFps() {
		//? if <26.1 {
		return net.minecraft.client.MinecraftClient.getInstance().options.getMaxFps().getValue();
		//?} else {
		/*return net.minecraft.client.Minecraft.getInstance().options.framerateLimit().get();
		*///?}
	}

	/** End of every frame, after the present and vanilla's limiter, before the next frame polls input. Main thread. */
	public static void frameEnd() {
		LowLatencyModule m = instance;
		if (m == null || !m.isEnabled()) {
			return;
		}
		boolean waited = false;
		if (m.reduceQueue) {
			waited = waitForGpu();
		}
		int fps = capFps();
		if (fps > 0 && !net.veloclient.velo.client.util.WindowOcclusion.covered()) {
			pace(fps);
			waited = true;
		} else {
			nextFrameNanos = 0;
		}
		//? if <26.1 {
		// 1.21.x polls input inside the buffer swap, before this wait: poll again so the next frame sees the newest input
		// (26.x polls at the top of its loop, right after this).
		if (waited) {
			net.veloclient.velo.client.util.Keys.pollEvents();
		}
		//?}
	}

	/** Sleep, then spin the last half millisecond: within ~50 us of the target even on Windows' coarse timer. */
	private static void pace(int fps) {
		long period = 1_000_000_000L / fps;
		long now = System.nanoTime();
		if (nextFrameNanos == 0 || now - nextFrameNanos > period) {
			nextFrameNanos = now + period; // first frame, or we fell behind: don't try to catch up with a burst
			return;
		}
		long target = nextFrameNanos;
		long remaining;
		while ((remaining = target - System.nanoTime()) > 0) {
			if (remaining > 1_500_000L) {
				LockSupport.parkNanos(remaining - 1_000_000L);
			} else {
				Thread.onSpinWait();
			}
		}
		nextFrameNanos = target + period;
	}

	/**
	 * Waits until the GPU has finished the frame that was just presented (OpenGL only - Vulkan 26.2 waits in the submit
	 * throttle instead, see the class comment). True when it waited.
	 */
	private static boolean waitForGpu() {
		try {
			//? if >=26.2 {
			/*var device = com.mojang.blaze3d.systems.RenderSystem.tryGetDevice();
			if (device == null || "vulkan".equalsIgnoreCase(device.getDeviceInfo().backendName())) {
				return false;
			}
			*///?}
			if (org.lwjgl.opengl.GL.getCapabilities() == null) {
				return false;
			}
			long sync = org.lwjgl.opengl.GL32C.glFenceSync(org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
			if (sync == 0) {
				return false;
			}
			// FLUSH: the fence itself must reach the GPU, or the wait would sit out the whole timeout
			org.lwjgl.opengl.GL32C.glClientWaitSync(sync, org.lwjgl.opengl.GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L);
			org.lwjgl.opengl.GL32C.glDeleteSync(sync);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Reduce Render Queue", () -> reduceQueue, v -> reduceQueue = v),
				new ConfigField.ChoiceField("Frame Cap", CAPS, () -> cap, v -> {
					cap = CAPS.contains(v) ? v : "Monitor Refresh";
					nextFrameNanos = 0;
				}),
				new ConfigField.SliderField("Custom Cap", 30, 1000, () -> customFps, v -> customFps = (int) v, v -> (int) v + " FPS"));
	}
}
