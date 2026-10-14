package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The 3D wings: the ones bundled from the mod's own assets (see the launcher's bundleWingAssets
 * task), updated and extended by the Velo server's cosmetics catalog - new pairs, prices, what's in
 * the store right now - so a new pair needs no launcher update. Server-only art is downloaded to the
 * same {@code cache/cosmetics/files/} the game uses. Also which pair is equipped and how each one is
 * worn - written to the same {@code config/cosmetics-wings.json} the game reads.
 */
public final class WingsCatalog {

	public static final String ROOT = "/net/veloclient/launcher/wings/";
	public static final String ITEM_PREFIX = "wings_";

	/**
	 * {@code collection} is a {@link Collection} id, {@code accent} the pair's colour ("#rrggbb"). {@code bundled}:
	 * the art ships with the launcher, else {@code files} (mesh / texture / preview -> sha256) is downloaded.
	 */
	public record WingType(String id, String name, String description, String style, float scale, float offsetY, int price,
			String collection, String accent, boolean glow, boolean bundled, boolean inStore, Long availableUntil, Map<String, String> files) {
		public String itemId() {
			return ITEM_PREFIX + id;
		}
	}

	/** A themed drop of wings, newest first. */
	public record Collection(String id, String name, String tag, String description) {
	}

	/** How a pair is worn: the same fields (and ranges) the game uses. */
	public record Settings(float scale, float offsetY, float offsetZ, float spread, float flapSpeed, float flapStrength) {
		public static final Settings DEFAULT = new Settings(1f, 0f, 0f, 6f, 1f, 1f);
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static List<WingType> bundled;
	private static List<Collection> bundledCollections;
	private static volatile JsonObject server;
	private static List<WingType> catalog;
	private static List<Collection> collections;

	private WingsCatalog() {
	}

	// ---- Catalog ----

	private static synchronized void loadBundled() {
		if (bundled != null) {
			return;
		}
		List<Collection> cols = new ArrayList<>();
		try (InputStream in = WingsCatalog.class.getResourceAsStream(ROOT + "collections.json")) {
			if (in != null) {
				for (JsonElement e : GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class)) {
					JsonObject o = e.getAsJsonObject();
					cols.add(new Collection(o.get("id").getAsString(), o.get("name").getAsString(), str(o, "tag", "COLLECTION"), str(o, "description", "")));
				}
			}
		} catch (Exception ignored) {
			// No collections bundled.
		}
		List<WingType> out = new ArrayList<>();
		try (InputStream in = WingsCatalog.class.getResourceAsStream(ROOT + "wings.json")) {
			if (in != null) {
				for (JsonElement e : GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class)) {
					JsonObject o = e.getAsJsonObject();
					out.add(new WingType(o.get("id").getAsString(), o.get("name").getAsString(), str(o, "description", ""),
							str(o, "style", "feathered"), o.has("scale") ? o.get("scale").getAsFloat() : 0.9f,
							o.has("offsetY") ? o.get("offsetY").getAsFloat() : 0f, o.has("price") ? o.get("price").getAsInt() : 2500,
							str(o, "collection", ""), str(o, "accent", "#ff4d4d"), false, true, true, null, Map.of()));
				}
			}
		} catch (Exception ignored) {
			// No wings bundled.
		}
		bundled = List.copyOf(out);
		bundledCollections = List.copyOf(cols);
		// The last catalog the server sent, until a fresh one arrives.
		try {
			Path cached = cacheDir().resolve("launcher-catalog.json");
			if (Files.exists(cached)) {
				server = JsonParser.parseString(Files.readString(cached, StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (Exception ignored) {
			// Start without.
		}
	}

	/**
	 * Fetches the server's catalog (what's for sale, plus what you own; everything for the owner) and
	 * downloads the art it needs. Blocking - call off the UI thread. True if anything changed.
	 */
	public static boolean refresh() {
		loadBundled();
		String base = net.veloclient.launcher.social.LauncherSocial.baseUrl();
		if (base == null) {
			return false;
		}
		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/v1/cosmetics/catalog")).timeout(Duration.ofSeconds(20)).GET();
			String token = net.veloclient.launcher.social.LauncherSocial.sessionToken();
			if (token != null) {
				request.header("Authorization", "Bearer " + token);
			}
			HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return false;
			}
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			boolean changed = !json.equals(server);
			Files.createDirectories(cacheDir());
			Files.writeString(cacheDir().resolve("launcher-catalog.json"), response.body(), StandardCharsets.UTF_8);
			synchronized (WingsCatalog.class) {
				server = json;
				catalog = null;
			}
			for (WingType type : all()) {
				if (!type.bundled()) {
					for (String sha : type.files().values()) {
						file(sha);
					}
				}
			}
			return changed;
		} catch (Exception e) {
			return false;
		}
	}

	private static synchronized void build() {
		loadBundled();
		if (catalog != null) {
			return;
		}
		Map<String, WingType> merged = new LinkedHashMap<>();
		JsonObject s = server;
		for (WingType t : bundled) {
			merged.put(t.id(), s == null ? t : new WingType(t.id(), t.name(), t.description(), t.style(), t.scale(), t.offsetY(), t.price(),
					t.collection(), t.accent(), t.glow(), true, false, null, t.files()));
		}
		List<Collection> cols = new ArrayList<>(bundledCollections);
		if (s != null) {
			for (JsonElement e : s.getAsJsonArray("items")) {
				JsonObject o = e.getAsJsonObject();
				if (!"wings".equals(str(o, "type", "")) || !o.get("id").getAsString().startsWith(ITEM_PREFIX)) {
					continue;
				}
				String id = o.get("id").getAsString().substring(ITEM_PREFIX.length());
				WingType base = merged.get(id);
				boolean isBundled = base != null && base.bundled();
				Map<String, String> files = new LinkedHashMap<>();
				if (o.has("files") && o.get("files").isJsonObject()) {
					o.getAsJsonObject("files").entrySet().forEach(f -> files.put(f.getKey(), f.getValue().getAsString()));
				}
				merged.put(id, new WingType(id, str(o, "name", id), str(o, "description", ""),
						str(o, "style", base != null ? base.style() : "feathered"),
						has(o, "scale") ? o.get("scale").getAsFloat() : base != null ? base.scale() : 0.9f,
						has(o, "offsetY") ? o.get("offsetY").getAsFloat() : base != null ? base.offsetY() : 0f,
						has(o, "price") && o.get("price").getAsInt() > 0 ? o.get("price").getAsInt() : base != null ? base.price() : 0,
						str(o, "collection", ""), str(o, "accent", base != null ? base.accent() : "#ff4d4d"),
						has(o, "glow") && o.get("glow").getAsBoolean(), isBundled, has(o, "inStore") && o.get("inStore").getAsBoolean(),
						has(o, "availableUntil") ? o.get("availableUntil").getAsLong() : null, Map.copyOf(files)));
			}
			if (s.has("collections") && s.getAsJsonArray("collections").size() > 0) {
				cols = new ArrayList<>();
				for (JsonElement e : s.getAsJsonArray("collections")) {
					JsonObject o = e.getAsJsonObject();
					cols.add(new Collection(o.get("id").getAsString(), o.get("name").getAsString(), str(o, "tag", "COLLECTION"), str(o, "description", "")));
				}
			}
		}
		List<String> order = cols.stream().map(Collection::id).toList();
		List<WingType> out = new ArrayList<>(merged.values());
		out.sort(java.util.Comparator.comparingInt((WingType w) -> order.contains(w.collection()) ? order.indexOf(w.collection()) : order.size())
				.thenComparing(WingType::name));
		catalog = List.copyOf(out);
		collections = List.copyOf(cols);
	}

	/** Every pair the launcher knows (bundled, for sale, or owned). */
	public static synchronized List<WingType> all() {
		build();
		return catalog;
	}

	/** What the Store sells right now. */
	public static List<WingType> forSale() {
		return all().stream().filter(WingType::inStore).toList();
	}

	/** Collections with something for sale, newest first. */
	public static synchronized List<Collection> collections() {
		build();
		List<WingType> sale = forSale();
		return collections.stream().filter(c -> sale.stream().anyMatch(w -> w.collection().equals(c.id()))).toList();
	}

	/** The server's featured collection, else the newest one with something for sale. */
	public static Optional<Collection> featured() {
		JsonObject s = server;
		String pick = s != null ? str(s, "featured", null) : null;
		List<Collection> list = collections();
		return list.stream().filter(c -> c.id().equals(pick)).findFirst().or(() -> list.stream().findFirst());
	}

	public static synchronized Optional<Collection> collection(String id) {
		build();
		return collections.stream().filter(c -> c.id().equals(id)).findFirst();
	}

	/** The pairs for sale in one collection. */
	public static List<WingType> inCollection(String collectionId) {
		return forSale().stream().filter(w -> w.collection().equals(collectionId)).toList();
	}

	public static Optional<WingType> byId(String id) {
		return all().stream().filter(w -> w.id().equals(id)).findFirst();
	}

	// ---- Art ----

	private static Path cacheDir() {
		return VeloPaths.root().resolve("cache").resolve("cosmetics");
	}

	/** The cached file for a hash, downloading it first if needed (blocking); null if that fails. */
	private static Path file(String sha) {
		if (sha == null || !sha.matches("[0-9a-f]{64}")) {
			return null;
		}
		Path path = cacheDir().resolve("files").resolve(sha);
		if (Files.exists(path)) {
			return path;
		}
		String base = net.veloclient.launcher.social.LauncherSocial.baseUrl();
		if (base == null) {
			return null;
		}
		try {
			HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/cosmetics/file/" + sha))
					.timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
			byte[] bytes = response.body();
			if (response.statusCode() != 200 || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(sha)) {
				return null;
			}
			Files.createDirectories(path.getParent());
			Path tmp = Files.createTempFile(path.getParent(), "dl", ".tmp");
			Files.write(tmp, bytes);
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return path;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Opens a pair's art by its bundled path - {@code <id>.vmesh}, {@code textures/<id>.png} or
	 * {@code textures/<id>_preview.png} - from the launcher's resources, or the downloaded server file
	 * for a server-only pair. Null if it isn't available.
	 */
	public static InputStream open(String path) {
		InputStream bundledStream = WingsCatalog.class.getResourceAsStream(ROOT + path);
		if (bundledStream != null) {
			return bundledStream;
		}
		String name = path.substring(path.lastIndexOf('/') + 1);
		String role;
		String id;
		if (name.endsWith(".vmesh")) {
			role = "mesh";
			id = name.substring(0, name.length() - 6);
		} else if (name.endsWith("_preview.png")) {
			role = "preview";
			id = name.substring(0, name.length() - 12);
		} else if (name.endsWith(".png")) {
			role = "texture";
			id = name.substring(0, name.length() - 4);
		} else {
			return null;
		}
		WingType type = byId(id).orElse(null);
		Path file = type == null ? null : file(type.files().get(role));
		try {
			return file == null ? null : new ByteArrayInputStream(Files.readAllBytes(file));
		} catch (Exception e) {
			return null;
		}
	}

	/** A server file by hash (downloaded if needed), or a transparent placeholder - for the owner panel's thumbnails. */
	public static InputStream openFile(String sha) {
		Path file = file(sha);
		try {
			return file == null ? new ByteArrayInputStream(PLACEHOLDER_PNG) : new ByteArrayInputStream(Files.readAllBytes(file));
		} catch (Exception e) {
			return new ByteArrayInputStream(PLACEHOLDER_PNG);
		}
	}

	/** A pair's still picture, or a transparent placeholder (never null - safe for {@code new Image(...)}). */
	public static InputStream openPreview(String id) {
		InputStream in = open("textures/" + id + "_preview.png");
		return in != null ? in : new ByteArrayInputStream(PLACEHOLDER_PNG);
	}

	/** 1x1 transparent PNG. */
	private static final byte[] PLACEHOLDER_PNG = java.util.Base64.getDecoder().decode(
			"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGBgAAAABQABpfZFQAAAAABJRU5ErkJggg==");

	// ---- Equipped pair and settings ----

	private static Path configFile() {
		return VeloPaths.config().resolve("cosmetics-wings.json");
	}

	private static JsonObject read() {
		try {
			if (Files.exists(configFile())) {
				return JsonParser.parseString(Files.readString(configFile(), StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (Exception ignored) {
			// Start over.
		}
		return new JsonObject();
	}

	private static void write(JsonObject o) {
		try {
			VeloPaths.ensureDirectories();
			Files.writeString(configFile(), GSON.toJson(o), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new RuntimeException("Couldn't save the wings settings", e);
		}
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
		write(o);
	}

	/** How you wear this pair (as the game saved it). */
	public static Settings settings(String id) {
		JsonObject o = read();
		if (o.has("perWing") && o.get("perWing").isJsonObject() && o.getAsJsonObject("perWing").has(id)) {
			JsonObject s = o.getAsJsonObject("perWing").getAsJsonObject(id);
			return new Settings(f(s, "scale", 1f), f(s, "offsetY", 0f), f(s, "offsetZ", 0f), f(s, "spread", 6f), f(s, "flapSpeed", 1f),
					f(s, "flapStrength", 1f));
		}
		return Settings.DEFAULT;
	}

	/** Saves how this pair is worn - the game reads it on its next start. */
	public static void setSettings(String id, Settings settings) {
		JsonObject o = read();
		JsonObject perWing = o.has("perWing") && o.get("perWing").isJsonObject() ? o.getAsJsonObject("perWing") : new JsonObject();
		if (settings == null || settings.equals(Settings.DEFAULT)) {
			perWing.remove(id);
		} else {
			perWing.add(id, GSON.toJsonTree(settings));
		}
		o.add("perWing", perWing);
		write(o);
	}

	private static float f(JsonObject o, String key, float fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsFloat() : fallback;
	}

	private static boolean has(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull();
	}

	private static String str(JsonObject o, String key, String fallback) {
		return has(o, key) ? o.get(key).getAsString() : fallback;
	}
}
