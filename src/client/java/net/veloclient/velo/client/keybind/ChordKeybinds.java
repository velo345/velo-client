package net.veloclient.velo.client.keybind;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Polling helpers for {@link net.veloclient.velo.module.ConfigField.ChordKeybindField}
 * - checks raw GLFW key state directly rather than going through vanilla's
 * {@code KeyBinding}, since a multi-key chord isn't something vanilla's own
 * single-key binding system can represent at all. Mouse buttons aren't
 * supported here (only {@code GLFW_KEY_*} codes) - chords are keyboard-only.
 */
public final class ChordKeybinds {

	private ChordKeybinds() {
	}

	/** True only when every key in the chord is currently held down; an empty/unbound chord is never "held". */
	public static boolean isHeld(List<Integer> keyCodes) {
		if (keyCodes == null || keyCodes.isEmpty()) {
			return false;
		}
		long window = MinecraftClient.getInstance().getWindow().getHandle();
		for (int code : keyCodes) {
			if (GLFW.glfwGetKey(window, code) != GLFW.GLFW_PRESS) {
				return false;
			}
		}
		return true;
	}

	public static String displayText(List<Integer> keyCodes) {
		if (keyCodes == null || keyCodes.isEmpty()) {
			return "Unbound";
		}
		StringBuilder text = new StringBuilder();
		for (int code : keyCodes) {
			if (!text.isEmpty()) {
				text.append(" + ");
			}
			text.append(InputUtil.Type.KEYSYM.createFromCode(code).getLocalizedText().getString());
		}
		return text.toString();
	}
}
