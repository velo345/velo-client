package net.veloclient.legacy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Gson helpers that work with 1.8.9's old Gson (no static JsonParser.parseString). */
public final class Json {

	private Json() {
	}

	@SuppressWarnings("deprecation")
	public static JsonObject parse(String text) {
		return new JsonParser().parse(text).getAsJsonObject();
	}

	public static JsonObject read(Path file) {
		try {
			return Files.exists(file) ? parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)) : null;
		} catch (Exception e) {
			return null;
		}
	}

	public static String string(JsonObject o, String key) {
		if (o == null || !o.has(key)) {
			return null;
		}
		JsonElement e = o.get(key);
		return e.isJsonNull() ? null : e.getAsString();
	}

	public static boolean bool(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
	}
}
