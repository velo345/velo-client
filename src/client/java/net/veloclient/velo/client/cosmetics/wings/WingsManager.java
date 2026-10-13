package net.veloclient.velo.client.cosmetics.wings;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.veloclient.velo.VeloClient;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The 3D wings that ship with Velo Client ({@code cosmetics/wings/wings.json}), which pair is
 * equipped, and the Cosmetics screen's temporary preview. Wings are drawn together with a cape
 * (they sit just outside it), hidden only while an elytra is worn. Other Velo Client players see
 * them too: the equipped id goes out with the Velo Network heartbeat.
 */
public final class WingsManager {

	/** How each pair moves: feathered flaps softly, bat flaps snappier, crystal is stiffer and slower. */
	public enum Style {
		FEATHERED(1f, 1f, 1f),
		BAT(1.15f, 1.1f, 0.6f),
		CRYSTAL(0.9f, 1.3f, 0.5f);

		public final float speed;
		public final float amplitude;
		public final float flex;

		Style(float speed, float amplitude, float flex) {
			this.speed = speed;
			this.amplitude = amplitude;
			this.flex = flex;
		}
	}

	/** A pair in the catalog; {@code offsetY} nudges it down on the back (blocks), {@code price} is a fallback for the shop. */
	public record WingType(String id, String name, Style style, float scale, float offsetY, String description, int price) {
		/** Its Velo Store item id. */
		public String itemId() {
			return ITEM_PREFIX + id;
		}
	}

	/** Wings are Velo Store items: {@code wings_<id>}. */
	public static final String ITEM_PREFIX = "wings_";

	/**
	 * How you wear your wings: size, position on the back, how far they're spread, how fast and how
	 * strongly they flap. Sent with the equipped id, so everyone sees them the way you set them.
	 */
	public record Settings(float scale, float offsetY, float offsetZ, float spread, float flapSpeed, float flapStrength) {
		/** Spread 6 degrees: resting a touch further back reads more natural than flat out. */
		public static final Settings DEFAULT = new Settings(1f, 0f, 0f, 6f, 1f, 1f);

		public Settings {
			scale = clamp(scale, 0.4f, 2f, 1f);
			offsetY = clamp(offsetY, -0.5f, 0.5f, 0f);
			offsetZ = clamp(offsetZ, -0.2f, 0.5f, 0f);
			spread = clamp(spread, -30f, 60f, 6f);
			flapSpeed = clamp(flapSpeed, 0.25f, 3f, 1f);
			flapStrength = clamp(flapStrength, 0f, 2f, 1f);
		}

		private static float clamp(float v, float min, float max, float fallback) {
			return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : fallback;
		}

		/** Spread in radians (added to how far back the wings rest). */
		public float spreadRadians() {
			return (float) Math.toRadians(spread);
		}

		/** "100;0;0;0;100;100" - percent / hundredths of a block / degrees, network-safe. */
		String encode() {
			return Math.round(scale * 100) + ";" + Math.round(offsetY * 100) + ";" + Math.round(offsetZ * 100) + ";" + Math.round(spread)
					+ ";" + Math.round(flapSpeed * 100) + ";" + Math.round(flapStrength * 100);
		}

		static Settings decode(String[] parts, int from) {
			try {
				return new Settings(Integer.parseInt(parts[from]) / 100f, Integer.parseInt(parts[from + 1]) / 100f,
						Integer.parseInt(parts[from + 2]) / 100f, Integer.parseInt(parts[from + 3]),
						Integer.parseInt(parts[from + 4]) / 100f, Integer.parseInt(parts[from + 5]) / 100f);
			} catch (RuntimeException e) {
				return DEFAULT;
			}
		}
	}

	/** A pair to draw and how: what the renderer gets for any player. */
	public record Worn(String id, Settings settings) {
	}

	/** Saved: the equipped pair and every pair's own settings (old files had one {@code settings} for all). */
	private record EquipState(String wingsId, Map<String, Settings> perWing, Settings settings) {
	}

	private static final Map<String, WingType> CATALOG = new LinkedHashMap<>();
	private static final Map<String, WingModel> MODELS = new HashMap<>();
	private static final java.util.Set<String> FAILED = new java.util.HashSet<>();
	private static boolean loaded;
	private static String equippedId;
	private static final Map<String, Settings> SETTINGS = new HashMap<>();
	private static String previewId;
	private static boolean previewActive;

	private WingsManager() {
	}

	public static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		try (InputStream in = WingsManager.class.getResourceAsStream("/assets/velo-client/cosmetics/wings/wings.json")) {
			if (in != null) {
				JsonArray entries = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class);
				for (JsonElement element : entries) {
					JsonObject e = element.getAsJsonObject();
					Style style = Style.FEATHERED;
					try {
						style = Style.valueOf(e.get("style").getAsString().toUpperCase(java.util.Locale.ROOT));
					} catch (RuntimeException ignored) {
						// Unknown style - feathered.
					}
					String id = e.get("id").getAsString();
					CATALOG.put(id, new WingType(id, e.get("name").getAsString(), style, e.has("scale") ? e.get("scale").getAsFloat() : 0.9f,
							e.has("offsetY") ? e.get("offsetY").getAsFloat() : 0f, e.has("description") ? e.get("description").getAsString() : "",
							e.has("price") ? e.get("price").getAsInt() : 2500));
				}
			}
		} catch (Exception e) {
			VeloClient.LOGGER.warn("Couldn't read the wings catalog", e);
		}
		EquipState state = net.veloclient.velo.config.ConfigManager.load("cosmetics-wings", EquipState.class, new EquipState(null, null, null));
		equippedId = state != null && state.wingsId() != null && CATALOG.containsKey(state.wingsId()) ? state.wingsId() : null;
		if (state != null && state.perWing() != null) {
			state.perWing().forEach((id, s) -> {
				if (s != null && CATALOG.containsKey(id)) {
					// Through the constructor again, so values from the file are clamped.
					SETTINGS.put(id, new Settings(s.scale(), s.offsetY(), s.offsetZ(), s.spread(), s.flapSpeed(), s.flapStrength()));
				}
			});
		} else if (state != null && state.settings() != null && equippedId != null) {
			Settings s = state.settings();
			SETTINGS.put(equippedId, new Settings(s.scale(), s.offsetY(), s.offsetZ(), s.spread(), s.flapSpeed(), s.flapStrength()));
		}
	}

	public static List<WingType> catalog() {
		load();
		return new ArrayList<>(CATALOG.values());
	}

	public static Optional<WingType> type(String id) {
		load();
		return Optional.ofNullable(id == null ? null : CATALOG.get(id));
	}

	/** The equipped pair's id, or null. */
	public static String equippedId() {
		load();
		return equippedId;
	}

	/** Whether you own this pair (bought with Velo Coins). Before the store has answered, a saved choice is trusted. */
	public static boolean owns(String id) {
		if (id == null) {
			return false;
		}
		return net.veloclient.velo.client.network.StoreClient.owns(ITEM_PREFIX + id)
				|| !net.veloclient.velo.client.network.StoreClient.connected() && id.equals(equippedId);
	}

	/** Equips a pair you own (or takes wings off with null); returns false if you don't own it. */
	public static boolean equip(String id) {
		load();
		if (id != null && (!CATALOG.containsKey(id) || !owns(id))) {
			return false;
		}
		equippedId = id;
		save();
		return true;
	}

	/** How you wear this pair (each pair remembers its own). */
	public static Settings settings(String id) {
		load();
		Settings s = id == null ? null : SETTINGS.get(id);
		return s != null ? s : Settings.DEFAULT;
	}

	public static void setSettings(String id, Settings value) {
		load();
		if (id == null) {
			return;
		}
		if (value == null || value.equals(Settings.DEFAULT)) {
			SETTINGS.remove(id);
		} else {
			SETTINGS.put(id, value);
		}
		save();
	}

	private static void save() {
		net.veloclient.velo.config.ConfigManager.save("cosmetics-wings", new EquipState(equippedId, new HashMap<>(SETTINGS), null));
	}

	/** What goes out with the Velo Network heartbeat: "id;scale;y;z;spread;speed;strength", or null. */
	public static String networkValue() {
		String id = equippedId();
		return id == null ? null : id + ";" + settings(id).encode();
	}

	/** Reads another player's {@link #networkValue()} (a bare id from older clients works too). */
	public static Worn parseNetworkValue(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String[] parts = value.split(";");
		if (!CATALOG.containsKey(parts[0])) {
			return null;
		}
		return new Worn(parts[0], parts.length >= 7 ? Settings.decode(parts, 1) : Settings.DEFAULT);
	}

	/** What the local player wears right now (the screen's preview while one is set). */
	public static Worn wornByLocalPlayer() {
		String id = renderIdForLocalPlayer();
		if (id == null || !previewActive && !owns(id)) {
			return null;
		}
		return new Worn(id, settings(id));
	}

	/** Cosmetics screen: show {@code id} (null = no wings) on you instead of the equipped pair until cleared. */
	public static void setPreview(String id) {
		previewId = id;
		previewActive = true;
	}

	public static void clearPreview() {
		previewActive = false;
		previewId = null;
	}

	/** What the local player shows right now (the preview while one is set). */
	public static String renderIdForLocalPlayer() {
		return previewActive ? previewId : equippedId();
	}

	/** The model for a catalog id, loaded on first use (null if it can't be). */
	public static synchronized WingModel model(String id) {
		WingModel model = MODELS.get(id);
		if (model != null || FAILED.contains(id)) {
			return model;
		}
		WingType type = CATALOG.get(id);
		if (type == null) {
			return null;
		}
		try {
			model = WingModel.load(id, type.scale());
			MODELS.put(id, model);
		} catch (Exception e) {
			FAILED.add(id);
			VeloClient.LOGGER.warn("Couldn't load wings {}", id, e);
		}
		return model;
	}
}
