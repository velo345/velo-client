package net.veloclient.velo.client.modules.utility;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Copies your current coordinates to the system clipboard with one keypress - for sharing a location, marking a build site, etc. */
public final class CopyCoordinatesModule extends AbstractModule implements Configurable {

	public static final KeyBinding COPY_COORDS_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.copy_coordinates", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));

	public CopyCoordinatesModule() {
		super("copy-coordinates", "Copy Coordinates", "Copies your current X/Y/Z to the clipboard with one keypress.",
				ModuleCategory.UTILITY, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled() || client.player == null) {
			return;
		}
		var player = client.player;
		while (COPY_COORDS_KEY.wasPressed()) {
			String text = String.format("%.1f, %.1f, %.1f", player.getX(), player.getY(), player.getZ());
			client.keyboard.setClipboard(text);
			player.sendMessage(net.minecraft.text.Text.literal("Copied: " + text), true);
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(KeybindConfig.field("Copy Key", COPY_COORDS_KEY));
	}
}
