package net.veloclient.server;

import com.sun.net.httpserver.HttpServer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only numbers for the owner panel (panel.velo-client.com): usage stats, live sessions, known players and the
 * Store's money. Admin token only ({@code X-Velo-Admin-Token}) - the panel's backend holds the token, the browser
 * never sees it.
 */
final class PanelRoutes {

	private PanelRoutes() {
	}

	/** TikTok / YouTube Shorts tracking: GET the numbers, POST {"url"} to track, {"remove": key} to stop, {"refresh": true} to measure now. */
	static void registerSocials(HttpServer server, SocialStatsService socials, String adminToken) {
		admin(server, adminToken, "/v1/admin/panel/socials", ex -> JsonHttp.writeJson(ex, 200, socials.summary()), "GET");
		admin(server, adminToken, "/v1/admin/panel/socials/edit", ex -> {
			var body = com.google.gson.JsonParser.parseString(new String(JsonHttp.readRawBody(ex, 8192), java.nio.charset.StandardCharsets.UTF_8))
					.getAsJsonObject();
			if (body.has("remove")) {
				JsonHttp.writeJson(ex, 200, socials.remove(body.get("remove").getAsString()));
			} else if (body.has("refresh")) {
				socials.refreshSoon();
				JsonHttp.writeJson(ex, 200, socials.summary());
			} else {
				JsonHttp.writeJson(ex, 200, socials.add(body.has("url") ? body.get("url").getAsString() : null));
			}
		}, "POST");
	}

	static void register(HttpServer server, SessionRegistry registry, StatsService stats, net.veloclient.server.store.StoreService store, String adminToken) {
		admin(server, adminToken, "/v1/admin/panel/stats", ex -> {
			Map<String, String> q = StoreRoutes.query(ex);
			int days = clamp(q.get("days"), 30, 7, 365);
			int hours = clamp(q.get("hours"), 48, 1, 24 * 30);
			Map<String, Object> out = new LinkedHashMap<>(stats.overview(days, hours));
			int[] live = registry.countsByKind();
			out.put("onlineNow", Map.of("game", live[0], "launcher", live[1], "web", live[2]));
			JsonHttp.writeJson(ex, 200, out);
		});
		admin(server, adminToken, "/v1/admin/panel/sessions", ex -> JsonHttp.writeJson(ex, 200, Map.of("sessions", registry.liveSessions())));
		admin(server, adminToken, "/v1/admin/panel/players", ex -> {
			Map<String, String> q = StoreRoutes.query(ex);
			JsonHttp.writeJson(ex, 200, Map.of("players", stats.players(clamp(q.get("limit"), 100, 1, 500), q.get("q"))));
		});
		admin(server, adminToken, "/v1/admin/panel/store", ex -> {
			int days = clamp(StoreRoutes.query(ex).get("days"), 30, 7, 365);
			long now = System.currentTimeMillis();
			JsonHttp.writeJson(ex, 200, store.database().panelSummary(now - days * 86_400_000L, now, store.ownerUuids()));
		});
	}

	private static int clamp(String value, int fallback, int min, int max) {
		try {
			return value == null ? fallback : Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	@FunctionalInterface
	private interface Handler {
		void handle(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
	}

	private static void admin(HttpServer server, String adminToken, String path, Handler handler) {
		admin(server, adminToken, path, handler, "GET");
	}

	private static void admin(HttpServer server, String adminToken, String path, Handler handler, String method) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, method, ex -> {
			String header = ex.getRequestHeaders().getFirst("X-Velo-Admin-Token");
			if (adminToken == null || header == null || !java.security.MessageDigest.isEqual(
					adminToken.getBytes(java.nio.charset.StandardCharsets.UTF_8), header.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
				JsonHttp.writeError(ex, 403, "Forbidden");
				return;
			}
			handler.handle(ex);
		}));
	}
}
