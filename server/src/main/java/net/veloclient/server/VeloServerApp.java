package net.veloclient.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
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

	public static void main(String[] args) throws IOException {
		int port = resolvePort();
		SessionRegistry registry = new SessionRegistry();

		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

		server.createContext("/v1/health", exchange -> handle(exchange, "GET", ex -> handleHealth(ex, registry)));
		server.createContext("/v1/session/challenge", exchange -> handle(exchange, "POST", ex -> handleChallenge(ex, registry)));
		server.createContext("/v1/session/verify", exchange -> handle(exchange, "POST", ex -> handleVerify(ex, registry)));
		server.createContext("/v1/session/end", exchange -> handle(exchange, "POST", ex -> handleEnd(ex, registry)));
		server.createContext("/v1/heartbeat", exchange -> handle(exchange, "POST", ex -> handleHeartbeat(ex, registry)));
		server.createContext("/v1/online", exchange -> handle(exchange, "GET", ex -> handleOnline(ex, registry)));

		// Expired sessions/challenges are already ignored by every lookup
		// (both check their own expiry), so this is just periodic
		// housekeeping to stop the maps growing forever under real churn -
		// not required for correctness.
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "velo-server-sweep");
			t.setDaemon(true);
			return t;
		}).scheduleAtFixedRate(registry::sweepExpired, 30, 30, TimeUnit.SECONDS);

		server.start();
		System.out.println("Velo Client server listening on port " + port);
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
	private interface Route {
		void handle(HttpExchange exchange) throws IOException;
	}

	/** Shared method-check + error handling wrapper so every route below only has to write its happy path. */
	private static void handle(HttpExchange exchange, String requiredMethod, Route route) throws IOException {
		try {
			if (!requiredMethod.equals(exchange.getRequestMethod())) {
				JsonHttp.writeError(exchange, 405, "Method not allowed");
				return;
			}
			route.handle(exchange);
		} catch (IOException e) {
			JsonHttp.writeError(exchange, 400, e.getMessage() != null ? e.getMessage() : "Bad request");
		} catch (Exception e) {
			JsonHttp.writeError(exchange, 500, "Internal server error");
		} finally {
			exchange.close();
		}
	}

	private record HealthResponse(String status, int onlineCount) {
	}

	private static void handleHealth(HttpExchange exchange, SessionRegistry registry) throws IOException {
		JsonHttp.writeJson(exchange, 200, new HealthResponse("ok", registry.onlineCount()));
	}

	private record ChallengeRequest(String uuid, String username) {
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
		String serverId = registry.createChallenge(uuid, username);
		JsonHttp.writeJson(exchange, 200, new ChallengeResponse(serverId));
	}

	private record VerifyRequest(String uuid, String serverId) {
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
		SessionRegistry.Challenge challenge = registry.takeChallenge(uuid, request.serverId());
		if (challenge == null) {
			JsonHttp.writeError(exchange, 400, "Unknown or expired challenge - request a new one");
			return;
		}
		String verifiedUuid = MojangSessionVerifier.verify(challenge.username(), challenge.serverId());
		if (verifiedUuid == null || !verifiedUuid.equals(uuid)) {
			JsonHttp.writeError(exchange, 401, "Mojang could not verify this session - did the client actually join with this serverId?");
			return;
		}
		String token = registry.createSession(uuid, challenge.username());
		JsonHttp.writeJson(exchange, 200, new VerifyResponse(token,
				SessionRegistry.HEARTBEAT_INTERVAL_SECONDS, SessionRegistry.SESSION_TTL_MILLIS / 1000));
	}

	private record HeartbeatRequest(String sessionToken, String capeId) {
	}

	private record HeartbeatResponse(boolean ok, int onlineCount) {
	}

	private static void handleHeartbeat(HttpExchange exchange, SessionRegistry registry) throws IOException {
		HeartbeatRequest request = JsonHttp.readBody(exchange, HeartbeatRequest.class);
		if (request.sessionToken() == null || request.sessionToken().isBlank()) {
			JsonHttp.writeError(exchange, 400, "sessionToken is required");
			return;
		}
		boolean ok = registry.heartbeat(request.sessionToken(), request.capeId());
		if (!ok) {
			JsonHttp.writeError(exchange, 401, "Unknown or expired session - re-authenticate");
			return;
		}
		JsonHttp.writeJson(exchange, 200, new HeartbeatResponse(true, registry.onlineCount()));
	}

	private record EndRequest(String sessionToken) {
	}

	private static void handleEnd(HttpExchange exchange, SessionRegistry registry) throws IOException {
		EndRequest request = JsonHttp.readBody(exchange, EndRequest.class);
		if (request.sessionToken() != null) {
			registry.endSession(request.sessionToken());
		}
		JsonHttp.writeJson(exchange, 200, new HeartbeatResponse(true, registry.onlineCount()));
	}

	private record OnlineResponse(List<SessionRegistry.OnlineUser> users, long serverTimeMillis) {
	}

	private static void handleOnline(HttpExchange exchange, SessionRegistry registry) throws IOException {
		JsonHttp.writeJson(exchange, 200, new OnlineResponse(registry.onlineUsers(), System.currentTimeMillis()));
	}

	/** Accepts dashed or dashless UUIDs from the client; everything downstream keys on this same lowercase-dashless form. */
	private static String normalizeUuid(String uuid) {
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
