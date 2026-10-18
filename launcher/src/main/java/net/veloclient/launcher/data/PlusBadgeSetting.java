package net.veloclient.launcher.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Whether the + of Velo+ shows on your badge for others - the same file the game reads ({@code config/plus-badge.json}, on by default). */
public final class PlusBadgeSetting {

	private PlusBadgeSetting() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("plus-badge.json");
	}

	public static boolean show() {
		try {
			if (Files.exists(file())) {
				JsonObject json = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
				return !json.has("show") || json.get("show").getAsBoolean();
			}
		} catch (IOException | RuntimeException ignored) {
			// Unreadable: the default.
		}
		return true;
	}

	public static void set(boolean show) {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), "{\n  \"show\": " + show + "\n}", StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// Best effort.
		}
	}
}
