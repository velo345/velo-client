package net.veloclient.launcher.ui;

import javafx.scene.Cursor;
import javafx.stage.Stage;
import net.veloclient.launcher.LauncherLog;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Linux: hands moving and resizing the launcher's frameless window to the window manager (GDK's begin_move_drag /
 * begin_resize_drag, what GTK apps, Chrome and Discord do), instead of moving it ourselves. Setting the window's size
 * from Java isn't reliably followed by KWin under Wayland (XWayland): JavaFX then lays out for a size the window
 * doesn't have - black where it shrank, content past the edge where it grew, clicks landing in the wrong place.
 *
 * <p>Needs JavaFX's internals opened (see the launcher's JVM options); returns false whenever it can't, and
 * {@link WindowChrome} falls back to moving the window itself.
 */
final class NativeWindowDrag {

	private static boolean tried;
	private static MethodHandle displayDefault;
	private static MethodHandle lookupWindow;
	private static MethodHandle beginResize;
	private static MethodHandle beginMove;

	private NativeWindowDrag() {
	}

	/** Starts a window-manager resize from {@code edge} (a resize cursor); false if not available. */
	static boolean resize(Stage stage, Cursor edge, double screenX, double screenY) {
		int gdkEdge = edge == Cursor.NW_RESIZE ? 0 : edge == Cursor.N_RESIZE ? 1 : edge == Cursor.NE_RESIZE ? 2 : edge == Cursor.W_RESIZE ? 3
				: edge == Cursor.E_RESIZE ? 4 : edge == Cursor.SW_RESIZE ? 5 : edge == Cursor.S_RESIZE ? 6 : edge == Cursor.SE_RESIZE ? 7 : -1;
		if (gdkEdge < 0) {
			return false;
		}
		MemorySegment window = gdkWindow(stage);
		if (window == null) {
			return false;
		}
		try {
			beginResize.invokeExact(window, gdkEdge, 1, root(stage, screenX), root(stage, screenY), 0);
			return true;
		} catch (Throwable t) {
			disable("resize", t);
			return false;
		}
	}

	/** Starts a window-manager move (the window follows the pointer, with the desktop's snapping); false if not available. */
	static boolean move(Stage stage, double screenX, double screenY) {
		MemorySegment window = gdkWindow(stage);
		if (window == null) {
			return false;
		}
		try {
			beginMove.invokeExact(window, 1, root(stage, screenX), root(stage, screenY), 0);
			return true;
		} catch (Throwable t) {
			disable("move", t);
			return false;
		}
	}

	/** JavaFX screen coordinates are scaled by the UI scale; GDK's root coordinates aren't. */
	private static int root(Stage stage, double v) {
		return (int) Math.round(v * stage.getOutputScaleX());
	}

	private static MemorySegment gdkWindow(Stage stage) {
		if (!init()) {
			return null;
		}
		try {
			long xid = nativeWindow(stage);
			if (xid == 0) {
				return null;
			}
			MemorySegment display = (MemorySegment) displayDefault.invokeExact();
			if (display.address() == 0) {
				return null;
			}
			MemorySegment window = (MemorySegment) lookupWindow.invokeExact(display, xid);
			return window.address() == 0 ? null : window;
		} catch (Throwable t) {
			disable("window lookup", t);
			return null;
		}
	}

	/** The stage's X11 window id, from JavaFX's glass window (internals; opened by the launcher's JVM options). */
	private static long nativeWindow(Stage stage) throws ReflectiveOperationException {
		Object peer = Class.forName("com.sun.javafx.stage.WindowHelper").getMethod("getPeer", javafx.stage.Window.class).invoke(null, stage);
		if (peer == null) {
			return 0;
		}
		java.lang.reflect.Method platform = null;
		for (Class<?> c = peer.getClass(); c != null && platform == null; c = c.getSuperclass()) {
			try {
				platform = c.getDeclaredMethod("getPlatformWindow");
			} catch (NoSuchMethodException ignored) {
			}
		}
		if (platform == null) {
			return 0;
		}
		platform.setAccessible(true);
		Object glass = platform.invoke(peer);
		return glass == null ? 0 : (long) Class.forName("com.sun.glass.ui.Window").getMethod("getNativeWindow").invoke(glass);
	}

	private static synchronized boolean init() {
		if (tried) {
			return beginMove != null;
		}
		tried = true;
		if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux") || Boolean.getBoolean("velo.noNativeDrag")) {
			return false;
		}
		try {
			Linker linker = Linker.nativeLinker();
			SymbolLookup gdk = SymbolLookup.libraryLookup("libgdk-3.so.0", Arena.global());
			displayDefault = linker.downcallHandle(gdk.find("gdk_display_get_default").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
			// Only an X11 display has X11 windows (JavaFX runs GTK on X11 / XWayland).
			MethodHandle typeName = linker.downcallHandle(gdk.find("gdk_x11_display_get_type").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_LONG));
			MethodHandle isA = linker.downcallHandle(SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global()).find("g_type_check_instance_is_a").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
			MemorySegment display = (MemorySegment) displayDefault.invokeExact();
			if (display.address() == 0 || (int) isA.invokeExact(display, (long) typeName.invokeExact()) == 0) {
				LauncherLog.info("Window drag: not an X11 display - moving the window ourselves");
				return false;
			}
			lookupWindow = linker.downcallHandle(gdk.find("gdk_x11_window_lookup_for_display").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
			beginResize = linker.downcallHandle(gdk.find("gdk_window_begin_resize_drag").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
					ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
			beginMove = linker.downcallHandle(gdk.find("gdk_window_begin_move_drag").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
					ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
			return true;
		} catch (Throwable t) {
			disable("setup", t);
			return false;
		}
	}

	private static void disable(String what, Throwable t) {
		LauncherLog.info("Window drag: native " + what + " unavailable (" + t + ") - moving the window ourselves");
		beginMove = null;
		beginResize = null;
		tried = true;
	}
}
