package net.veloclient.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.veloclient.server.store.StoreService;

import java.util.Map;

/**
 * Discord linking:
 * <ul>
 * <li>{@code GET /v1/discord/status} (signed in) - whether you're linked, the invite link</li>
 * <li>{@code POST /v1/discord/code} (signed in) - a code to paste in the Discord's configured link channel</li>
 * <li>{@code POST /v1/admin/discord/link} - the bot redeems a code ({@code X-Velo-Admin-Token})</li>
 * <li>{@code POST /v1/admin/discord/unlink} - the bot removes a link</li>
 * </ul>
 */
final class DiscordRoutes {

	private DiscordRoutes() {
	}

	@FunctionalInterface
	private interface Admin {
		void handle(HttpExchange exchange) throws Exception;
	}

	private record LinkRequest(String code, String discordId, String discordName) {
	}

	private record UnlinkRequest(String discordId, Boolean revoke) {
	}

	static void register(HttpServer server, SessionRegistry registry, StoreService store, DiscordLinks links, String inviteUrl, String adminToken) {
		server.createContext("/v1/discord/status", ex -> VeloServerApp.handle(ex, "GET", e -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(e));
			if (session == null) {
				JsonHttp.writeError(e, 401, "Unknown or expired session - re-authenticate");
				return;
			}
			DiscordLinks.Link link = links.byUuid(session.uuid());
			JsonHttp.writeJson(e, 200, Map.of("linked", link != null, "discordName", link == null ? "" : link.discordName(),
					"inviteUrl", inviteUrl == null ? "" : inviteUrl));
		}));
		server.createContext("/v1/discord/code", ex -> VeloServerApp.handle(ex, "POST", e -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(e));
			if (session == null) {
				JsonHttp.writeError(e, 401, "Unknown or expired session - re-authenticate");
				return;
			}
			if (links.byUuid(session.uuid()) != null) {
				JsonHttp.writeError(e, 409, "Your Discord account is already linked");
				return;
			}
			JsonHttp.writeJson(e, 200, Map.of("code", links.createCode(session.uuid(), session.username()),
					"expiresInSeconds", DiscordLinks.codeTtlSeconds(), "inviteUrl", inviteUrl == null ? "" : inviteUrl));
		}));
		admin(server, adminToken, "/v1/admin/discord/link", e -> {
			LinkRequest r = JsonHttp.readBody(e, LinkRequest.class);
			if (r.discordId() == null || r.discordId().isBlank()) {
				JsonHttp.writeError(e, 400, "discordId is required");
				return;
			}
			DiscordLinks.Result result = links.redeem(r.code(), r.discordId(), r.discordName() == null ? r.discordId() : r.discordName());
			JsonHttp.writeJson(e, 200, Map.of("uuid", result.uuid(), "username", result.username(), "rewards", result.rewards()));
		});
		admin(server, adminToken, "/v1/admin/discord/unlink", e -> {
			UnlinkRequest r = JsonHttp.readBody(e, UnlinkRequest.class);
			boolean removed = links.unlink(r.discordId(), r.revoke() == null || r.revoke());
			JsonHttp.writeJson(e, 200, Map.of("removed", removed));
		});
	}

	/** Admin token only (these are the bot's calls - owners use the launcher, not these). */
	private static void admin(HttpServer server, String adminToken, String path, Admin handler) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
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
