package net.veloclient.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Tier Tagger data (2026-11-24): PvP tiers from PvPTiers (mctiers.io now points there; mctiers.com is gone) and SubTiers,
 * fetched here and cached so the clients never hit the tier sites themselves (one request per player and 30 minutes
 * for everyone, instead of one per player per client).
 *
 * <p>{@code GET /v1/tiers?uuids=a,b,c} (up to 40, dashed or not) answers what is cached right now and queues the rest;
 * the client asks again a few seconds later. Per source and player: {@code {"modes": {"sword": {"t": 3, "h": true,
 * "r": false, "pt": 2}}, "rank": 812, "region": "EU", "points": 40}} or {@code null} (not on that list).
 */
final class TierService {

	private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{32}");
	private static final long TTL_MS = 30 * 60_000L;
	private static final long MISS_TTL_MS = 60 * 60_000L;
	private static final long ERROR_RETRY_MS = 5 * 60_000L;
	private static final int MAX_BATCH = 40;

	private record Entry(JsonObject data, long fetchedAt, boolean failed) {
	}

	private final Map<String, Entry> cache = new ConcurrentHashMap<>();
	private final Set<String> pending = ConcurrentHashMap.newKeySet();
	// the tier sites are small: a few requests at a time
	private final ExecutorService fetcher = Executors.newFixedThreadPool(3, r -> {
		Thread t = new Thread(r, "velo-tiers");
		t.setDaemon(true);
		return t;
	});
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
	private final RateLimiter limiter = new RateLimiter();

	void register(HttpServer server) {
		server.createContext("/v1/tiers", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			String ip = WebRoutes.clientIp(ex);
			if (limiter.tryAcquire(ip, "tiers", new RateLimiter.Window(120, 60_000L), new RateLimiter.Window(3000, 3_600_000L)) > 0) {
				JsonHttp.writeError(ex, 429, "Slow down");
				return;
			}
			String list = StoreRoutes.query(ex).getOrDefault("uuids", "");
			Map<String, Object> players = new LinkedHashMap<>();
			int n = 0;
			long now = System.currentTimeMillis();
			for (String raw : list.split(",")) {
				String id = raw.trim().replace("-", "").toLowerCase(java.util.Locale.ROOT);
				if (!UUID.matcher(id).matches() || ++n > MAX_BATCH) {
					continue;
				}
				Entry e = cache.get(id);
				boolean fresh = e != null && now - e.fetchedAt() < (e.failed() ? ERROR_RETRY_MS : hasAny(e.data()) ? TTL_MS : MISS_TTL_MS);
				if (e != null) {
					players.put(id, e.data());
				}
				if (!fresh && pending.add(id)) {
					fetcher.submit(() -> fetch(id));
				}
			}
			JsonHttp.writeJson(ex, 200, Map.of("players", players));
		}));
	}

	private static boolean hasAny(JsonObject data) {
		return data != null && (!data.get("pvptiers").isJsonNull() || !data.get("subtiers").isJsonNull());
	}

	private void fetch(String id) {
		try {
			String dashed = id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20);
			Fetched pvp = get("https://pvptiers.com/api/profile/" + id);
			Fetched sub = get("https://subtiers.net/api/profile/" + dashed);
			JsonObject out = new JsonObject();
			out.add("pvptiers", normalise(pvp.body()));
			out.add("subtiers", normalise(sub.body()));
			Entry old = cache.get(id);
			// a site that's down keeps what we had instead of wiping the tiers
			if (pvp.failed() && old != null && old.data().has("pvptiers")) {
				out.add("pvptiers", old.data().get("pvptiers"));
			}
			if (sub.failed() && old != null && old.data().has("subtiers")) {
				out.add("subtiers", old.data().get("subtiers"));
			}
			cache.put(id, new Entry(out, System.currentTimeMillis(), pvp.failed() || sub.failed()));
			if (cache.size() > 200_000) {
				cache.clear();   // crude cap; the cache refills on demand
			}
		} finally {
			pending.remove(id);
		}
	}

	private record Fetched(JsonObject body, boolean failed) {
	}

	private Fetched get(String url) {
		try {
			HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
					.header("User-Agent", "VeloClient-TierTagger/1.0 (+https://velo-client.com)").header("Accept", "application/json").GET().build(),
					HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() == 404) {
				return new Fetched(null, false);
			}
			if (r.statusCode() / 100 != 2) {
				return new Fetched(null, true);
			}
			JsonElement el = JsonParser.parseString(r.body());
			return new Fetched(el.isJsonObject() ? el.getAsJsonObject() : null, false);
		} catch (Exception e) {
			return new Fetched(null, true);
		}
	}

	/** The site's profile down to what the tag needs, or null when the player isn't ranked there. */
	private static JsonElement normalise(JsonObject p) {
		if (p == null || !p.has("rankings") || !p.get("rankings").isJsonObject()) {
			return com.google.gson.JsonNull.INSTANCE;
		}
		JsonObject modes = new JsonObject();
		for (Map.Entry<String, JsonElement> m : p.getAsJsonObject("rankings").entrySet()) {
			if (!m.getValue().isJsonObject()) {
				continue;
			}
			JsonObject r = m.getValue().getAsJsonObject();
			if (!r.has("tier") || r.get("tier").isJsonNull()) {
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("t", r.get("tier").getAsInt());
			o.addProperty("h", !r.has("pos") || r.get("pos").isJsonNull() || r.get("pos").getAsInt() == 0);
			o.addProperty("r", r.has("retired") && !r.get("retired").isJsonNull() && r.get("retired").getAsBoolean());
			if (r.has("peak_tier") && !r.get("peak_tier").isJsonNull()) {
				o.addProperty("pt", r.get("peak_tier").getAsInt());
				o.addProperty("ph", !r.has("peak_pos") || r.get("peak_pos").isJsonNull() || r.get("peak_pos").getAsInt() == 0);
			}
			modes.add(m.getKey(), o);
		}
		if (modes.isEmpty()) {
			return com.google.gson.JsonNull.INSTANCE;
		}
		JsonObject out = new JsonObject();
		out.add("modes", modes);
		if (p.has("overall") && !p.get("overall").isJsonNull()) {
			out.addProperty("rank", p.get("overall").getAsInt());
		}
		if (p.has("region") && !p.get("region").isJsonNull()) {
			out.addProperty("region", p.get("region").getAsString());
		}
		if (p.has("points") && !p.get("points").isJsonNull()) {
			out.addProperty("points", p.get("points").getAsInt());
		}
		return out;
	}
}
