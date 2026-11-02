package net.veloclient.server;

import com.sun.net.httpserver.HttpServer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code /v1/notes/*} for players (see {@link NotesService}) and {@code /v1/staff/notes*} for the team: list / read /
 * remove any shared note, which the staff panel and the owner panel both use. Reports go into the normal moderation
 * queue (kind "note"); blocking players from notes is the "note" block in {@code /v1/staff/blocks/set}.
 */
final class NotesRoutes {

	private NotesRoutes() {
	}

	private record SaveRequest(NotesService.NoteInput note) {
	}

	private record IdRequest(String id) {
	}

	private record CheckRequest(String id, String check, boolean done) {
	}

	private record LiveRequest(String id, String sid, NotesService.Stroke stroke) {
	}

	private record BoardRequest(String id, List<NotesService.Stroke> strokes, boolean clear, List<String> sids) {
	}

	private record ImageRequest(String id, String png) {
	}

	private record ReportRequest(String id, String category, String reason) {
	}

	private record RemoveRequest(String id, String note, Integer blockDays) {
	}

	static void register(HttpServer server, SessionRegistry registry, NotesService notes, ModerationService moderation, SocialService social,
			Function<String, String> levelOf) {
		SocialRoutes.route(server, registry, "/v1/notes/list", "GET", (ex, me) -> {
			String world = StoreRoutes.query(ex).get("server");
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("notes", notes.list(me, world));
			out.put("inbox", notes.inbox(me));
			out.put("blocked", moderation.blockOf(ModerationService.NOTE, me) != null);
			JsonHttp.writeJson(ex, 200, out);
		});
		SocialRoutes.route(server, registry, "/v1/notes/all", "GET", (ex, me) -> JsonHttp.writeJson(ex, 200,
				Map.of("notes", notes.list(me, null), "inbox", notes.inbox(me))));
		SocialRoutes.route(server, registry, "/v1/notes/save", "POST", (ex, me) ->
				JsonHttp.writeJson(ex, 200, notes.save(me, JsonHttp.readBody(ex, SaveRequest.class, 64 * 1024).note())));
		SocialRoutes.route(server, registry, "/v1/notes/check", "POST", (ex, me) -> {
			CheckRequest r = JsonHttp.readBody(ex, CheckRequest.class);
			JsonHttp.writeJson(ex, 200, notes.check(me, r.id(), r.check(), r.done()));
		});
		SocialRoutes.route(server, registry, "/v1/notes/board", "POST", (ex, me) -> {
			BoardRequest r = JsonHttp.readBody(ex, BoardRequest.class, 256 * 1024);
			JsonHttp.writeJson(ex, 200, notes.board(me, r.id(), r.strokes(), r.clear(), r.sids()));
		});
		SocialRoutes.route(server, registry, "/v1/notes/live", "POST", (ex, me) -> {
			LiveRequest r = JsonHttp.readBody(ex, LiveRequest.class, 64 * 1024);
			notes.live(me, r.id(), r.sid(), r.stroke());
			JsonHttp.writeJson(ex, 200, java.util.Map.of("ok", true));
		});
		SocialRoutes.route(server, registry, "/v1/notes/image", "POST", (ex, me) -> {
			ImageRequest r = JsonHttp.readBody(ex, ImageRequest.class, NotesService.MAX_IMAGE_BYTES * 2);
			JsonHttp.writeJson(ex, 200, notes.image(me, r.id(), r.png()));
		});
		SocialRoutes.route(server, registry, "/v1/notes/image-data", "GET", (ex, me) ->
				JsonHttp.writeJson(ex, 200, Map.of("png", notes.imageData(me, StoreRoutes.query(ex).get("id")))));
		SocialRoutes.route(server, registry, "/v1/notes/seen", "POST", (ex, me) -> {
			notes.seen(me, JsonHttp.readBody(ex, IdRequest.class).id());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		SocialRoutes.route(server, registry, "/v1/notes/delete", "POST", (ex, me) -> {
			notes.delete(me, JsonHttp.readBody(ex, IdRequest.class).id());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		SocialRoutes.route(server, registry, "/v1/notes/leave", "POST", (ex, me) -> {
			notes.leave(me, JsonHttp.readBody(ex, IdRequest.class).id());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		SocialRoutes.route(server, registry, "/v1/notes/report", "POST", (ex, me) -> {
			ReportRequest r = JsonHttp.readBody(ex, ReportRequest.class);
			NotesService.Note note = notes.get(r.id());
			if (note == null || notes.list(me, note.server).stream().noneMatch(v -> v.note().id.equals(note.id))) {
				throw new ApiError(404, "That note doesn't exist any more");
			}
			moderation.report(ModerationService.NOTE, note.id, note.title == null ? "Note" : note.title, note.owner, note.ownerName, me,
					social.usernameOf(me), r.category(), r.reason());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});

		// ---- Staff (team can read and remove; moderators and up can block) ----
		SocialRoutes.route(server, registry, "/v1/staff/notes", "GET", (ex, me) -> {
			ModerationRoutes.requireTeam(levelOf.apply(me));
			Map<String, String> q = StoreRoutes.query(ex);
			int limit = Math.max(1, Math.min(500, ModerationRoutes.parseInt(q.get("limit"), 150)));
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("notes", notes.staffList(q.get("q"), "1".equals(q.get("reported")), q.get("kind"), limit));
			out.put("total", notes.count());
			out.put("openReports", moderation.openCount(ModerationService.NOTE));
			JsonHttp.writeJson(ex, 200, out);
		});
		SocialRoutes.route(server, registry, "/v1/staff/notes/view", "GET", (ex, me) -> {
			ModerationRoutes.requireTeam(levelOf.apply(me));
			String id = StoreRoutes.query(ex).get("id");
			NotesService.Note note = notes.staffView(id);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("note", note);
			out.put("reports", moderation.reports(ModerationService.NOTE, "all").stream().filter(r -> r.target.equals(id)).toList());
			if (note.image > 0) {
				try {
					out.put("png", notes.imageData(null, id));
				} catch (ApiError ignored) {
					// picture file missing
				}
			}
			JsonHttp.writeJson(ex, 200, out);
		});
		SocialRoutes.route(server, registry, "/v1/staff/notes/remove", "POST", (ex, me) -> {
			String level = ModerationRoutes.requireTeam(levelOf.apply(me));
			RemoveRequest r = JsonHttp.readBody(ex, RemoveRequest.class);
			NotesService.Note note = notes.removeByStaff(r.id());
			String by = social.usernameOf(me);
			moderation.close(ModerationService.NOTE, note.id, "removed", by, r.note());
			if (r.blockDays() != null) {
				ModerationRoutes.requireRank(level, StaffService.MOD, "Only moderators and up can block players");
				moderation.block(ModerationService.NOTE, note.owner, r.blockDays(), r.note(), by);
			}
			moderation.log(by, "removed note", (note.title == null ? note.id : note.title) + " (" + note.ownerName + ")", r.note());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
	}
}
