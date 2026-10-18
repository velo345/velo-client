package net.veloclient.server;

import com.sun.net.httpserver.HttpServer;

import java.util.Map;

/**
 * {@code /v1/party/*} - see {@link PartyService}. Same session-token auth as the social routes;
 * updates reach the members through the social long-poll.
 */
final class PartyRoutes {

	private PartyRoutes() {
	}

	private record Ok(boolean ok) {
	}

	private record TargetRequest(String target) {
	}

	private record UuidRequest(String uuid) {
	}

	private record RespondRequest(String party, boolean accept) {
	}

	private record SyncRequest(PartyService.ModSync sync) {
	}

	private record LaunchRequest(String server, String mcVersion) {
	}

	private record ChatRequest(String text) {
	}

	private record FollowRequest(String server, String serverName) {
	}

	private record VoiceRequest(String name, String password) {
	}

	private record ShareRequest(Map<String, Object> payload) {
	}

	private record PositionsResponse(java.util.List<PartyService.LivePosition> positions) {
	}

	static void register(HttpServer server, SessionRegistry registry, SocialService social, PartyService parties) {
		SocialRoutes.route(server, registry, "/v1/party/state", "GET", (ex, me) -> JsonHttp.writeJson(ex, 200, parties.state(me)));
		SocialRoutes.route(server, registry, "/v1/party/create", "POST", (ex, me) -> JsonHttp.writeJson(ex, 200, parties.create(me)));
		SocialRoutes.route(server, registry, "/v1/party/invite", "POST", (ex, me) -> {
			String target = JsonHttp.readBody(ex, TargetRequest.class).target();
			JsonHttp.writeJson(ex, 200, parties.invite(me, social.resolvePlayer(target).uuid()));
		});
		SocialRoutes.route(server, registry, "/v1/party/respond", "POST", (ex, me) -> {
			RespondRequest request = JsonHttp.readBody(ex, RespondRequest.class);
			parties.respond(me, request.party(), request.accept());
			JsonHttp.writeJson(ex, 200, parties.state(me));
		});
		SocialRoutes.route(server, registry, "/v1/party/leave", "POST", (ex, me) -> {
			parties.leave(me);
			JsonHttp.writeJson(ex, 200, new Ok(true));
		});
		SocialRoutes.route(server, registry, "/v1/party/kick", "POST", (ex, me) -> {
			parties.kick(me, SocialRoutes.requireUuid(JsonHttp.readBody(ex, UuidRequest.class).uuid()));
			JsonHttp.writeJson(ex, 200, new Ok(true));
		});
		SocialRoutes.route(server, registry, "/v1/party/transfer", "POST", (ex, me) -> {
			parties.transfer(me, SocialRoutes.requireUuid(JsonHttp.readBody(ex, UuidRequest.class).uuid()));
			JsonHttp.writeJson(ex, 200, new Ok(true));
		});
		SocialRoutes.route(server, registry, "/v1/party/sync", "POST", (ex, me) -> {
			parties.setSync(me, JsonHttp.readBody(ex, SyncRequest.class).sync());
			JsonHttp.writeJson(ex, 200, new Ok(true));
		});
		SocialRoutes.route(server, registry, "/v1/party/launch", "POST", (ex, me) -> {
			LaunchRequest request = JsonHttp.readBody(ex, LaunchRequest.class);
			parties.launch(me, request.server(), request.mcVersion());
			JsonHttp.writeJson(ex, 200, new Ok(true));
		});
		SocialRoutes.route(server, registry, "/v1/party/voice", "POST", (ex, me) -> {
			VoiceRequest request = JsonHttp.readBody(ex, VoiceRequest.class);
			parties.setVoice(me, request.name(), request.password());
			JsonHttp.writeJson(ex, 200, new Ok(true));
		});
		SocialRoutes.route(server, registry, "/v1/party/follow", "POST", (ex, me) -> {
			FollowRequest request = JsonHttp.readBody(ex, FollowRequest.class);
			parties.follow(me, request.server(), request.serverName());
			JsonHttp.writeJson(ex, 200, new Ok(true));
		});
		SocialRoutes.route(server, registry, "/v1/party/chat", "POST", (ex, me) ->
				JsonHttp.writeJson(ex, 200, parties.chat(me, JsonHttp.readBody(ex, ChatRequest.class).text())));
		SocialRoutes.route(server, registry, "/v1/party/ping", "POST", (ex, me) -> {
			parties.share(me, "ping", JsonHttp.readBody(ex, ShareRequest.class).payload());
			JsonHttp.writeJson(ex, 200, new Ok(true));
		});
		// Live positions: members who turned sharing on post theirs (null payload = stop); the map polls the others'.
		SocialRoutes.route(server, registry, "/v1/party/position", "POST", (ex, me) -> {
			parties.setPosition(me, JsonHttp.readBody(ex, ShareRequest.class).payload());
			JsonHttp.writeJson(ex, 200, new Ok(true));
		});
		SocialRoutes.route(server, registry, "/v1/party/positions", "GET", (ex, me) ->
				JsonHttp.writeJson(ex, 200, new PositionsResponse(parties.positions(me))));
		SocialRoutes.route(server, registry, "/v1/party/waypoint", "POST", (ex, me) -> {
			parties.share(me, "waypoint", JsonHttp.readBody(ex, ShareRequest.class).payload());
			JsonHttp.writeJson(ex, 200, new Ok(true));
		});
	}
}
