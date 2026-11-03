package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Interface sounds, the in-game title track and the first-start guide - one file shared with the game
 * ({@code config/interface.json}, read by the mod's UiPrefs), so a switch flipped here counts in the game too.
 */
public final class InterfacePrefs {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final class Data {
		public boolean sounds = true;
		public boolean titleMusic = true;
		/** False: no profile shows the first-start guide. */
		public boolean guides = true;
	}

	private InterfacePrefs() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("interface.json");
	}

	public static Data get() {
		try {
			if (Files.exists(file())) {
				Data data = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Data.class);
				if (data != null) {
					return data;
				}
			}
		} catch (Exception ignored) {
			// broken file: defaults
		}
		return new Data();
	}

	public static void save(Data data) {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(data), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// not saved this time
		}
	}
}
