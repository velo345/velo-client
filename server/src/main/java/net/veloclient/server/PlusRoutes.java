package net.veloclient.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.veloclient.server.store.StoreService;

import java.util.List;
import java.util.Map;

/**
 * Velo+ (the subscription):
 * <ul>
 * <li>{@code GET /v1/plus/status}, {@code POST /v1/plus/checkout} - signed in</li>
 * <li>{@code GET /v1/plus/token} - a signed proof of membership for the game (members and Addons players)</li>
 * <li>{@code GET /v1/plus/addons}, {@code GET /v1/plus/addons/file?mc=} - the Addons jars, only for players the owner
 * gave them to (everyone else gets a plain 404, as if the route didn't exist)</li>
 * <li>{@code POST /v1/admin/addons?mc=&version=} - upload a jar ({@code X-Velo-Admin-Token})</li>
 * <li>{@code POST /v1/admin/store/plus}, {@code POST /v1/admin/store/addons}, {@code GET /v1/admin/discord/links} - owner / bot</li>
 * </ul>
 */
final class PlusRoutes {

	private PlusRoutes() {
	}

	private record GrantRequest(String target, int days) {
	}

	private record AddonGrantRequest(String target, boolean granted) {
	}

	private interface Authed {
		void handle(HttpExchange exchange, SessionRegistry.Session session) throws Exception;
	}

	static void register(HttpServer server, SessionRegistry registry, StoreService store, PlusTokens tokens, AddonStore addons, AddonSync addonSync,
			DiscordLinks links, String adminToken) {
		route(server, registry, "/v1/plus/status", "GET", (ex, s) -> {
			var plus = store.config().plus;
			Map<String, Object> out = new java.util.LinkedHashMap<>(Map.of("plus", store.isPlus(s.uuid()), "until", store.plusUntil(s.uuid()),
					"price", plus.price, "currency", store.config().currency, "monthlyCoins", plus.monthlyCoins, "periodDays", plus.periodDays,
					"checkoutEnabled", store.checkoutEnabled()));
			out.put("trialDays", plus.trialDays);
			out.put("yearlyPrice", plus.yearlyPrice);
			out.put("trialAvailable", store.trialAvailable(s.uuid()));
			// Only the players who have them hear about the Addons at all.
			if (store.hasAddons(s.uuid())) {
				out.put("addons", true);
				out.put("addonsEnabled", tokens.enabled());
			}
			JsonHttp.writeJson(ex, 200, out);
		});
		route(server, registry, "/v1/plus/trial", "POST", (ex, s) -> {
			long until = store.startTrial(s.uuid(), s.username());
			JsonHttp.writeJson(ex, 200, Map.of("plus", true, "until", until));
		});
		route(server, registry, "/v1/plus/checkout", "POST", (ex, s) -> {
			// {"period": "year"} for the yearly plan; anything else (or no body) is monthly.
			String body = new String(JsonHttp.readRawBody(ex, 4096), java.nio.charset.StandardCharsets.UTF_8);
			boolean yearly = false;
			try {
				var json = com.google.gson.JsonParser.parseString(body.isBlank() ? "{}" : body).getAsJsonObject();
				yearly = json.has("period") && "year".equals(json.get("period").getAsString());
			} catch (RuntimeException ignored) {
				// not JSON: monthly
			}
			JsonHttp.writeJson(ex, 200, store.plusCheckout(s.uuid(), s.username(), yearly));
		});
		route(server, registry, "/v1/plus/token", "GET", (ex, s) -> {
			boolean plusMember = store.isPlus(s.uuid());
			boolean addonAccess = store.hasAddons(s.uuid());
			if (!plusMember && !addonAccess) {
				JsonHttp.writeError(ex, 403, "Velo+ is required");
				return;
			}
			if (!tokens.enabled()) {
				JsonHttp.writeError(ex, 503, "Velo+ tokens aren't set up on this server (VELO_PLUS_SIGNING_KEY)");
				return;
			}
			long until = store.plusUntil(s.uuid());
			JsonHttp.writeJson(ex, 200, Map.of("token", tokens.issue(s.uuid(), until, plusMember, addonAccess),
					"until", tokens.validUntil(plusMember ? until : 0, addonAccess)));
		});
		route(server, registry, "/v1/plus/addons", "GET", (ex, s) -> {
			if (!store.hasAddons(s.uuid())) {
				JsonHttp.writeError(ex, 404, "Not found");
				return;
			}
			JsonHttp.writeJson(ex, 200, Map.of("addons", addons.list()));
		});
		route(server, registry, "/v1/plus/addons/file", "GET", (ex, s) -> {
			if (!store.hasAddons(s.uuid())) {
				JsonHttp.writeError(ex, 404, "Not found");
				return;
			}
			String mc = StoreRoutes.query(ex).get("mc");
			byte[] jar = addons.jar(mc);
			if (jar == null) {
				JsonHttp.writeError(ex, 404, "No addons for " + mc);
				return;
			}
			JsonHttp.writeBytes(ex, 200, "application/java-archive", jar, "no-store");
		});

		admin(server, adminToken, "/v1/admin/addons", ex -> {
			Map<String, String> q = StoreRoutes.query(ex);
			try {
				AddonStore.Entry entry = addons.put(q.get("mc"), q.get("version"), JsonHttp.readRawBody(ex, AddonStore.MAX_JAR_BYTES));
				JsonHttp.writeJson(ex, 200, entry);
			} catch (IllegalArgumentException e) {
				JsonHttp.writeError(ex, 400, e.getMessage());
			}
		});
		admin(server, adminToken, "/v1/admin/addons/sync", ex -> {
			try {
				JsonHttp.writeJson(ex, 200, Map.of("stored", addonSync.sync()));
			} catch (IllegalStateException e) {
				JsonHttp.writeError(ex, 503, e.getMessage());
			}
		});
		// Owner (a session) gives or ends Velo+; a negative or zero number of days ends it.
		route(server, registry, "/v1/admin/store/plus", "POST", (ex, s) -> {
			GrantRequest r = JsonHttp.readBody(ex, GrantRequest.class);
			JsonHttp.writeJson(ex, 200, store.adminPlus(s.uuid(), s.username(), r.target(), r.days()));
		});
		// Owner gives or takes away the Addons (independent of Velo+).
		route(server, registry, "/v1/admin/store/addons", "POST", (ex, s) -> {
			AddonGrantRequest r = JsonHttp.readBody(ex, AddonGrantRequest.class);
			JsonHttp.writeJson(ex, 200, store.adminAddons(s.uuid(), s.username(), r.target(), r.granted()));
		});
		admin(server, adminToken, "/v1/admin/discord/links", ex -> {
			List<Map<String, Object>> out = new java.util.ArrayList<>();
			for (DiscordLinks.Link l : links.all()) {
				out.add(Map.of("discordId", l.discordId(), "uuid", l.uuid(), "username", l.username(), "plus", store.isPlus(l.uuid())));
			}
			JsonHttp.writeJson(ex, 200, Map.of("links", out));
		});
	}

	private static void route(HttpServer server, SessionRegistry registry, String path, String method, Authed handler) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, method, ex -> {
			String token = JsonHttp.bearerToken(ex);
			SessionRegistry.Session session = registry.session(token);
			if (session == null && path.startsWith("/v1/admin/")) {
				session = StoreRoutes.panelSession(ex);
			}
			if (session == null) {
				JsonHttp.writeError(ex, 401, "Unknown or expired session - re-authenticate");
				return;
			}
			registry.touch(token);
			handler.handle(ex, session);
		}));
	}

	@FunctionalInterface
	private interface Admin {
		void handle(HttpExchange exchange) throws Exception;
	}

	/** Admin token only (the bot and the release tools). */
	private static void admin(HttpServer server, String adminToken, String path, Admin handler) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, exchange.getRequestMethod(), ex -> {
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
