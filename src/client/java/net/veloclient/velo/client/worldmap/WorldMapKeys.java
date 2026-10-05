package net.veloclient.velo.client.worldmap;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import org.lwjgl.glfw.GLFW;

/** The world map's own key (M by default, rebindable in Controls). */
public final class WorldMapKeys {

	public static final KeyBinding OPEN_MAP = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.world_map", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, VeloKeybinds.CATEGORY));

	private WorldMapKeys() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_MAP.wasPressed()) {
				if (client.player != null && net.veloclient.velo.client.util.ClientCompat.currentScreen() == null) {
					client.setScreen(new WorldMapScreen(null));
				}
			}
		});
	}

	/** GLFW key code the map key is bound to (for closing the map with the same key). */
	static int keyCode() {
		//? if <26.1 {
		return KeyBindingHelper.getBoundKeyOf(OPEN_MAP).getCode();
		//?} else {
		/*return KeyBindingHelper.getBoundKeyOf(OPEN_MAP).getValue();
		*///?}
	}
}
