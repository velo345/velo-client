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
import org.lwjgl.glfw.GLFW;

/** Registers Velo Client's global keybinds. Default panel key is Right Shift, matching section 5. */
public final class VeloKeybinds {

	/**
	 * Shared keybind category for the whole mod. {@link KeyBinding.Category#create} throws if the
	 * same id is registered twice, so every module with its own keybind must reuse this constant
	 * rather than creating their own category.
	 */
	public static final KeyBinding.Category CATEGORY =
			KeyBinding.Category.create(Identifier.of("velo-client", "general"));

	public static final KeyBinding OPEN_MOD_MENU = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.open_mod_menu",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_RIGHT_SHIFT,
			CATEGORY));

	private VeloKeybinds() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(VeloKeybinds::onClientTick);
	}

	private static void onClientTick(MinecraftClient client) {
		while (OPEN_MOD_MENU.wasPressed()) {
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
