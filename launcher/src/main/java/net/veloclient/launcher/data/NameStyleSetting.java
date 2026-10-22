package net.veloclient.launcher.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.client.namestyle.NameStyle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Your Velo+ name style, in {@code config/name-style.json} - the same file the game's Locker writes and watches, so a
 * change here shows on your name in a running game within a second (and goes out to other Velo players with its next
 * heartbeat). {@code {"enabled": true, "style": "1|wave|10|bg|ff2d3c,ff7a7e|1|ctn"}}.
 */
public final class NameStyleSetting {

	private NameStyleSetting() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("name-style.json");
	}

	public static NameStyle style() {
		JsonObject json = read();
		NameStyle parsed = json != null && json.has("style") ? NameStyle.decode(json.get("style").getAsString()) : null;
		return parsed == null ? NameStyle.defaults() : parsed;
	}

	public static boolean enabled() {
		JsonObject json = read();
		return json != null && json.has("enabled") && json.get("enabled").getAsBoolean();
	}

	public static void save(NameStyle style, boolean enabled) {
		JsonObject json = new JsonObject();
		json.addProperty("enabled", enabled);
		json.addProperty("style", style.encode());
		try {
			Files.createDirectories(file().getParent());
			Path temp = file().resolveSibling("name-style.json.tmp");
			Files.writeString(temp, json.toString(), StandardCharsets.UTF_8);
			Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException ignored) {
			// Read-only config folder: nothing to do, the editor still shows it.
		}
	}

	private static JsonObject read() {
		try {
			if (Files.exists(file())) {
				return JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (IOException | RuntimeException ignored) {
			// Unreadable (or half-written by the game): the defaults.
		}
		return null;
	}
}
