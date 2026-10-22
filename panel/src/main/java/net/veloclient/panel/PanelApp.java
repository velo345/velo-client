package net.veloclient.panel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * The Velo owner panel (panel.velo-client.com): live players, installs and purchases with charts, the launcher's
 * owner tools (cosmetics, bundles, players, news, reports, Addons), the machine's CPU/RAM/disk/network, service
 * restarts with logs, and a file manager for the server's data - behind a password plus TOTP sign-in.
 *
 * <p>Runs on 127.0.0.1 behind nginx (TLS, rate limits, headers). The Velo server's admin token stays here: the
 * browser only talks to {@code /api/velo/...}, which this process forwards with the token added.
 *
 * <p>{@code java -jar velo-panel.jar adduser <name>} creates an account (password from {@code PANEL_NEW_PASSWORD}
 * or typed in) and prints the TOTP secret for an authenticator app.
 */
public final class PanelApp {

	/** Public Velo routes the panel may read besides everything under /v1/admin/. */
	private static final Set<String> PUBLIC_VELO = Set.of("health", "online", "store/catalog", "cosmetics/catalog", "news", "plus/status");

	private static Auth auth;
	private static final ProofOfWork POW = new ProofOfWork();
	private static Audit audit;
	private static Metrics metrics;
	private static Services services;
	private static FileManager files;
	private static String origin;
	private static String veloUrl;
	private static String adminToken;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private PanelApp() {
	}

	public static void main(String[] args) throws Exception {
		Path users = Path.of(env("PANEL_USERS_FILE", "/etc/velo-panel/users.json"));
		if (args.length >= 2 && args[0].equals("adduser")) {
			addUser(users, args[1]);
			return;
		}
		audit = new Audit(Path.of(env("PANEL_AUDIT_LOG", "audit.log")));
		auth = new Auth(users, audit);
		metrics = new Metrics();
		metrics.start();
		services = new Services(Arrays.stream(env("PANEL_SERVICES", "velo-server,velo-bot,velo-panel,nginx").split(","))
				.map(String::trim).filter(s -> !s.isEmpty()).toList(), env("PANEL_SELF", "velo-panel"));
		files = new FileManager(env("PANEL_FILE_ROOTS", null));
		origin = env("PANEL_ORIGIN", "https://panel.velo-client.com");
		veloUrl = env("VELO_SERVER_URL", "http://127.0.0.1:8787").replaceAll("/+$", "");
		adminToken = env("VELO_ADMIN_TOKEN", null);
		if (adminToken == null) {
			System.out.println("[panel] VELO_ADMIN_TOKEN not set - the Velo pages will show errors");
		}

		int port = Integer.parseInt(env("PANEL_PORT", "8790"));
		HttpServer server = HttpServer.create(new InetSocketAddress(env("PANEL_BIND", "127.0.0.1"), port), 0);
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.createContext("/api/", PanelApp::api);
		server.createContext("/", PanelApp::staticFile);
		server.start();
		System.out.println("Velo panel on " + env("PANEL_BIND", "127.0.0.1") + ":" + port + " (origin " + origin + ", users " + users + ")");
	}

	private static String env(String name, String fallback) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? fallback : v.trim();
	}

	private static void addUser(Path users, String name) throws Exception {
		if (!name.matches("[A-Za-z0-9_.-]{2,32}")) {
			System.err.println("Names are 2-32 letters, digits, . _ -");
			System.exit(2);
		}
		char[] password;
		String fromEnv = System.getenv("PANEL_NEW_PASSWORD");
		if (fromEnv != null && !fromEnv.isBlank()) {
			password = fromEnv.toCharArray();
		} else {
			Console console = System.console();
			if (console == null) {
				System.err.println("Set PANEL_NEW_PASSWORD or run in a terminal");
				System.exit(2);
				return;
			}
			password = console.readPassword("Password for %s: ", name);
		}
		if (password.length < 12) {
			System.err.println("Use at least 12 characters");
			System.exit(2);
		}
		String secret = Auth.addUser(users, name, password);
		System.out.println("Account " + name + " saved in " + users);
		System.out.println("TOTP secret: " + secret);
		System.out.println("otpauth://totp/Velo%20Panel:" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "?secret=" + secret + "&issuer=Velo%20Panel");
	}

	// ---- Static UI ----

	private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
			"js", "text/javascript; charset=utf-8", "png", "image/png", "svg", "image/svg+xml", "ico", "image/x-icon", "woff2", "font/woff2");

	private static void staticFile(HttpExchange ex) throws IOException {
		try (ex) {
			String path = ex.getRequestURI().getPath();
			if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
				Http.error(ex, 405, "Method not allowed");
				return;
			}
			if (path.equals("/") || !path.contains(".")) {
				path = "/index.html";
			}
			if (!path.matches("/[a-z0-9_./-]+") || path.contains("..")) {
				Http.error(ex, 404, "Not found");
				return;
			}
			try (InputStream in = PanelApp.class.getResourceAsStream("/web" + path)) {
				if (in == null) {
					Http.error(ex, 404, "Not found");
					return;
				}
				byte[] body = in.readAllBytes();
				String ext = path.substring(path.lastIndexOf('.') + 1);
				securityHeaders(ex);
				ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
				ex.getResponseHeaders().set("Cache-Control", path.equals("/index.html") ? "no-cache" : "public, max-age=300");
				ex.sendResponseHeaders(200, body.length);
				try (OutputStream out = ex.getResponseBody()) {
					out.write(body);
				}
			}
		}
	}

	private static void securityHeaders(HttpExchange ex) {
		var h = ex.getResponseHeaders();
		h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; style-src 'self'; script-src 'self'; connect-src 'self'; "
				+ "font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'");
		h.set("X-Frame-Options", "DENY");
		h.set("X-Content-Type-Options", "nosniff");
		h.set("Referrer-Policy", "no-referrer");
		h.set("Cross-Origin-Opener-Policy", "same-origin");
		h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
	}

	// ---- API ----

	private record ServiceAction(String unit, String action) {
	}

	private record FileWrite(String root, String path, String text, Long expectedModified) {
	}

	private record FilePath(String root, String path, Boolean recursive) {
	}

	private record FileRename(String root, String from, String to) {
	}

	private static void api(HttpExchange ex) throws IOException {
		try (ex) {
			securityHeaders(ex);
			String path = ex.getRequestURI().getPath();
			String method = ex.getRequestMethod();
			try {
				if (path.equals("/api/challenge") && method.equals("GET")) {
					Http.json(ex, 200, POW.issue());
					return;
				}
				if (path.equals("/api/login") && method.equals("POST")) {
					String origin = ex.getRequestHeaders().getFirst("Origin");
					if (origin != null && !origin.equals(PanelApp.origin)) {
						throw new Http.Fail(403, "Cross-site request refused");
					}
					Auth.LoginRequest request = Http.read(ex, Auth.LoginRequest.class);
					// The bot check comes first: it costs us one hash, a password check costs 310 000.
					if (!POW.verify(request.pow())) {
						throw new Http.Fail(400, "The security check failed - reload the page and try again");
					}
					String token = auth.login(ex, request);
					ex.getResponseHeaders().add("Set-Cookie", auth.setCookie(token));
					Http.json(ex, 200, Map.of("ok", true));
					return;
				}
				Auth.Session session = auth.session(ex);
				if (session == null) {
					if (path.equals("/api/logout")) {
						ex.getResponseHeaders().add("Set-Cookie", Auth.clearCookie());
						Http.json(ex, 200, Map.of("ok", true));
						return;
					}
					throw new Http.Fail(401, "Signed out");
				}
				Auth.checkChange(ex, session, origin);
				route(ex, session, path, method);
			} catch (Http.Fail f) {
				Http.error(ex, f.status, f.getMessage());
			} catch (java.nio.file.NoSuchFileException e) {
				Http.error(ex, 404, "Not found");
			} catch (java.nio.file.AccessDeniedException e) {
				Http.error(ex, 403, "The panel isn't allowed to touch that file");
			} catch (Exception e) {
				System.out.println("[panel] " + path + ": " + e);
				Http.error(ex, 500, "Something went wrong: " + e.getClass().getSimpleName());
			}
		}
	}

	private static void route(HttpExchange ex, Auth.Session s, String path, String method) throws Exception {
		String ip = Http.clientIp(ex);
		Map<String, String> q = Http.query(ex);
		switch (method + " " + path) {
			case "GET /api/me" -> Http.json(ex, 200, Map.of("username", s.username(), "csrf", s.csrf(), "sessions", auth.sessionsOf(s.username()),
					"services", services.units(), "roots", files.roots()));
			case "POST /api/logout" -> {
				auth.logout(ex);
				ex.getResponseHeaders().add("Set-Cookie", Auth.clearCookie());
				Http.json(ex, 200, Map.of("ok", true));
			}
			case "POST /api/logout-all" -> {
				int n = auth.logoutAll(s.username());
				audit.log(s.username(), ip, "signed out everywhere", n + " sessions");
				ex.getResponseHeaders().add("Set-Cookie", Auth.clearCookie());
				Http.json(ex, 200, Map.of("ok", true));
			}
			case "GET /api/system" -> Http.json(ex, 200, metrics.snapshot(q.getOrDefault("range", "1h")));
			case "GET /api/processes" -> Http.json(ex, 200, Map.of("processes", Metrics.topProcesses(15)));
			case "GET /api/services" -> Http.json(ex, 200, Map.of("services", services.status()));
			case "POST /api/services/action" -> {
				ServiceAction a = Http.read(ex, ServiceAction.class);
				audit.log(s.username(), ip, "service " + a.action(), a.unit());
				Http.json(ex, 200, Map.of("result", services.act(a.unit(), a.action())));
			}
			case "GET /api/logs" -> Http.json(ex, 200, Map.of("text", services.logs(q.get("unit"),
					parseInt(q.get("lines"), 300), q.get("grep"))));
			case "GET /api/audit" -> Http.json(ex, 200, Map.of("entries", audit.recent(parseInt(q.get("limit"), 200))));
			case "GET /api/files/list" -> Http.json(ex, 200, Map.of("entries", files.list(q.get("root"), q.get("path"))));
			case "GET /api/files/read" -> Http.json(ex, 200, files.read(q.get("root"), q.get("path")));
			case "GET /api/files/download" -> {
				Path file = files.download(q.get("root"), q.get("path"));
				audit.log(s.username(), ip, "file download", q.get("root") + ":" + q.get("path"));
				ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
				ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\""
						+ file.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
				ex.getResponseHeaders().set("Cache-Control", "no-store");
				ex.sendResponseHeaders(200, Files.size(file));
				try (OutputStream out = ex.getResponseBody()) {
					Files.copy(file, out);
				}
			}
			case "POST /api/files/write" -> {
				FileWrite w = Http.read(ex, FileWrite.class);
				byte[] bytes = (w.text() == null ? "" : w.text()).getBytes(StandardCharsets.UTF_8);
				if (bytes.length > FileManager.MAX_EDIT_BYTES) {
					throw new Http.Fail(413, "Too big for the editor - upload it instead");
				}
				files.write(w.root(), w.path(), bytes, w.expectedModified());
				audit.log(s.username(), ip, "file save", w.root() + ":" + w.path());
				Http.json(ex, 200, Map.of("ok", true));
			}
			case "POST /api/files/upload" -> {
				byte[] bytes = Http.body(ex, FileManager.MAX_UPLOAD_BYTES);
				files.write(q.get("root"), q.get("path"), bytes, null);
				audit.log(s.username(), ip, "file upload", q.get("root") + ":" + q.get("path") + " (" + bytes.length + " bytes)");
				Http.json(ex, 200, Map.of("ok", true));
			}
			case "POST /api/files/mkdir" -> {
				FilePath p = Http.read(ex, FilePath.class);
				files.mkdir(p.root(), p.path());
				audit.log(s.username(), ip, "folder create", p.root() + ":" + p.path());
				Http.json(ex, 200, Map.of("ok", true));
			}
			case "POST /api/files/rename" -> {
				FileRename r = Http.read(ex, FileRename.class);
				files.rename(r.root(), r.from(), r.to());
				audit.log(s.username(), ip, "file rename", r.root() + ":" + r.from() + " -> " + r.to());
				Http.json(ex, 200, Map.of("ok", true));
			}
			case "POST /api/files/delete" -> {
				FilePath p = Http.read(ex, FilePath.class);
				int n = files.delete(p.root(), p.path(), Boolean.TRUE.equals(p.recursive()));
				audit.log(s.username(), ip, "file delete", p.root() + ":" + p.path() + " (" + n + ")");
				Http.json(ex, 200, Map.of("deleted", n));
			}
			default -> {
				if (path.startsWith("/api/velo/")) {
					velo(ex, s, path.substring("/api/velo/".length()), method);
					return;
				}
				throw new Http.Fail(404, "Unknown API call");
			}
		}
	}

	private static int parseInt(String value, int fallback) {
		try {
			return value == null ? fallback : Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** Forwards to the Velo server's admin API with the admin token added. */
	private static void velo(HttpExchange ex, Auth.Session s, String rest, String method) throws Exception {
		if (!rest.matches("[a-z0-9/_-]+") || rest.contains("//")) {
			throw new Http.Fail(400, "Invalid path");
		}
		boolean allowed = rest.startsWith("admin/") || rest.startsWith("staff/") || PUBLIC_VELO.contains(rest) && method.equals("GET")
				|| rest.matches("cape/[0-9a-f]{64}") && method.equals("GET")
				|| rest.matches("skins/[0-9a-f]{32}/(head\\.png|skin\\.png|meta)") && method.equals("GET")
				|| rest.startsWith("cosmetics/file/") && method.equals("GET") || rest.startsWith("news/image/") && method.equals("GET");
		if (!allowed || !(method.equals("GET") || method.equals("POST"))) {
			throw new Http.Fail(403, "Not available through the panel");
		}
		String query = ex.getRequestURI().getRawQuery();
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(veloUrl + "/v1/" + rest + (query == null ? "" : "?" + query)))
				.timeout(Duration.ofMinutes(5))
				.header("X-Velo-Admin-Token", adminToken == null ? "" : adminToken)
				.header("X-Real-IP", Http.clientIp(ex));
		if (method.equals("POST")) {
			byte[] body = Http.body(ex, 64L * 1024 * 1024);
			String type = ex.getRequestHeaders().getFirst("Content-Type");
			request.header("Content-Type", type == null ? "application/json" : type).POST(HttpRequest.BodyPublishers.ofByteArray(body));
			audit.log(s.username(), Http.clientIp(ex), "velo " + rest, summary(body, type));
		} else {
			request.GET();
		}
		HttpResponse<byte[]> response;
		try {
			response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
		} catch (IOException e) {
			throw new Http.Fail(502, "The Velo server isn't answering - is velo-server running?");
		}
		String type = response.headers().firstValue("Content-Type").orElse("application/json");
		Http.bytes(ex, response.statusCode(), type, response.body());
	}

	/** What the audit log keeps of a request body: short JSON as is, the size of anything else. */
	private static String summary(byte[] body, String type) {
		if (type != null && type.contains("json") && body.length <= 400) {
			return new String(body, StandardCharsets.UTF_8);
		}
		return body.length + " bytes";
	}
}
