package net.veloclient.velo.client.util;

import net.minecraft.client.MinecraftClient;

/**
 * Whether the game window is really the one in front - on Windows the game's own "focused" flag isn't enough.
 *
 * <p>A window made by a process in the background (the game started from the launcher while the player is in another
 * window) gets keyboard focus inside its own thread even though Windows keeps it behind; GLFW then reports it focused,
 * so joining a world captured the mouse (hidden and clipped to a window that wasn't even visible). Windows' own answer
 * is {@code GetForegroundWindow()}, called here through LWJGL's dynamic native calls. Elsewhere, and whenever the call
 * isn't available, this says yes and the game's own focus check decides as before.
 *
 * <p>The same native access reads the physical mouse buttons ({@code GetAsyncKeyState}) for {@link StuckButtons}.
 */
public final class WindowFront {

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	private static long getForegroundWindow;
	private static long getAsyncKeyState;
	private static long getSystemMetrics;
	private static long mapVirtualKey;
	private static boolean loaded;
	private static long hwnd;
	/** A mouse grab was refused because another window was in front; done when the game comes to the front. */
	public static volatile boolean grabWaiting;

	private WindowFront() {
	}

	public static boolean windows() {
		return WINDOWS;
	}

	private static synchronized boolean load() {
		if (!loaded) {
			loaded = true;
			try {
				org.lwjgl.system.SharedLibrary user32 = org.lwjgl.system.Library.loadNative(WindowFront.class, "org.lwjgl", "user32");
				getForegroundWindow = user32.getFunctionAddress("GetForegroundWindow");
				getAsyncKeyState = user32.getFunctionAddress("GetAsyncKeyState");
				getSystemMetrics = user32.getFunctionAddress("GetSystemMetrics");
				mapVirtualKey = user32.getFunctionAddress("MapVirtualKeyW");
			} catch (Throwable t) {
				getForegroundWindow = 0;
				getAsyncKeyState = 0;
			}
		}
		return getForegroundWindow != 0;
	}

	/** The native window (HWND) of the game window, 0 if unknown. */
	private static long hwnd() {
		if (hwnd != 0) {
			return hwnd;
		}
		long handle = MinecraftClient.getInstance().getWindow().getHandle();
		if (Keys.SDL) {
			// 26.3+: an SDL window
			try {
				Class<?> video = Class.forName("org.lwjgl.sdl.SDLVideo");
				int props = (int) video.getMethod("SDL_GetWindowProperties", long.class).invoke(null, handle);
				hwnd = (long) Class.forName("org.lwjgl.sdl.SDLProperties").getMethod("SDL_GetPointerProperty", int.class, CharSequence.class, long.class)
						.invoke(null, props, "SDL.window.win32.hwnd", 0L);
			} catch (ReflectiveOperationException | LinkageError ignored) {
				hwnd = 0;
			}
			return hwnd;
		}
		try {
			hwnd = (long) Class.forName("org.lwjgl.glfw.GLFWNativeWin32").getMethod("glfwGetWin32Window", long.class).invoke(null, handle);
		} catch (ReflectiveOperationException | LinkageError ignored) {
			hwnd = 0;
		}
		return hwnd;
	}

	/** Called every tick (Windows): finishes a grab that waited for the window to come to the front. */
	static void tickWaitingGrab(MinecraftClient client) {
		if (!grabWaiting) {
			return;
		}
		if (ClientCompat.currentScreen() != null || client.world == null) {
			grabWaiting = false;
			return;
		}
		if (inFront()) {
			grabWaiting = false;
			//? if <26.1 {
			client.mouse.lockCursor();
			//?} else {
			/*client.mouseHandler.grabMouse();
			*///?}
		}
	}

	/** True unless this is Windows and another window is in front of the game. Main thread. */
	public static boolean inFront() {
		if (!WINDOWS || !load()) {
			return true;
		}
		long own = hwnd();
		if (own == 0) {
			return true;
		}
		try {
			return org.lwjgl.system.JNI.callP(getForegroundWindow) == own;
		} catch (Throwable t) {
			return true;
		}
	}

	/**
	 * Windows' hardware scan code for a GLFW key code (0 when unknown / not Windows). The browser's native key handling on
	 * Windows turns the scan code back into the key - without it Tab, Backspace and friends did nothing in a page.
	 */
	public static int scanCodeForGlfw(int key) {
		if (!WINDOWS || !load() || mapVirtualKey == 0) {
			return 0;
		}
		int vk = virtualKey(key);
		if (vk <= 0) {
			return 0;
		}
		try {
			return org.lwjgl.system.JNI.callI(vk, 0 /* MAPVK_VK_TO_VSC */, mapVirtualKey);
		} catch (Throwable t) {
			return 0;
		}
	}

	private static int virtualKey(int key) {
		if (key >= 'A' && key <= 'Z' || key >= '0' && key <= '9' || key == ' ') {
			return key;
		}
		if (key >= 290 && key <= 301) {
			return 0x70 + key - 290; // F1-F12
		}
		if (key >= 320 && key <= 329) {
			return 0x60 + key - 320; // keypad 0-9
		}
		return switch (key) {
			case 39 -> 0xDE;
			case 44 -> 0xBC;
			case 45 -> 0xBD;
			case 46 -> 0xBE;
			case 47 -> 0xBF;
			case 59 -> 0xBA;
			case 61 -> 0xBB;
			case 91 -> 0xDB;
			case 92 -> 0xDC;
			case 93 -> 0xDD;
			case 96 -> 0xC0;
			case 256 -> 0x1B;
			case 257, 335 -> 0x0D;
			case 258 -> 0x09;
			case 259 -> 0x08;
			case 260 -> 0x2D;
			case 261 -> 0x2E;
			case 262 -> 0x27;
			case 263 -> 0x25;
			case 264 -> 0x28;
			case 265 -> 0x26;
			case 266 -> 0x21;
			case 267 -> 0x22;
			case 268 -> 0x24;
			case 269 -> 0x23;
			case 280 -> 0x14;
			case 281 -> 0x91;
			case 282 -> 0x90;
			case 283 -> 0x2C;
			case 284 -> 0x13;
			case 340 -> 0xA0;
			case 341 -> 0xA2;
			case 342 -> 0xA4;
			case 344 -> 0xA1;
			case 345 -> 0xA3;
			case 346 -> 0xA5;
			default -> 0;
		};
	}

	/**
	 * The physical state of a mouse button (0 left, 1 right, 2 middle) on Windows, or null when it can't be read (other
	 * systems, no native access). Honours swapped mouse buttons.
	 */
	public static Boolean mouseButtonDown(int button) {
		if (!WINDOWS || !load() || getAsyncKeyState == 0) {
			return null;
		}
		try {
			boolean swapped = getSystemMetrics != 0 && org.lwjgl.system.JNI.callI(23 /* SM_SWAPBUTTON */, getSystemMetrics) != 0;
			int vk = switch (button) {
				case 0 -> swapped ? 0x02 : 0x01; // VK_RBUTTON : VK_LBUTTON
				case 1 -> swapped ? 0x01 : 0x02;
				case 2 -> 0x04; // VK_MBUTTON
				default -> -1;
			};
			if (vk < 0) {
				return null;
			}
			return (org.lwjgl.system.JNI.callS(vk, getAsyncKeyState) & 0x8000) != 0;
		} catch (Throwable t) {
			return null;
		}
	}
}
