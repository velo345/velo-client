package net.veloclient.velo.client.util;

import java.util.Locale;

/**
 * Whether the game window is completely out of sight while it isn't the focused window - fully covered by the window in
 * front, on another virtual desktop / Space - so frames drawn now are frames nobody sees. Vanilla only throttles a
 * minimized window (and AFK); a game left behind a browser or Discord kept rendering at full speed, heating the PC and
 * draining laptop batteries for nothing. The frame cap hook (FrameCapMixin) then drops to {@link #HIDDEN_FPS}.
 *
 * <p>Deliberately conservative - "not sure" always means visible:
 * <ul>
 * <li>Never while the game has focus (alt-tabbing back is instant: the check runs every frame).</li>
 * <li><b>Windows</b>: the game is cloaked (another virtual desktop), or the foreground window - visible, not minimized,
 * not see-through (layered/transparent), not the desktop or taskbar, not one of our own windows - fully covers the
 * game's visible frame. Two windows each covering half of it count as visible.</li>
 * <li><b>macOS</b>: AppKit's own answer, {@code NSWindow.occlusionState} without the visible bit.</li>
 * <li><b>Linux</b>: no reliable answer (Wayland hides other windows from apps; XWayland can't see native Wayland
 * windows) - never throttles.</li>
 * <li>Never while OBS / Streamlabs / XSplit runs or a screen share is detected: a window capture of a covered window
 * still records, and the stream shouldn't drop to a slideshow.</li>
 * </ul>
 * Main thread only (native window calls).
 */
public final class WindowOcclusion {

	/** Frame rate while hidden: low enough to cost next to nothing, high enough that the game keeps ticking smoothly. */
	public static final int HIDDEN_FPS = 15;
	/** Settings toggle (Performance Boost, "Throttle Hidden Window"); on by default. */
	public static volatile boolean enabled = !Boolean.getBoolean("velo.noHiddenThrottle");

	private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
	private static final boolean WINDOWS = OS.contains("win");
	private static final boolean MAC = OS.contains("mac");
	private static final long CHECK_NANOS = 250_000_000L;

	private static long lastCheck;
	private static boolean hidden;
	private static boolean broken;

	// Windows
	private static boolean winLoaded;
	private static long fnForeground, fnGetWindowRect, fnIsIconic, fnIsVisible, fnGetLong, fnThreadPid, fnClassName, fnDwmAttr;
	private static long ownHwnd;

	// macOS
	private static long nsWindow, msgSend, selOcclusion;

	private WindowOcclusion() {
	}

	/** True while the game window can't be seen (see the class comment). Cheap: re-checked at most 4x a second. */
	public static boolean covered() {
		if (!enabled || broken || !(WINDOWS || MAC)) {
			return false;
		}
		if (focused()) {
			hidden = false;
			lastCheck = 0;
			return false;
		}
		long now = System.nanoTime();
		if (now - lastCheck < CHECK_NANOS) {
			return hidden;
		}
		lastCheck = now;
		try {
			boolean h = WINDOWS ? windowsHidden() : macHidden();
			hidden = h && !CaptureApps.running();
		} catch (Throwable t) {
			// a native call that isn't there on this system: stay out of the way for the rest of the session
			broken = true;
			hidden = false;
			net.veloclient.velo.VeloClient.LOGGER.warn("[Velo] hidden-window check unavailable: {}", t.toString());
		}
		return hidden;
	}

	private static boolean focused() {
		//? if <26.1 {
		return net.minecraft.client.MinecraftClient.getInstance().isWindowFocused();
		//?} else {
		/*return net.minecraft.client.Minecraft.getInstance().isWindowActive();
		*///?}
	}

	private static long windowHandle() {
		//? if <26.1 {
		return net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle();
		//?} else {
		/*return net.minecraft.client.Minecraft.getInstance().getWindow().handle();
		*///?}
	}

	// ---- Windows ----

	private static boolean windowsHidden() {
		if (!winLoaded) {
			winLoaded = true;
			org.lwjgl.system.SharedLibrary user32 = org.lwjgl.system.Library.loadNative(WindowOcclusion.class, "org.lwjgl", "user32");
			fnForeground = user32.getFunctionAddress("GetForegroundWindow");
			fnGetWindowRect = user32.getFunctionAddress("GetWindowRect");
			fnIsIconic = user32.getFunctionAddress("IsIconic");
			fnIsVisible = user32.getFunctionAddress("IsWindowVisible");
			fnGetLong = user32.getFunctionAddress("GetWindowLongW");
			fnThreadPid = user32.getFunctionAddress("GetWindowThreadProcessId");
			fnClassName = user32.getFunctionAddress("GetClassNameW");
			try {
				fnDwmAttr = org.lwjgl.system.Library.loadNative(WindowOcclusion.class, "org.lwjgl", "dwmapi").getFunctionAddress("DwmGetWindowAttribute");
			} catch (Throwable t) {
				fnDwmAttr = 0;
			}
			ownHwnd = win32Window();
			if (fnForeground == 0 || fnGetWindowRect == 0 || fnIsIconic == 0 || fnIsVisible == 0 || fnGetLong == 0 || fnThreadPid == 0
					|| fnClassName == 0 || ownHwnd == 0) {
				throw new IllegalStateException("user32 functions or the game's window handle missing");
			}
		}
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			// another virtual desktop: Windows "cloaks" the window
			if (cloaked(stack, ownHwnd)) {
				return true;
			}
			long fg = org.lwjgl.system.JNI.callP(fnForeground);
			if (fg == 0 || fg == ownHwnd) {
				return false;
			}
			if (org.lwjgl.system.JNI.callPI(fg, fnIsVisible) == 0 || org.lwjgl.system.JNI.callPI(fg, fnIsIconic) != 0 || cloaked(stack, fg)) {
				return false;
			}
			// one of the game's own windows (browser popup, file dialog)
			java.nio.IntBuffer pid = stack.mallocInt(1);
			org.lwjgl.system.JNI.callPPI(fg, org.lwjgl.system.MemoryUtil.memAddress(pid), fnThreadPid);
			if (pid.get(0) == ProcessHandle.current().pid()) {
				return false;
			}
			int exStyle = org.lwjgl.system.JNI.callPI(fg, -20 /* GWL_EXSTYLE */, fnGetLong);
			if ((exStyle & (0x00080000 /* LAYERED */ | 0x00000020 /* TRANSPARENT */ | 0x08000000 /* NOACTIVATE */)) != 0) {
				return false; // see-through overlays
			}
			int ownEx = org.lwjgl.system.JNI.callPI(ownHwnd, -20, fnGetLong);
			if ((ownEx & 0x00000008 /* TOPMOST */) != 0 && (exStyle & 0x00000008) == 0) {
				return false; // the game stays on top of it
			}
			String cls = className(stack, fg);
			if (cls.equals("Progman") || cls.equals("WorkerW") || cls.equals("Shell_TrayWnd") || cls.equals("Shell_SecondaryTrayWnd")) {
				return false; // the desktop sits behind everything
			}
			int[] own = frame(stack, ownHwnd);
			int[] front = frame(stack, fg);
			if (own == null || front == null || own[2] <= own[0] || own[3] <= own[1]) {
				return false;
			}
			int slack = 2;
			return front[0] <= own[0] + slack && front[1] <= own[1] + slack && front[2] >= own[2] - slack && front[3] >= own[3] - slack;
		}
	}

	private static boolean cloaked(org.lwjgl.system.MemoryStack stack, long hwnd) {
		if (fnDwmAttr == 0) {
			return false;
		}
		java.nio.IntBuffer value = stack.callocInt(1);
		int hr = org.lwjgl.system.JNI.callPPI(hwnd, 14 /* DWMWA_CLOAKED */, org.lwjgl.system.MemoryUtil.memAddress(value), 4, fnDwmAttr);
		return hr == 0 && value.get(0) != 0;
	}

	/** The window's visible frame (DWM extended frame bounds, without the invisible resize borders): l, t, r, b. */
	private static int[] frame(org.lwjgl.system.MemoryStack stack, long hwnd) {
		java.nio.IntBuffer rect = stack.callocInt(4);
		long addr = org.lwjgl.system.MemoryUtil.memAddress(rect);
		boolean ok = fnDwmAttr != 0 && org.lwjgl.system.JNI.callPPI(hwnd, 9 /* DWMWA_EXTENDED_FRAME_BOUNDS */, addr, 16, fnDwmAttr) == 0;
		if (!ok && org.lwjgl.system.JNI.callPPI(hwnd, addr, fnGetWindowRect) == 0) {
			return null;
		}
		return new int[] {rect.get(0), rect.get(1), rect.get(2), rect.get(3)};
	}

	private static String className(org.lwjgl.system.MemoryStack stack, long hwnd) {
		java.nio.ByteBuffer buf = stack.calloc(256 * 2);
		int n = org.lwjgl.system.JNI.callPPI(hwnd, org.lwjgl.system.MemoryUtil.memAddress(buf), 256, fnClassName);
		return n <= 0 ? "" : org.lwjgl.system.MemoryUtil.memUTF16(buf, n * 2);
	}

	private static long win32Window() {
		long handle = windowHandle();
		if (Keys.SDL) {
			return sdlPointer(handle, "SDL.window.win32.hwnd");
		}
		try {
			return (long) Class.forName("org.lwjgl.glfw.GLFWNativeWin32").getMethod("glfwGetWin32Window", long.class).invoke(null, handle);
		} catch (ReflectiveOperationException | LinkageError e) {
			return 0;
		}
	}

	// ---- macOS ----

	private static boolean macHidden() {
		if (nsWindow == 0) {
			long handle = windowHandle();
			if (Keys.SDL) {
				nsWindow = sdlPointer(handle, "SDL.window.cocoa.window");
			} else {
				try {
					nsWindow = (long) Class.forName("org.lwjgl.glfw.GLFWNativeCocoa").getMethod("glfwGetCocoaWindow", long.class).invoke(null, handle);
				} catch (ReflectiveOperationException | LinkageError e) {
					nsWindow = 0;
				}
			}
			msgSend = org.lwjgl.system.macosx.ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
			selOcclusion = org.lwjgl.system.macosx.ObjCRuntime.sel_getUid("occlusionState");
			if (nsWindow == 0 || msgSend == 0 || selOcclusion == 0) {
				throw new IllegalStateException("no NSWindow");
			}
		}
		long state = org.lwjgl.system.JNI.callPPJ(nsWindow, selOcclusion, msgSend);
		return (state & 2L /* NSWindowOcclusionStateVisible */) == 0;
	}

	private static long sdlPointer(long window, String property) {
		try {
			Class<?> video = Class.forName("org.lwjgl.sdl.SDLVideo");
			int props = (int) video.getMethod("SDL_GetWindowProperties", long.class).invoke(null, window);
			return (long) Class.forName("org.lwjgl.sdl.SDLProperties").getMethod("SDL_GetPointerProperty", int.class, CharSequence.class, long.class)
					.invoke(null, props, property, 0L);
		} catch (ReflectiveOperationException | LinkageError e) {
			return 0;
		}
	}

	/** Streaming / recording apps and screen shares, checked off the main thread every 10 s (a process scan costs ms). */
	private static final class CaptureApps {

		private static volatile boolean running;
		private static volatile long lastScan;
		private static volatile boolean scanning;

		static boolean running() {
			if (net.veloclient.velo.client.privacy.ScreenShareDetector.sharing()) {
				return true;
			}
			long now = System.currentTimeMillis();
			if (!scanning && now - lastScan > 10_000) {
				scanning = true;
				lastScan = now;
				Thread t = new Thread(() -> {
					try {
						running = ProcessHandle.allProcesses().anyMatch(p -> p.info().command().map(CaptureApps::isCaptureApp).orElse(false));
					} catch (Throwable ignored) {
						running = false;
					} finally {
						scanning = false;
					}
				}, "velo-capture-scan");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				t.start();
			}
			return running;
		}

		private static boolean isCaptureApp(String command) {
			String name = command.substring(Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
			return name.equals("obs") || name.startsWith("obs64") || name.startsWith("obs32") || name.equals("obs.exe")
					|| name.startsWith("streamlabs") || name.startsWith("xsplit");
		}
	}
}
