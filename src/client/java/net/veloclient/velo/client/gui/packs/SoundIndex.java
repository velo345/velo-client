package net.veloclient.velo.client.gui.packs;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The game's sound events (from every loaded {@code sounds.json}, merged the way the game does) and what a pack
 * changes about them: replaced .ogg files and its own {@code sounds.json} entries (extra variants, volume, pitch).
 */
public final class SoundIndex {

	/** Sound groups for the browser, by event prefix. */
	public enum Group {
		MUSIC("Music"), AMBIENT("Ambient"), BLOCKS("Blocks"), ENTITIES("Entities"), ITEMS("Items"), UI("Interface"), WEATHER("Weather"),
		OTHER("Other");

		public final String label;

		Group(String label) {
			this.label = label;
		}
	}

	/** One variant of an event: {@code name} like "mob/creeper/say1" (or "ns:path"), plus its sounds.json settings. */
	public record Variant(String name, float volume, float pitch, int weight, boolean stream) {
		/** "minecraft:sounds/mob/creeper/say1.ogg". */
		public String assetKey() {
			int colon = name.indexOf(':');
			String ns = colon < 0 ? "minecraft" : name.substring(0, colon);
			String path = colon < 0 ? name : name.substring(colon + 1);
			return ns + ":sounds/" + path + ".ogg";
		}

		public String shortName() {
			return name.substring(name.lastIndexOf('/') + 1);
		}
	}

	public record Event(String id, List<Variant> variants, String subtitle) {
		public Group group() {
			return groupOf(id);
		}
	}

	private static volatile Map<String, Event> game;

	private SoundIndex() {
	}

	public static Group groupOf(String id) {
		String e = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		if (e.startsWith("music") || e.startsWith("record")) {
			return Group.MUSIC;
		}
		if (e.startsWith("ambient")) {
			return Group.AMBIENT;
		}
		if (e.startsWith("block")) {
			return Group.BLOCKS;
		}
		if (e.startsWith("entity")) {
			return Group.ENTITIES;
		}
		if (e.startsWith("item")) {
			return Group.ITEMS;
		}
		if (e.startsWith("ui")) {
			return Group.UI;
		}
		if (e.startsWith("weather")) {
			return Group.WEATHER;
		}
		return Group.OTHER;
	}

	/** Every sound event of the game (vanilla + selected packs, merged), sorted. Slow the first time - call off the render thread. */
	public static Map<String, Event> game() {
		Map<String, Event> known = game;
		if (known != null) {
			return known;
		}
		Map<String, Event> out = new TreeMap<>();
		for (byte[] data : PackBridge.readGameStack("minecraft:sounds.json")) {
			merge(out, data);
		}
		game = out;
		return out;
	}

	public static boolean gameReady() {
		return game != null;
	}

	/** Applies one sounds.json on top of {@code events} ("replace": true drops the earlier variants). */
	private static void merge(Map<String, Event> events, byte[] data) {
		JsonObject json;
		try {
			json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (RuntimeException e) {
			return;
		}
		for (Map.Entry<String, JsonElement> e : json.entrySet()) {
			if (!e.getValue().isJsonObject()) {
				continue;
			}
			JsonObject def = e.getValue().getAsJsonObject();
			String id = e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey();
			boolean replace = def.has("replace") && def.get("replace").getAsBoolean();
			Event before = events.get(id);
			List<Variant> variants = new ArrayList<>(replace || before == null ? List.of() : before.variants());
			variants.addAll(parseVariants(def));
			String subtitle = def.has("subtitle") ? def.get("subtitle").getAsString() : before != null ? before.subtitle() : null;
			events.put(id, new Event(id, variants, subtitle));
		}
	}

	private static List<Variant> parseVariants(JsonObject def) {
		List<Variant> out = new ArrayList<>();
		if (!def.has("sounds")) {
			return out;
		}
		for (JsonElement s : def.getAsJsonArray("sounds")) {
			if (s.isJsonPrimitive()) {
				out.add(new Variant(s.getAsString(), 1, 1, 1, false));
			} else if (s.isJsonObject()) {
				JsonObject o = s.getAsJsonObject();
				if (o.has("type") && o.get("type").getAsString().equals("event")) {
					continue;
				}
				out.add(new Variant(o.get("name").getAsString(), o.has("volume") ? o.get("volume").getAsFloat() : 1,
						o.has("pitch") ? o.get("pitch").getAsFloat() : 1, o.has("weight") ? o.get("weight").getAsInt() : 1,
						o.has("stream") && o.get("stream").getAsBoolean()));
			}
		}
		return out;
	}

	// ---- The pack's side ----

	private static Path soundsJson(Path packRoot) {
		return packRoot.resolve("assets/minecraft/sounds.json");
	}

	private static JsonObject readPackJson(Path packRoot) {
		try {
			Path file = soundsJson(packRoot);
			return Files.exists(file) ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			return new JsonObject();
		}
	}

	/** An event as the game will see it with this pack on top. */
	public static Event withPack(String id, Path packRoot) {
		Map<String, Event> merged = new TreeMap<>();
		Event base = game().get(id);
		if (base != null) {
			merged.put(id, base);
		}
		JsonObject pack = readPackJson(packRoot);
		String key = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
		JsonElement def = pack.has(key) ? pack.get(key) : pack.get(id);
		if (def != null && def.isJsonObject()) {
			JsonObject wrapper = new JsonObject();
			wrapper.add(id, def);
			merge(merged, wrapper.toString().getBytes(StandardCharsets.UTF_8));
		}
		Event out = merged.get(id);
		return out != null ? out : new Event(id, new ArrayList<>(), null);
	}

	/** Events the pack touches: a replaced .ogg of one of their variants, or an entry in its sounds.json. */
	public static Map<String, Event> changedBy(Path packRoot) {
		Map<String, Event> out = new LinkedHashMap<>();
		JsonObject pack = readPackJson(packRoot);
		for (String key : pack.keySet()) {
			String id = key.contains(":") ? key : "minecraft:" + key;
			out.put(id, withPack(id, packRoot));
		}
		for (Event e : game().values()) {
			if (out.containsKey(e.id())) {
				continue;
			}
			for (Variant v : e.variants()) {
				if (Files.exists(PackContents.file(packRoot, v.assetKey()))) {
					out.put(e.id(), e);
					break;
				}
			}
		}
		return out;
	}

	/** Writes the event's full variant list into the pack's sounds.json ("replace": true), keeping the subtitle. */
	public static void writeEvent(Path packRoot, Event event) throws IOException {
		JsonObject pack = readPackJson(packRoot);
		String key = event.id().startsWith("minecraft:") ? event.id().substring("minecraft:".length()) : event.id();
		pack.remove(event.id());
		JsonObject def = new JsonObject();
		def.addProperty("replace", true);
		JsonArray sounds = new JsonArray();
		for (Variant v : event.variants()) {
			if (v.volume() == 1 && v.pitch() == 1 && v.weight() == 1 && !v.stream()) {
				sounds.add(v.name());
			} else {
				JsonObject o = new JsonObject();
				o.addProperty("name", v.name());
				if (v.volume() != 1) {
					o.addProperty("volume", round(v.volume()));
				}
				if (v.pitch() != 1) {
					o.addProperty("pitch", round(v.pitch()));
				}
				if (v.weight() != 1) {
					o.addProperty("weight", v.weight());
				}
				if (v.stream()) {
					o.addProperty("stream", true);
				}
				sounds.add(o);
			}
		}
		def.add("sounds", sounds);
		if (event.subtitle() != null) {
			def.addProperty("subtitle", event.subtitle());
		}
		pack.add(key, def);
		Path file = soundsJson(packRoot);
		Files.createDirectories(file.getParent());
		Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(pack), StandardCharsets.UTF_8);
	}

	private static float round(float v) {
		return Math.round(v * 100) / 100f;
	}

	/** "entity.creeper.primed" -> "Creeper primed"-ish label. */
	public static String label(String id) {
		String e = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		String[] parts = e.split("\\.");
		int from = parts.length > 2 ? 1 : 0;
		StringBuilder sb = new StringBuilder();
		for (int i = from; i < parts.length; i++) {
			String p = parts[i].replace('_', ' ');
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(p);
		}
		String s = sb.toString();
		return s.isEmpty() ? e : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
	}
}
