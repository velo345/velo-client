package net.veloclient.legacy.cape;

import com.google.gson.JsonObject;
import net.veloclient.legacy.Json;
import net.veloclient.legacy.VeloLegacy;
import net.veloclient.legacy.VeloPaths;
import net.veloclient.legacy.net.VeloNetwork;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The cloak library the launcher and the modern client keep in {@code ~/.velo-client/capes} ({@code .velocape} zips:
 * texture.png or frames.gif + meta.json) and which one this account wears ({@code cosmetics-cape.json}).
 */
public final class LocalCape {

	public record Bundle(String id, Path file, String sourceItemId) {
	}

	private static volatile Map<String, Bundle> library = Map.of();
	private static volatile long libraryStamp = -1;
	private static volatile long lastScan;
	private static volatile String equippedId;
	private static volatile long lastEquipRead;
	private static final Map<String, String> uploaded = new ConcurrentHashMap<>();
	private static final Set<String> refused = ConcurrentHashMap.newKeySet();

	private LocalCape() {
	}

	/** The equipped bundle of this account, or null (re-read every 2 s, so a Locker change shows up live). */
	public static Bundle equipped(String uuid) {
		long now = System.currentTimeMillis();
		if (now - lastEquipRead > 2000) {
			lastEquipRead = now;
			equippedId = Json.string(Json.read(VeloPaths.accountConfig(uuid, "cosmetics-cape.json")), "equippedCapeId");
		}
		String id = equippedId;
		return id == null ? null : library().get(id);
	}

	/** Bundle by the store item it was bought as (so a remote player's store cloak needs no download when you own it too). */
	public static Bundle bySourceItem(String itemId) {
		for (Bundle bundle : library().values()) {
			if (itemId.equals(bundle.sourceItemId())) {
				return bundle;
			}
		}
		return null;
	}

	private static Map<String, Bundle> library() {
		long now = System.currentTimeMillis();
		if (now - lastScan < 5000) {
			return library;
		}
		lastScan = now;
		Path dir = VeloPaths.capes();
		try {
			long stamp = Files.isDirectory(dir) ? Files.getLastModifiedTime(dir).toMillis() : 0;
			if (stamp == libraryStamp) {
				return library;
			}
			Map<String, Bundle> map = new HashMap<>();
			if (Files.isDirectory(dir)) {
				try (Stream<Path> files = Files.list(dir)) {
					files.filter(p -> p.toString().endsWith(".velocape")).forEach(p -> {
						try {
							byte[] meta = entry(p, "meta.json");
							if (meta != null) {
								JsonObject o = Json.parse(new String(meta, StandardCharsets.UTF_8));
								String id = Json.string(o, "id");
								if (id != null) {
									map.put(id, new Bundle(id, p, Json.string(o, "sourceItemId")));
								}
							}
						} catch (Exception ignored) {
							// A broken bundle is just skipped.
						}
					});
				}
			}
			library = map;
			libraryStamp = stamp;
		} catch (IOException ignored) {
			// Keep the last scan.
		}
		return library;
	}

	/** The picture of a bundle: frames.gif (animated) or texture.png. */
	public static byte[] art(Bundle bundle) throws IOException {
		byte[] gif = entry(bundle.file(), "frames.gif");
		return gif != null ? gif : entry(bundle.file(), "texture.png");
	}

	static byte[] entry(Path zip, String name) throws IOException {
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry e;
			while ((e = in.getNextEntry()) != null) {
				if (e.getName().equals(name)) {
					return readAll(in);
				}
			}
		}
		return null;
	}

	static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[65536];
		int n;
		while ((n = in.read(buf)) > 0) {
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	/**
	 * What the heartbeat publishes: a store cloak's item id, or {@code custom:<hash>} of your own picture (uploaded once;
	 * the server refuses what you may not show, e.g. an animated one without Velo+). Background thread only.
	 */
	public static String publishedId(String base, String token, String uuid) {
		Bundle bundle = equipped(uuid);
		if (bundle == null) {
			return null;
		}
		if (bundle.sourceItemId() != null && !bundle.sourceItemId().isBlank()) {
			return bundle.sourceItemId();
		}
		try {
			byte[] bytes = art(bundle);
			if (bytes == null) {
				return null;
			}
			String hash = sha256(bytes);
			String key = base + "|" + uuid + "|" + hash;
			String known = uploaded.get(key);
			if (known == null) {
				known = fromUploadCache(key);
			}
			if (known != null || refused.contains(key)) {
				return known;
			}
			boolean gif = bytes[0] == 'G';
			HttpResponse<String> response = VeloNetwork.HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/cape/upload"))
					.header("Authorization", "Bearer " + token).header("Content-Type", gif ? "image/gif" : "image/png")
					.timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
					HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			if (status / 100 == 2) {
				String id = Json.string(Json.parse(response.body()), "capeId");
				if (id != null) {
					uploaded.put(key, id);
				}
				return id;
			}
			if (status != 401 && status != 429 && status < 500) {
				refused.add(key);
				VeloLegacy.LOGGER.warn("Velo Network refused your cloak ({}): {}", status, response.body());
			}
		} catch (Exception e) {
			VeloLegacy.LOGGER.debug("Cloak upload failed", e);
		}
		return null;
	}

	/** The modern client's {@code network-uploads.json}: the same picture uploaded from there needs no second upload. */
	private static String fromUploadCache(String key) {
		JsonObject cache = Json.read(VeloPaths.config().resolve("network-uploads.json"));
		if (cache == null || !cache.has("capeIdByKey")) {
			return null;
		}
		return Json.string(cache.getAsJsonObject("capeIdByKey"), key);
	}

	static String sha256(byte[] bytes) throws Exception {
		StringBuilder hex = new StringBuilder();
		for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}
}
