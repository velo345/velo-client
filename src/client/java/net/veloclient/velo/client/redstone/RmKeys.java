package net.veloclient.velo.client.redstone;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.util.Keys;

/**
 * Redstone Mode's keys (Controls &gt; Velo Redstone Mode). Kept to a handful with defaults that
 * don't clash with movement, sprint or voice chat: R toggles the mode, 0 selects the tool slot, a
 * Left Alt tap frees the mouse for the overlay, F7 freezes the world, F9 hides the overlay, K
 * records / replays, End stops a running paste. Every tool also has its own key, unbound by default. While the mode is on
 * these are the only Velo keys that work. Editing shortcuts (Ctrl+C/X/V/Z/Y, Delete, Enter,
 * Backspace) only act while the Redstone tool slot is selected.
 */
public final class RmKeys {

	public static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("velo-client", "redstone"));

	public static final KeyBinding TOGGLE = key("rm_toggle", Keys.R);
	public static final KeyBinding CURSOR = key("rm_cursor", Keys.LEFT_ALT);
	public static final KeyBinding FREEZE = key("rm_freeze", Keys.F7);
	public static final KeyBinding HIDE = key("rm_hide", Keys.F9);
	public static final KeyBinding RECORD = key("rm_record", Keys.letter('K'));
	public static final KeyBinding CANCEL_JOB = key("rm_cancel_job", Keys.END);
	/** Selects the Redstone tool slot (slot 0, right of the hotbar) - like Axiom's tool slot. */
	public static final KeyBinding TOOL_SLOT = key("rm_tool_slot", Keys.digit(0));
	public static final KeyBinding TOOL_SELECT = key("rm_tool_select", Keys.UNKNOWN);
	public static final KeyBinding TOOL_MOVE = key("rm_tool_move", Keys.UNKNOWN);
	public static final KeyBinding TOOL_STACK = key("rm_tool_stack", Keys.UNKNOWN);
	public static final KeyBinding TOOL_WIRE = key("rm_tool_wire", Keys.UNKNOWN);
	public static final KeyBinding TOOL_WATER = key("rm_tool_water", Keys.UNKNOWN);
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

	/** Empties a key's click queue (keys pressed while they mean nothing). */
	static void drain(KeyBinding... keys) {
		for (KeyBinding key : keys) {
			while (key.wasPressed()) {
				// discard
			}
		}
	}
}
