package net.veloclient.velo.client.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.auth.VeloAccountAuth;
import net.veloclient.velo.client.cosmetics.CapeManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Talks to a self-hosted Velo Client server (see {@code /server}) so this
 * client and others running Velo Client can see each other's badge/cape.
 * Runs entirely on its own background scheduler - never touches the render
 * thread - and degrades to a no-op the moment {@link VeloNetworkConfig}
 * isn't configured, so a player who's never set a server URL sees zero
 * behavior change.
 *
 * <p>The identity handshake ({@link #authenticate}) is the same
 * join-a-server / hasJoined flow real Minecraft servers use for online-mode
 * auth: the client's real access token is only ever sent to Mojang, never to
 * this server (see server/MojangSessionVerifier.java for the other half).
 */
public final class VeloServerClient {

	// Deliberately independent of the server-advertised heartbeat interval
	// (see VerifyResponse#heartbeatIntervalSeconds) - a short, fixed period
	// keeps newly-joined players' badges appearing quickly for everyone else
	// without needing to coordinate the two sides' timing.
	private static final long TICK_INTERVAL_SECONDS = 20;

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private static final AtomicReference<String> sessionToken = new AtomicReference<>();
	private static ScheduledExecutorService scheduler;

	private VeloServerClient() {
	}

	public static synchronized void start() {
		if (scheduler != null) {
			return;
		}
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "velo-server-client");
			thread.setDaemon(true);
			return thread;
		});
		scheduler.scheduleWithFixedDelay(VeloServerClient::tick, 0, TICK_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	public static synchronized void stop() {
		if (scheduler == null) {
			return;
		}
		scheduler.shutdownNow();
		scheduler = null;
		String token = sessionToken.getAndSet(null);
		VeloUserRegistry.clear();
		if (token != null) {
			// Best-effort "going offline now" rather than waiting out the
			// server's own session TTL - fire-and-forget on a throwaway
			// thread since the module's disabling right now and shouldn't
			// block on it.
			Thread.ofVirtual().start(() -> endSessionQuietly(token));
		}
	}

	private static void tick() {
		try {
			VeloNetworkConfig config = VeloNetworkConfig.load();
			if (!config.isConfigured()) {
				return;
			}
			String base = config.normalizedUrl();
			if (sessionToken.get() == null) {
				authenticate(base);
			}
			String token = sessionToken.get();
			if (token == null) {
				return;
			}
			String capeId = CapeManager.equippedSourceItemId().orElse(null);
			if (!heartbeat(base, token, capeId)) {
				sessionToken.compareAndSet(token, null);
				return;
			}
			poll(base);
		} catch (Exception e) {
			VeloClient.LOGGER.debug("Velo Network sync failed", e);
		}
	}

	private static void authenticate(String base) {
		Optional<ActiveAccountReader.Account> accountOpt = ActiveAccountReader.read();
		if (accountOpt.isEmpty()) {
			return;
		}
		ActiveAccountReader.Account account = accountOpt.get();
		String uuid = normalizeUuid(account.uuid());
		if (uuid == null) {
			return;
		}
		String accessToken = account.accessToken();
		String username = account.username();
		if (!account.tokenLikelyValid()) {
			try {
				VeloAccountAuth.RefreshedSession refreshed = VeloAccountAuth.refreshActiveAccount();
				accessToken = refreshed.accessToken();
				uuid = normalizeUuid(refreshed.uuid());
				username = refreshed.username();
			} catch (VeloAccountAuth.AuthRefreshException e) {
				return;
			}
		}
		if (accessToken == null || uuid == null) {
			return;
		}
		try {
			String serverId = requestChallenge(base, uuid, username);
			joinMojangSession(accessToken, uuid, serverId);
			String token = verifySession(base, uuid, serverId);
			sessionToken.set(token);
		} catch (Exception e) {
			VeloClient.LOGGER.debug("Velo Network authentication failed", e);
		}
	}

	private static String requestChallenge(String base, String uuid, String username) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("uuid", uuid);
		body.addProperty("username", username);
		JsonObject response = postJson(base + "/v1/session/challenge", body);
		return requireString(response, "serverId");
	}

	/** Mojang's own "join a server" call - our server never sees this access token, only that this succeeded. */
	private static void joinMojangSession(String accessToken, String uuid, String serverId) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("accessToken", accessToken);
		body.addProperty("selectedProfile", uuid);
		body.addProperty("serverId", serverId);
		HttpRequest request = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/join"))
				.header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new java.io.IOException("Mojang rejected the join request (" + response.statusCode() + ")");
		}
	}

	private static String verifySession(String base, String uuid, String serverId) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("uuid", uuid);
		body.addProperty("serverId", serverId);
		JsonObject response = postJson(base + "/v1/session/verify", body);
		return requireString(response, "sessionToken");
	}

	private static boolean heartbeat(String base, String token, String capeId) {
		try {
			JsonObject body = new JsonObject();
			body.addProperty("sessionToken", token);
			if (capeId != null) {
				body.addProperty("capeId", capeId);
			}
			postJson(base + "/v1/heartbeat", body);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static void poll(String base) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/online"))
					.timeout(Duration.ofSeconds(10))
					.GET()
					.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				return;
			}
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			Map<String, VeloUserRegistry.Entry> entries = new HashMap<>();
			for (var element : json.getAsJsonArray("users")) {
				JsonObject user = element.getAsJsonObject();
				String uuid = normalizeUuid(user.has("uuid") ? user.get("uuid").getAsString() : null);
				String username = user.has("username") ? user.get("username").getAsString() : null;
				String capeId = user.has("capeId") && !user.get("capeId").isJsonNull() ? user.get("capeId").getAsString() : null;
				if (uuid != null && username != null) {
					entries.put(uuid, new VeloUserRegistry.Entry(username, capeId));
				}
			}
			VeloUserRegistry.replaceAll(entries);
		} catch (Exception e) {
			VeloClient.LOGGER.debug("Velo Network online-list poll failed", e);
		}
	}

	private static void endSessionQuietly(String token) {
		try {
			JsonObject body = new JsonObject();
			body.addProperty("sessionToken", token);
			VeloNetworkConfig config = VeloNetworkConfig.load();
			if (config.isConfigured()) {
				postJson(config.normalizedUrl() + "/v1/session/end", body);
			}
		} catch (Exception ignored) {
			// Not worth surfacing - the session will simply expire on its own.
		}
	}

	private static JsonObject postJson(String url, JsonObject payload) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
				.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new java.io.IOException("Velo server returned " + response.statusCode() + ": " + response.body());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private static String requireString(JsonObject json, String field) throws java.io.IOException {
		if (!json.has(field) || json.get(field).isJsonNull()) {
			throw new java.io.IOException("Velo server response missing \"" + field + "\"");
		}
		return json.get(field).getAsString();
	}

	private static String normalizeUuid(String uuid) {
		if (uuid == null) {
			return null;
		}
		String normalized = uuid.replace("-", "").toLowerCase();
		return normalized.length() == 32 ? normalized : null;
	}
}
