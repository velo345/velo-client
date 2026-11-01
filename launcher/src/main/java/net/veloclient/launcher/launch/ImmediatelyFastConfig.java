package net.veloclient.launcher.launch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.launcher.LauncherLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Keeps ImmediatelyFast's {@code fast_text_lookup} off in every profile (the launcher installs ImmediatelyFast itself).
 * That option makes name tag glyphs reuse the last vertex builder while their render type stays the same - but
 * Essential draws its name tag icon in between, which moves the shared builder onto the icon's draw. The rest of the
 * name then went into the icon's draw: players' names showed as a blue box or vanished, randomly, and only on clients
 * that ship ImmediatelyFast (Velo). Everything else in the file is left as it is.
 */
public final class ImmediatelyFastConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ImmediatelyFastConfig() {
	}

	public static void fixIn(Path gameDir) {
		Path file = gameDir.resolve("config").resolve("immediatelyfast.json");
		try {
			JsonObject json = Files.isRegularFile(file) ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()
					: new JsonObject();
			if (json.has("fast_text_lookup") && !json.get("fast_text_lookup").getAsBoolean()) {
				return;
			}
			json.addProperty("fast_text_lookup", false);
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			LauncherLog.warn("Couldn't set ImmediatelyFast's fast_text_lookup off in " + file, e);
		}
	}
}
