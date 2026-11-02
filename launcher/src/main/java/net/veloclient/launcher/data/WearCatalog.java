package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bodywear for the launcher: the items in the server catalog (type "wear"), what's worn in each slot and every item's
 * colour ({@code WearTint} codes) - the same {@code config/cosmetics-wear.json} the game reads ({@code WearManager}), so the Locker here and in
 * game stay in step. See the mod's WearManager for the model format.
 */
public final class WearCatalog {

	public static final String ITEM_PREFIX = "wear_";
	public static final List<String> SLOTS = List.of("head", "ears", "face", "mask", "neck", "wrist", "feet", "back");
	public static final List<String> SLOT_NAMES = List.of("Hats", "Ears", "Glasses", "Masks", "Neck", "Watches", "Shoes", "Back");
	/** Each slot's tab icon ({@code Icons}). */
	public static final List<String> SLOT_ICONS = List.of("hat", "ears", "glasses", "mask", "necklace", "watch", "shoe", "backpack");

	/** Slots whose items can be set nearer to or further from the face. */
	public static boolean depthAdjustable(String slot) {
		return "face".equals(slot) || "mask".equals(slot);
	}

	/** Slots whose items can be set to sit higher or lower (the game's setting too). */
	public static boolean adjustable(String slot) {
		return "face".equals(slot) || "mask".equals(slot) || "back".equals(slot);
	}

	public record WearType(String id, String name, String description, String slot, String bone, String mirrorBone, int price,
			boolean inStore, String accent, Map<String, String> files) {
		public String itemId() {
			return ITEM_PREFIX + id;
		}

		public boolean tintable() {
			return files.containsKey("mask");
		}
	}

	/** A worn item, its colour code, how far it sits lower and how far out from the face (quarter pixels) - the same as the game. */
	public record Worn(String id, int color, int offset, int depth) {
	}

	public static final int MAX_OFFSET = 8;

	private record State(Map<String, String> slots, Map<String, Integer> colors, Map<String, Integer> offsets, Map<String, Integer> depths) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Runs after anything worn or a colour changes (3D previews rebuild). */
	public static final List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();
	/** Try-on (Store pages, the Locker): shown instead of what's in that slot; a null value empties it. */
	private static final Map<String, String> PREVIEW = new HashMap<>();

	private WearCatalog() {
	}

	public static List<WearType> all() {
		List<WearType> out = new ArrayList<>();
		for (JsonObject o : WingsCatalog.serverItems("wear")) {
			String full = o.get("id").getAsString();
			if (!full.startsWith(ITEM_PREFIX) || !o.has("slot") || o.get("slot").isJsonNull()) {
				continue;
			}
			Map<String, String> files = new LinkedHashMap<>();
			if (o.has("files") && o.get("files").isJsonObject()) {
				o.getAsJsonObject("files").entrySet().forEach(e -> files.put(e.getKey(), e.getValue().getAsString()));
			}
			JsonObject model = o.has("model") && o.get("model").isJsonObject() ? o.getAsJsonObject("model") : new JsonObject();
			out.add(new WearType(full.substring(ITEM_PREFIX.length()), str(o, "name", full), str(o, "description", ""), o.get("slot").getAsString(),
					str(model, "bone", "head"), str(model, "mirror", null), o.has("price") ? o.get("price").getAsInt() : 0,
					o.has("inStore") && o.get("inStore").getAsBoolean(), str(o, "accent", "#ff4d4d"), files));
		}
		return out;
	}

	public static Optional<WearType> byId(String id) {
		return all().stream().filter(t -> t.id().equals(id)).findFirst();
	}

	public static boolean owns(String id) {
		return id != null && StoreOwnership.owns(ITEM_PREFIX + id);
	}

	// ---- what's worn ----

	private static Path path() {
		return VeloPaths.accountConfig("cosmetics-wear.json");
	}

	private static State read() {
		try {
			State s = GSON.fromJson(Files.readString(path(), StandardCharsets.UTF_8), State.class);
			return new State(s == null || s.slots() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(s.slots()),
					s == null || s.colors() == null ? new HashMap<>() : new HashMap<>(s.colors()),
					s == null || s.offsets() == null ? new HashMap<>() : new HashMap<>(s.offsets()),
					s == null || s.depths() == null ? new HashMap<>() : new HashMap<>(s.depths()));
		} catch (Exception e) {
			return new State(new LinkedHashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
		}
	}

	private static void write(State state) {
		try {
			Files.createDirectories(path().getParent());
			Path tmp = path().resolveSibling("cosmetics-wear.json.tmp");
			Files.writeString(tmp, GSON.toJson(state), StandardCharsets.UTF_8);
			Files.move(tmp, path(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			throw new RuntimeException("Couldn't save your bodywear", e);
		}
		LISTENERS.forEach(Runnable::run);
	}

	public static String equipped(String slot) {
		return read().slots().get(slot);
	}

	/** Puts an owned item on in its slot, or empties the slot with null. */
	public static void equip(String slot, String id) {
		State s = read();
		if (id == null) {
			s.slots().remove(slot);
		} else if (owns(id)) {
			s.slots().put(slot, id);
		}
		write(s);
	}

	public static int color(String id) {
		return read().colors().getOrDefault(id, 0);
	}

	public static void setColor(String id, int code) {
		State s = read();
		if (code <= 0 || !net.veloclient.velo.client.cosmetics.wear.WearTint.valid(code)) {
			s.colors().remove(id);
		} else {
			s.colors().put(id, code);
		}
		write(s);
	}

	public static int offset(String id) {
		return read().offsets().getOrDefault(id, 0);
	}

	public static void setOffset(String id, int offset) {
		State s = read();
		int o = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, offset));
		if (o == 0) {
			s.offsets().remove(id);
		} else {
			s.offsets().put(id, o);
		}
		write(s);
	}

	public static int depth(String id) {
		return read().depths().getOrDefault(id, 0);
	}

	/** Saves both placement settings at once (one rebuild of the 3D preview). */
	public static void setPlacement(String id, int offset, int depth) {
		State s = read();
		int o = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, offset));
		int d = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, depth));
		if (o == 0) {
			s.offsets().remove(id);
		} else {
			s.offsets().put(id, o);
		}
		if (d == 0) {
			s.depths().remove(id);
		} else {
			s.depths().put(id, d);
		}
		write(s);
	}

	public static synchronized void setPreview(String slot, String id) {
		PREVIEW.put(slot, id);
		LISTENERS.forEach(Runnable::run);
	}

	public static synchronized void clearPreview() {
		if (!PREVIEW.isEmpty()) {
			PREVIEW.clear();
			LISTENERS.forEach(Runnable::run);
		}
	}

	/** What you show: the worn items with the try-on over them. */
	public static synchronized List<Worn> shown() {
		State s = read();
		List<Worn> out = new ArrayList<>();
		for (String slot : SLOTS) {
			String id = PREVIEW.containsKey(slot) ? PREVIEW.get(slot) : s.slots().get(slot);
			if (id != null && (PREVIEW.containsKey(slot) || owns(id))) {
				out.add(new Worn(id, s.colors().getOrDefault(id, 0), s.offsets().getOrDefault(id, 0), s.depths().getOrDefault(id, 0)));
			}
		}
		return out;
	}

	private static String str(JsonObject o, String key, String fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}
}
