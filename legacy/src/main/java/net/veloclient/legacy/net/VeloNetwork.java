package net.veloclient.legacy.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Session;
import net.veloclient.legacy.Json;
import net.veloclient.legacy.VeloLegacy;
import net.veloclient.legacy.VeloPaths;
import net.veloclient.legacy.cape.LocalCape;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Velo Network on 1.8.9, the same protocol as the modern client: sign in with the Mojang join / hasJoined handshake
 * (the access token only ever goes to Mojang), send a heartbeat with the equipped cloak every 20 s, and read who else
 * is online every 5 s. Everything runs on one background thread.
 */
public final class VeloNetwork {

	public static final String DEFAULT_SERVER = "https://api.velo-client.com";
	public static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final long AUTH_RETRY_MILLIS = 120_000;

	private static ScheduledExecutorService scheduler;
	private static volatile String sessionToken;
	private static volatile String signedInUuid;
	private static volatile long nextAuth;
	private static volatile String status = "Not connected";
	private static volatile boolean warned;

	private VeloNetwork() {
	}

	public static String status() {
		return status;
	}

	public static String sessionToken() {
		return sessionToken;
	}

	public static synchronized void start() {
		if (scheduler != null || base() == null) {
			return;
		}
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "velo-network");
			t.setDaemon(true);
			return t;
		});
		scheduler.scheduleWithFixedDelay(VeloNetwork::tick, 2, 20, TimeUnit.SECONDS);
		scheduler.scheduleWithFixedDelay(() -> {
			if (sessionToken != null) {
				poll();
			}
		}, 5, 5, TimeUnit.SECONDS);
	}

	public static synchronized void stop() {
		if (scheduler == null) {
			return;
		}
		scheduler.shutdownNow();
		scheduler = null;
		String token = sessionToken;
		sessionToken = null;
		VeloUsers.clear();
		if (token != null) {
			try {
				JsonObject body = new JsonObject();
				body.addProperty("sessionToken", token);
				post("/v1/session/end", body, 3);
			} catch (Exception ignored) {
				// The session just times out on the server.
			}
		}
	}

	/** The server from {@code config/network.json} (shared with the modern client); null = Velo Network off. */
	public static String base() {
		JsonObject config = Json.read(VeloPaths.config().resolve("network.json"));
		String url = config == null || !config.has("serverUrl") ? DEFAULT_SERVER : Json.string(config, "serverUrl");
		if (url == null || url.isBlank()) {
			return null;
		}
		url = url.trim();
		if (url.contains("client.asteriasmp.net")) {
			url = DEFAULT_SERVER;
		}
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	/** Dashless uuid, name and token of the game's own session, or null for offline / dev sessions. */
	static String[] account() {
		Session session = Minecraft.getInstance().getSession();
		String uuid = session.getUuid();
		String token = session.getAccessToken();
		if (uuid == null || token == null || token.length() < 32) {
			return null;
		}
		uuid = uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
		return uuid.length() == 32 ? new String[] {uuid, session.getUsername(), token} : null;
	}

	private static void tick() {
		try {
			String[] account = account();
			if (account == null) {
				status = "Offline account - sign in with a Microsoft account to use Velo Network";
				return;
			}
			if (sessionToken != null && !account[0].equals(signedInUuid)) {
				sessionToken = null;
			}
			if (sessionToken == null) {
				authenticate(account);
			}
			String token = sessionToken;
			if (token == null) {
				return;
			}
			JsonObject body = new JsonObject();
			body.addProperty("sessionToken", token);
			String capeId = LocalCape.publishedId(base(), token, account[0]);
			if (capeId != null) {
				body.addProperty("capeId", capeId);
			}
			JsonObject plusBadge = Json.read(VeloPaths.config().resolve("plus-badge.json"));
			body.addProperty("hidePlus", plusBadge != null && plusBadge.has("show") && !plusBadge.get("show").getAsBoolean());
			body.addProperty("gameVersion", "1.8.9");
			// Not drawn on 1.8.9, but players on newer versions on the same server see them (bare ids = default settings).
			String wings = Json.string(Json.read(VeloPaths.accountConfig(account[0], "cosmetics-wings.json")), "wingsId");
			if (wings != null) {
				body.addProperty("wingsId", wings);
			}
			String aura = Json.string(Json.read(VeloPaths.accountConfig(account[0], "cosmetics-aura.json")), "auraId");
			if (aura != null) {
				body.addProperty("auraId", aura);
			}
			JsonObject wear = Json.read(VeloPaths.accountConfig(account[0], "cosmetics-wear.json"));
			if (wear != null && wear.has("slots") && wear.get("slots").isJsonObject()) {
				java.util.List<String> ids = new java.util.ArrayList<>();
				wear.getAsJsonObject("slots").entrySet().forEach(e -> {
					if (!e.getValue().isJsonNull()) {
						ids.add(e.getValue().getAsString());
					}
				});
				if (!ids.isEmpty()) {
					body.addProperty("wear", String.join(",", ids));
				}
			}
			try {
				post("/v1/heartbeat", body, 15);
			} catch (HttpStatus e) {
				if (e.code == 401) {
					sessionToken = null;
				}
				throw e;
			}
			poll();
			status = "Connected";
			warned = false;
		} catch (Exception e) {
			status = e instanceof HttpStatus h && h.code >= 500 ? "Velo server is down (HTTP " + h.code + ")" : "Can't reach the Velo server";
			if (!warned) {
				warned = true;
				VeloLegacy.LOGGER.warn("Velo Network: {} ({})", status, e.toString());
			}
		}
	}

	private static void authenticate(String[] account) throws Exception {
		long now = System.currentTimeMillis();
		if (now < nextAuth) {
			return;
		}
		nextAuth = now + AUTH_RETRY_MILLIS;
		JsonObject challenge = new JsonObject();
		challenge.addProperty("uuid", account[0]);
		challenge.addProperty("username", account[1]);
		challenge.addProperty("kind", "game");
		String serverId = Json.string(post("/v1/session/challenge", challenge, 15), "serverId");

		JsonObject join = new JsonObject();
		join.addProperty("accessToken", account[2]);
		join.addProperty("selectedProfile", account[0]);
		join.addProperty("serverId", serverId);
		HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/join"))
				.header("Content-Type", "application/json").timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(join.toString())).build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new java.io.IOException("Mojang rejected the join request (" + response.statusCode() + ")");
		}

		JsonObject verify = new JsonObject();
		verify.addProperty("uuid", account[0]);
		verify.addProperty("serverId", serverId);
		verify.addProperty("kind", "game");
		String token = Json.string(post("/v1/session/verify", verify, 15), "sessionToken");
		if (token == null) {
			throw new java.io.IOException("No session token from the Velo server");
		}
		signedInUuid = account[0];
		sessionToken = token;
		nextAuth = 0;
		VeloLegacy.LOGGER.info("Velo Network: signed in as {}", account[1]);
	}

	private static void poll() {
		try {
			HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/v1/online"))
					.timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				return;
			}
			Map<String, VeloUsers.Entry> entries = new HashMap<>();
			for (JsonElement element : Json.parse(response.body()).getAsJsonArray("users")) {
				JsonObject user = element.getAsJsonObject();
				String uuid = Json.string(user, "uuid");
				String name = Json.string(user, "username");
				if (uuid == null || name == null) {
					continue;
				}
				uuid = uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
				entries.put(uuid, new VeloUsers.Entry(name, Json.string(user, "capeId"), Json.bool(user, "plus")));
			}
			VeloUsers.replaceAll(entries);
		} catch (Exception e) {
			VeloLegacy.LOGGER.debug("Velo Network online poll failed", e);
		}
	}

	public static final class HttpStatus extends java.io.IOException {
		public final int code;

		HttpStatus(int code, String body) {
			super("HTTP " + code + ": " + body);
			this.code = code;
		}
	}

	static JsonObject post(String path, JsonObject body, int timeoutSeconds) throws Exception {
		HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base() + path))
				.header("Content-Type", "application/json").header("Accept", "application/json")
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new HttpStatus(response.statusCode(), response.body());
		}
		return Json.parse(response.body());
	}
}
