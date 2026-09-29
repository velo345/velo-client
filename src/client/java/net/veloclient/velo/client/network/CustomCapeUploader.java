package net.veloclient.velo.client.network;

import com.google.gson.JsonParser;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.cosmetics.CapeDefinition;
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.config.ConfigManager;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Uploads the player's own imported (non-Store) cape to the Velo server so everyone else on it
 * can see it - the server stores it content-addressed and hands back a {@code custom:<hash>} id,
 * which is what the heartbeat then publishes (see {@code server/CapeStore.java}).
 *
 * <p>Only ever runs on {@link VeloServerClient}'s background thread. Each image is uploaded at
 * most once per server: the resulting id is remembered keyed by the local image's own SHA-256 in
 * {@code network-uploads.json}, and a cape the server refused (wrong size, banned) is remembered
 * for the rest of the session so it's never retried every tick.
 */
final class CustomCapeUploader {

	static final String CUSTOM_PREFIX = "custom:";

	private static final Map<String, String> localHashByBundle = new ConcurrentHashMap<>();
	private static final Set<String> refused = ConcurrentHashMap.newKeySet();
	private static UploadCache cache;

	private CustomCapeUploader() {
	}

	private record UploadCache(Map<String, String> capeIdByKey) {
	}

	private static synchronized UploadCache cache() {
		if (cache == null) {
			UploadCache loaded = ConfigManager.load("network-uploads", UploadCache.class, new UploadCache(new HashMap<>()));
			cache = new UploadCache(new HashMap<>(loaded.capeIdByKey() == null ? Map.of() : loaded.capeIdByKey()));
		}
		return cache;
	}

	/** The published id for {@code definition}, uploading it first if this server hasn't got it yet; null if it can't be shared. */
	static String publishedIdFor(String base, String sessionToken, CapeDefinition definition) {
		try {
			String entry = definition.animated() ? "frames.gif" : "texture.png";
			String bundleKey = definition.bundleFile() + "@" + Files.getLastModifiedTime(definition.bundleFile()).toMillis();
			String localHash = localHashByBundle.get(bundleKey);
			byte[] bytes = null;
			if (localHash == null) {
				bytes = CapeManager.readBundleEntryBytes(definition, entry);
				localHash = sha256(bytes);
				localHashByBundle.put(bundleKey, localHash);
			}
			String key = base + "|" + localHash;
			synchronized (CustomCapeUploader.class) {
				String known = cache().capeIdByKey().get(key);
				if (known != null) {
					return known;
				}
			}
			if (refused.contains(key)) {
				return null;
			}
			if (bytes == null) {
				bytes = CapeManager.readBundleEntryBytes(definition, entry);
			}
			String capeId = upload(base, sessionToken, bytes, definition.animated(), key);
			if (capeId != null) {
				synchronized (CustomCapeUploader.class) {
					cache().capeIdByKey().put(key, capeId);
					ConfigManager.save("network-uploads", cache());
				}
			}
			return capeId;
		} catch (Exception e) {
			VeloClient.LOGGER.debug("Custom cape upload failed", e);
			return null;
		}
	}

	/** Drops a remembered upload the server no longer recognizes, so the next tick uploads it again. */
	static synchronized void forget(String capeId) {
		if (cache().capeIdByKey().values().removeIf(capeId::equals)) {
			ConfigManager.save("network-uploads", cache());
		}
	}

	private static String upload(String base, String sessionToken, byte[] bytes, boolean gif, String key) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/cape/upload"))
				.header("Authorization", "Bearer " + sessionToken)
				.header("Content-Type", gif ? "image/gif" : "image/png")
				.timeout(Duration.ofSeconds(60))
				.POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
				.build();
		HttpResponse<String> response = VeloServerClient.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		int status = response.statusCode();
		if (status / 100 == 2) {
			return JsonParser.parseString(response.body()).getAsJsonObject().get("capeId").getAsString();
		}
		// 401 = session expired (the heartbeat will re-auth), 429 = too fast, 5xx = server trouble:
		// all worth retrying later. Anything else is the server saying "not this image".
		if (status != 401 && status != 429 && status < 500) {
			refused.add(key);
			VeloClient.LOGGER.warn("Velo Network refused your custom cape ({}): {}", status, response.body());
		}
		return null;
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}
