package net.veloclient.velo.client.keybind;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.HudEditScreen;
import net.veloclient.velo.client.gui.ModMenuScreen;
import net.veloclient.velo.client.gui.window.VeloWindow;

/** Registers Velo Client's global keybinds. Default panel key is Right Shift, matching section 5. */
public final class VeloKeybinds {

	/**
	 * Shared keybind category for the whole mod. {@link KeyBinding.Category#create} throws if the
	 * same id is registered twice, so every module with its own keybind must reuse this constant
	 * rather than creating their own category.
	 */
	public static final KeyBinding.Category CATEGORY =
			KeyBinding.Category.create(Identifier.of("velo-client", "general"));

	/** Shared action-bar message for any singleplayer-only feature  that refuses to activate on a real server. */
	public static final net.minecraft.text.Text NO_SINGLEPLAYER_MSG =
			net.minecraft.text.Text.literal("This feature only works in singleplayer worlds.");

	public static final KeyBinding OPEN_MOD_MENU = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.open_mod_menu",
			InputUtil.Type.KEYSYM,
			net.veloclient.velo.client.util.Keys.RIGHT_SHIFT,
			CATEGORY));

	private VeloKeybinds() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(VeloKeybinds::onClientTick);
	}

	/**
	 * Reported 2026-11-09 (Windows): with the menu on Tab or another key, the menu opened and closed again at once, "like
	 * spam clicking". Holding a key makes the OS send repeats, and every repeat counted as a new press - so the menu
	 * toggled open/closed/open while the key was down. Now it toggles only once per physical press: after a toggle the
	 * key has to be let go first (checked on the real key state, not the binding's, which a screen may not update).
	 */
	private static boolean waitForRelease;
	private static long lastToggle;

	private static int menuKeyCode() {
		//? if <26.1 {
		return net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(OPEN_MOD_MENU).getCode();
		//?} else {
		/*return net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(OPEN_MOD_MENU).getValue();
		*///?}
	}

	private static boolean menuKeyDown() {
		int code = menuKeyCode();
		// mouse buttons and unbound keys have no keyboard state: fall back to the time gate alone
		return code >= 32 && net.veloclient.velo.client.util.Keys.isKeyDown(code);
	}

	private static void onClientTick(MinecraftClient client) {
		if (waitForRelease && !menuKeyDown()) {
			waitForRelease = false;
		}
		boolean pressed = false;
		while (OPEN_MOD_MENU.wasPressed()) {
			pressed = true;
		}
		if (!pressed || waitForRelease || System.currentTimeMillis() - lastToggle < 150) {
			return;
		}
		lastToggle = System.currentTimeMillis();
		waitForRelease = menuKeyCode() >= 32;
		{
			//? if <26.1 {
			Screen current = client.currentScreen;
			//?} else if <26.2 {
			/*Screen current = client.screen;
			*///?} else {
			/*Screen current = client.gui.screen();
			*///?}
			if (current == null) {
				client.setScreen(new ModMenuScreen());
			} else if (current instanceof VeloWindow window) {
				// Toggle closed: this key opens AND closes the panel, rather
				// than only opening it and requiring Escape to get back out.
				// closeToGame() (not close()) so this always lands back on
				// gameplay in one press even from a nested screen (e.g. a
				// module's settings screen), instead of popping one level.
				window.closeToGame();
			} else if (current instanceof HudEditScreen hudEdit) {
				hudEdit.closeToGame();
			}
		}
	}
}
