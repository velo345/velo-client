package net.veloclient.velo.client.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.auth.VeloAccountAuth;
import net.veloclient.velo.client.cosmetics.CapeDefinition;
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
 * The token comes from the running game's own session ({@link
 * GameSessionReader}) - that's what makes this work no matter which launcher
 * started the game - with the Velo launcher's {@code account.json} only as a
 * fallback for refreshing an expired token of that same account.
 */
public final class VeloServerClient {

	// Deliberately independent of the server-advertised heartbeat interval
	// (see VerifyResponse#heartbeatIntervalSeconds) - a short, fixed period
	// keeps newly-joined players' badges appearing quickly for everyone else
	// without needing to coordinate the two sides' timing.
	private static final long TICK_INTERVAL_SECONDS = 20;
	// Mojang rate-limits session/minecraft/join; a failing auth used to be
	// retried every single tick (20s) forever.
	private static final long AUTH_RETRY_MILLIS = 120_000;

	static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private static final AtomicReference<String> sessionToken = new AtomicReference<>();
	private static volatile String authenticatedUuid;
	private static volatile long nextAuthAttemptMillis;
	private static ScheduledExecutorService scheduler;

	/** Whether a custom (non-Store) equipped cape is uploaded and shown to other Velo users - see {@code VeloNetworkModule}. */
	public static volatile boolean shareCustomCapes = true;

	private VeloServerClient() {
	}

	/** Thrown for a non-2xx reply, so callers can tell "server said no" (e.g. 401) apart from "couldn't reach it". */
	static final class HttpStatusException extends java.io.IOException {
		final int status;

		HttpStatusException(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	public static synchronized void start() {
		if (scheduler != null) {
			return;
		}
		nextAuthAttemptMillis = 0;
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
		authenticatedUuid = null;
		VeloUserRegistry.clear();
		if (token != null) {
			// Best-effort "going offline now" rather than waiting out the
			// server's own session TTL - fire-and-forget on a throwaway
			// thread since the module's disabling right now and shouldn't
			// block on it.
			Thread.ofVirtual().start(() -> endSessionQuietly(token));
		}
	}

	/** The configured server's base URL, or null if none - shared with {@code RemoteCapeCache}'s downloads. */
	public static String serverBaseUrl() {
		VeloNetworkConfig config = VeloNetworkConfig.load();
		return config.isConfigured() ? config.normalizedUrl() : null;
	}

	private static void tick() {
		try {
			String base = serverBaseUrl();
			if (base == null) {
				return;
			}
			// Switched accounts in-game (e.g. the session fixer) - the old
			// session would keep badging the previous account's name.
			Optional<ActiveAccountReader.Account> gameAccount = GameSessionReader.read();
			if (sessionToken.get() != null && gameAccount.isPresent()
					&& !gameAccount.get().uuid().equals(authenticatedUuid)) {
				String old = sessionToken.getAndSet(null);
				endSessionQuietly(old);
			}
			if (sessionToken.get() == null) {
				authenticate(base, gameAccount);
			}
			String token = sessionToken.get();
			if (token == null) {
				return;
			}
			String capeId = publishedCapeId(base, token);
			try {
				String accepted = heartbeat(base, token, capeId);
				if (capeId != null && capeId.startsWith(CustomCapeUploader.CUSTOM_PREFIX) && !capeId.equals(accepted)) {
					// Server no longer has our upload (data reset, or removed) - forget the mapping
					// so the next tick re-uploads (a removed/banned cape is then refused for good).
					CustomCapeUploader.forget(capeId);
				}
			} catch (HttpStatusException e) {
				if (e.status == 401) {
					sessionToken.compareAndSet(token, null);
				}
				return;
			}
			poll(base);
		} catch (Exception e) {
			// Network blips land here - keep the session token, just try again next tick.
			VeloClient.LOGGER.debug("Velo Network sync failed", e);
		}
	}

	/** Store capes publish their catalog id; a custom cape publishes {@code custom:<hash>} after uploading it once. */
	private static String publishedCapeId(String base, String token) {
		Optional<CapeDefinition> equipped = CapeManager.equipped();
		if (equipped.isEmpty()) {
			return null;
		}
		CapeDefinition definition = equipped.get();
		if (definition.sourceItemId() != null && !definition.sourceItemId().isBlank()) {
			return definition.sourceItemId();
		}
		if (!shareCustomCapes) {
			return null;
		}
		return CustomCapeUploader.publishedIdFor(base, token, definition);
	}

	private static void authenticate(String base, Optional<ActiveAccountReader.Account> gameAccount) {
		long now = System.currentTimeMillis();
		if (now < nextAuthAttemptMillis) {
			return;
		}
		nextAuthAttemptMillis = now + AUTH_RETRY_MILLIS;

		if (gameAccount.isPresent() && tryAuthenticate(base, gameAccount.get())) {
			nextAuthAttemptMillis = 0;
			return;
		}
		// The game's own token can expire in a very long session - the Velo
		// launcher's saved account can refresh it, but only if it's the same
		// account actually playing (never badge someone else's name).
		Optional<ActiveAccountReader.Account> saved = ActiveAccountReader.read();
		if (saved.isEmpty()) {
			return;
		}
		String savedUuid = normalizeUuid(saved.get().uuid());
		if (gameAccount.isPresent() && !gameAccount.get().uuid().equals(savedUuid)) {
			return;
		}
		ActiveAccountReader.Account account = saved.get();
		if (!account.tokenLikelyValid()) {
			try {
				VeloAccountAuth.RefreshedSession refreshed = VeloAccountAuth.refreshActiveAccount();
				account = new ActiveAccountReader.Account(refreshed.uuid(), refreshed.username(), refreshed.accessToken(), Long.MAX_VALUE);
			} catch (VeloAccountAuth.AuthRefreshException e) {
				return;
			}
		}
		if (tryAuthenticate(base, account)) {
			nextAuthAttemptMillis = 0;
		}
	}

	private static boolean tryAuthenticate(String base, ActiveAccountReader.Account account) {
		String uuid = normalizeUuid(account.uuid());
		if (uuid == null || account.accessToken() == null || account.username() == null) {
			return false;
		}
		try {
			String serverId = requestChallenge(base, uuid, account.username());
			joinMojangSession(account.accessToken(), uuid, serverId);
			String token = verifySession(base, uuid, serverId);
			authenticatedUuid = uuid;
			sessionToken.set(token);
			return true;
		} catch (Exception e) {
			VeloClient.LOGGER.debug("Velo Network authentication failed", e);
			return false;
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

	/** Returns the cape id the server actually accepted (null if it rejected/cleared it). */
	private static String heartbeat(String base, String token, String capeId) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("sessionToken", token);
		if (capeId != null) {
			body.addProperty("capeId", capeId);
		}
		JsonObject response = postJson(base + "/v1/heartbeat", body);
		// Older servers don't echo the cape back - treat that as "accepted as sent".
		if (!response.has("capeId")) {
			return capeId;
		}
		return response.get("capeId").isJsonNull() ? null : response.get("capeId").getAsString();
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
			String base = serverBaseUrl();
			if (base != null) {
				postJson(base + "/v1/session/end", body);
			}
		} catch (Exception ignored) {
			// Not worth surfacing - the session will simply expire on its own.
		}
	}

	static JsonObject postJson(String url, JsonObject payload) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
				.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new HttpStatusException(response.statusCode(), "Velo server returned " + response.statusCode() + ": " + response.body());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private static String requireString(JsonObject json, String field) throws java.io.IOException {
		if (!json.has(field) || json.get(field).isJsonNull()) {
			throw new java.io.IOException("Velo server response missing \"" + field + "\"");
		}
		return json.get(field).getAsString();
	}

	static String normalizeUuid(String uuid) {
		if (uuid == null) {
			return null;
		}
		String normalized = uuid.replace("-", "").toLowerCase();
		return normalized.length() == 32 ? normalized : null;
	}
}
