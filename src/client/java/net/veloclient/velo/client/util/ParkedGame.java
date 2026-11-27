package net.veloclient.velo.client.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Instant Play, the game's half (see the launcher's InstantPlay): started with {@code -Dvelo.parked=true} the game builds
 * its window hidden ({@code ParkedWindowMixin}), loads as usual, rests at the title screen at a low frame rate and shows
 * its window when the launcher creates the file named by {@code -Dvelo.parkSignal}. A file instead of stdin because a
 * Windows GUI process can't count on a usable standard input.
 *
 * <p>A waiting game that outlives the launcher (killed, crashed) closes itself: nobody could ever show it.
 */
public final class ParkedGame {

	private static final String SIGNAL = System.getProperty("velo.parkSignal");
	/** Started by the launcher as a waiting game. */
	public static final boolean PARKED = Boolean.getBoolean("velo.parked") && SIGNAL != null && !SIGNAL.isBlank();
	/** Frames a second while waiting at the title screen: enough to keep it alive, next to nothing for the PC. */
	private static final int WAITING_FPS = 5;

	/** The last window mode/position the game asked for while waiting (GLFW's glfwSetWindowMonitor arguments), applied at the reveal. */
	private static long[] pendingMonitor;

	private static volatile boolean revealed;
	private static volatile boolean atTitle;
	private static volatile long window;
	private static boolean started;

	private ParkedGame() {
	}

	/** Hidden and not yet shown. */
	public static boolean waiting() {
		return PARKED && !revealed;
	}

	/** The window the game just created (hidden, see the mixin). */
	public static void windowCreated(long handle) {
		window = handle;
		if (PARKED) {
			System.out.println("[Velo] Instant Play: window created" + visibility(handle));
		}
	}

	/** True while waiting: the call is kept for the reveal instead of made (it would map the window on X11). */
	public static boolean deferMonitor(long handle, long monitor, int x, int y, int width, int height, int refresh) {
		if (!waiting()) {
			return false;
		}
		pendingMonitor = new long[] {handle, monitor, x, y, width, height, refresh};
		System.out.println("[Velo] Instant Play: window mode kept for the reveal");
		return true;
	}

	private static String visibility(long handle) {
		//? if <26.3 {
		return PARKED && handle != 0 ? (org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(handle, org.lwjgl.glfw.GLFW.GLFW_VISIBLE) == 0 ? " (hidden)" : " (VISIBLE)") : "";
		//?} else {
		/*return "";
		*///?}
	}

	/** Vanilla's frame limit while waiting: once the title screen is up there is nothing to draw for anyone. */
	public static int frameCap(int limit) {
		if (!waiting()) {
			return limit;
		}
		if (!atTitle && titleOpen()) {
			atTitle = true;
			System.out.println("[Velo] Instant Play: waiting at the title screen" + visibility(window));
		}
		return atTitle ? Math.min(limit, WAITING_FPS) : limit;
	}

	public static void init() {
		if (!PARKED || started) {
			return;
		}
		started = true;
		Path signal = Path.of(SIGNAL);
		Optional<ProcessHandle> parent = ProcessHandle.current().parent();
		long launcherPid = parent.map(ProcessHandle::pid).orElse(-1L);
		Thread watcher = new Thread(() -> {
			int ticks = 0;
			while (!revealed) {
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					return;
				}
				if (Files.exists(signal)) {
					reveal(signal);
					return;
				}
			}
		}, "Velo Instant Play");
		watcher.setDaemon(true);
		watcher.start();
	}

	private static void reveal(Path signal) {
		try {
			Files.deleteIfExists(signal);
		} catch (Exception ignored) {
			// nothing else reads it
		}
		// The window may not exist yet (Play pressed within the first seconds): wait for it, then show it on the render thread.
		long give = System.currentTimeMillis() + 120_000;
		while (window == 0 && System.currentTimeMillis() < give) {
			try {
				Thread.sleep(25);
			} catch (InterruptedException e) {
				return;
			}
		}
		long handle = window;
		revealed = true;
		runOnRenderThread(() -> {
			//? if <26.3 {
			if (handle != 0) {
				long[] m = pendingMonitor;
				pendingMonitor = null;
				if (m != null) {
					org.lwjgl.glfw.GLFW.glfwSetWindowMonitor(m[0], m[1], (int) m[2], (int) m[3], (int) m[4], (int) m[5], (int) m[6]);
				}
				org.lwjgl.glfw.GLFW.glfwShowWindow(handle);
				org.lwjgl.glfw.GLFW.glfwFocusWindow(handle);
			}
			//?}
			System.out.println("[Velo] Instant Play: window shown");
		});
	}

	private static void runOnRenderThread(Runnable task) {
		//? if <26.1 {
		var client = net.minecraft.client.MinecraftClient.getInstance();
		//?} else {
		/*var client = net.minecraft.client.Minecraft.getInstance();
		*///?}
		if (client != null) {
			client.execute(task);
		} else {
			task.run();
		}
	}

	private static boolean titleOpen() {
		//? if <26.1 {
		var client = net.minecraft.client.MinecraftClient.getInstance();
		return client != null && client.currentScreen instanceof net.minecraft.client.gui.screen.TitleScreen;
		//?} else if <26.2 {
		/*var client = net.minecraft.client.Minecraft.getInstance();
		return client != null && client.screen instanceof net.minecraft.client.gui.screens.TitleScreen;
		*///?} else {
		/*var client = net.minecraft.client.Minecraft.getInstance();
		return client != null && client.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen;
		*///?}
	}
}
