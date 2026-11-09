package net.veloclient.velo.dev.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server mappings (github.com/velo345/server-mappings, served by the Velo Library): which server an address belongs
 * to, with its name, logo and Discord texts. Looked up once per address in the background (cached 30 minutes, misses
 * 10 minutes) - the presence loop never waits on the network.
 */
final class ServerMappingsClient {

	record Mapping(String id, String name, String logo, String details, String state, String largeText, JsonElement buttons) {
	}

	private static final Mapping NONE = new Mapping(null, null, null, null, null, null, null);
	private static final Map<String, Mapping> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, Long> FETCHED = new ConcurrentHashMap<>();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private ServerMappingsClient() {
	}

	/** The mapping for an address, or null (unknown yet, or not mapped). */
	static Mapping get(String address) {
		if (address == null || address.isBlank()) {
			return null;
		}
		String key = address.trim().toLowerCase(Locale.ROOT);
		Mapping m = CACHE.get(key);
		long now = System.currentTimeMillis();
		Long at = FETCHED.get(key);
		if (at == null || now - at > (m == NONE ? 10 * 60_000L : 30 * 60_000L)) {
			FETCHED.put(key, now);
			Thread.ofVirtual().name("velo-server-mapping").start(() -> fetch(key));
		}
		return m == null || m == NONE ? null : m;
	}

	private static void fetch(String key) {
		try {
			HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://velo-client.com/v2/servers/lookup?address="
					+ URLEncoder.encode(key, StandardCharsets.UTF_8))).header("User-Agent", "VeloClient").timeout(Duration.ofSeconds(8)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() == 404) {
				CACHE.put(key, NONE);
				return;
			}
			if (r.statusCode() != 200) {
				return; // Library busy or paused: keep what we had, try again later
			}
			JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
			JsonObject p = o.has("presence") && o.get("presence").isJsonObject() ? o.getAsJsonObject("presence") : new JsonObject();
			String name = o.get("name").getAsString();
			CACHE.put(key, new Mapping(o.get("id").getAsString(), name, o.has("logo") ? o.get("logo").getAsString() : null,
					str(p, "details", "Playing on %name%"), str(p, "state", null), str(p, "largeImageText", name), p.get("buttons")));
		} catch (Exception ignored) {
			// offline: no mapping this time
		}
	}

	private static String str(JsonObject o, String k, String fallback) {
		String v = o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
		return v == null || v.isBlank() ? fallback : v;
	}
}
