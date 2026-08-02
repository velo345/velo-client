package net.veloclient.velo.client.modules.utility;

import net.veloclient.velo.config.ConfigManager;

import java.util.ArrayList;
import java.util.List;

/** Persists the command-keybind list to {@code ~/.velo-client/config/command-keybinds.json}. */
final class CommandKeybindStore {

	private static final String MODULE_ID = "command-keybinds";

	private CommandKeybindStore() {
	}

	record Data(List<CommandKeybindEntry> entries) {
	}

	static List<CommandKeybindEntry> load() {
		Data data = ConfigManager.load(MODULE_ID, Data.class, new Data(new ArrayList<>()));
		return new ArrayList<>(data.entries());
	}

	static void save(List<CommandKeybindEntry> entries) {
		ConfigManager.save(MODULE_ID, new Data(entries));
	}
}
