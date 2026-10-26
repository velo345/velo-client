package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Emotes in the launcher: Velo Store items {@code emote_<id>} from the server's catalog (never free), whether you own
 * them, your emote wheel (eight slots in the same {@code config/cosmetics-emotes.json} the game reads) and the
 * animations themselves - downloaded from the catalog and parsed in the background, kept for the session.
 */
public final class EmoteCatalog {

	public static final String ITEM_PREFIX = "emote_";
	public static final int SLOTS = 8;
	public static final int FLOATS_PER_FRAME = 36;

	public record EmoteType(String id, String name, String description, int price, boolean inStore, String accent, String animSha) {
		public String itemId() {
			return ITEM_PREFIX + id;
		}
	}

	/**
	 * An effect {@code t} seconds into the emote (see tools/cosmetics/emotes/emote_specs.py), reduced to what the
	 * previews draw: its shape ({@code particle}: glow, star, heart, note, smoke, bat, ghost, L...), first colour
	 * ("#rrggbb", "rainbow" or null) and text. Sounds, music and props only play in the game.
	 */
	public record Event(double t, String sound, String particle, String at, int count, double spread, double up, String color, String text) {
	}

	/** The animation: six parts per frame (pivot x/y/z in model pixels, rotation x/y/z in radians). */
	public record Animation(String id, double fps, boolean loop, int frameCount, float[] frames, List<Event> events) {

		public double duration() {
			return Math.max(1, frameCount - 1) / fps;
		}

		/** The pose at a (fractional) frame into {@code out} (36 floats). */
		public void pose(double frame, float[] out) {
			int a = Math.max(0, Math.min(frameCount - 1, (int) frame));
			int b = Math.min(frameCount - 1, a + 1);
			float f = (float) (frame - a);
			for (int i = 0; i < FLOATS_PER_FRAME; i++) {
				float va = frames[a * FLOATS_PER_FRAME + i];
				float vb = frames[b * FLOATS_PER_FRAME + i];
				float d = vb - va;
				if (i % 6 >= 3) {
					d = (float) Math.IEEEremainder(d, Math.PI * 2);
				}
				out[i] = va + d * f;
			}
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Animation> ANIMATIONS = new ConcurrentHashMap<>();
	private static final Map<String, List<Consumer<Animation>>> WAITING = new ConcurrentHashMap<>();
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "velo-emotes");
		t.setDaemon(true);
		return t;
	});

	private EmoteCatalog() {
	}

	// ---- Catalog ----

	public static List<EmoteType> all() {
		List<EmoteType> out = new ArrayList<>();
		for (JsonObject o : WingsCatalog.serverItems("emote")) {
			String itemId = o.get("id").getAsString();
			if (!itemId.startsWith(ITEM_PREFIX)) {
				continue;
			}
			String sha = o.has("files") && o.getAsJsonObject("files").has("anim") ? o.getAsJsonObject("files").get("anim").getAsString() : null;
			out.add(new EmoteType(itemId.substring(ITEM_PREFIX.length()), str(o, "name", itemId), str(o, "description", ""),
					o.has("price") ? o.get("price").getAsInt() : 0, o.has("inStore") && o.get("inStore").getAsBoolean(), str(o, "accent", "#8b7cf6"), sha));
		}
		return out;
	}

	public static List<EmoteType> forSale() {
		return all().stream().filter(EmoteType::inStore).toList();
	}

	public static Optional<EmoteType> byId(String id) {
		return all().stream().filter(e -> e.id().equals(id)).findFirst();
	}

	public static boolean owns(String id) {
		return id != null && StoreOwnership.owns(ITEM_PREFIX + id);
	}

	public static List<EmoteType> owned() {
		return all().stream().filter(e -> owns(e.id())).toList();
	}

	// ---- Animations ----

	/** The parsed animation if it's ready, else null (then {@link #load} it). */
	public static Animation ready(EmoteType type) {
		return type == null || type.animSha() == null ? null : ANIMATIONS.get(type.animSha());
	}

	/** Calls {@code done} (on a background thread) with the animation once it's downloaded and parsed - at once if it already is. */
	public static void load(EmoteType type, Consumer<Animation> done) {
		if (type == null || type.animSha() == null) {
			return;
		}
		Animation ready = ANIMATIONS.get(type.animSha());
		if (ready != null) {
			done.accept(ready);
			return;
		}
		boolean first = WAITING.computeIfAbsent(type.animSha(), k -> new java.util.concurrent.CopyOnWriteArrayList<>()).isEmpty();
		WAITING.get(type.animSha()).add(done);
		if (!first) {
			return;
		}
		IO.execute(() -> {
			Animation animation = null;
			try (InputStream in = WingsCatalog.openFile(type.animSha())) {
				byte[] bytes = in.readAllBytes();
				if (bytes.length > 0 && bytes[0] == '{') {
					animation = parse(type.id(), JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject());
					ANIMATIONS.put(type.animSha(), animation);
				}
			} catch (Exception e) {
				// stays unloaded; a later call tries again
			}
			List<Consumer<Animation>> waiting = WAITING.remove(type.animSha());
			if (animation != null && waiting != null) {
				for (Consumer<Animation> c : waiting) {
					c.accept(animation);
				}
			}
		});
	}

	private static Animation parse(String id, JsonObject json) {
		JsonArray frames = json.getAsJsonArray("frames");
		float[] data = new float[frames.size() * FLOATS_PER_FRAME];
		int at = 0;
		for (JsonElement frame : frames) {
			for (JsonElement part : frame.getAsJsonArray()) {
				for (JsonElement v : part.getAsJsonArray()) {
					if (at < data.length) {
						data[at++] = v.getAsFloat();
					}
				}
			}
		}
		List<Event> events = new ArrayList<>();
		if (json.has("events")) {
			for (JsonElement element : json.getAsJsonArray("events")) {
				JsonObject e = element.getAsJsonObject();
				if (!e.has("fx")) {
					continue; // a sound, the music or a prop
				}
				JsonElement shape = e.get("shape");
				String particle = shape == null ? "glow" : shape.isJsonArray() ? shape.getAsJsonArray().get(0).getAsString() : shape.getAsString();
				JsonElement color = e.get("color");
				String first = color == null ? null : color.isJsonArray() ? color.getAsJsonArray().get(0).getAsString() : color.getAsString();
				double speed = e.has("speed") ? e.get("speed").getAsDouble() : 1;
				double radius = e.has("radius") ? e.get("radius").getAsDouble() : 0;
				events.add(new Event(e.get("t").getAsDouble(), null, particle, str(e, "at", "chest"), e.has("count") ? e.get("count").getAsInt() : 4,
						Math.min(1.2, radius + speed * 0.12), speed * 0.04, first, str(e, "text", null)));
			}
		}
		return new Animation(id, json.get("fps").getAsDouble(), json.has("loop") && json.get("loop").getAsBoolean(), frames.size(), data,
				List.copyOf(events));
	}

	// ---- The wheel (config/cosmetics-emotes.json, shared with the game) ----

	private static Path file() {
		return VeloPaths.config().resolve("cosmetics-emotes.json");
	}

	private static JsonObject read() {
		try {
			if (Files.exists(file())) {
				return JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (Exception ignored) {
			// start over
		}
		return new JsonObject();
	}

	private static String[] slots(JsonObject o) {
		String[] out = new String[SLOTS];
		if (o.has("slots") && o.get("slots").isJsonArray()) {
			JsonArray a = o.getAsJsonArray("slots");
			for (int i = 0; i < SLOTS && i < a.size(); i++) {
				out[i] = a.get(i).isJsonNull() ? null : a.get(i).getAsString();
			}
		}
		return out;
	}

	/** What a wheel slot holds (null = empty or an emote you don't own). */
	public static String slot(int index) {
		String id = slots(read())[index];
		return owns(id) ? id : null;
	}

	public static int slotOf(String id) {
		String[] s = slots(read());
		for (int i = 0; i < s.length; i++) {
			if (id != null && id.equals(s[i])) {
				return i;
			}
		}
		return -1;
	}

	/** Puts an emote on a slot (null clears it); it moves off any other slot. The game reads it on its next start. */
	public static void setSlot(int index, String id) {
		JsonObject o = read();
		String[] s = slots(o);
		if (id != null) {
			for (int i = 0; i < s.length; i++) {
				if (id.equals(s[i])) {
					s[i] = s[index];
				}
			}
		}
		s[index] = id;
		JsonArray array = new JsonArray();
		for (String v : s) {
			if (v == null) {
				array.add(com.google.gson.JsonNull.INSTANCE);
			} else {
				array.add(v);
			}
		}
		o.add("slots", array);
		// the game only auto-fills emotes it hasn't seen yet: anything placed here counts as seen
		JsonArray known = o.has("known") && o.get("known").isJsonArray() ? o.getAsJsonArray("known") : new JsonArray();
		if (id != null && !known.contains(new com.google.gson.JsonPrimitive(id))) {
			known.add(id);
		}
		o.add("known", known);
		try {
			VeloPaths.ensureDirectories();
			Files.writeString(file(), GSON.toJson(o), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new RuntimeException("Couldn't save the emote wheel", e);
		}
	}

	/** Puts a newly bought emote on the first free slot (if there is one). */
	public static void placeOnWheel(String id) {
		if (slotOf(id) >= 0) {
			return;
		}
		for (int i = 0; i < SLOTS; i++) {
			if (slot(i) == null) {
				setSlot(i, id);
				return;
			}
		}
	}

	private static String str(JsonObject o, String key, String fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}
}
