package net.veloclient.velo.client.tiers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.client.network.VeloNetworkConfig;
import net.veloclient.velo.client.network.VeloServerClient;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier Tagger data: the PvPTiers / SubTiers tiers of the players around you, from Velo's server ({@code /v1/tiers}, which
 * fetches and caches the tier sites). Players are asked for in batches as their names are drawn; an answer is kept for
 * 15 minutes. {@link #generation()} changes whenever something new arrived, so decorated names can be rebuilt.
 */
public final class TierData {

	/** One game mode on one list: tier 1-5, high or low, retired, and the best it ever was. */
	public record Tier(String mode, int tier, boolean high, boolean retired, int peak, boolean peakHigh) {
		/** 1 (HT1) .. 10 (LT5): lower is better. */
		public int score() {
			return tier * 2 - (high ? 1 : 0);
		}
	}

	public record Player(Map<String, Tier> pvptiers, Map<String, Tier> subtiers) {
	}

	private record Known(Player player, long at) {
	}

	private static final long KEEP_MS = 15 * 60_000L;
	private static final long RETRY_MS = 4_000L;
	private static final Map<UUID, Known> KNOWN = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> ASKED = new ConcurrentHashMap<>();
	private static final Set<UUID> WANTED = ConcurrentHashMap.newKeySet();
	private static volatile int generation;
	private static volatile boolean busy;
	private static long lastSend;

	private TierData() {
	}

	public static int generation() {
		return generation;
	}

	/** What's known for {@code uuid} (null = nothing yet); asks the server when it isn't known or is old. */
	public static Player get(UUID uuid) {
		Known k = KNOWN.get(uuid);
		long now = System.currentTimeMillis();
		if (k == null || now - k.at() > KEEP_MS) {
			Long asked = ASKED.get(uuid);
			if (asked == null || now - asked > RETRY_MS) {
				WANTED.add(uuid);
			}
		}
		return k == null ? null : k.player();
	}

	/** Every client tick: sends the wanted players (at most 40 per request, one request in flight, ~2 per second). */
	public static void tick() {
		long now = System.currentTimeMillis();
		if (busy || WANTED.isEmpty() || now - lastSend < 500) {
			return;
		}
		List<UUID> batch = new ArrayList<>();
		for (UUID id : WANTED) {
			batch.add(id);
			if (batch.size() == 40) {
				break;
			}
		}
		batch.forEach(WANTED::remove);
		batch.forEach(id -> ASKED.put(id, now));
		lastSend = now;
		busy = true;
		StringBuilder q = new StringBuilder();
		for (UUID id : batch) {
			if (!q.isEmpty()) {
				q.append(',');
			}
			q.append(id.toString().replace("-", ""));
		}
		VeloNetworkConfig config = VeloNetworkConfig.load();
		if (!config.isConfigured()) {
			busy = false;
			return;
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(config.normalizedUrl() + "/v1/tiers?uuids=" + q)).timeout(Duration.ofSeconds(10)).GET().build();
		VeloServerClient.HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((r, error) -> {
			try {
				if (error == null && r.statusCode() == 200) {
					read(r.body());
				}
			} catch (RuntimeException ignored) {
				// a bad answer: these players are asked again later
			} finally {
				busy = false;
			}
		});
	}

	private static void read(String body) {
		JsonObject players = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("players");
		long now = System.currentTimeMillis();
		boolean changed = false;
		for (Map.Entry<String, JsonElement> e : players.entrySet()) {
			String s = e.getKey();
			if (s.length() != 32) {
				continue;
			}
			UUID id = UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" + s.substring(16, 20) + "-" + s.substring(20));
			JsonObject o = e.getValue().getAsJsonObject();
			KNOWN.put(id, new Known(new Player(modes(o.get("pvptiers")), modes(o.get("subtiers"))), now));
			changed = true;
		}
		if (changed) {
			generation++;
		}
	}

	private static Map<String, Tier> modes(JsonElement el) {
		Map<String, Tier> out = new java.util.LinkedHashMap<>();
		if (el == null || !el.isJsonObject() || !el.getAsJsonObject().has("modes")) {
			return out;
		}
		for (Map.Entry<String, JsonElement> m : el.getAsJsonObject().getAsJsonObject("modes").entrySet()) {
			JsonObject t = m.getValue().getAsJsonObject();
			int tier = t.get("t").getAsInt();
			out.put(m.getKey(), new Tier(m.getKey(), tier, t.get("h").getAsBoolean(), t.has("r") && t.get("r").getAsBoolean(),
					t.has("pt") ? t.get("pt").getAsInt() : tier, t.has("ph") ? t.get("ph").getAsBoolean() : t.get("h").getAsBoolean()));
		}
		return out;
	}
}
