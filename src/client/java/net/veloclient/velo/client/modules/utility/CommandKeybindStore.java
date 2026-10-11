package net.veloclient.velo.client.modules.utility;

import net.veloclient.velo.client.util.Keys;
import net.veloclient.velo.config.ConfigManager;

import java.util.ArrayList;
import java.util.List;

/** Persists the command-keybind list to {@code ~/.velo-client/config/command-keybinds.json}. */
final class CommandKeybindStore {

	private static final String MODULE_ID = "command-keybinds";

	private CommandKeybindStore() {
	}

	/** {@code keyFormat}: "glfw"/"sdl" (see {@link Keys}); missing in files written before 26.3 = GLFW. */
	record Data(List<CommandKeybindEntry> entries, String keyFormat) {
	}

	static List<CommandKeybindEntry> load() {
		Data data = ConfigManager.load(MODULE_ID, Data.class, new Data(new ArrayList<>(), Keys.format()));
		String format = data.keyFormat() == null ? "glfw" : data.keyFormat();
		List<CommandKeybindEntry> entries = new ArrayList<>();
		for (CommandKeybindEntry entry : data.entries() == null ? List.<CommandKeybindEntry>of() : data.entries()) {
			entries.add(new CommandKeybindEntry(entry.command(), Keys.convert(format, entry.keyCode())));
		}
		return entries;
	}

	static void save(List<CommandKeybindEntry> entries) {
		ConfigManager.save(MODULE_ID, new Data(entries, Keys.format()));
	}
}
