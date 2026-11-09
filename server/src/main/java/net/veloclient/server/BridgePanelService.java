package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Velo Bridge owner panel (dev.velo-client.com/panel): servers running the plugin sync their live numbers and their
 * config here; owners open the panel through a one-time link from {@code /velobridge panel} and see the dashboard and
 * change every setting - the plugin picks the change up on its next sync (every 5 s while the panel is open).
 *
 * <p>A server proves it's the same server with a secret it made itself (stored here as a hash the first time it
 * syncs). No ports on the Minecraft server, no accounts: the link is the login.
 */
final class BridgePanelService {

	static final class Hist {
		/** [minute, online, velo] - last 24 h */
		List<long[]> minutes = new ArrayList<>();
		/** [hour, maxOnline, maxVelo, joins] - last 30 days */
		List<long[]> hours = new ArrayList<>();
		/** joins per hour of the week (Monday 00:00 = 0, server-local hours come from the plugin's offset) */
		long[] week = new long[168];
		/** last 100 Velo joins: name, version, minecraft, time, first */
		List<Map<String, Object>> joins = new ArrayList<>();
		Map<String, Long> versionJoins = new LinkedHashMap<>();
		long totalJoins;
	}

	static final class Server {
		String serverId;
		String secretHash;
		String name;
		long lastSync;
		JsonObject live = new JsonObject();
		JsonObject config;
		long configVersion;
		JsonObject pending;
		long pendingVersion;
		long appliedVersion;
		String applyError;
		Hist hist = new Hist();
		long panelSeen;
		/** server-wide World Notes (short list from the plugin) and the ones the owner removed, not yet sent */
		JsonArray notes = new JsonArray();
		List<String> removeNotes = new ArrayList<>();
		/** names of the server's NPCs (FancyNpcs / Citizens) for the NPC designer */
		JsonArray npcs = new JsonArray();
	}

	private record Session(String serverId, long expires) {
	}

	private record Code(String serverId, long expires) {
	}

	private static final Gson GSON = new GsonBuilder().create();
	private static final SecureRandom RANDOM = new SecureRandom();
	private final Path dir;
	private final Map<String, Server> servers = new ConcurrentHashMap<>();
	private final Map<String, Code> codes = new ConcurrentHashMap<>();
	private final Map<String, Session> sessions = new ConcurrentHashMap<>();
	private final RateLimiter limits = new RateLimiter();
	private final CapeStore capes;

	BridgePanelService(Path dataDir, CapeStore capes) throws IOException {
		this.capes = capes;
		dir = Files.createDirectories(dataDir.resolve("bridge-panel"));
		try (var list = Files.list(dir)) {
			list.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
				try {
					Server s = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Server.class);
					if (s != null && s.serverId != null) {
						servers.put(s.serverId, s);
					}
				} catch (Exception e) {
					System.out.println("[bridge-panel] couldn't read " + p.getFileName() + ": " + e.getMessage());
				}
			});
		}
	}

	private static String sha(String s) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String token(int bytes) {
		byte[] b = new byte[bytes];
		RANDOM.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	/** The plugin's identity: serverId + secret (trust on first use). */
	private Server authServer(JsonObject in) throws ApiError {
		String id = str(in, "serverId"), secret = str(in, "secret");
		if (id == null || !id.matches("[0-9a-fA-F-]{36}") || secret == null || secret.length() < 32 || secret.length() > 128) {
			throw new ApiError(400, "serverId/secret missing");
		}
		Server s = servers.computeIfAbsent(id, k -> {
			Server n = new Server();
			n.serverId = k;
			n.secretHash = sha(secret);
			return n;
		});
		if (!MessageDigest.isEqual(s.secretHash.getBytes(StandardCharsets.UTF_8), sha(secret).getBytes(StandardCharsets.UTF_8))) {
			throw new ApiError(403, "This server id belongs to another server - delete plugins/VeloBridge/server-id.txt to get a new one");
		}
		return s;
	}

	private Server authPanel(HttpExchange ex) throws ApiError {
		String t = JsonHttp.bearerToken(ex);
		Session session = t == null ? null : sessions.get(t);
		if (session == null || session.expires() < System.currentTimeMillis()) {
			throw new ApiError(401, "The panel link expired - run /velobridge panel on your server for a new one");
		}
		Server s = servers.get(session.serverId());
		if (s == null) {
			throw new ApiError(404, "Server unknown");
		}
		s.panelSeen = System.currentTimeMillis();
		return s;
	}

	// ---------------------------------------------------------------- plugin side

	/** Sync: live numbers in, pending config out. {@code full} syncs carry stats + config; quick polls only ask. */
	synchronized Map<String, Object> sync(JsonObject in) throws ApiError, IOException {
		Server s = authServer(in);
		long now = System.currentTimeMillis();
		s.lastSync = now;
		if (in.has("name")) {
			s.name = clip(str(in, "name"), 60);
		}
		if (in.has("applied")) {
			long v = in.get("applied").getAsLong();
			s.appliedVersion = Math.max(s.appliedVersion, v);
			s.applyError = str(in, "applyError");
			if (s.pending != null && s.appliedVersion >= s.pendingVersion) {
				s.pending = null;
			}
		}
		if (in.has("config") && in.get("config").isJsonObject() && in.get("config").toString().length() < 256 * 1024) {
			s.config = in.getAsJsonObject("config");
		}
		if (in.has("npcs") && in.get("npcs").isJsonArray() && in.get("npcs").toString().length() < 32 * 1024) {
			s.npcs = in.getAsJsonArray("npcs");
		}
		if (in.has("notes") && in.get("notes").isJsonArray() && in.get("notes").toString().length() < 200 * 1024) {
			s.notes = in.getAsJsonArray("notes");
			if (s.removeNotes != null && !s.removeNotes.isEmpty()) {
				// hide the ones already on their way out
				JsonArray kept = new JsonArray();
				for (JsonElement e : s.notes) {
					if (!e.isJsonObject() || !s.removeNotes.contains(str(e.getAsJsonObject(), "id"))) {
						kept.add(e);
					}
				}
				s.notes = kept;
			}
		}
		if (in.has("live") && in.get("live").isJsonObject() && in.get("live").toString().length() < 64 * 1024) {
			JsonObject live = in.getAsJsonObject("live");
			s.live = live;
			int online = num(live, "online"), velo = num(live, "velo");
			long minute = now / 60_000;
			List<long[]> m = s.hist.minutes;
			if (m.isEmpty() || m.get(m.size() - 1)[0] != minute) {
				m.add(new long[] {minute, online, velo});
			}
			while (m.size() > 1440) {
				m.remove(0);
			}
			long hour = now / 3_600_000;
			List<long[]> h = s.hist.hours;
			if (h.isEmpty() || h.get(h.size() - 1)[0] != hour) {
				h.add(new long[] {hour, online, velo, 0});
			} else {
				long[] last = h.get(h.size() - 1);
				last[1] = Math.max(last[1], online);
				last[2] = Math.max(last[2], velo);
			}
			while (h.size() > 24 * 30) {
				h.remove(0);
			}
			int offsetMin = num(live, "utcOffsetMinutes");
			for (JsonElement e : arr(live, "joins")) {
				if (!e.isJsonObject()) {
					continue;
				}
				JsonObject j = e.getAsJsonObject();
				long t = j.has("t") ? j.get("t").getAsLong() : now;
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("name", clip(str(j, "name"), 16));
				row.put("version", clip(str(j, "v"), 24));
				row.put("minecraft", clip(str(j, "mc"), 16));
				row.put("t", t);
				row.put("first", j.has("first") && j.get("first").getAsBoolean());
				s.hist.joins.add(0, row);
				s.hist.totalJoins++;
				s.hist.versionJoins.merge(String.valueOf(row.get("version")), 1L, Long::sum);
				java.time.ZonedDateTime local = java.time.Instant.ofEpochMilli(t + offsetMin * 60_000L).atZone(java.time.ZoneOffset.UTC);
				s.hist.week[(local.getDayOfWeek().getValue() - 1) * 24 + local.getHour()]++;
				if (!h.isEmpty()) {
					h.get(h.size() - 1)[3]++;
				}
			}
			while (s.hist.joins.size() > 100) {
				s.hist.joins.remove(s.hist.joins.size() - 1);
			}
			while (s.hist.versionJoins.size() > 30) {
				s.hist.versionJoins.remove(s.hist.versionJoins.keySet().iterator().next());
			}
			save(s);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		boolean panelOpen = now - s.panelSeen < 3 * 60_000L;
		out.put("panel", panelOpen);
		if (s.pending != null) {
			out.put("config", s.pending);
			out.put("version", s.pendingVersion);
		}
		if (s.removeNotes != null && !s.removeNotes.isEmpty()) {
			out.put("removeNotes", new ArrayList<>(s.removeNotes));
			s.removeNotes.clear();
			save(s);
		}
		return out;
	}

	synchronized Map<String, Object> panelCode(JsonObject in) throws ApiError {
		Server s = authServer(in);
		if (limits.tryAcquire(s.serverId, "code", new RateLimiter.Window(10, 3_600_000)) > 0) {
			throw new ApiError(429, "Too many panel links - wait a bit");
		}
		codes.entrySet().removeIf(e -> e.getValue().expires() < System.currentTimeMillis());
		String code = token(12);
		codes.put(code, new Code(s.serverId, System.currentTimeMillis() + 15 * 60_000L));
		return Map.of("code", code, "url", "https://dev.velo-client.com/panel#" + code, "expiresInMinutes", 15);
	}

	// ---------------------------------------------------------------- panel side

	synchronized Map<String, Object> login(JsonObject in, String ip) throws ApiError {
		if (limits.tryAcquire(ip, "panel-login", new RateLimiter.Window(20, 600_000)) > 0) {
			throw new ApiError(429, "Too many tries - wait a few minutes");
		}
		Code c = codes.remove(str(in, "code") == null ? "" : str(in, "code"));
		if (c == null || c.expires() < System.currentTimeMillis()) {
			throw new ApiError(401, "That link is used or expired - run /velobridge panel again");
		}
		String t = token(24);
		sessions.put(t, new Session(c.serverId(), System.currentTimeMillis() + 12 * 3_600_000L));
		sessions.entrySet().removeIf(e -> e.getValue().expires() < System.currentTimeMillis());
		Server s = servers.get(c.serverId());
		s.panelSeen = System.currentTimeMillis();
		return Map.of("token", t, "name", s.name == null ? "Your server" : s.name, "expiresInHours", 12);
	}

	synchronized Map<String, Object> overview(HttpExchange ex) throws ApiError {
		Server s = authPanel(ex);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", s.name);
		out.put("serverId", s.serverId);
		out.put("online", System.currentTimeMillis() - s.lastSync < 3 * 60_000L);
		out.put("lastSync", s.lastSync);
		out.put("live", s.live);
		out.put("minutes", s.hist.minutes);
		out.put("hours", s.hist.hours);
		out.put("week", s.hist.week);
		out.put("joins", s.hist.joins);
		out.put("versionJoins", s.hist.versionJoins);
		out.put("totalJoins", s.hist.totalJoins);
		out.put("config", s.pending != null ? s.pending : s.config);
		out.put("pending", s.pending != null);
		out.put("appliedVersion", s.appliedVersion);
		out.put("pendingVersion", s.pendingVersion);
		out.put("applyError", s.applyError);
		out.put("notes", s.notes == null ? new JsonArray() : s.notes);
		out.put("npcs", s.npcs == null ? new JsonArray() : s.npcs);
		return out;
	}

	synchronized Map<String, Object> saveConfig(HttpExchange ex) throws ApiError, IOException {
		Server s = authPanel(ex);
		JsonObject body = JsonParser.parseString(new String(JsonHttp.readRawBody(ex, 256 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
		if (!body.has("config") || !body.get("config").isJsonObject()) {
			throw new ApiError(400, "config missing");
		}
		s.pending = body.getAsJsonObject("config");
		s.pendingVersion = Math.max(s.pendingVersion, s.appliedVersion) + 1;
		s.applyError = null;
		save(s);
		return Map.of("ok", true, "version", s.pendingVersion);
	}

	synchronized Map<String, Object> removeNotes(HttpExchange ex) throws ApiError, IOException {
		Server s = authPanel(ex);
		JsonObject body = JsonParser.parseString(new String(JsonHttp.readRawBody(ex, 64 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
		if (s.removeNotes == null) {
			s.removeNotes = new ArrayList<>();
		}
		int n = 0;
		for (JsonElement e : arr(body, "ids")) {
			String id = e.isJsonPrimitive() ? e.getAsString() : "";
			if (id.matches("srv-[a-f0-9]{1,32}") && !s.removeNotes.contains(id) && s.removeNotes.size() < 1000) {
				s.removeNotes.add(id);
				n++;
			}
		}
		if (s.notes != null) {
			JsonArray kept = new JsonArray();
			for (JsonElement e : s.notes) {
				if (!e.isJsonObject() || !s.removeNotes.contains(str(e.getAsJsonObject(), "id"))) {
					kept.add(e);
				}
			}
			s.notes = kept;
		}
		save(s);
		return Map.of("ok", true, "queued", n);
	}

	/**
	 * A custom cloak for one of the server's NPCs (PNG, up to 2048 px like players' own): stored with the players'
	 * custom cloaks under "npc:&lt;server&gt;:&lt;npc&gt;", so staff review and bans cover it too.
	 */
	Map<String, Object> npcCape(HttpExchange ex) throws ApiError, IOException {
		Server s;
		synchronized (this) {
			s = authPanel(ex);
		}
		String q = ex.getRequestURI().getRawQuery();
		String npc = q == null ? "" : java.net.URLDecoder.decode(q.replaceFirst("^.*?npc=([^&]*).*$", "$1"), StandardCharsets.UTF_8);
		if (!npc.matches("[A-Za-z0-9_\\- .:]{1,64}")) {
			throw new ApiError(400, "Bad NPC name");
		}
		if (limits.tryAcquire(s.serverId, "npc-cape", new RateLimiter.Window(30, 3_600_000)) > 0) {
			throw new ApiError(429, "Too many cloak uploads - wait a bit");
		}
		byte[] bytes = JsonHttp.readRawBody(ex, 6 * 1024 * 1024);
		try {
			String hash = capes.store("npc:" + s.serverId + ":" + npc.toLowerCase(java.util.Locale.ROOT), bytes, false);
			return Map.of("capeId", CapeStore.CUSTOM_PREFIX + hash, "hash", hash);
		} catch (CapeStore.RejectedUpload e) {
			throw new ApiError(e.status, e.getMessage());
		}
	}

	private void save(Server s) throws IOException {
		Path f = dir.resolve(s.serverId + ".json");
		Path tmp = dir.resolve(s.serverId + ".json.tmp");
		Files.writeString(tmp, GSON.toJson(s), StandardCharsets.UTF_8);
		Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	void register(HttpServer server) {
		server.createContext("/v1/bridge/sync", ex -> VeloServerApp.handle(ex, "POST", e -> JsonHttp.writeJson(e, 200, sync(body(e, 512 * 1024)))));
		server.createContext("/v1/bridge/panel-code", ex -> VeloServerApp.handle(ex, "POST", e -> JsonHttp.writeJson(e, 200, panelCode(body(e, 4096)))));
		server.createContext("/v1/bridge/panel/login", ex -> VeloServerApp.handle(ex, "POST", e -> JsonHttp.writeJson(e, 200, login(body(e, 4096), WebRoutes.clientIp(e)))));
		server.createContext("/v1/bridge/panel/overview", ex -> VeloServerApp.handle(ex, "GET", e -> JsonHttp.writeJson(e, 200, overview(e))));
		server.createContext("/v1/bridge/panel/cape", ex -> VeloServerApp.handle(ex, "POST", e -> JsonHttp.writeJson(e, 200, npcCape(e))));
		server.createContext("/v1/bridge/panel/notes/remove", ex -> VeloServerApp.handle(ex, "POST", e -> JsonHttp.writeJson(e, 200, removeNotes(e))));
		server.createContext("/v1/bridge/panel/config", ex -> VeloServerApp.handle(ex, "POST", e -> JsonHttp.writeJson(e, 200, saveConfig(e))));
	}

	private static JsonObject body(HttpExchange ex, int max) throws IOException, ApiError {
		try {
			return JsonParser.parseString(new String(JsonHttp.readRawBody(ex, max), StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new ApiError(400, "Malformed JSON");
		}
	}

	private static JsonArray arr(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
	}

	private static String str(JsonObject json, String key) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
	}

	private static int num(JsonObject json, String key) {
		try {
			return json.has(key) ? json.get(key).getAsInt() : 0;
		} catch (RuntimeException e) {
			return 0;
		}
	}

	private static String clip(String s, int max) {
		return s == null ? null : s.length() > max ? s.substring(0, max) : s;
	}
}
