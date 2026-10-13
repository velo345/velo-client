package net.veloclient.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.veloclient.server.store.StoreService;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Community schematics (see {@link SchematicShareService}):
 * <ul>
 * <li>{@code GET /v1/schematics?sort=hot|new|top|mine&q=&tag=&page=} - a page of uploads (a session adds your votes)</li>
 * <li>{@code POST /v1/schematics/upload} - share one (game session): title, description, tags, fileName, base64 file + preview</li>
 * <li>{@code GET /v1/schematics/<id>/preview} - its preview PNG</li>
 * <li>{@code GET /v1/schematics/<id>/file} - the file itself (counts a download)</li>
 * <li>{@code POST /v1/schematics/<id>/vote} - {@code {"up": true}} to upvote, false to take it back</li>
 * <li>{@code POST /v1/schematics/<id>/delete} - your own, or any for an owner</li>
 * </ul>
 */
final class SchematicRoutes {

	private SchematicRoutes() {
	}

	private record UploadRequest(String title, String description, List<String> tags, String fileName, String file, String preview) {
	}

	private record VoteRequest(Boolean up) {
	}

	private record OkResponse(boolean ok) {
	}

	static void register(HttpServer server, SessionRegistry registry, SchematicShareService schematics, StoreService store) {
		server.createContext("/v1/schematics", ex -> VeloServerApp.handle(ex, "GET", e -> {
			Map<String, String> query = query(e);
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(e));
			int page = 0;
			try {
				page = Integer.parseInt(query.getOrDefault("page", "0"));
			} catch (NumberFormatException ignored) {
				// First page.
			}
			JsonHttp.writeJson(e, 200, schematics.list(query.get("sort"), query.get("q"), query.get("tag"), page,
					session == null ? null : session.uuid()));
		}));
		server.createContext("/v1/schematics/upload", ex -> VeloServerApp.handle(ex, "POST", e -> {
			SessionRegistry.Session session = gameSession(e, registry);
			if (session == null) {
				return;
			}
			int maxBody = (SchematicShareService.MAX_FILE_BYTES + SchematicShareService.MAX_PREVIEW_BYTES) * 4 / 3 + 64 * 1024;
			UploadRequest request = JsonHttp.readBody(e, UploadRequest.class, maxBody);
			byte[] file;
			byte[] preview;
			try {
				file = Base64.getDecoder().decode(request.file() == null ? "" : request.file());
				preview = Base64.getDecoder().decode(request.preview() == null ? "" : request.preview());
			} catch (IllegalArgumentException bad) {
				JsonHttp.writeError(e, 400, "Malformed upload");
				return;
			}
			JsonHttp.writeJson(e, 200, schematics.upload(session.uuid(), session.username(), request.title(), request.description(),
					request.tags(), request.fileName(), file, preview));
		}));
		server.createContext("/v1/schematics/", ex -> {
			String path = ex.getRequestURI().getPath().substring("/v1/schematics/".length());
			String[] parts = path.split("/");
			String id = parts[0];
			String action = parts.length > 1 ? parts[1] : "";
			if (!id.matches("[0-9a-f]{16}")) {
				VeloServerApp.handle(ex, ex.getRequestMethod(), e -> JsonHttp.writeError(e, 404, "No such schematic"));
				return;
			}
			switch (action) {
				case "preview" -> VeloServerApp.handle(ex, "GET", e -> {
					byte[] png = schematics.preview(id);
					if (png == null) {
						JsonHttp.writeError(e, 404, "No such schematic");
					} else {
						JsonHttp.writeBytes(e, 200, "image/png", png, "public, max-age=86400");
					}
				});
				case "file" -> VeloServerApp.handle(ex, "GET", e -> {
					Object[] file = schematics.download(id);
					if (file == null) {
						JsonHttp.writeError(e, 404, "No such schematic");
						return;
					}
					e.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + file[1] + "\"");
					e.getResponseHeaders().set("X-Velo-File-Name", (String) file[1]);
					JsonHttp.writeBytes(e, 200, "application/octet-stream", (byte[]) file[0], "no-store");
				});
				case "vote" -> VeloServerApp.handle(ex, "POST", e -> {
					SessionRegistry.Session session = gameSession(e, registry);
					if (session == null) {
						return;
					}
					VoteRequest request = JsonHttp.readBody(e, VoteRequest.class);
					JsonHttp.writeJson(e, 200, schematics.vote(id, session.uuid(), request.up() == null || request.up()));
				});
				case "delete" -> VeloServerApp.handle(ex, "POST", e -> {
					SessionRegistry.Session session = gameSession(e, registry);
					if (session == null) {
						return;
					}
					JsonHttp.writeJson(e, 200, new OkResponse(schematics.delete(id, session.uuid(), store.isOwner(session.uuid(), session.username()))));
				});
				default -> VeloServerApp.handle(ex, ex.getRequestMethod(), e -> JsonHttp.writeError(e, 404, "Not found"));
			}
		});
	}

	/** The signed-in session, or null after answering 401. */
	private static SessionRegistry.Session gameSession(HttpExchange exchange, SessionRegistry registry) throws java.io.IOException {
		SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(exchange));
		if (session == null) {
			JsonHttp.writeError(exchange, 401, "Sign in to Velo Network first (start the game with a Microsoft account)");
		}
		return session;
	}

	private static Map<String, String> query(HttpExchange exchange) {
		Map<String, String> out = new HashMap<>();
		String raw = exchange.getRequestURI().getRawQuery();
		if (raw == null) {
			return out;
		}
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		return out;
	}
}
