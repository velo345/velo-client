package net.veloclient.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.veloclient.server.store.StoreService;

import java.util.List;
import java.util.Map;

/**
 * News, polls and bug reports:
 * <ul>
 * <li>{@code GET /v1/news} - posts and polls (a signed-in session adds your own votes; owners also get drafts)</li>
 * <li>{@code GET /v1/news/image/<id>} - an uploaded news image</li>
 * <li>{@code POST /v1/news/vote} - vote in a poll (signed in)</li>
 * <li>{@code POST /v1/reports} - send a bug/crash report (signing in is optional)</li>
 * <li>{@code POST /v1/admin/news/{post,post/delete,poll,poll/delete,image}}, {@code GET /v1/admin/reports},
 * {@code GET /v1/admin/reports/get}, {@code POST /v1/admin/reports/{status,delete}} - owner only (an owner
 * session, or the {@code X-Velo-Admin-Token} header for scripts)</li>
 * </ul>
 */
final class NewsRoutes {

	private NewsRoutes() {
	}

	@FunctionalInterface
	private interface Admin {
		void handle(HttpExchange exchange, String author) throws Exception;
	}

	private record VoteRequest(String pollId, List<Integer> options) {
	}

	private record IdRequest(String id) {
	}

	private record StatusRequest(String id, String status, String note) {
	}

	private static final RateLimiter LIMITS = new RateLimiter();

	static void register(HttpServer server, SessionRegistry registry, NewsService news, ReportService reports, StoreService store,
			String adminToken) {
		server.createContext("/v1/news", ex -> VeloServerApp.handle(ex, "GET", e -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(e));
			boolean owner = session != null && store.isOwner(session.uuid(), session.username());
			JsonHttp.writeJson(e, 200, news.feed(session == null ? null : session.uuid(), owner));
		}));
		server.createContext("/v1/news/image/", ex -> VeloServerApp.handle(ex, "GET", e -> {
			String id = e.getRequestURI().getPath().substring("/v1/news/image/".length());
			byte[] bytes = news.image(id);
			if (bytes == null) {
				JsonHttp.writeError(e, 404, "No such image");
				return;
			}
			JsonHttp.writeBytes(e, 200, id.endsWith(".png") ? "image/png" : "image/jpeg", bytes, "public, max-age=31536000, immutable");
		}));
		server.createContext("/v1/news/vote", ex -> VeloServerApp.handle(ex, "POST", e -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(e));
			if (session == null) {
				JsonHttp.writeError(e, 401, "Sign in to vote");
				return;
			}
			long wait = LIMITS.tryAcquire(session.uuid(), "vote", new RateLimiter.Window(20, 60_000));
			if (wait > 0) {
				JsonHttp.writeError(e, 429, "Slow down - try again in " + RateLimiter.describeWait(wait));
				return;
			}
			VoteRequest r = JsonHttp.readBody(e, VoteRequest.class);
			JsonHttp.writeJson(e, 200, news.vote(session.uuid(), r.pollId(), r.options()));
		}));

		server.createContext("/v1/reports", ex -> VeloServerApp.handle(ex, "POST", e -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(e));
			String who = session != null ? session.uuid() : clientIp(e);
			long wait = LIMITS.tryAcquire(who, "report", new RateLimiter.Window(4, 10 * 60_000), new RateLimiter.Window(20, 24 * 3600_000L));
			if (wait > 0) {
				JsonHttp.writeError(e, 429, "Too many reports - try again in " + RateLimiter.describeWait(wait));
				return;
			}
			ReportService.Report r = JsonHttp.readBody(e, ReportService.Report.class, ReportService.MAX_REPORT_BYTES);
			String id = reports.submit(r, session == null ? null : session.uuid(), session == null ? null : session.username());
			JsonHttp.writeJson(e, 200, Map.of("id", id));
		}));

		admin(server, registry, store, adminToken, "/v1/admin/news/post", "POST", (e, author) ->
				JsonHttp.writeJson(e, 200, news.savePost(JsonHttp.readBody(e, NewsService.Post.class, 1024 * 1024), author)));
		admin(server, registry, store, adminToken, "/v1/admin/news/post/delete", "POST", (e, author) -> {
			news.deletePost(JsonHttp.readBody(e, IdRequest.class).id());
			JsonHttp.writeJson(e, 200, Map.of("ok", true));
		});
		admin(server, registry, store, adminToken, "/v1/admin/news/poll", "POST", (e, author) ->
				JsonHttp.writeJson(e, 200, news.savePoll(JsonHttp.readBody(e, NewsService.Poll.class, 64 * 1024), author)));
		admin(server, registry, store, adminToken, "/v1/admin/news/poll/delete", "POST", (e, author) -> {
			news.deletePoll(JsonHttp.readBody(e, IdRequest.class).id());
			JsonHttp.writeJson(e, 200, Map.of("ok", true));
		});
		admin(server, registry, store, adminToken, "/v1/admin/news/image", "POST", (e, author) ->
				JsonHttp.writeJson(e, 200, news.storeImage(JsonHttp.readRawBody(e, NewsService.MAX_IMAGE_BYTES))));
		admin(server, registry, store, adminToken, "/v1/admin/reports", "GET", (e, author) ->
				JsonHttp.writeJson(e, 200, Map.of("reports", reports.list())));
		admin(server, registry, store, adminToken, "/v1/admin/reports/get", "GET", (e, author) ->
				JsonHttp.writeJson(e, 200, reports.get(StoreRoutes.query(e).get("id"))));
		admin(server, registry, store, adminToken, "/v1/admin/reports/status", "POST", (e, author) -> {
			StatusRequest r = JsonHttp.readBody(e, StatusRequest.class);
			reports.setStatus(r.id(), r.status(), r.note());
			JsonHttp.writeJson(e, 200, Map.of("ok", true));
		});
		admin(server, registry, store, adminToken, "/v1/admin/reports/delete", "POST", (e, author) -> {
			reports.delete(JsonHttp.readBody(e, IdRequest.class).id());
			JsonHttp.writeJson(e, 200, Map.of("ok", true));
		});
	}

	private static void admin(HttpServer server, SessionRegistry registry, StoreService store, String adminToken, String path, String method,
			Admin handler) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, method, ex -> {
			String header = ex.getRequestHeaders().getFirst("X-Velo-Admin-Token");
			if (adminToken != null && header != null && java.security.MessageDigest.isEqual(
					adminToken.getBytes(java.nio.charset.StandardCharsets.UTF_8), header.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
				handler.handle(ex, "Velo Team");
				return;
			}
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(ex));
			if (session == null) {
				JsonHttp.writeError(ex, 401, "Unknown or expired session - re-authenticate");
				return;
			}
			if (!store.isOwner(session.uuid(), session.username())) {
				JsonHttp.writeError(ex, 403, "Only the server owner can do that");
				return;
			}
			handler.handle(ex, session.username());
		}));
	}

	/** The caller's IP - behind a local reverse proxy (Caddy) the forwarded address. */
	private static String clientIp(HttpExchange exchange) {
		var remote = exchange.getRemoteAddress().getAddress();
		String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
		if (remote.isLoopbackAddress() && forwarded != null && !forwarded.isBlank()) {
			return forwarded.split(",")[0].trim();
		}
		return remote.getHostAddress();
	}
}
