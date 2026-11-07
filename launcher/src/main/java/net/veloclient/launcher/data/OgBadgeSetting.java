package net.veloclient.launcher.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The OG badge (players from Velo's first two weeks): the same file the game reads ({@code config/og-badge.json}) -
 * whether the player shows it (opt-in, off by default), whether the server said they may, and whether the launcher
 * already thanked them.
 */
public final class OgBadgeSetting {

	private OgBadgeSetting() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("og-badge.json");
	}

	private static JsonObject read() {
		try {
			if (Files.exists(file())) {
				return JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (IOException | RuntimeException ignored) {
			// Unreadable: the defaults.
		}
		return new JsonObject();
	}

	private static boolean flag(String key) {
		JsonObject json = read();
		return json.has(key) && json.get(key).getAsBoolean();
	}

	private static synchronized void write(String key, boolean value) {
		JsonObject json = read();
		json.addProperty("use", json.has("use") && json.get("use").getAsBoolean());
		json.addProperty("eligible", json.has("eligible") && json.get("eligible").getAsBoolean());
		json.addProperty("thanked", json.has("thanked") && json.get("thanked").getAsBoolean());
		json.addProperty(key, value);
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), json.toString(), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// Best effort.
		}
	}

	public static boolean use() {
		return flag("use");
	}

	public static void setUse(boolean use) {
		write("use", use);
	}

	public static boolean eligible() {
		return flag("eligible");
	}

	public static void setEligible(boolean eligible) {
		if (eligible() != eligible) {
			write("eligible", eligible);
		}
	}

	public static boolean thanked() {
		return flag("thanked");
	}

	public static void setThanked() {
		write("thanked", true);
	}
}
