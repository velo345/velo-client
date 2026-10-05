package net.veloclient.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The Velo Client community server: the small piece of shared state that
 * lets players running Velo Client see the badge next to *each other's*
 * names (not just their own, which is purely local - see
 * {@code VeloBadge}'s doc) and each other's equipped cosmetic cape, since
 * neither of those can work across players without something they all talk
 * to. See server/README.md for what this does/doesn't do and how to run it.
 *
 * <p>Deliberately minimal: everything lives in memory ({@link
 * SessionRegistry}), identity is proven via the same join/hasJoined
 * handshake vanilla servers use ({@link MojangSessionVerifier}) so this
 * process never has to see anyone's real access token, and there's exactly
 * one thing being tracked today (who's online + which of the built-in Store
 * capes they have equipped) - more cosmetics/social features can be added
 * as new endpoints later without touching this shape.
 */
public final class VeloServerApp {

	private static final int DEFAULT_PORT = 8787;
	private static SocialService social;
	private static net.veloclient.server.store.StoreService store;

	public static void main(String[] args) throws IOException {
		int port = resolvePort();
		SessionRegistry registry = new SessionRegistry();
		Path dataDir = Path.of(envOr("VELO_DATA_DIR", "data")).toAbsolutePath();
		CapeStore capes = new CapeStore(dataDir);
		social = new SocialService(dataDir, registry);
		String adminToken = envOr("VELO_ADMIN_TOKEN", null);
		try {
			var storeConfig = net.veloclient.server.store.StoreConfig.load(dataDir);
			var storeDb = new net.veloclient.server.store.StoreDatabase(dataDir.resolve("store.db"));
			var tebex = new net.veloclient.server.store.TebexCheckout(envOr("TEBEX_PROJECT_ID", null),
					envOr("TEBEX_PRIVATE_KEY", null), envOr("TEBEX_WEBHOOK_SECRET", null));
			store = new net.veloclient.server.store.StoreService(storeConfig, storeDb, tebex,
					net.veloclient.server.store.StoreService.parseOwners(envOr("VELO_OWNERS", null), VeloServerApp::normalizeUuid),
					envOr("VELO_PUBLIC_URL", null),
					new net.veloclient.server.store.StoreService.AdConfig(envOr("AYET_PLACEMENT_ID", null),
							envOr("AYET_ADSLOT", null), envOr("AYET_API_KEY", null)),
					query -> {
						SocialService.PlayerRef ref = social.resolvePlayer(query);
						return new String[] {ref.uuid(), ref.username()};
					});
		} catch (java.sql.SQLException e) {
			throw new IOException("Couldn't open the store database", e);
		}

		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

		server.createContext("/v1/health", exchange -> handle(exchange, "GET", ex -> handleHealth(ex, registry)));
		server.createContext("/v1/session/challenge", exchange -> handle(exchange, "POST", ex -> handleChallenge(ex, registry)));
		server.createContext("/v1/session/verify", exchange -> handle(exchange, "POST", ex -> handleVerify(ex, registry)));
		server.createContext("/v1/session/end", exchange -> handle(exchange, "POST", ex -> handleEnd(ex, registry)));
		server.createContext("/v1/heartbeat", exchange -> handle(exchange, "POST", ex -> handleHeartbeat(ex, registry, capes)));
		server.createContext("/v1/online", exchange -> handle(exchange, "GET", ex -> handleOnline(ex, registry)));
		server.createContext("/v1/cape/upload", exchange -> handle(exchange, "POST", ex -> handleCapeUpload(ex, registry, capes)));
		server.createContext("/v1/cape/remove", exchange -> handle(exchange, "POST", ex -> handleCapeRemove(ex, registry, capes)));
		// GET /v1/cape/<sha256> - the path suffix is the hash (see handleCapeDownload).
		server.createContext("/v1/cape/", exchange -> handle(exchange, "GET", ex -> handleCapeDownload(ex, capes)));
		SocialRoutes.register(server, registry, social);
		StoreRoutes.register(server, registry, store, dataDir);
		NewsRoutes.register(server, registry, new NewsService(dataDir), new ReportService(dataDir), store, adminToken);
		server.createContext("/v1/admin/cape/remove", exchange -> handle(exchange, "POST", ex -> handleAdminCapeRemove(ex, capes, adminToken)));

		// Expired sessions/challenges are already ignored by every lookup
		// (both check their own expiry), so this is just periodic
		// housekeeping to stop the maps growing forever under real churn -
		// not required for correctness.
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "velo-server-sweep");
			t.setDaemon(true);
			return t;
		}).scheduleAtFixedRate(() -> {
			registry.sweepExpired();
			social.saveIfDirty();
			social.sweepLimits();
		}, 5, 5, TimeUnit.SECONDS);
		Runtime.getRuntime().addShutdownHook(new Thread(social::saveIfDirty, "velo-social-save"));

		server.start();
		System.out.println("Velo Client server listening on port " + port + " (data: " + dataDir + ")");
		if (adminToken == null) {
			System.out.println("VELO_ADMIN_TOKEN not set - the admin cape-removal endpoint is disabled");
		}
		System.out.println("Store: coin checkout " + (store.checkoutEnabled() ? "ON" : "OFF (set TEBEX_PROJECT_ID, TEBEX_PRIVATE_KEY, VELO_PUBLIC_URL)")
				+ ", Tebex webhooks " + (envOr("TEBEX_WEBHOOK_SECRET", null) != null ? "ON" : "OFF (set TEBEX_WEBHOOK_SECRET)")
				+ ", rewarded ads " + (store.adsEnabled() ? "ON" : "OFF (set AYET_PLACEMENT_ID, AYET_ADSLOT, AYET_API_KEY, VELO_PUBLIC_URL)"));
	}

	private static String envOr(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static int resolvePort() {
		String env = System.getenv("VELO_SERVER_PORT");
		if (env != null && !env.isBlank()) {
			try {
				return Integer.parseInt(env.trim());
			} catch (NumberFormatException ignored) {
				// Falls through to the default below.
			}
		}
		return DEFAULT_PORT;
	}

	@FunctionalInterface
	interface Route {
		void handle(HttpExchange exchange) throws Exception;
	}

	/** A failure after the response headers already went out can't send an error body - just let the connection close. */
	private static void writeErrorQuietly(HttpExchange exchange, int status, String message) {
		try {
			JsonHttp.writeError(exchange, status, message);
		} catch (IOException | RuntimeException ignored) {
			// Headers were already sent; nothing more to tell the client.
		}
	}

	/** Shared method-check + error handling wrapper so every route below only has to write its happy path. */
	static void handle(HttpExchange exchange, String requiredMethod, Route route) throws IOException {
		try {
			if (!requiredMethod.equals(exchange.getRequestMethod())) {
				JsonHttp.writeError(exchange, 405, "Method not allowed");
				return;
			}
			route.handle(exchange);
		} catch (CapeStore.RejectedUpload e) {
			writeErrorQuietly(exchange, e.status, e.getMessage());
		} catch (SocialService.SocialException e) {
			writeErrorQuietly(exchange, e.status, e.getMessage());
		} catch (net.veloclient.server.store.StoreService.StoreException e) {
			writeErrorQuietly(exchange, e.status, e.getMessage());
		} catch (NewsService.NewsException e) {
			writeErrorQuietly(exchange, e.status, e.getMessage());
		} catch (IOException e) {
			writeErrorQuietly(exchange, 400, e.getMessage() != null ? e.getMessage() : "Bad request");
		} catch (Exception e) {
			e.printStackTrace();
			writeErrorQuietly(exchange, 500, "Internal server error");
		} finally {
			exchange.close();
		}
	}

	private record HealthResponse(String status, int onlineCount) {
	}

	private static void handleHealth(HttpExchange exchange, SessionRegistry registry) throws IOException {
		JsonHttp.writeJson(exchange, 200, new HealthResponse("ok", registry.onlineCount()));
	}

	private record ChallengeRequest(String uuid, String username, String kind) {
	}

	private record ChallengeResponse(String serverId) {
	}

	private static void handleChallenge(HttpExchange exchange, SessionRegistry registry) throws IOException {
		ChallengeRequest request = JsonHttp.readBody(exchange, ChallengeRequest.class);
		String uuid = normalizeUuid(request.uuid());
		String username = validUsername(request.username());
		if (uuid == null || username == null) {
			JsonHttp.writeError(exchange, 400, "uuid and username are required");
			return;
		}
		String serverId = registry.createChallenge(uuid, username, request.kind());
		JsonHttp.writeJson(exchange, 200, new ChallengeResponse(serverId));
	}

	private record VerifyRequest(String uuid, String serverId, String kind) {
	}

	private record VerifyResponse(String sessionToken, long heartbeatIntervalSeconds, long sessionTtlSeconds) {
	}

	private static void handleVerify(HttpExchange exchange, SessionRegistry registry) throws IOException {
		VerifyRequest request = JsonHttp.readBody(exchange, VerifyRequest.class);
		String uuid = normalizeUuid(request.uuid());
		if (uuid == null || request.serverId() == null || request.serverId().isBlank()) {
			JsonHttp.writeError(exchange, 400, "uuid and serverId are required");
			return;
		}
		SessionRegistry.Challenge challenge = registry.takeChallenge(uuid, request.kind(), request.serverId());
		if (challenge == null) {
			JsonHttp.writeError(exchange, 400, "Unknown or expired challenge - request a new one");
			return;
		}
		String verifiedUuid = MojangSessionVerifier.verify(challenge.username(), challenge.serverId());
		if (verifiedUuid == null || !verifiedUuid.equals(uuid)) {
			JsonHttp.writeError(exchange, 401, "Mojang could not verify this session - did the client actually join with this serverId?");
			return;
		}
		String token = registry.createSession(uuid, challenge.username(), challenge.kind());
		social.onAuthenticated(uuid, challenge.username());
		JsonHttp.writeJson(exchange, 200, new VerifyResponse(token,
				SessionRegistry.HEARTBEAT_INTERVAL_SECONDS, SessionRegistry.SESSION_TTL_MILLIS / 1000));
	}

	private record HeartbeatRequest(String sessionToken, String capeId) {
	}

	private record HeartbeatResponse(boolean ok, int onlineCount, String capeId) {
	}

	private static void handleHeartbeat(HttpExchange exchange, SessionRegistry registry, CapeStore capes) throws IOException {
		HeartbeatRequest request = JsonHttp.readBody(exchange, HeartbeatRequest.class);
		if (request.sessionToken() == null || request.sessionToken().isBlank()) {
			JsonHttp.writeError(exchange, 400, "sessionToken is required");
			return;
		}
		SessionRegistry.Session session = registry.session(request.sessionToken());
		String ownCustomHash = session == null ? null : capes.hashFor(session.uuid());
		// Store capes are only shown for players who actually own them (server-side ownership).
		String capeId = session != null && !store.mayDisplayCape(session.uuid(), request.capeId()) ? null : request.capeId();
		SessionRegistry.Session refreshed = registry.heartbeat(request.sessionToken(), capeId, ownCustomHash);
		if (refreshed != null && "game".equals(refreshed.kind())) {
			store.onGameHeartbeat(refreshed.uuid());
		}
		if (refreshed == null) {
			JsonHttp.writeError(exchange, 401, "Unknown or expired session - re-authenticate");
			return;
		}
		// Echoes the cape id actually accepted, so a client whose custom cape was removed (or a
		// server whose data was reset) notices and can re-upload instead of silently showing nothing.
		JsonHttp.writeJson(exchange, 200, new HeartbeatResponse(true, registry.onlineCount(), refreshed.capeId()));
	}

	private record EndRequest(String sessionToken) {
	}

	private static void handleEnd(HttpExchange exchange, SessionRegistry registry) throws IOException {
		EndRequest request = JsonHttp.readBody(exchange, EndRequest.class);
		if (request.sessionToken() != null) {
			registry.endSession(request.sessionToken());
		}
		JsonHttp.writeJson(exchange, 200, new HeartbeatResponse(true, registry.onlineCount(), null));
	}

	private record OnlineResponse(List<SessionRegistry.OnlineUser> users, long serverTimeMillis) {
	}

	private static void handleOnline(HttpExchange exchange, SessionRegistry registry) throws IOException {
		JsonHttp.writeJson(exchange, 200, new OnlineResponse(registry.onlineUsers(), System.currentTimeMillis()));
	}

	private record CapeUploadResponse(String capeId) {
	}

	/** Raw PNG/GIF body, authenticated with the session token as {@code Authorization: Bearer}. */
	private static void handleCapeUpload(HttpExchange exchange, SessionRegistry registry, CapeStore capes) throws Exception {
		SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(exchange));
		if (session == null) {
			JsonHttp.writeError(exchange, 401, "Unknown or expired session - re-authenticate");
			return;
		}
		byte[] bytes = JsonHttp.readRawBody(exchange, CapeStore.MAX_UPLOAD_BYTES);
		String hash = capes.store(session.uuid(), bytes);
		JsonHttp.writeJson(exchange, 200, new CapeUploadResponse(CapeStore.CUSTOM_PREFIX + hash));
	}

	private record OkResponse(boolean ok) {
	}

	private static void handleCapeRemove(HttpExchange exchange, SessionRegistry registry, CapeStore capes) throws IOException {
		SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(exchange));
		if (session == null) {
			JsonHttp.writeError(exchange, 401, "Unknown or expired session - re-authenticate");
			return;
		}
		JsonHttp.writeJson(exchange, 200, new OkResponse(capes.remove(session.uuid())));
	}

	private static void handleCapeDownload(HttpExchange exchange, CapeStore capes) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String hash = path.substring(path.lastIndexOf('/') + 1);
		CapeStore.StoredFile file = capes.read(hash);
		if (file == null) {
			JsonHttp.writeError(exchange, 404, "No such cape");
			return;
		}
		// Content-addressed, so a given URL's bytes can never change - clients and any proxy/CDN
		// in front of this can cache it forever.
		JsonHttp.writeBytes(exchange, 200, file.contentType(), file.bytes(), "public, max-age=31536000, immutable");
	}

	private record AdminRemoveRequest(String uuid) {
	}

	/** Moderation: removes (and bans the image of) a player's custom cape. Needs {@code Authorization: Bearer $VELO_ADMIN_TOKEN}. */
	private static void handleAdminCapeRemove(HttpExchange exchange, CapeStore capes, String adminToken) throws IOException {
		String given = JsonHttp.bearerToken(exchange);
		if (adminToken == null || given == null
				|| !java.security.MessageDigest.isEqual(adminToken.getBytes(), given.getBytes())) {
			JsonHttp.writeError(exchange, 403, "Forbidden");
			return;
		}
		AdminRemoveRequest request = JsonHttp.readBody(exchange, AdminRemoveRequest.class);
		String uuid = normalizeUuid(request.uuid());
		if (uuid == null) {
			JsonHttp.writeError(exchange, 400, "uuid is required");
			return;
		}
		JsonHttp.writeJson(exchange, 200, new OkResponse(capes.removeAndBan(uuid)));
	}

	/** Accepts dashed or dashless UUIDs from the client; everything downstream keys on this same lowercase-dashless form. */
	static String normalizeUuid(String uuid) {
		if (uuid == null) {
			return null;
		}
		String normalized = uuid.replace("-", "").toLowerCase();
		return normalized.length() == 32 ? normalized : null;
	}

	private static String validUsername(String username) {
		if (username == null || !username.matches("[a-zA-Z0-9_]{1,16}")) {
			return null;
		}
		return username;
	}

	private VeloServerApp() {
	}
}
