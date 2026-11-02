package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Map;
import java.util.stream.Stream;

/**
 * "Share profile": a launcher uploads a profile's recipe - Minecraft version, the Modrinth versions of its mods /
 * resource packs / shaders, optionally its options.txt - and gets a short code (VELO-7XK2Q9). Anyone with the code
 * gets the same setup; no jar ever passes through here, only Modrinth ids. Codes live 180 days
 * ({@code data/shared-profiles/<code>.json}).
 */
final class ProfileShareService {

	private static final Gson GSON = new Gson();
	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I
	private static final long TTL_MILLIS = 180L * 24 * 3600_000L;
	private static final int MAX_BODY = 160 * 1024;
	private final Path dir;
	private final SecureRandom random = new SecureRandom();

	ProfileShareService(Path dataDir) throws IOException {
		this.dir = dataDir.resolve("shared-profiles");
		Files.createDirectories(dir);
		cleanup();
	}

	private void cleanup() {
		long now = System.currentTimeMillis();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : files.toList()) {
				if (now - Files.getLastModifiedTime(f).toMillis() > TTL_MILLIS) {
					Files.deleteIfExists(f);
				}
			}
		} catch (IOException ignored) {
			// Next start.
		}
	}

	private String newCode() {
		for (int attempt = 0; attempt < 20; attempt++) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 6; i++) {
				sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
			}
			if (!Files.exists(dir.resolve(sb + ".json"))) {
				return sb.toString();
			}
		}
		throw new IllegalStateException("no free code");
	}

	/** Only what a launcher needs to rebuild the profile, with sane sizes. */
	private static JsonObject clean(JsonObject in, String username) throws ApiError {
		JsonObject out = new JsonObject();
		String mc = str(in, "minecraft");
		if (mc == null || !mc.matches("[0-9.]{3,12}")) {
			throw new ApiError(400, "Which Minecraft version?");
		}
		out.addProperty("minecraft", mc);
		String name = str(in, "name");
		out.addProperty("name", name == null ? "Shared profile" : name.substring(0, Math.min(40, name.length())));
		out.addProperty("by", username);
		out.addProperty("at", System.currentTimeMillis());
		int total = 0;
		for (String kind : new String[] {"mods", "resourcepacks", "shaders"}) {
			JsonArray list = new JsonArray();
			if (in.has(kind) && in.get(kind).isJsonArray()) {
				for (JsonElement e : in.getAsJsonArray(kind)) {
					if (!e.isJsonObject() || list.size() >= 400) {
						continue;
					}
					JsonObject o = e.getAsJsonObject();
					String project = str(o, "project");
					String version = str(o, "version");
					if (project == null || version == null || !project.matches("[A-Za-z0-9]{4,16}") || !version.matches("[A-Za-z0-9]{4,16}")) {
						continue;
					}
					JsonObject c = new JsonObject();
					c.addProperty("project", project);
					c.addProperty("version", version);
					String title = str(o, "title");
					if (title != null) {
						c.addProperty("title", title.substring(0, Math.min(80, title.length())));
					}
					c.addProperty("enabled", !o.has("enabled") || o.get("enabled").getAsBoolean());
					list.add(c);
					total++;
				}
			}
			out.add(kind, list);
		}
		if (total == 0) {
			throw new ApiError(400, "Nothing to share - add mods or packs from Modrinth first");
		}
		String options = str(in, "options");
		if (options != null && options.length() <= 64 * 1024) {
			out.addProperty("options", options);
		}
		out.addProperty("skipped", in.has("skipped") ? Math.max(0, Math.min(999, in.get("skipped").getAsInt())) : 0);
		return out;
	}

	void register(HttpServer server, SessionRegistry registry) {
		RateLimiter limiter = new RateLimiter();
		server.createContext("/v1/profiles/share", exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(ex));
			if (session == null || SessionRegistry.KIND_WEB.equals(session.kind())) {
				throw new ApiError(401, "Sign in to the Velo launcher first");
			}
			if (limiter.tryAcquire(session.uuid(), "profile-share", new RateLimiter.Window(10, 3600_000), new RateLimiter.Window(40, 24 * 3600_000L)) > 0) {
				throw new ApiError(429, "You shared a lot of profiles - try again later");
			}
			JsonObject body = JsonParser.parseString(new String(JsonHttp.readRawBody(ex, MAX_BODY), StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject profile = clean(body, session.username());
			String code = newCode();
			Path tmp = dir.resolve(code + ".json.tmp");
			Files.writeString(tmp, GSON.toJson(profile), StandardCharsets.UTF_8);
			Files.move(tmp, dir.resolve(code + ".json"), StandardCopyOption.ATOMIC_MOVE);
			JsonHttp.writeJson(ex, 200, Map.of("code", "VELO-" + code, "expiresInDays", TTL_MILLIS / 86_400_000L));
		}));
		server.createContext("/v1/profiles/shared/", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			String ip = WebRoutes.clientIp(ex);
			if (limiter.tryAcquire(ip, "profile-get", new RateLimiter.Window(60, 600_000)) > 0) {
				throw new ApiError(429, "Slow down");
			}
			String code = ex.getRequestURI().getPath().substring("/v1/profiles/shared/".length()).toUpperCase(java.util.Locale.ROOT)
					.replaceFirst("^VELO-?", "").replaceAll("[^A-Z0-9]", "");
			Path file = dir.resolve(code + ".json");
			if (code.length() != 6 || !Files.exists(file)) {
				throw new ApiError(404, "No profile with that code - check it, or ask for a new one");
			}
			JsonHttp.writeJson(ex, 200, JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
		}));
	}

	private static String str(JsonObject json, String key) {
		return json != null && json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
	}
}
