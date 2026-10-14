package net.veloclient.velo.client.cosmetics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.network.VeloServerClient;
import net.veloclient.velo.config.VeloPaths;

import java.net.URI;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The server's cosmetics catalog (wings and capes) and their art, so a new pair or cape shows up
 * without a client update. The catalog is what's in the store plus whatever you own (testing items
 * the owner gave you included); items someone else wears are looked up one by one
 * ({@link #requestItem}) so a tester's wings are drawn for everyone.
 *
 * <p>The last catalog is kept in {@code cache/cosmetics/catalog.json} for offline starts; art is
 * cached under {@code cache/cosmetics/files/<sha256>} and checked against its hash. Nothing here
 * blocks the render thread: callers get null until a download is done and simply try again, and
 * {@link #version()} goes up whenever something new arrived.
 */
public final class CosmeticsCatalog {

	public record Item(String id, String type, String name, String description, int price, String collection, Integer accent,
			String style, Float scale, Float offsetY, boolean glow, Map<String, String> files, boolean inStore, Long availableUntil,
			String status) {
	}

	public record Collection(String id, String name, String tag, String description) {
	}

	private static final long RETRY_MILLIS = 60_000;
	private static final int MAX_FILE_BYTES = 12 * 1024 * 1024;

	private static volatile Map<String, Item> items = Map.of();
	/** Items looked up one by one (worn by someone else), kept apart so a catalog refresh doesn't drop them. */
	private static final Map<String, Item> looked = new ConcurrentHashMap<>();
	private static volatile List<Collection> collections = List.of();
	private static volatile String featured;
	private static volatile boolean loaded;
	private static volatile int version;
	private static volatile long lastRefresh;
	private static final Map<String, Long> pending = new ConcurrentHashMap<>();
	private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "velo-cosmetics");
		t.setDaemon(true);
		return t;
	});

	private CosmeticsCatalog() {
	}

	/** Goes up whenever the catalog, a looked-up item or a file arrived. */
	public static int version() {
		return version;
	}

	/** True once a catalog (from the server, or the cached one) is known. */
	public static boolean loaded() {
		return loaded;
	}

	public static List<Item> items() {
		return List.copyOf(items.values());
	}

	public static Optional<Item> item(String id) {
		Item item = items.get(id);
		return Optional.ofNullable(item != null ? item : looked.get(id));
	}

	/** Items looked up because someone else wears them. */
	public static List<Item> lookedUp() {
		return List.copyOf(looked.values());
	}

	public static List<Collection> collections() {
		return collections;
	}

	public static String featured() {
		return featured;
	}

	private static Path cacheDir() {
		return VeloPaths.root().resolve("cache").resolve("cosmetics");
	}

	/** Loads the cached catalog (first call) and fetches a fresh one (at most every 30 s unless {@code force}). */
	public static void refresh(boolean force) {
		if (!loaded) {
			try {
				Path cached = cacheDir().resolve("catalog.json");
				if (Files.exists(cached)) {
					apply(JsonParser.parseString(Files.readString(cached, StandardCharsets.UTF_8)).getAsJsonObject());
				}
			} catch (Exception e) {
				VeloClient.LOGGER.debug("Couldn't read the cached cosmetics catalog", e);
			}
		}
		long now = System.currentTimeMillis();
		if (!force && now - lastRefresh < 30_000 || VeloServerClient.serverBaseUrl() == null) {
			return;
		}
		lastRefresh = now;
		IO.execute(() -> {
			try {
				String body = get("/v1/cosmetics/catalog");
				JsonObject json = JsonParser.parseString(body).getAsJsonObject();
				apply(json);
				Files.createDirectories(cacheDir());
				Files.writeString(cacheDir().resolve("catalog.json"), body, StandardCharsets.UTF_8);
				// Fetch the art of everything listed now, so cards and models are there when needed.
				for (Item item : items.values()) {
					item.files().values().forEach(CosmeticsCatalog::file);
				}
			} catch (Exception e) {
				VeloClient.LOGGER.debug("Couldn't load the cosmetics catalog: {}", e.toString());
			}
		});
	}

	/** Looks up an item that isn't in your catalog (someone else wears it); arrives later via {@link #version()}. */
	public static void requestItem(String id) {
		if (id == null || items.containsKey(id) || looked.containsKey(id) || VeloServerClient.serverBaseUrl() == null) {
			return;
		}
		Long since = pending.get("item:" + id);
		if (since != null && System.currentTimeMillis() - since < RETRY_MILLIS) {
			return;
		}
		pending.put("item:" + id, System.currentTimeMillis());
		IO.execute(() -> {
			try {
				Item item = parseItem(JsonParser.parseString(get("/v1/cosmetics/item?id="
						+ java.net.URLEncoder.encode(id, StandardCharsets.UTF_8))).getAsJsonObject());
				looked.put(id, item);
				item.files().values().forEach(CosmeticsCatalog::file);
				version++;
			} catch (Exception e) {
				VeloClient.LOGGER.debug("Couldn't look up cosmetic {}: {}", id, e.toString());
			}
		});
	}

	/** The cached file for a hash, or null (then it's downloaded in the background - ask again later). */
	public static Path file(String sha256) {
		if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
			return null;
		}
		Path path = cacheDir().resolve("files").resolve(sha256);
		if (Files.exists(path)) {
			return path;
		}
		Long since = pending.get(sha256);
		if (since != null && (since == 0L || System.currentTimeMillis() - since < RETRY_MILLIS) || VeloServerClient.serverBaseUrl() == null) {
			return null;
		}
		pending.put(sha256, 0L);
		IO.execute(() -> {
			try {
				HttpResponse<byte[]> response = VeloServerClient.HTTP.send(HttpRequest.newBuilder(
						URI.create(VeloServerClient.serverBaseUrl() + "/v1/cosmetics/file/" + sha256)).timeout(Duration.ofSeconds(60)).GET().build(),
						HttpResponse.BodyHandlers.ofByteArray());
				byte[] bytes = response.body();
				if (response.statusCode() != 200 || bytes.length > MAX_FILE_BYTES
						|| !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(sha256)) {
					throw new java.io.IOException("bad download (HTTP " + response.statusCode() + ")");
				}
				Files.createDirectories(path.getParent());
				Path tmp = Files.createTempFile(path.getParent(), "dl", ".tmp");
				Files.write(tmp, bytes);
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				pending.remove(sha256);
				version++;
				// An owned server-only cape can be put in the library now.
				net.veloclient.velo.client.store.StoreRestore.scheduleRestore();
			} catch (Exception e) {
				pending.put(sha256, System.currentTimeMillis());
				VeloClient.LOGGER.debug("Couldn't download cosmetic file {}: {}", sha256, e.toString());
			}
		});
		return null;
	}

	// ---- Parsing ----

	private static void apply(JsonObject json) {
		Map<String, Item> map = new LinkedHashMap<>();
		for (JsonElement element : json.getAsJsonArray("items")) {
			Item item = parseItem(element.getAsJsonObject());
			map.put(item.id(), item);
		}
		List<Collection> list = new ArrayList<>();
		if (json.has("collections")) {
			for (JsonElement element : json.getAsJsonArray("collections")) {
				JsonObject c = element.getAsJsonObject();
				list.add(new Collection(c.get("id").getAsString(), c.get("name").getAsString(), string(c, "tag", "COLLECTION"), string(c, "description", "")));
			}
		}
		items = map;
		collections = List.copyOf(list);
		featured = string(json, "featured", null);
		loaded = true;
		version++;
	}

	private static Item parseItem(JsonObject o) {
		Map<String, String> files = new LinkedHashMap<>();
		if (o.has("files") && o.get("files").isJsonObject()) {
			o.getAsJsonObject("files").entrySet().forEach(e -> files.put(e.getKey(), e.getValue().getAsString()));
		}
		Integer accent = null;
		String accentText = string(o, "accent", null);
		if (accentText != null && accentText.matches("#[0-9a-fA-F]{6}")) {
			accent = 0xFF000000 | Integer.parseInt(accentText.substring(1), 16);
		}
		return new Item(o.get("id").getAsString(), string(o, "type", "wings"), string(o, "name", o.get("id").getAsString()),
				string(o, "description", ""), has(o, "price") ? o.get("price").getAsInt() : 0, string(o, "collection", null), accent,
				string(o, "style", null), has(o, "scale") ? o.get("scale").getAsFloat() : null,
				has(o, "offsetY") ? o.get("offsetY").getAsFloat() : null, has(o, "glow") && o.get("glow").getAsBoolean(), Map.copyOf(files),
				has(o, "inStore") && o.get("inStore").getAsBoolean(), has(o, "availableUntil") ? o.get("availableUntil").getAsLong() : null,
				string(o, "status", null));
	}

	private static boolean has(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull();
	}

	private static String string(JsonObject o, String key, String fallback) {
		return has(o, key) ? o.get(key).getAsString() : fallback;
	}

	private static String get(String path) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(VeloServerClient.serverBaseUrl() + path))
				.header("Accept", "application/json").timeout(Duration.ofSeconds(20)).GET();
		String token = VeloServerClient.sessionToken();
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		HttpResponse<String> response = VeloServerClient.HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new VeloServerClient.HttpStatusException(response.statusCode(), response.body());
		}
		return response.body();
	}
}
