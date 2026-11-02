package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servers running Velo Bridge report anonymous numbers every 5 minutes ({@code data/bridge-servers.json}): their name,
 * software, Minecraft version, players online, how many of them use Velo and which Velo versions. Only counts - no
 * player names. The owner panel lists them (who supports Velo, how big, how many Velo players).
 */
final class BridgeService {

	static final class ServerInfo {
		String serverId;
		String name;
		String plugin;
		String software;
		String minecraft;
		int online;
		int velo;
		int peakVelo;
		Map<String, Integer> versions = new LinkedHashMap<>();
		String ip;
		long firstSeen;
		long lastSeen;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long FORGET_AFTER = 30L * 24 * 3600_000L;
	private final Path file;
	private final Map<String, ServerInfo> servers = new ConcurrentHashMap<>();
	private long lastSave;

	BridgeService(Path dataDir) {
		this.file = dataDir.resolve("bridge-servers.json");
		try {
			if (Files.exists(file)) {
				List<ServerInfo> list = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<ServerInfo>>() {
				}.getType());
				if (list != null) {
					list.forEach(s -> servers.put(s.serverId, s));
				}
			}
		} catch (Exception e) {
			System.out.println("[bridge] couldn't read bridge-servers.json: " + e.getMessage());
		}
	}

	synchronized void heartbeat(JsonObject in, String ip) throws ApiError {
		String id = str(in, "serverId");
		if (id == null || !id.matches("[0-9a-fA-F-]{36}")) {
			throw new ApiError(400, "serverId missing");
		}
		long now = System.currentTimeMillis();
		ServerInfo s = servers.computeIfAbsent(id, k -> {
			ServerInfo n = new ServerInfo();
			n.serverId = k;
			n.firstSeen = now;
			return n;
		});
		s.name = clip(str(in, "name"), 60);
		s.plugin = clip(str(in, "plugin"), 20);
		s.software = clip(str(in, "software"), 40);
		s.minecraft = clip(str(in, "minecraft"), 16);
		s.online = Math.max(0, Math.min(100_000, num(in, "online")));
		s.velo = Math.max(0, Math.min(s.online, num(in, "velo")));
		s.peakVelo = Math.max(s.peakVelo, s.velo);
		s.versions = new LinkedHashMap<>();
		if (in.has("versions") && in.get("versions").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : in.getAsJsonObject("versions").entrySet()) {
				if (s.versions.size() < 20) {
					s.versions.put(clip(e.getKey(), 24), Math.max(0, e.getValue().getAsInt()));
				}
			}
		}
		s.ip = ip;
		s.lastSeen = now;
		servers.values().removeIf(x -> now - x.lastSeen > FORGET_AFTER);
		if (now - lastSave > 60_000) {
			lastSave = now;
			save();
		}
	}

	private void save() {
		try {
			Path tmp = file.resolveSibling("bridge-servers.json.tmp");
			Files.writeString(tmp, GSON.toJson(new ArrayList<>(servers.values())), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[bridge] save failed: " + e.getMessage());
		}
	}

	synchronized Map<String, Object> overview() {
		long now = System.currentTimeMillis();
		List<ServerInfo> list = new ArrayList<>(servers.values());
		list.sort(Comparator.comparingInt((ServerInfo s) -> now - s.lastSeen < 15 * 60_000L ? -s.velo : 1).thenComparingLong(s -> -s.lastSeen));
		int onlineServers = 0;
		int veloNow = 0;
		Map<String, Integer> versions = new LinkedHashMap<>();
		for (ServerInfo s : list) {
			if (now - s.lastSeen < 15 * 60_000L) {
				onlineServers++;
				veloNow += s.velo;
				s.versions.forEach((v, c) -> versions.merge(v, c, Integer::sum));
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("servers", list);
		out.put("onlineServers", onlineServers);
		out.put("veloPlayersNow", veloNow);
		out.put("versions", versions);
		return out;
	}

	void register(HttpServer server, String adminToken) {
		RateLimiter limiter = new RateLimiter();
		server.createContext("/v1/bridge/heartbeat", exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
			String ip = WebRoutes.clientIp(ex);
			if (limiter.tryAcquire(ip, "bridge-heartbeat", new RateLimiter.Window(30, 3600_000)) > 0) {
				throw new ApiError(429, "Slow down");
			}
			heartbeat(JsonParser.parseString(new String(JsonHttp.readRawBody(ex, 16 * 1024), StandardCharsets.UTF_8)).getAsJsonObject(), ip);
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		}));
		server.createContext("/v1/admin/bridge/servers", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			String header = ex.getRequestHeaders().getFirst("X-Velo-Admin-Token");
			if (adminToken == null || header == null || !java.security.MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8),
					header.getBytes(StandardCharsets.UTF_8))) {
				JsonHttp.writeError(ex, 403, "Forbidden");
				return;
			}
			JsonHttp.writeJson(ex, 200, overview());
		}));
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
