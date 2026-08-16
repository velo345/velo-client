package net.veloclient.velo.client.modules.utility;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bind any key to instantly run a chat command (e.g. "/spawn") - a fully
 * user-managed list, not a single fixed binding, so it's driven by raw GLFW
 * key polling with its own press-edge tracking rather than vanilla's
 * once-at-startup {@code KeyBinding} registration, which doesn't support a
 * list that grows/shrinks at runtime. Editing is done through {@link
 * net.veloclient.velo.client.gui.CommandKeybindsScreen}, opened from the
 * module tile instead of the generic per-field settings screen.
 */
public final class CommandKeybindsModule extends AbstractModule {

	private static List<CommandKeybindEntry> entries = new ArrayList<>();
	private static final Map<Integer, Boolean> WAS_DOWN = new HashMap<>();
	private static boolean loaded;

	public CommandKeybindsModule() {
		super("command-keybinds", "Command Keybinds", "Bind a key to instantly run a chat command, like /spawn.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(CommandKeybindsModule::onTick);
	}

	/** Entries are plain user data (not "state to restore on enable"), so they're readable/editable from the settings screen at any time regardless of the module's own enabled toggle. */
	public static List<CommandKeybindEntry> entries() {
		if (!loaded) {
			entries = CommandKeybindStore.load();
			loaded = true;
		}
		return entries;
	}

	public static void setEntries(List<CommandKeybindEntry> newEntries) {
		entries = newEntries;
		loaded = true;
		CommandKeybindStore.save(entries);
	}

	private static void onTick(MinecraftClient client) {
		var module = net.veloclient.velo.module.ModuleRegistry.get("command-keybinds").orElse(null);
		if (!(module instanceof CommandKeybindsModule) || !module.isEnabled()) {
			return;
		}
		// Matches vanilla's own keybinds: nothing fires while any screen
		// (chat, inventory, this mod's own menus, ...) is open, so typing
		// numbers/letters into a text field can't accidentally run a command.
		var networkHandler = client.getNetworkHandler();
		//? if <26.1 {
		if (client.player == null || networkHandler == null || client.currentScreen != null) {
			return;
		}
		//?} else if <26.2 {
		/*if (client.player == null || networkHandler == null || client.screen != null) {
			return;
		}
		*///?} else {
		/*if (client.player == null || networkHandler == null || client.gui.screen() != null) {
			return;
		}
		*///?}
		long handle = client.getWindow().getHandle();
		List<CommandKeybindEntry> current = entries();
		for (int i = 0; i < current.size(); i++) {
			CommandKeybindEntry entry = current.get(i);
			if (entry.keyCode() == GLFW.GLFW_KEY_UNKNOWN) {
				continue;
			}
			boolean down = GLFW.glfwGetKey(handle, entry.keyCode()) == GLFW.GLFW_PRESS;
			boolean wasDown = WAS_DOWN.getOrDefault(i, false);
			if (down && !wasDown && !entry.command().isBlank()) {
				String command = entry.command().startsWith("/") ? entry.command().substring(1) : entry.command();
				networkHandler.sendChatCommand(command);
			}
			WAS_DOWN.put(i, down);
		}
	}
}
