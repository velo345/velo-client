package net.veloclient.velo.client.redstone;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.util.Keys;

/**
 * Redstone Mode's keys, in their own controls category. While Redstone Mode is on these are the
 * only Velo keybinds that work - every module keybind is paused (see {@code RedstoneKeyPauseMixin}).
 * Ctrl+C / Ctrl+X / Ctrl+V / Ctrl+Z / Ctrl+Y / Ctrl+D are fixed shortcuts on top of these.
 */
public final class RmKeys {

	public static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("velo-client", "redstone"));

	public static final KeyBinding TOGGLE = key("rm_toggle", Keys.R);
	public static final KeyBinding NEXT_TOOL = key("rm_next_tool", Keys.letter('Z'));
	public static final KeyBinding MOVE = key("rm_move", Keys.letter('G'));
	public static final KeyBinding STACK = key("rm_stack", Keys.letter('U'));
	public static final KeyBinding FLIP = key("rm_flip", Keys.letter('H'));
	public static final KeyBinding REPLACE = key("rm_replace", Keys.letter('J'));
	public static final KeyBinding TIMELINE = key("rm_timeline", Keys.letter('K'));
	public static final KeyBinding SETTINGS = key("rm_settings", Keys.O);
	public static final KeyBinding ERASE = key("rm_erase", Keys.DELETE);
	public static final KeyBinding CONFIRM = key("rm_confirm", Keys.ENTER);
	public static final KeyBinding CANCEL = key("rm_cancel", Keys.BACKSPACE);
	public static final KeyBinding STEP = key("rm_tick_step", Keys.RIGHT);
	public static final KeyBinding BACK = key("rm_tick_back", Keys.LEFT);
	public static final KeyBinding PLAY = key("rm_tick_play", Keys.UP);
	public static final KeyBinding REWIND = key("rm_tick_rewind", Keys.DOWN);

	private RmKeys() {
	}

	private static KeyBinding key(String id, int code) {
		return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.velo-client." + id, InputUtil.Type.KEYSYM, code, CATEGORY));
	}

	/** Forces class loading (registration) during mod init. */
	public static void init() {
	}

	public static boolean ctrl() {
		return Keys.isKeyDown(Keys.LEFT_CONTROL) || Keys.isKeyDown(Keys.RIGHT_CONTROL);
	}

	public static boolean shift() {
		return Keys.isKeyDown(Keys.LEFT_SHIFT) || Keys.isKeyDown(Keys.RIGHT_SHIFT);
	}

	public static boolean alt() {
		return Keys.isKeyDown(Keys.LEFT_ALT);
	}
}
