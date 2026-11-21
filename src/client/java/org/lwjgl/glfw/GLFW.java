//? if >=26.3 {
/*package org.lwjgl.glfw;

/^*
 * 26.3 only: Minecraft dropped GLFW for SDL, but the Velo Browser's native code (java-cef) looks up GLFW's key, button
 * and modifier numbers on this class for every mouse and key event - without it every click, scroll and key into a page
 * was silently dropped. Only the constants it reads, with GLFW's real values; never present where real GLFW ships.
 ^/
public final class GLFW {

	public static final int GLFW_RELEASE = 0, GLFW_PRESS = 1, GLFW_REPEAT = 2;
	public static final int GLFW_MOUSE_BUTTON_1 = 0, GLFW_MOUSE_BUTTON_2 = 1, GLFW_MOUSE_BUTTON_3 = 2;
	public static final int GLFW_MOD_SHIFT = 0x1, GLFW_MOD_CONTROL = 0x2, GLFW_MOD_ALT = 0x4, GLFW_MOD_SUPER = 0x8;
	public static final int GLFW_KEY_ESCAPE = 256, GLFW_KEY_ENTER = 257, GLFW_KEY_TAB = 258, GLFW_KEY_BACKSPACE = 259,
			GLFW_KEY_INSERT = 260, GLFW_KEY_DELETE = 261, GLFW_KEY_RIGHT = 262, GLFW_KEY_LEFT = 263, GLFW_KEY_DOWN = 264,
			GLFW_KEY_UP = 265, GLFW_KEY_PAGE_UP = 266, GLFW_KEY_PAGE_DOWN = 267, GLFW_KEY_HOME = 268, GLFW_KEY_END = 269,
			GLFW_KEY_CAPS_LOCK = 280, GLFW_KEY_SCROLL_LOCK = 281, GLFW_KEY_NUM_LOCK = 282, GLFW_KEY_PRINT_SCREEN = 283,
			GLFW_KEY_PAUSE = 284, GLFW_KEY_KP_2 = 322, GLFW_KEY_KP_4 = 324, GLFW_KEY_KP_6 = 326, GLFW_KEY_KP_8 = 328,
			GLFW_KEY_KP_ENTER = 335, GLFW_KEY_LEFT_CONTROL = 341, GLFW_KEY_RIGHT_CONTROL = 345;

	/^* Called by the native code on Windows for a few keys (Backspace, Insert, keypad arrows...). ^/
	public static int glfwGetKeyScancode(int key) {
		return net.veloclient.velo.client.util.WindowFront.scanCodeForGlfw(key);
	}

	private GLFW() {
	}
}
*///?}
