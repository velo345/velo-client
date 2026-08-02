package net.veloclient.velo.client.modules.servertools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.gui.LogViewerScreen;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.util.LogCapture;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * In-game log viewer with filters and exportable session logs, for debugging
 * plugin interactions live (design spec section 6.3). Reads only this
 * client's own log output - never anything from the server.
 */
public final class ClientLogViewerModule extends AbstractModule implements Configurable {

	public static final KeyBinding OPEN_LOG_VIEWER = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.open_log_viewer", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F10, VeloKeybinds.CATEGORY));

	public ClientLogViewerModule() {
		super("client-log-viewer", "Client Log Viewer",
				"In-game log viewer with a text filter and one-click session export.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.ALWAYS_SAFE, false);
		LogCapture.install();
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled()) {
			return;
		}
		while (OPEN_LOG_VIEWER.wasPressed()) {
			if (client.currentScreen == null) {
				client.setScreen(new LogViewerScreen(null));
			}
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(KeybindConfig.field("Open Viewer Key", OPEN_LOG_VIEWER));
	}
}
