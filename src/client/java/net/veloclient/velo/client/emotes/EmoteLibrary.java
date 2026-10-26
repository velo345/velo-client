package net.veloclient.velo.client.emotes;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.cosmetics.CosmeticsCatalog;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Emote animations. The server's cosmetics catalog is the source (item {@code emote_<id>}, file role "anim"), so a new
 * emote or a fixed one needs no client update; the game also ships the launch set ({@code assets/velo-client/emotes})
 * so an emote plays at once before (or without) the download.
 *
 * <p>Nothing here blocks: {@link #get} returns what's ready (the server's version when downloaded and parsed, else the
 * bundled one, else null) and loads the rest on a background thread - ask again next frame. Each frame holds the six
 * player model parts (head, body, right arm, left arm, right leg, left leg) as pivot x/y/z (model pixels) and x/y/z
 * rotation (radians); {@link Event}s are its sounds, music, effects and props.
 */
public final class EmoteLibrary {

	public static final int PARTS = 6;
	public static final int FLOATS_PER_FRAME = PARTS * 6;

	/**
	 * Something that happens {@code t} seconds into the emote (every loop): a sound, the music starting (again every
	 * {@code every} seconds, independent of the dance's loop), an effect ({@link EmoteFx.Spec}) at anchor {@code at},
	 * or a prop appearing. Sound ids without a namespace are Velo's ({@code emote.chime} = {@code velo-client:emote.chime}).
	 */
	public record Event(float t, String sound, float volume, float pitch, String music, float every, String at, EmoteFx.Spec fx,
			EmoteFx.PropSpec prop) {
	}

	/**
	 * One emote: {@code frames[i * FLOATS_PER_FRAME + part * 6 + k]}. {@code credit} names its music (shown on its Store
	 * page - the tracks are CC BY), or is null.
	 */
	public record Emote(String id, String name, float fps, boolean loop, int frameCount, float[] frames, List<Event> events, String credit) {

		public float durationSeconds() {
			return Math.max(1, frameCount - 1) / fps;
		}
	}

	private static final Map<String, Emote> BUNDLED = new ConcurrentHashMap<>();
	private static final Map<String, Emote> BY_SHA = new ConcurrentHashMap<>();
	private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
	private static volatile List<String> bundledIds;
	private static volatile int version;
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "velo-emotes");
		t.setDaemon(true);
		return t;
	});

	private EmoteLibrary() {
	}

	/** Goes up whenever an animation finished loading. */
	public static int version() {
		return version;
	}

	/** Loads the bundled set in the background (called once at start). */
	public static void warmUp() {
		IO.execute(EmoteLibrary::bundledIds);
	}

	/** The emote ready to play: the server's animation once it's here, the bundled one until then; null = still loading. */
	public static Emote get(String id) {
		if (id == null) {
			return null;
		}
		String sha = CosmeticsCatalog.item(EmoteCatalog.ITEM_PREFIX + id).map(i -> i.files().get("anim")).orElse(null);
		if (sha != null) {
			Emote ready = BY_SHA.get(sha);
			if (ready != null) {
				return ready;
			}
			Path file = CosmeticsCatalog.file(sha);
			if (file != null && LOADING.add(sha)) {
				IO.execute(() -> {
					try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
						BY_SHA.put(sha, parse(id, JsonParser.parseReader(in).getAsJsonObject()));
						version++;
					} catch (Exception e) {
						VeloClient.LOGGER.warn("Couldn't read emote {} from the catalog", id, e);
					}
				});
			}
		} else if (!bundledIds().contains(id)) {
			// someone else plays an emote this client doesn't know yet
			CosmeticsCatalog.requestItem(EmoteCatalog.ITEM_PREFIX + id);
		}
		Emote bundled = BUNDLED.get(id);
		if (bundled == null && bundledIds().contains(id) && LOADING.add("bundled:" + id)) {
			IO.execute(() -> {
				Emote e = loadBundled(id);
				if (e != null) {
					BUNDLED.put(id, e);
					version++;
				}
			});
		}
		return bundled;
	}

	/** Starts loading these in the background (owned emotes, the wheel) so they play without a wait. */
	public static void prefetch(List<String> ids) {
		for (String id : ids) {
			get(id);
		}
	}

	/** The emotes the game ships, in their order. */
	public static List<String> bundledIds() {
		List<String> ids = bundledIds;
		if (ids != null) {
			return ids;
		}
		List<String> out = new ArrayList<>();
		try (InputStream in = EmoteLibrary.class.getResourceAsStream("/assets/velo-client/emotes/emotes.json")) {
			if (in != null) {
				JsonObject index = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				for (JsonElement entry : index.getAsJsonArray("emotes")) {
					out.add(entry.getAsJsonObject().get("id").getAsString());
				}
			}
		} catch (Exception e) {
			VeloClient.LOGGER.warn("Couldn't load the emote list", e);
		}
		bundledIds = Collections.unmodifiableList(out);
		return bundledIds;
	}

	/** The bundled display names (before the catalog is in). */
	static Map<String, String> bundledNames() {
		Map<String, String> out = new LinkedHashMap<>();
		try (InputStream in = EmoteLibrary.class.getResourceAsStream("/assets/velo-client/emotes/emotes.json")) {
			if (in != null) {
				for (JsonElement entry : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("emotes")) {
					JsonObject o = entry.getAsJsonObject();
					out.put(o.get("id").getAsString(), o.get("name").getAsString());
				}
			}
		} catch (Exception e) {
			VeloClient.LOGGER.debug("Couldn't read emote names", e);
		}
		return out;
	}

	private static Emote loadBundled(String id) {
		try (InputStream in = EmoteLibrary.class.getResourceAsStream("/assets/velo-client/emotes/" + id + ".vemote.json")) {
			return in == null ? null : parse(id, JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
		} catch (Exception e) {
			VeloClient.LOGGER.warn("Couldn't load emote {}", id, e);
			return null;
		}
	}

	private static Emote parse(String id, JsonObject json) {
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
		if (json.has("events") && json.get("events").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("events")) {
				try {
					events.add(event(element.getAsJsonObject()));
				} catch (RuntimeException e) {
					VeloClient.LOGGER.debug("Skipped a broken event in emote {}", id, e);
				}
			}
			events.sort((a, b) -> Float.compare(a.t(), b.t()));
		}
		String name = json.has("name") ? json.get("name").getAsString() : id;
		return new Emote(id, name, json.get("fps").getAsFloat(), json.has("loop") && json.get("loop").getAsBoolean(), frames.size(), data,
				List.copyOf(events), str(json, "credit"));
	}

	private static Event event(JsonObject e) {
		String at = e.has("at") ? e.get("at").getAsString() : "chest";
		EmoteFx.Spec fx = null;
		if (e.has("fx")) {
			fx = new EmoteFx.Spec(e.get("fx").getAsString(), cells(e.get("shape")), colors(e.get("color")), (int) num(e, "count", 8f),
					num(e, "speed", 1f), num(e, "life", 1f), num(e, "size", 0.12f), num(e, "gravity", 0f), num(e, "dur", 0f),
					num(e, "radius", 0f), num(e, "spin", 0f), str(e, "text"));
		}
		EmoteFx.PropSpec prop = null;
		if (e.has("prop")) {
			float[] pos = vec(e, "pos", 3);
			float[] rot = vec(e, "rot", 3);
			float[] vel = vec(e, "vel", 3);
			prop = new EmoteFx.PropSpec(e.get("prop").getAsString(), e.has("at") ? at : "ground", num(e, "dur", 0f), pos[0], pos[1], pos[2],
					rot[0], rot[1], rot[2], num(e, "scale", 0.5f), str(e, "anim"), vel[0], vel[1], vel[2], num(e, "release", 0f),
					num(e, "spin", 0f), num(e, "pulse", 0f));
		}
		return new Event(num(e, "t", 0f), soundId(str(e, "sound")), num(e, "volume", 1f), num(e, "pitch", 1f), soundId(str(e, "music")),
				num(e, "every", 0f), at, fx, prop);
	}

	private static String soundId(String id) {
		return id == null || id.contains(":") ? id : "velo-client:" + id;
	}

	private static int[] cells(JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return new int[] {EmoteFx.GLOW};
		}
		if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			int[] out = new int[a.size()];
			for (int i = 0; i < out.length; i++) {
				out[i] = EmoteFx.cell(a.get(i).getAsString());
			}
			return out;
		}
		return new int[] {EmoteFx.cell(e.getAsString())};
	}

	private static int[] colors(JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return new int[] {0xFFFFFF};
		}
		JsonArray a;
		if (e.isJsonArray()) {
			a = e.getAsJsonArray();
		} else {
			a = new JsonArray();
			a.add(e);
		}
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			String c = a.get(i).getAsString();
			out[i] = "rainbow".equals(c) ? EmoteFx.RAINBOW : Integer.parseInt(c.startsWith("#") ? c.substring(1) : c, 16);
		}
		return out;
	}

	private static float[] vec(JsonObject o, String key, int n) {
		float[] out = new float[n];
		if (o.has(key) && o.get(key).isJsonArray()) {
			JsonArray a = o.getAsJsonArray(key);
			for (int i = 0; i < Math.min(n, a.size()); i++) {
				out[i] = a.get(i).getAsFloat();
			}
		}
		return out;
	}

	private static float num(JsonObject o, String key, float fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsFloat() : fallback;
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}
}
