package net.veloclient.library;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * API keys and "who is this" for writes (presets, pack uploads). A visitor proves who they are with their
 * velo-client.com sign-in (the web Store session, kept by velo-server). The Library asks velo-server once
 * ({@code /v1/web/me}, {@code /v1/plus/status}) and remembers the answer for 10 minutes - and it never asks more than a
 * few times a second in total, so a flood here can't become load on velo-server. If velo-server doesn't answer, the
 * visitor is simply treated as signed out; nothing here waits on it for long.
 */
final class Accounts {

	record User(String uuid, String username, boolean plus) {
	}

	static final class KeyRecord {
		String key;
		String uuid;
		String username;
		String label;
		long created;
		long lastUsed;
	}

	private final JsonFile<List<KeyRecord>> keys;
	private final String veloServer;
	private final Set<String> owners;
	private final Cache.Memo<User> sessions = new Cache.Memo<>(5000);
	private final Cache.Memo<Boolean> plusByUuid = new Cache.Memo<>(5000);
	private final double[] budget = {10, System.currentTimeMillis()};
	private static final SecureRandom RANDOM = new SecureRandom();

	Accounts(Path dataDir, String veloServer, Set<String> owners) {
		this.keys = new JsonFile<>(dataDir.resolve("keys.json"), new TypeToken<List<KeyRecord>>() {
		}.getType(), ArrayList::new);
		this.veloServer = veloServer;
		this.owners = owners;
	}

	/** The caller: a key (tier KEY or PLUS) or their address (ANON). An unknown key is an error, not silently anonymous. */
	Guard.Identity identify(Http h) {
		if (h.token != null && h.token.startsWith("velo_")) {
			KeyRecord k = find(h.token);
			if (k == null) {
				throw new Http.Fail(401, "Unknown API key");
			}
			long now = System.currentTimeMillis();
			if (now - k.lastUsed > 3_600_000) {
				k.lastUsed = now;
				keys.changed();
			}
			Boolean plus = plusByUuid.get(k.uuid);
			return new Guard.Identity("key:" + k.key, Boolean.TRUE.equals(plus) ? Guard.Tier.PLUS : Guard.Tier.KEY, k.uuid);
		}
		return new Guard.Identity("ip:" + h.ip, Guard.Tier.ANON, null);
	}

	private synchronized KeyRecord find(String key) {
		for (KeyRecord k : keys.get()) {
			if (k.key.equals(key)) {
				return k;
			}
		}
		return null;
	}

	/** The signed-in visitor (velo-client.com session in the Authorization header), or a 401. */
	User requireUser(Http h) {
		User u = user(h);
		if (u == null) {
			throw new Http.Fail(401, "Sign in on velo-client.com first (top right, with your launcher code)");
		}
		return u;
	}

	User user(Http h) {
		if (h.token == null || h.token.startsWith("velo_") || h.token.length() > 200) {
			return null;
		}
		User cached = sessions.get(h.token);
		if (cached != null) {
			return cached;
		}
		if (!spend()) {
			throw new Http.Fail(503, "Signing in is busy - try again in a moment", 5);
		}
		try {
			Net.Response me = callVelo("/v1/web/me", h.token);
			if (me.status() != 200) {
				return null;
			}
			JsonObject o = JsonParser.parseString(me.text()).getAsJsonObject();
			boolean plus = false;
			Net.Response p = callVelo("/v1/plus/status", h.token);
			if (p.status() == 200) {
				JsonObject po = JsonParser.parseString(p.text()).getAsJsonObject();
				plus = po.has("plus") && po.get("plus").getAsBoolean();
			}
			User u = new User(o.get("uuid").getAsString().replace("-", ""), o.get("username").getAsString(), plus);
			sessions.put(h.token, u, 600_000);
			plusByUuid.put(u.uuid(), plus, 24 * 3_600_000L);
			return u;
		} catch (Exception e) {
			return null;
		}
	}

	boolean isOwner(User u) {
		return u != null && owners.contains(u.uuid());
	}

	private Net.Response callVelo(String path, String token) throws Exception {
		var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(veloServer + path))
				.header("Authorization", "Bearer " + token).header("User-Agent", Net.UA)
				.timeout(java.time.Duration.ofSeconds(3)).GET().build();
		var r = java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
		return new Net.Response(r.statusCode(), r.body(), r.headers().map());
	}

	/** At most ~10 calls to velo-server per second, whatever happens here. */
	private boolean spend() {
		synchronized (budget) {
			long now = System.currentTimeMillis();
			budget[0] = Math.min(10, budget[0] + (now - budget[1]) / 100.0);
			budget[1] = now;
			if (budget[0] < 1) {
				return false;
			}
			budget[0] -= 1;
			return true;
		}
	}

	synchronized List<Map<String, Object>> list(User u) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (KeyRecord k : keys.get()) {
			if (k.uuid.equals(u.uuid())) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("key", k.key);
				m.put("label", k.label);
				m.put("created", k.created);
				m.put("lastUsed", k.lastUsed);
				out.add(m);
			}
		}
		return out;
	}

	synchronized KeyRecord create(User u, String label) {
		long mine = keys.get().stream().filter(k -> k.uuid.equals(u.uuid())).count();
		if (mine >= 5) {
			throw new Http.Fail(400, "You already have 5 keys - delete one first");
		}
		byte[] b = new byte[24];
		RANDOM.nextBytes(b);
		KeyRecord k = new KeyRecord();
		k.key = "velo_" + HexFormat.of().formatHex(b);
		k.uuid = u.uuid();
		k.username = u.username();
		k.label = label == null || label.isBlank() ? "Key " + (mine + 1) : label.strip().substring(0, Math.min(40, label.strip().length()));
		k.created = System.currentTimeMillis();
		keys.get().add(k);
		keys.changed();
		plusByUuid.put(u.uuid(), u.plus(), 24 * 3_600_000L);
		return k;
	}

	synchronized boolean delete(User u, String key) {
		boolean removed = keys.get().removeIf(k -> k.key.equals(key) && (k.uuid.equals(u.uuid()) || isOwner(u)));
		if (removed) {
			keys.changed();
		}
		return removed;
	}

	void save() {
		keys.saveIfDirty();
	}
}
