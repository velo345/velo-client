package net.veloclient.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP surface of {@link SocialService} under {@code /v1/social/}. Every route is authenticated
 * with the same session token the game/launcher got from {@code /v1/session/verify}, sent as
 * {@code Authorization: Bearer <token>} - the caller's identity always comes from that verified
 * session, never from the request body.
 */
final class SocialRoutes {

	private SocialRoutes() {
	}

	@FunctionalInterface
	private interface Authed {
		void handle(HttpExchange exchange, String me) throws Exception;
	}

	private record OkResponse(boolean ok) {
	}

	private record TargetRequest(String target) {
	}

	private record UuidRequest(String uuid) {
	}

	private record RespondRequest(String uuid, boolean accept) {
	}

	private record StatusRequest(boolean invisible) {
	}

	private record PresenceRequest(String kind, String detail) {
	}

	private record MessageRequest(String to, String text) {
	}

	private record ShareRequest(List<String> to, Map<String, Object> waypoint) {
	}

	private record ResolvedResponse(boolean ok, String uuid, String username) {
	}

	private record MessagesResponse(List<SocialService.MessageView> messages) {
	}

	static void register(HttpServer server, SessionRegistry registry, SocialService social) {
		route(server, registry, "/v1/social/state", "GET", (ex, me) -> JsonHttp.writeJson(ex, 200, social.state(me)));
		route(server, registry, "/v1/social/poll", "GET", (ex, me) -> {
			long since = parseLong(query(ex).get("since"));
			JsonHttp.writeJson(ex, 200, social.poll(me, since));
		});
		route(server, registry, "/v1/social/messages", "GET", (ex, me) -> {
			Map<String, String> query = query(ex);
			String with = VeloServerApp.normalizeUuid(query.get("with"));
			if (with == null) {
				JsonHttp.writeError(ex, 400, "with is required");
				return;
			}
			JsonHttp.writeJson(ex, 200, new MessagesResponse(social.messages(me, with, parseLong(query.get("before")))));
		});
		route(server, registry, "/v1/social/request", "POST", (ex, me) -> {
			SocialService.PlayerRef ref = social.sendRequest(me, JsonHttp.readBody(ex, TargetRequest.class).target());
			JsonHttp.writeJson(ex, 200, new ResolvedResponse(true, ref.uuid(), ref.username()));
		});
		route(server, registry, "/v1/social/respond", "POST", (ex, me) -> {
			RespondRequest request = JsonHttp.readBody(ex, RespondRequest.class);
			social.respond(me, requireUuid(request.uuid()), request.accept());
			JsonHttp.writeJson(ex, 200, new OkResponse(true));
		});
		route(server, registry, "/v1/social/cancel", "POST", (ex, me) -> {
			social.cancelRequest(me, requireUuid(JsonHttp.readBody(ex, UuidRequest.class).uuid()));
			JsonHttp.writeJson(ex, 200, new OkResponse(true));
		});
		route(server, registry, "/v1/social/unfriend", "POST", (ex, me) -> {
			social.unfriend(me, requireUuid(JsonHttp.readBody(ex, UuidRequest.class).uuid()));
			JsonHttp.writeJson(ex, 200, new OkResponse(true));
		});
		route(server, registry, "/v1/social/block", "POST", (ex, me) -> {
			social.block(me, JsonHttp.readBody(ex, TargetRequest.class).target());
			JsonHttp.writeJson(ex, 200, new OkResponse(true));
		});
		route(server, registry, "/v1/social/unblock", "POST", (ex, me) -> {
			social.unblock(me, requireUuid(JsonHttp.readBody(ex, UuidRequest.class).uuid()));
			JsonHttp.writeJson(ex, 200, new OkResponse(true));
		});
		route(server, registry, "/v1/social/status", "POST", (ex, me) -> {
			social.setInvisible(me, JsonHttp.readBody(ex, StatusRequest.class).invisible());
			JsonHttp.writeJson(ex, 200, new OkResponse(true));
		});
		route(server, registry, "/v1/social/presence", "POST", (ex, me) -> {
			PresenceRequest request = JsonHttp.readBody(ex, PresenceRequest.class);
			social.setPresence(me, request.kind(), request.detail());
			JsonHttp.writeJson(ex, 200, new OkResponse(true));
		});
		route(server, registry, "/v1/social/message", "POST", (ex, me) -> {
			MessageRequest request = JsonHttp.readBody(ex, MessageRequest.class);
			JsonHttp.writeJson(ex, 200, social.sendMessage(me, requireUuid(request.to()), request.text()));
		});
		route(server, registry, "/v1/social/read", "POST", (ex, me) -> {
			social.markRead(me, requireUuid(JsonHttp.readBody(ex, UuidRequest.class).uuid()));
			JsonHttp.writeJson(ex, 200, new OkResponse(true));
		});
		route(server, registry, "/v1/social/share-waypoint", "POST", (ex, me) -> {
			ShareRequest request = JsonHttp.readBody(ex, ShareRequest.class);
			if (request.to() == null || request.to().isEmpty()) {
				JsonHttp.writeError(ex, 400, "Pick at least one friend");
				return;
			}
			List<String> targets = new java.util.ArrayList<>();
			for (String uuid : request.to()) {
				targets.add(requireUuid(uuid));
			}
			social.shareWaypoint(me, targets, request.waypoint());
			JsonHttp.writeJson(ex, 200, new OkResponse(true));
		});
	}

	private static void route(HttpServer server, SessionRegistry registry, String path, String method, Authed handler) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, method, ex -> {
			String token = JsonHttp.bearerToken(ex);
			SessionRegistry.Session session = registry.session(token);
			if (session == null) {
				JsonHttp.writeError(ex, 401, "Unknown or expired session - re-authenticate");
				return;
			}
			registry.touch(token);
			handler.handle(ex, session.uuid());
		}));
	}

	private static String requireUuid(String uuid) throws SocialService.SocialException {
		String normalized = VeloServerApp.normalizeUuid(uuid);
		if (normalized == null) {
			throw new SocialService.SocialException(400, "A valid player uuid is required");
		}
		return normalized;
	}

	private static Map<String, String> query(HttpExchange exchange) {
		Map<String, String> out = new HashMap<>();
		String raw = exchange.getRequestURI().getRawQuery();
		if (raw == null) {
			return out;
		}
		for (String part : raw.split("&")) {
			int eq = part.indexOf('=');
			if (eq > 0) {
				out.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
						URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		return out;
	}

	private static long parseLong(String value) {
		if (value == null) {
			return 0;
		}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
