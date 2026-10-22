package net.veloclient.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.util.Map;

/**
 * Signing in on velo-client.com (the web Store): the launcher asks for a 6-digit code ({@code /v1/web/code}, with its
 * own session - it already proved who you are through Mojang), you type it on the website, which swaps it once for
 * a web session ({@code /v1/web/login}). That token then works with the normal Store routes (wallet, buy, coin and
 * Velo+ checkout). Codes last 5 minutes; logins are rate limited per IP so codes can't be guessed.
 */
final class WebRoutes {

	private static final RateLimiter.Window[] LOGIN_LIMIT = {new RateLimiter.Window(8, 60_000), new RateLimiter.Window(30, 3_600_000)};
	private static final RateLimiter.Window[] CODE_LIMIT = {new RateLimiter.Window(5, 60_000)};

	private record LoginRequest(String code, ProofOfWork.Solution pow) {
	}

	private record CodeRequest(String name, ProofOfWork.Solution pow) {
	}

	private static final RateLimiter.Window[] REQUEST_LIMIT = {new RateLimiter.Window(4, 600_000), new RateLimiter.Window(12, 3_600_000)};
	private static final RateLimiter.Window[] REQUEST_TARGET_LIMIT = {new RateLimiter.Window(1, 30_000), new RateLimiter.Window(6, 3_600_000)};
	private static final RateLimiter.Window[] SKIN_LIMIT = {new RateLimiter.Window(120, 60_000)};

	/** The invisible bot check on the website's sign-in form. */
	private static final ProofOfWork POW = new ProofOfWork();

	private WebRoutes() {
	}

	static void register(HttpServer server, SessionRegistry registry, SocialService social, SkinService skins) {
		RateLimiter limiter = new RateLimiter();
		// "Send code to my launcher": the website names the account, the launcher (already open, or once it opens)
		// shows the code. Only someone at that launcher sees it - asking for someone else's just pops a dialog for them,
		// so both the asker and the target are rate limited.
		server.createContext("/v1/web/request", exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
			if (limiter.tryAcquire(clientIp(ex), "web-request", REQUEST_LIMIT) > 0) {
				JsonHttp.writeError(ex, 429, "Too many requests - wait a few minutes");
				return;
			}
			CodeRequest request = JsonHttp.readBody(ex, CodeRequest.class);
			if (request == null || !POW.verify(request.pow())) {
				JsonHttp.writeError(ex, 400, "The security check failed - reload the page and try again");
				return;
			}
			SocialService.PlayerRef ref;
			try {
				ref = social.resolvePlayer(request.name());
			} catch (SocialService.SocialException e) {
				JsonHttp.writeError(ex, 404, "No Minecraft account with that name");
				return;
			}
			if (limiter.tryAcquire(ref.uuid(), "web-request-target", REQUEST_TARGET_LIMIT) > 0) {
				JsonHttp.writeError(ex, 429, "A code was just sent to that launcher - check it, or wait a moment");
				return;
			}
			registry.requestWebCode(ref.uuid());
			// An open launcher hears about it at once through its social long-poll.
			social.pushEvent(ref.uuid(), "web_code", Map.of());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true, "username", ref.username() == null ? request.name() : ref.username(),
					"expiresInSeconds", SessionRegistry.WEB_CODE_TTL_MILLIS / 1000));
		}));
		server.createContext("/v1/web/pending", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(ex));
			if (session == null || SessionRegistry.KIND_WEB.equals(session.kind())) {
				JsonHttp.writeError(ex, 401, "Sign in to the Velo launcher first");
				return;
			}
			String code = registry.takeRequestedWebCode(session.uuid(), session.username());
			Map<String, Object> out = new java.util.HashMap<>();
			out.put("code", code);
			out.put("expiresInSeconds", SessionRegistry.WEB_CODE_TTL_MILLIS / 1000);
			JsonHttp.writeJson(ex, 200, out);
		}));
		// Skins: /v1/skins/<uuid or name>/{skin.png, head.png, meta}
		server.createContext("/v1/skins/", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			if (limiter.tryAcquire(clientIp(ex), "skins", SKIN_LIMIT) > 0) {
				JsonHttp.writeError(ex, 429, "Slow down");
				return;
			}
			String[] parts = ex.getRequestURI().getPath().substring("/v1/skins/".length()).split("/");
			if (parts.length != 2 || !parts[0].matches("[0-9a-fA-F-]{32,36}|[A-Za-z0-9_]{1,16}")) {
				JsonHttp.writeError(ex, 404, "Not found");
				return;
			}
			SocialService.PlayerRef ref;
			try {
				ref = social.resolvePlayer(parts[0]);
			} catch (SocialService.SocialException e) {
				JsonHttp.writeError(ex, 404, "No such player");
				return;
			}
			switch (parts[1]) {
				case "meta" -> {
					SkinService.Skin skin = skins.skin(ref.uuid());
					Map<String, Object> out = new java.util.HashMap<>();
					out.put("uuid", ref.uuid());
					out.put("username", ref.username());
					out.put("slim", skin != null && skin.slim());
					out.put("hasSkin", skin != null);
					JsonHttp.writeJson(ex, 200, out);
				}
				case "skin.png" -> {
					SkinService.Skin skin = skins.skin(ref.uuid());
					if (skin == null) {
						JsonHttp.writeError(ex, 404, "No skin");
						return;
					}
					JsonHttp.writeBytes(ex, 200, "image/png", skin.png(), "public, max-age=3600");
				}
				case "head.png" -> {
					byte[] head = skins.head(ref.uuid());
					if (head == null) {
						JsonHttp.writeError(ex, 404, "No skin");
						return;
					}
					JsonHttp.writeBytes(ex, 200, "image/png", head, "public, max-age=3600");
				}
				default -> JsonHttp.writeError(ex, 404, "Not found");
			}
		}));
		server.createContext("/v1/web/code", exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(ex));
			if (session == null || SessionRegistry.KIND_WEB.equals(session.kind())) {
				JsonHttp.writeError(ex, 401, "Sign in to the Velo launcher first");
				return;
			}
			if (limiter.tryAcquire(session.uuid(), "web-code", CODE_LIMIT) > 0) {
				JsonHttp.writeError(ex, 429, "Too many codes - wait a minute");
				return;
			}
			String code = registry.createWebCode(session.uuid(), session.username());
			JsonHttp.writeJson(ex, 200, Map.of("code", code, "expiresInSeconds", SessionRegistry.WEB_CODE_TTL_MILLIS / 1000));
		}));
		server.createContext("/v1/web/challenge", exchange -> VeloServerApp.handle(exchange, "GET", ex -> JsonHttp.writeJson(ex, 200, POW.issue())));
		server.createContext("/v1/web/login", exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
			if (limiter.tryAcquire(clientIp(ex), "web-login", LOGIN_LIMIT) > 0) {
				JsonHttp.writeError(ex, 429, "Too many tries - wait a minute");
				return;
			}
			LoginRequest request = JsonHttp.readBody(ex, LoginRequest.class);
			if (request == null || !POW.verify(request.pow())) {
				JsonHttp.writeError(ex, 400, "The security check failed - reload the page and try again");
				return;
			}
			String[] result = registry.redeemWebCode(request == null ? null : request.code());
			if (result == null) {
				JsonHttp.writeError(ex, 401, "That code is wrong or expired - get a new one in the launcher");
				return;
			}
			JsonHttp.writeJson(ex, 200, Map.of("token", result[0], "uuid", result[1], "username", result[2],
					"expiresInSeconds", SessionRegistry.WEB_SESSION_TTL_MILLIS / 1000));
		}));
		server.createContext("/v1/web/me", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(ex));
			if (session == null) {
				JsonHttp.writeError(ex, 401, "Signed out");
				return;
			}
			JsonHttp.writeJson(ex, 200, Map.of("uuid", session.uuid(), "username", session.username()));
		}));
		server.createContext("/v1/web/logout", exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
			String token = JsonHttp.bearerToken(ex);
			SessionRegistry.Session session = registry.session(token);
			if (session != null && SessionRegistry.KIND_WEB.equals(session.kind())) {
				registry.endSession(token);
			}
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		}));
	}

	/** The visitor's address: nginx passes it in X-Real-IP. */
	static String clientIp(HttpExchange exchange) throws IOException {
		String real = exchange.getRequestHeaders().getFirst("X-Real-IP");
		return real != null && !real.isBlank() ? real.trim() : exchange.getRemoteAddress().getAddress().getHostAddress();
	}
}
