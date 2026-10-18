package net.veloclient.velo.client.cosmetics.wings;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.Identifier;
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

	/**
	 * A pair in the catalog; {@code offsetY} nudges it down on the back (blocks), {@code price} is a fallback for the shop,
	 * {@code collection} is the id of the {@link Collection} it belongs to and {@code accent} its colour (ARGB) for glows.
	 * {@code bundled}: its art ships inside the mod; otherwise {@code mesh}/{@code texture}/{@code preview} are the server
	 * files (sha256) it's drawn from. {@code inStore}: can be bought right now ({@code availableUntil}: until when, or null).
	 */
	public record WingType(String id, String name, Style style, float scale, float offsetY, String description, int price,
			String collection, int accent, boolean glow, boolean bundled, boolean inStore, Long availableUntil,
			String mesh, String texture, String preview) {
		/** Its Velo Store item id. */
		public String itemId() {
			return ITEM_PREFIX + id;
		}
	}

	/** A themed drop of wings, e.g. "Afterlife". */
	public record Collection(String id, String name, String tag, String description) {
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

	private static final Map<String, WingType> BUNDLED = new LinkedHashMap<>();
	private static final List<Collection> BUNDLED_COLLECTIONS = new ArrayList<>();
	private static Map<String, WingType> CATALOG = new LinkedHashMap<>();
	private static List<Collection> COLLECTIONS = new ArrayList<>();
	private static String featuredId;
	private static int catalogVersion = -1;
	private static final Map<String, WingModel> MODELS = new HashMap<>();
	private static final Map<String, Identifier> TEXTURES = new HashMap<>();
	/** id or file hash -> when loading it last failed; tried again after a while instead of never (a bad moment shouldn't hide wings for the session). */
	private static final java.util.Map<String, Long> FAILED = new java.util.HashMap<>();

	private static boolean failedRecently(String key) {
		Long at = FAILED.get(key);
		return at != null && System.currentTimeMillis() - at < 30_000;
	}
	private static boolean loaded;
	private static String equippedId;
	private static final Map<String, Settings> SETTINGS = new HashMap<>();
	private static String previewId;
	private static boolean previewActive;

	private WingsManager() {
	}

	public static synchronized void load() {
		if (loaded) {
			sync();
			return;
		}
		loaded = true;
		try (InputStream in = WingsManager.class.getResourceAsStream("/assets/velo-client/cosmetics/wings/collections.json")) {
			if (in != null) {
				for (JsonElement element : new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class)) {
					JsonObject c = element.getAsJsonObject();
					BUNDLED_COLLECTIONS.add(new Collection(c.get("id").getAsString(), c.get("name").getAsString(),
							c.has("tag") ? c.get("tag").getAsString() : "COLLECTION", c.has("description") ? c.get("description").getAsString() : ""));
				}
			}
		} catch (Exception e) {
			VeloClient.LOGGER.warn("Couldn't read the wings collections", e);
		}
		try (InputStream in = WingsManager.class.getResourceAsStream("/assets/velo-client/cosmetics/wings/wings.json")) {
			if (in != null) {
				JsonArray entries = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class);
				for (JsonElement element : entries) {
					JsonObject e = element.getAsJsonObject();
					String id = e.get("id").getAsString();
					int accent = 0xFFFF4D4D;
					try {
						accent = 0xFF000000 | Integer.parseInt(e.get("accent").getAsString().substring(1), 16);
					} catch (RuntimeException ignored) {
						// No / bad accent - Velo red.
					}
					BUNDLED.put(id, new WingType(id, e.get("name").getAsString(), style(e.has("style") ? e.get("style").getAsString() : null),
							e.has("scale") ? e.get("scale").getAsFloat() : 0.9f, e.has("offsetY") ? e.get("offsetY").getAsFloat() : 0f,
							e.has("description") ? e.get("description").getAsString() : "", e.has("price") ? e.get("price").getAsInt() : 2500,
							e.has("collection") ? e.get("collection").getAsString() : "", accent, false, true, true, null, null, null, null));
				}
			}
		} catch (Exception e) {
			VeloClient.LOGGER.warn("Couldn't read the wings catalog", e);
		}
		net.veloclient.velo.client.cosmetics.CosmeticsCatalog.refresh(false);
		sync();
		// The saved choice is kept even for a pair this client doesn't know yet (a server-only one): the
		// server catalog arrives a moment later.
		EquipState state = net.veloclient.velo.config.ConfigManager.load("cosmetics-wings", EquipState.class, new EquipState(null, null, null));
		equippedId = state != null ? state.wingsId() : null;
		if (state != null && state.perWing() != null) {
			state.perWing().forEach((id, s) -> {
				if (s != null) {
					// Through the constructor again, so values from the file are clamped.
					SETTINGS.put(id, new Settings(s.scale(), s.offsetY(), s.offsetZ(), s.spread(), s.flapSpeed(), s.flapStrength()));
				}
			});
		} else if (state != null && state.settings() != null && equippedId != null) {
			Settings s = state.settings();
			SETTINGS.put(equippedId, new Settings(s.scale(), s.offsetY(), s.offsetZ(), s.spread(), s.flapSpeed(), s.flapStrength()));
		}
	}

	private static Style style(String name) {
		try {
			return name == null ? Style.FEATHERED : Style.valueOf(name.toUpperCase(java.util.Locale.ROOT));
		} catch (RuntimeException e) {
			return Style.FEATHERED;
		}
	}

	/**
	 * Rebuilds the catalog when the server's changed: the bundled pairs, updated / extended by the
	 * server's (prices, status, new pairs). Once the server answered, a bundled pair it didn't list
	 * isn't in the store (hidden or its time is over) - still drawn on whoever wears it.
	 */
	private static void sync() {
		int version = net.veloclient.velo.client.cosmetics.CosmeticsCatalog.version();
		if (version == catalogVersion) {
			return;
		}
		catalogVersion = version;
		boolean server = net.veloclient.velo.client.cosmetics.CosmeticsCatalog.loaded();
		Map<String, WingType> merged = new LinkedHashMap<>();
		for (WingType t : BUNDLED.values()) {
			merged.put(t.id(), server ? withStore(t, false, null) : t);
		}
		List<net.veloclient.velo.client.cosmetics.CosmeticsCatalog.Item> remote = new ArrayList<>(net.veloclient.velo.client.cosmetics.CosmeticsCatalog.items());
		// Plus pairs looked up because someone wears them.
		for (var looked : net.veloclient.velo.client.cosmetics.CosmeticsCatalog.lookedUp()) {
			if (remote.stream().noneMatch(i -> i.id().equals(looked.id()))) {
				remote.add(looked);
			}
		}
		for (var item : remote) {
			if (!"wings".equals(item.type()) || !item.id().startsWith(ITEM_PREFIX)) {
				continue;
			}
			String id = item.id().substring(ITEM_PREFIX.length());
			WingType base = BUNDLED.get(id);
			merged.put(id, new WingType(id, item.name(), item.style() != null ? style(item.style()) : base != null ? base.style() : Style.FEATHERED,
					item.scale() != null ? item.scale() : base != null ? base.scale() : 0.9f,
					item.offsetY() != null ? item.offsetY() : base != null ? base.offsetY() : 0f,
					item.description(), item.price() > 0 ? item.price() : base != null ? base.price() : 0,
					item.collection() != null ? item.collection() : "", item.accent() != null ? item.accent() : base != null ? base.accent() : 0xFFFF4D4D,
					item.glow(), base != null, item.inStore(), item.availableUntil(),
					item.files().get("mesh"), item.files().get("texture"), item.files().get("preview")));
		}
		List<Collection> collections = new ArrayList<>(BUNDLED_COLLECTIONS);
		if (server && !net.veloclient.velo.client.cosmetics.CosmeticsCatalog.collections().isEmpty()) {
			collections = new ArrayList<>();
			for (var c : net.veloclient.velo.client.cosmetics.CosmeticsCatalog.collections()) {
				collections.add(new Collection(c.id(), c.name(), c.tag(), c.description()));
			}
		}
		featuredId = server ? net.veloclient.velo.client.cosmetics.CosmeticsCatalog.featured() : null;
		List<Collection> order = collections;
		List<WingType> types = new ArrayList<>(merged.values());
		// Newest collection first, by name inside each one.
		types.sort(java.util.Comparator.comparingInt((WingType t) -> index(order, t.collection())).thenComparing(WingType::name));
		Map<String, WingType> sorted = new LinkedHashMap<>();
		types.forEach(t -> sorted.put(t.id(), t));
		CATALOG = sorted;
		COLLECTIONS = collections;
	}

	private static WingType withStore(WingType t, boolean inStore, Long until) {
		return new WingType(t.id(), t.name(), t.style(), t.scale(), t.offsetY(), t.description(), t.price(), t.collection(), t.accent(),
				t.glow(), t.bundled(), inStore, until, t.mesh(), t.texture(), t.preview());
	}

	private static int index(List<Collection> collections, String id) {
		for (int i = 0; i < collections.size(); i++) {
			if (collections.get(i).id().equals(id)) {
				return i;
			}
		}
		return collections.size();
	}

	/** Every pair this client knows (bundled, in the store, owned, or seen on someone). */
	public static synchronized List<WingType> catalog() {
		load();
		return new ArrayList<>(CATALOG.values());
	}

	/** What the Store sells right now. */
	public static List<WingType> forSale() {
		return catalog().stream().filter(WingType::inStore).toList();
	}

	/** Pairs you can equip: the ones you own (the saved one too, until the server answered). */
	public static List<WingType> owned() {
		return catalog().stream().filter(t -> owns(t.id())).toList();
	}

	/** Collections with something for sale, newest first. */
	public static synchronized List<Collection> collections() {
		load();
		List<WingType> sale = forSale();
		return COLLECTIONS.stream().filter(c -> sale.stream().anyMatch(t -> t.collection().equals(c.id()))).toList();
	}

	/** What the Store features: the server's pick, else the newest collection with something for sale. */
	public static Optional<Collection> featured() {
		List<Collection> withItems = collections();
		return withItems.stream().filter(c -> c.id().equals(featuredId)).findFirst().or(() -> withItems.stream().findFirst());
	}

	public static synchronized Optional<Collection> collection(String id) {
		load();
		return COLLECTIONS.stream().filter(c -> c.id().equals(id)).findFirst();
	}

	/** The pairs for sale in one collection. */
	public static List<WingType> inCollection(String collectionId) {
		return forSale().stream().filter(t -> t.collection().equals(collectionId)).toList();
	}

	/** A pair by id; one this client doesn't know yet (someone else wears it) is looked up on the server. */
	public static synchronized Optional<WingType> type(String id) {
		load();
		if (id == null) {
			return Optional.empty();
		}
		WingType type = CATALOG.get(id);
		if (type == null) {
			net.veloclient.velo.client.cosmetics.CosmeticsCatalog.requestItem(ITEM_PREFIX + id);
			var item = net.veloclient.velo.client.cosmetics.CosmeticsCatalog.item(ITEM_PREFIX + id);
			if (item.isPresent()) {
				catalogVersion = -1;
				sync();
				type = CATALOG.get(id);
			}
		}
		return Optional.ofNullable(type);
	}

	/** The equipped pair's id, or null. */
	public static String equippedId() {
		load();
		return equippedId;
	}

	/**
	 * Whether you own this pair (bought with Velo Coins or given by the owner). Until the server has said
	 * what you own, the saved choice is trusted - otherwise your wings vanish after every launch.
	 */
	public static boolean owns(String id) {
		if (id == null) {
			return false;
		}
		return net.veloclient.velo.client.network.StoreClient.owns(ITEM_PREFIX + id)
				|| !net.veloclient.velo.client.network.StoreClient.walletLoaded() && id.equals(equippedId);
	}

	/** Equips a pair you own (or takes wings off with null); returns false if you don't own it. */
	public static boolean equip(String id) {
		load();
		if (id != null && (type(id).isEmpty() || !owns(id))) {
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
		if (type(parts[0]).isEmpty()) {
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

	/**
	 * The model for a catalog id, loaded on first use. Null if it can't be - or, for a server-only pair,
	 * until its mesh has downloaded (asked again next frame).
	 */
	public static synchronized WingModel model(String id) {
		WingModel model = MODELS.get(id);
		if (model != null || failedRecently(id)) {
			return model;
		}
		WingType type = type(id).orElse(null);
		if (type == null) {
			return null;
		}
		try {
			if (type.bundled()) {
				model = WingModel.load(id, type.scale());
			} else {
				java.nio.file.Path file = net.veloclient.velo.client.cosmetics.CosmeticsCatalog.file(type.mesh());
				if (file == null) {
					return null;
				}
				try (InputStream in = java.nio.file.Files.newInputStream(file)) {
					model = WingModel.read(in, id, type.scale());
				}
			}
			MODELS.put(id, model);
		} catch (Exception e) {
			FAILED.put(id, System.currentTimeMillis());
			VeloClient.LOGGER.warn("Couldn't load wings {}", id, e);
		}
		return model;
	}

	/** The pair's texture: bundled, or registered from the downloaded file (null until it's there). Render thread only. */
	public static synchronized Identifier texture(String id) {
		WingType type = type(id).orElse(null);
		if (type == null) {
			return null;
		}
		if (type.bundled()) {
			return Identifier.of("velo-client", "textures/cosmetics/wings/" + id + ".png");
		}
		return downloaded(type.texture(), "wings " + id);
	}

	/** The pair's still picture for cards (null until downloaded). Render thread only. */
	public static synchronized Identifier previewTexture(String id) {
		WingType type = type(id).orElse(null);
		if (type == null) {
			return null;
		}
		if (type.bundled()) {
			return Identifier.of("velo-client", "textures/cosmetics/wings/" + id + "_preview.png");
		}
		return downloaded(type.preview(), "wings preview " + id);
	}

	private static Identifier downloaded(String sha, String label) {
		if (sha == null) {
			return null;
		}
		Identifier known = TEXTURES.get(sha);
		if (known != null || failedRecently(sha)) {
			return known;
		}
		java.nio.file.Path file = net.veloclient.velo.client.cosmetics.CosmeticsCatalog.file(sha);
		if (file == null) {
			return null;
		}
		try (InputStream in = java.nio.file.Files.newInputStream(file)) {
			net.minecraft.client.texture.NativeImage image = net.minecraft.client.texture.NativeImage.read(in);
			Identifier id = net.veloclient.velo.client.cosmetics.CapeManager.registerPreloadedTexture("wingtex_" + sha.substring(0, 24), image, label);
			TEXTURES.put(sha, id);
			return id;
		} catch (Exception e) {
			FAILED.put(sha, System.currentTimeMillis());
			VeloClient.LOGGER.warn("Couldn't load {}", label, e);
			return null;
		}
	}
}
