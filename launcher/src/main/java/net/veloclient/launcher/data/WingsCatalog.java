package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The 3D wings, as bundled from the mod's own assets (see the launcher's bundleWingAssets task):
 * the catalog, and which pair is equipped - written to the same {@code config/cosmetics-wings.json}
 * the game reads, keeping each pair's settings the game saved there.
 */
public final class WingsCatalog {

	public static final String ROOT = "/net/veloclient/launcher/wings/";
	public static final String ITEM_PREFIX = "wings_";

	public record WingType(String id, String name, String description, String style, float scale, float offsetY, int price) {
		public String itemId() {
			return ITEM_PREFIX + id;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static List<WingType> catalog;

	private WingsCatalog() {
	}

	public static synchronized List<WingType> all() {
		if (catalog == null) {
			List<WingType> out = new ArrayList<>();
			try (InputStream in = WingsCatalog.class.getResourceAsStream(ROOT + "wings.json")) {
				if (in != null) {
					for (JsonElement e : GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class)) {
						JsonObject o = e.getAsJsonObject();
						out.add(new WingType(o.get("id").getAsString(), o.get("name").getAsString(),
								o.has("description") ? o.get("description").getAsString() : "", o.has("style") ? o.get("style").getAsString() : "feathered",
								o.has("scale") ? o.get("scale").getAsFloat() : 0.9f, o.has("offsetY") ? o.get("offsetY").getAsFloat() : 0f,
								o.has("price") ? o.get("price").getAsInt() : 2500));
					}
				}
			} catch (Exception ignored) {
				// No wings bundled.
			}
			catalog = List.copyOf(out);
		}
		return catalog;
	}

	public static Optional<WingType> byId(String id) {
		return all().stream().filter(w -> w.id().equals(id)).findFirst();
	}

	public static InputStream open(String path) {
		return WingsCatalog.class.getResourceAsStream(ROOT + path);
	}

	private static Path file() {
		return VeloPaths.config().resolve("cosmetics-wings.json");
	}

	private static JsonObject read() {
		try {
			if (Files.exists(file())) {
				return JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (Exception ignored) {
			// Start over.
		}
		return new JsonObject();
	}

	public static String equipped() {
		JsonObject o = read();
		return o.has("wingsId") && !o.get("wingsId").isJsonNull() ? o.get("wingsId").getAsString() : null;
	}

	/** Equips a pair (null takes them off) - the game picks it up the next time it starts. */
	public static void equip(String id) {
		JsonObject o = read();
		if (id == null) {
			o.add("wingsId", com.google.gson.JsonNull.INSTANCE);
		} else {
			o.addProperty("wingsId", id);
		}
		try {
			VeloPaths.ensureDirectories();
			Files.writeString(file(), GSON.toJson(o), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new RuntimeException("Couldn't save the equipped wings", e);
		}
	}
}
