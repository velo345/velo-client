package net.veloclient.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.veloclient.server.store.StoreService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tester programme (see {@link TesterService}):
 * <ul>
 * <li>{@code GET /v1/tester/me}, {@code GET /v1/tester/builds}, {@code GET /v1/tester/file?version=&name=},
 * {@code POST /v1/tester/event} - signed in, testers and owners only</li>
 * <li>{@code GET /v1/tester/overview} - owners: who downloaded, launched, played and tested what</li>
 * <li>{@code POST /v1/admin/tester/file?version=&name=}, {@code POST /v1/admin/tester/notes?version=} - CI
 * ({@code X-Velo-Tester-Token}, or the admin token)</li>
 * <li>{@code POST /v1/admin/tester/sync} {@code {"discordIds": [...]}} - the bot (admin token)</li>
 * </ul>
 */
final class TesterRoutes {

	private TesterRoutes() {
	}

	private interface Authed {
		void handle(HttpExchange ex, SessionRegistry.Session session, boolean owner) throws Exception;
	}

	private interface Plain {
		void handle(HttpExchange ex) throws Exception;
	}

	static void register(HttpServer server, SessionRegistry registry, TesterService tester, StoreService store, String adminToken,
			String uploadToken) {
		authed(server, registry, tester, store, "/v1/tester/me", "GET", false, (ex, s, owner) ->
				JsonHttp.writeJson(ex, 200, Map.of("tester", tester.isTester(s.uuid()), "owner", owner, "mine", tester.mine(s.uuid()))));
		authed(server, registry, tester, store, "/v1/tester/builds", "GET", true, (ex, s, owner) ->
				JsonHttp.writeJson(ex, 200, Map.of("builds", tester.builds())));
		authed(server, registry, tester, store, "/v1/tester/file", "GET", true, (ex, s, owner) -> {
			Map<String, String> q = StoreRoutes.query(ex);
			var path = tester.filePath(q.get("version"), q.get("name"));
			if (path == null || !java.nio.file.Files.isRegularFile(path) || q.get("name").equals("notes.json")) {
				JsonHttp.writeError(ex, 404, "No such file");
				return;
			}
			tester.downloaded(s.uuid(), q.get("version"), q.get("name"));
			JsonHttp.writeFile(ex, path, java.nio.file.Files.size(path), "application/octet-stream", null, "no-store");
		});
		authed(server, registry, tester, store, "/v1/tester/event", "POST", true, (ex, s, owner) -> {
			JsonObject body = JsonParser.parseString(new String(JsonHttp.readRawBody(ex, 64 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
			try {
				tester.event(s.uuid(), body);
				JsonHttp.writeJson(ex, 200, Map.of("ok", true));
			} catch (IllegalArgumentException e) {
				JsonHttp.writeError(ex, 400, e.getMessage());
			}
		});
		authed(server, registry, tester, store, "/v1/tester/overview", "GET", true, (ex, s, owner) -> {
			if (!owner) {
				JsonHttp.writeError(ex, 403, "Owners only");
				return;
			}
			JsonHttp.writeJson(ex, 200, tester.overview());
		});

		// The Tester window: reports (a broken test item, a bug, a suggestion) with logs, then their screenshots / videos.
		authed(server, registry, tester, store, "/v1/tester/report", "POST", true, (ex, s, owner) -> {
			JsonObject body = JsonParser.parseString(new String(JsonHttp.readRawBody(ex, 12 * 1024 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
			try {
				TesterService.Report r = tester.addReport(s.uuid(), s.username(), body);
				JsonHttp.writeJson(ex, 200, Map.of("id", r.id));
			} catch (IllegalArgumentException e) {
				JsonHttp.writeError(ex, 400, e.getMessage());
			}
		});
		authed(server, registry, tester, store, "/v1/tester/attachment", "POST", true, (ex, s, owner) -> {
			Map<String, String> q = StoreRoutes.query(ex);
			try {
				long size = tester.putAttachment(s.uuid(), owner, q.get("report"), q.get("name"), ex.getRequestBody());
				JsonHttp.writeJson(ex, 200, Map.of("stored", q.get("name"), "size", size));
			} catch (IllegalArgumentException e) {
				JsonHttp.writeError(ex, 400, e.getMessage());
			}
		});
		authed(server, registry, tester, store, "/v1/tester/reports", "GET", true, (ex, s, owner) -> {
			if (!owner) {
				JsonHttp.writeError(ex, 403, "Owners only");
				return;
			}
			JsonHttp.writeJson(ex, 200, Map.of("reports", tester.reports()));
		});
		authed(server, registry, tester, store, "/v1/tester/report/status", "POST", true, (ex, s, owner) -> {
			Map<String, String> q = StoreRoutes.query(ex);
			if (!owner) {
				JsonHttp.writeError(ex, 403, "Owners only");
				return;
			}
			JsonHttp.writeJson(ex, tester.setReportStatus(q.get("id"), q.get("status")) ? 200 : 404, Map.of("ok", true));
		});
		authed(server, registry, tester, store, "/v1/tester/report/file", "GET", true, (ex, s, owner) -> {
			Map<String, String> q = StoreRoutes.query(ex);
			if (!owner) {
				JsonHttp.writeError(ex, 403, "Owners only");
				return;
			}
			var path = tester.reportFile(q.get("id"), q.get("name"));
			if (path == null || !java.nio.file.Files.isRegularFile(path)) {
				JsonHttp.writeError(ex, 404, "No such file");
				return;
			}
			ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + path.getFileName() + "\"");
			JsonHttp.writeFile(ex, path, java.nio.file.Files.size(path), "application/octet-stream", null, "no-store");
		});

		upload(server, adminToken, uploadToken, "/v1/admin/tester/file", ex -> {
			Map<String, String> q = StoreRoutes.query(ex);
			try {
				long size = tester.putFile(q.get("version"), q.get("name"), ex.getRequestBody());
				System.out.println("[tester] stored " + q.get("version") + "/" + q.get("name") + " (" + size / 1024 + " KB)");
				JsonHttp.writeJson(ex, 200, Map.of("stored", q.get("name"), "size", size));
			} catch (IllegalArgumentException e) {
				JsonHttp.writeError(ex, 400, e.getMessage());
			}
		});
		upload(server, adminToken, uploadToken, "/v1/admin/tester/notes", ex -> {
			Map<String, String> q = StoreRoutes.query(ex);
			try {
				tester.putNotes(q.get("version"), new String(JsonHttp.readRawBody(ex, 256 * 1024), StandardCharsets.UTF_8));
				JsonHttp.writeJson(ex, 200, Map.of("ok", true));
			} catch (IllegalArgumentException | IllegalStateException e) {
				JsonHttp.writeError(ex, 400, e.getMessage());
			}
		});
		// Discord /tester: personal download links (24 h) for the newest build - how a tester gets the launcher at first.
		upload(server, adminToken, null, "/v1/admin/tester/link", ex -> {
			JsonObject body = JsonParser.parseString(new String(JsonHttp.readRawBody(ex, 4096), StandardCharsets.UTF_8)).getAsJsonObject();
			String discordId = body.has("discordId") ? body.get("discordId").getAsString() : "";
			TesterService.Tester t = tester.testerByDiscord(discordId);
			if (t == null) {
				JsonHttp.writeError(ex, 404, "not-tester");
				return;
			}
			long until = System.currentTimeMillis() + 24 * 3600_000L;
			String token = sign(adminToken, t.uuid + ":" + until);
			String base = "https://api.velo-client.com/v1/tester/get?t=" + token + "&os=";
			Map<String, Object> links = new java.util.LinkedHashMap<>();
			for (String os : List.of("windows", "mac-arm", "mac-intel", "linux-deb", "linux-tar")) {
				if (tester.newestInstaller(os) != null) {
					links.put(os, base + os);
				}
			}
			List<Map<String, Object>> builds = tester.builds();
			JsonHttp.writeJson(ex, 200, Map.of("name", t.name, "links", links, "version", builds.isEmpty() ? "" : builds.get(0).get("version")));
		});
		server.createContext("/v1/tester/get", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			Map<String, String> q = StoreRoutes.query(ex);
			String uuid = verify(adminToken, q.get("t"));
			if (uuid == null || !tester.isTester(uuid)) {
				JsonHttp.writeError(ex, 403, "This download link has expired - type /tester in the Velo Discord for a new one.");
				return;
			}
			java.nio.file.Path file = tester.newestInstaller(q.get("os"));
			if (file == null || !java.nio.file.Files.isRegularFile(file)) {
				JsonHttp.writeError(ex, 404, "No tester build for this system yet");
				return;
			}
			tester.downloaded(uuid, file.getParent().getFileName().toString(), file.getFileName().toString());
			ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"");
			JsonHttp.writeFile(ex, file, java.nio.file.Files.size(file), "application/octet-stream", null, "no-store");
		}));
		upload(server, adminToken, null, "/v1/admin/tester/sync", ex -> {
			JsonObject body = JsonParser.parseString(new String(JsonHttp.readRawBody(ex, 512 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
			List<String> ids = new ArrayList<>();
			if (body.has("discordIds")) {
				for (var e : body.getAsJsonArray("discordIds")) {
					ids.add(e.getAsString());
				}
			}
			JsonHttp.writeJson(ex, 200, tester.sync(ids));
		});
	}

	/** Signed in; {@code testersOnly}: testers and owners (everyone else gets a plain 404, as if it didn't exist). */
	private static void authed(HttpServer server, SessionRegistry registry, TesterService tester, StoreService store, String path,
			String method, boolean testersOnly, Authed handler) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, method, ex -> {
			String token = JsonHttp.bearerToken(ex);
			SessionRegistry.Session session = registry.session(token);
			if (session == null) {
				JsonHttp.writeError(ex, 401, "Unknown or expired session - re-authenticate");
				return;
			}
			registry.touch(token);
			boolean owner = store.isOwner(session.uuid(), session.username());
			if (testersOnly && !owner && !tester.isTester(session.uuid())) {
				JsonHttp.writeError(ex, 404, "Not found");
				return;
			}
			handler.handle(ex, session, owner);
		}));
	}

	/** CI / bot: the admin token, or (when given) the tester upload token that can only upload builds. */
	private static void upload(HttpServer server, String adminToken, String uploadToken, String path, Plain handler) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
			String admin = ex.getRequestHeaders().getFirst("X-Velo-Admin-Token");
			String upload = ex.getRequestHeaders().getFirst("X-Velo-Tester-Token");
			boolean ok = matches(adminToken, admin) || matches(uploadToken, upload);
			if (!ok) {
				JsonHttp.writeError(ex, 403, "Forbidden");
				return;
			}
			handler.handle(ex);
		}));
	}

	private static String sign(String key, String payload) {
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(("tester-link:" + key).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			String sig = java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
			return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sig;
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The uuid a link token belongs to, or null when it's forged or expired. */
	private static String verify(String key, String token) {
		if (key == null || token == null || !token.contains(".")) {
			return null;
		}
		try {
			String payload = new String(java.util.Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))), StandardCharsets.UTF_8);
			if (!java.security.MessageDigest.isEqual(sign(key, payload).getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
				return null;
			}
			String[] parts = payload.split(":");
			return parts.length == 2 && Long.parseLong(parts[1]) > System.currentTimeMillis() ? parts[0] : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static boolean matches(String expected, String given) {
		return expected != null && !expected.isBlank() && given != null
				&& java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
	}
}
