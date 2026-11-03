package net.veloclient.velo.client.sound;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.veloclient.velo.config.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Interface sounds, the title track and the first-start guide, shared with the launcher (it shows the same switches):
 * {@code config/interface.json}. Re-read when the file changes, so a switch flipped in the launcher counts in the game.
 */
public final class UiPrefs {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final class Data {
		boolean sounds = true;
		boolean titleMusic = true;
		/** False: no profile ever shows the first-start guide (launcher Settings). */
		boolean guides = true;
	}

	private static Data data;
	private static long readStamp = Long.MIN_VALUE;

	private UiPrefs() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("interface.json");
	}

	private static Data data() {
		long stamp;
		try {
			stamp = Files.exists(file()) ? Files.getLastModifiedTime(file()).toMillis() : -1;
		} catch (Exception e) {
			stamp = -1;
		}
		if (data == null || stamp != readStamp) {
			readStamp = stamp;
			Data read = null;
			try {
				read = stamp >= 0 ? GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Data.class) : null;
			} catch (Exception ignored) {
				// broken file: defaults
			}
			data = read == null ? new Data() : read;
		}
		return data;
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(data), StandardCharsets.UTF_8);
			readStamp = Files.getLastModifiedTime(file()).toMillis();
		} catch (Exception ignored) {
			// not saved this time
		}
	}

	public static boolean sounds() {
		return data().sounds;
	}

	public static void setSounds(boolean on) {
		data().sounds = on;
		save();
	}

	public static boolean titleMusic() {
		return data().titleMusic;
	}

	public static void setTitleMusic(boolean on) {
		data().titleMusic = on;
		save();
	}

	public static boolean guides() {
		return data().guides;
	}

	public static void setGuides(boolean on) {
		data().guides = on;
		save();
	}
}
