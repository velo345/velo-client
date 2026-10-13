package net.veloclient.velo.client.util;

//? if >=26.3 {
/*import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
*///?} else {
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
//?}

/**
 * Key codes, mouse buttons and raw input polling that work on every supported version.
 *
 * <p>Up to 26.2 the game used GLFW: keys are GLFW key codes (A = 65), mouse buttons are 0-based
 * (left 0, right 1). 26.3 replaced GLFW with SDL3: keys are SDL scancodes (A = 4), mouse buttons
 * are 1-based in SDL order (left 1, middle 2, right 3) and the modifier bits differ too. Never
 * write a raw number or a {@code GLFW.*} constant for a key or button - use these.
 *
 * <p>Velo's own settings stored GLFW codes before 26.3; {@link #fromStored} converts those
 * (see {@code ModuleStateStore} / {@code CommandKeybindStore}, which mark migrated files).
 */
public final class Keys {

	public static final int UNKNOWN = -1;
	//? if >=26.3 {
	/*public static final boolean SDL = true;
	public static final int ESCAPE = InputConstants.KEY_ESCAPE;
	public static final int ENTER = InputConstants.KEY_RETURN;
	public static final int KP_ENTER = InputConstants.KEY_NUMPADENTER;
	public static final int LEFT_SHIFT = InputConstants.KEY_LSHIFT;
	public static final int RIGHT_SHIFT = InputConstants.KEY_RSHIFT;
	public static final int LEFT_CONTROL = InputConstants.KEY_LCONTROL;
	public static final int RIGHT_CONTROL = InputConstants.KEY_RCONTROL;
	public static final int SPACE = InputConstants.KEY_SPACE;
	public static final int BACKSPACE = InputConstants.KEY_BACKSPACE;
	public static final int DELETE = InputConstants.KEY_DELETE;
	public static final int TAB = InputConstants.KEY_TAB;
	public static final int UP = InputConstants.KEY_UP;
	public static final int DOWN = InputConstants.KEY_DOWN;
	public static final int LEFT = InputConstants.KEY_LEFT;
	public static final int RIGHT = InputConstants.KEY_RIGHT;
	public static final int F3 = InputConstants.KEY_F3;
	public static final int F10 = InputConstants.KEY_F10;
	public static final int F8 = InputConstants.KEY_F8;
	public static final int LEFT_ALT = InputConstants.KEY_LALT;
	public static final int F7 = InputConstants.KEY_F7;
	public static final int F9 = InputConstants.KEY_F9;
	public static final int END = InputConstants.KEY_END;
	public static final int K = InputConstants.KEY_K;
	public static final int V = InputConstants.KEY_V;
	public static final int B = InputConstants.KEY_B;
	public static final int C = InputConstants.KEY_C;
	public static final int E = InputConstants.KEY_E;
	public static final int G = InputConstants.KEY_G;
	public static final int I = InputConstants.KEY_I;
	public static final int J = InputConstants.KEY_J;
	public static final int M = InputConstants.KEY_M;
	public static final int N = InputConstants.KEY_N;
	public static final int O = InputConstants.KEY_O;
	public static final int P = InputConstants.KEY_P;
	public static final int R = InputConstants.KEY_R;
	public static final int Y = InputConstants.KEY_Y;
	public static final int Z = InputConstants.KEY_Z;
	public static final int MOUSE_LEFT = InputConstants.MOUSE_BUTTON_LEFT;
	public static final int MOUSE_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;
	public static final int MOUSE_MIDDLE = InputConstants.MOUSE_BUTTON_MIDDLE;
	public static final int MOD_SHIFT = InputConstants.MOD_SHIFT;
	public static final int MOD_CONTROL = InputConstants.MOD_CONTROL;
	public static final int PRESS = InputConstants.PRESS;
	*///?} else {
	public static final boolean SDL = false;
	public static final int ESCAPE = GLFW.GLFW_KEY_ESCAPE;
	public static final int ENTER = GLFW.GLFW_KEY_ENTER;
	public static final int KP_ENTER = GLFW.GLFW_KEY_KP_ENTER;
	public static final int LEFT_SHIFT = GLFW.GLFW_KEY_LEFT_SHIFT;
	public static final int RIGHT_SHIFT = GLFW.GLFW_KEY_RIGHT_SHIFT;
	public static final int LEFT_CONTROL = GLFW.GLFW_KEY_LEFT_CONTROL;
	public static final int RIGHT_CONTROL = GLFW.GLFW_KEY_RIGHT_CONTROL;
	public static final int SPACE = GLFW.GLFW_KEY_SPACE;
	public static final int BACKSPACE = GLFW.GLFW_KEY_BACKSPACE;
	public static final int DELETE = GLFW.GLFW_KEY_DELETE;
	public static final int TAB = GLFW.GLFW_KEY_TAB;
	public static final int UP = GLFW.GLFW_KEY_UP;
	public static final int DOWN = GLFW.GLFW_KEY_DOWN;
	public static final int LEFT = GLFW.GLFW_KEY_LEFT;
	public static final int RIGHT = GLFW.GLFW_KEY_RIGHT;
	public static final int F3 = GLFW.GLFW_KEY_F3;
	public static final int F10 = GLFW.GLFW_KEY_F10;
	public static final int F8 = GLFW.GLFW_KEY_F8;
	public static final int LEFT_ALT = GLFW.GLFW_KEY_LEFT_ALT;
	public static final int F7 = GLFW.GLFW_KEY_F7;
	public static final int F9 = GLFW.GLFW_KEY_F9;
	public static final int END = GLFW.GLFW_KEY_END;
	public static final int K = GLFW.GLFW_KEY_K;
	public static final int V = GLFW.GLFW_KEY_V;
	public static final int B = GLFW.GLFW_KEY_B;
	public static final int C = GLFW.GLFW_KEY_C;
	public static final int E = GLFW.GLFW_KEY_E;
	public static final int G = GLFW.GLFW_KEY_G;
	public static final int I = GLFW.GLFW_KEY_I;
	public static final int J = GLFW.GLFW_KEY_J;
	public static final int M = GLFW.GLFW_KEY_M;
	public static final int N = GLFW.GLFW_KEY_N;
	public static final int O = GLFW.GLFW_KEY_O;
	public static final int P = GLFW.GLFW_KEY_P;
	public static final int R = GLFW.GLFW_KEY_R;
	public static final int Y = GLFW.GLFW_KEY_Y;
	public static final int Z = GLFW.GLFW_KEY_Z;
	public static final int MOUSE_LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
	public static final int MOUSE_RIGHT = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
	public static final int MOUSE_MIDDLE = GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
	public static final int MOD_SHIFT = GLFW.GLFW_MOD_SHIFT;
	public static final int MOD_CONTROL = GLFW.GLFW_MOD_CONTROL;
	public static final int PRESS = GLFW.GLFW_PRESS;
	//?}

	/** Pairs of {GLFW key code, SDL scancode} from Mojang's own 26.2 vs 26.3 InputConstants (matched by name). */
	private static final int[] LEGACY_GLFW = {32, 39, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 59, 61, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 96, 256, 257, 258, 259, 260, 261, 262, 263, 264, 265, 266, 267, 268, 269, 280, 281, 282, 283, 284, 290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301, 302, 303, 304, 305, 306, 307, 308, 309, 310, 311, 312, 313, 320, 321, 322, 323, 324, 325, 326, 327, 328, 329, 330, 332, 334, 335, 336, 340, 341, 342, 343, 344, 345, 346, 347};
	private static final int[] LEGACY_SDL = {44, 52, 54, 45, 55, 56, 39, 30, 31, 32, 33, 34, 35, 36, 37, 38, 51, 46, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 47, 49, 48, 53, 41, 40, 43, 42, 73, 76, 79, 80, 81, 82, 75, 78, 74, 77, 57, 71, 83, 70, 72, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 98, 89, 90, 91, 92, 93, 94, 95, 96, 97, 220, 85, 87, 88, 103, 225, 224, 226, 227, 229, 228, 230, 231};

	private Keys() {
	}

	/** Key code of the letter key {@code c} (A-Z) on this version: ASCII before 26.3, SDL scancodes 4-29 after. */
	public static int letter(char c) {
		int index = Character.toUpperCase(c) - 'A';
		return SDL ? 4 + index : 'A' + index;
	}

	/** A key code Velo saved before 26.3 (GLFW) in this version's codes; unchanged before 26.3 and for -1. */
	public static int fromStored(int glfwCode) {
		if (!SDL || glfwCode < 0) {
			return glfwCode;
		}
		int i = java.util.Arrays.binarySearch(LEGACY_GLFW, glfwCode);
		return i >= 0 ? LEGACY_SDL[i] : UNKNOWN;
	}

	/** "glfw" or "sdl" - the kind of key code this version uses (stored next to saved bindings). */
	public static String format() {
		return SDL ? "sdl" : "glfw";
	}

	/** A key code saved in {@code storedFormat} ("glfw"/"sdl") as this version's code; -1 when the key doesn't exist here. */
	public static int convert(String storedFormat, int code) {
		if (code < 0 || format().equals(storedFormat)) {
			return code;
		}
		if (SDL) {
			return fromStored(code);
		}
		for (int i = 0; i < LEGACY_SDL.length; i++) {
			if (LEGACY_SDL[i] == code) {
				return LEGACY_GLFW[i];
			}
		}
		return UNKNOWN;
	}

	public static boolean isKeyDown(int key) {
		if (key < 0) {
			return false;
		}
		//? if >=26.3 {
		/*return InputConstants.isKeyDown(key);
		*///?} else {
		return GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), key) == GLFW.GLFW_PRESS;
		//?}
	}

	/** Live state of a mouse button ({@link #MOUSE_LEFT} etc.) - also safe from a background thread. */
	public static boolean isMouseDown(int button) {
		//? if >=26.3 {
		/*int state = org.lwjgl.sdl.SDLMouse.SDL_GetMouseState((java.nio.FloatBuffer) null, null);
		int mask = button == MOUSE_LEFT ? org.lwjgl.sdl.SDLMouse.SDL_BUTTON_LMASK
				: button == MOUSE_RIGHT ? org.lwjgl.sdl.SDLMouse.SDL_BUTTON_RMASK : org.lwjgl.sdl.SDLMouse.SDL_BUTTON_MMASK;
		return (state & mask) != 0;
		*///?} else {
		return GLFW.glfwGetMouseButton(MinecraftClient.getInstance().getWindow().getHandle(), button) == GLFW.GLFW_PRESS;
		//?}
	}

	/** Processes pending window/input events now (what the render loop does once per frame). Main thread only. */
	public static void pollEvents() {
		//? if >=26.3 {
		/*try {
			java.lang.reflect.Field field = Minecraft.class.getDeclaredField("sdlEventHandler");
			field.setAccessible(true);
			((com.mojang.blaze3d.platform.SDLEventHandler) field.get(Minecraft.getInstance())).pollEvents();
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// Only an early-dispatch optimisation; the next frame polls anyway.
		}
		*///?} else {
		GLFW.glfwPollEvents();
		//?}
	}

	/** 0 = off, 1 = on, -1 = adaptive. A no-op from 26.3 (SDL + the new renderer pick the present mode from the VSync option). */
	public static void setSwapInterval(int interval) {
		//? if <26.3 {
		GLFW.glfwSwapInterval(interval);
		//?}
	}
}
