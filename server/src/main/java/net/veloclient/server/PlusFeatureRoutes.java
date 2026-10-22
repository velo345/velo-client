package net.veloclient.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.veloclient.server.store.StoreService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Velo+ features that need the server:
 * <ul>
 * <li>Cloud Sync - {@code GET /v1/cloud/backups}, {@code POST /v1/cloud/backups/upload?device=&label=&auto=} (zip body),
 * {@code GET /v1/cloud/backups/file?id=}, {@code POST /v1/cloud/backups/{delete,pin}}</li>
 * <li>Clips Pro share links - {@code POST /v1/clips/upload?title=&seconds=&width=&height=&fps=&trigger=&server=} (MP4 body),
 * {@code POST /v1/clips/thumb?id=} (JPEG body), {@code GET /v1/clips/mine}, {@code POST /v1/clips/{delete,rename}},
 * {@code GET /v1/clips/info?id=}</li>
 * <li>The public share page {@code GET /c/<id>} (served on velo-client.com through nginx) with the video at
 * {@code /c/<id>.mp4} (HTTP ranges, so browsers can seek) and the thumbnail at {@code /c/<id>.jpg}</li>
 * </ul>
 * Making backups, restoring and sharing need Velo+; listing and deleting work for anyone signed in, so a lapsed member
 * can still tidy up.
 */
final class PlusFeatureRoutes {

	private PlusFeatureRoutes() {
	}

	private interface Authed {
		void handle(HttpExchange exchange, SessionRegistry.Session session) throws Exception;
	}

	private record IdRequest(String id) {
	}

	private record PinRequest(String id, boolean pinned, String label) {
	}

	private record RenameRequest(String id, String title) {
	}

	/** {@code client}: the Velo Client version (sent since 0.9.91). */
	private record WorldOpen(String name, String version, Boolean friendsCanJoin, String visibility, String description, Integer maxPlayers,
			List<Map<String, Object>> mods, String client) {
	}

	private record WorldMember(String uuid, Boolean ban) {
	}

	private record WorldJoin(String world, String client) {
	}

	/** World Hosting before 0.9.91 couldn't connect guests: those clients are asked to update instead of failing. */
	static final String WORLDS_MIN_CLIENT = "0.9.91";

	private static void requireFixedClient(String client) throws ApiError {
		if (UpdatePolicy.older(client, WORLDS_MIN_CLIENT)) {
			throw new ApiError(426, "World Hosting needs Velo Client " + WORLDS_MIN_CLIENT + " - update in the Velo launcher (Settings > Updates) and restart the game");
		}
	}

	private record WorldCode(String code, Boolean enabled) {
	}

	/**
	 * World Hosting (Velo+ to host, free to join): {@code POST /v1/worlds/{open,close,settings,invite,remove,join}},
	 * {@code GET /v1/worlds/mine}, {@code GET /v1/worlds/joinable}. The bytes go through {@link WorldHostService}'s relay.
	 */
	/** Reports and blocks (set before the routes are registered). */
	private static volatile ModerationService moderation;

	static void setModeration(ModerationService service) {
		moderation = service;
	}

	/** A player staff stopped from listing public worlds can still host for friends. */
	private static void checkListing(SessionRegistry.Session s, String visibility) throws ApiError {
		if (("public".equals(visibility) || "request".equals(visibility)) && moderation != null) {
			moderation.check(ModerationService.WORLD, s.uuid());
		}
	}

	static void registerWorlds(HttpServer server, SessionRegistry registry, StoreService store, WorldHostService worlds) {
		route(server, registry, "/v1/worlds/open", "POST", (ex, s) -> {
			requirePlus(store, s);
			WorldOpen r = JsonHttp.readBody(ex, WorldOpen.class);
			requireFixedClient(r.client());
			WorldHostService.World world = worlds.open(s.uuid(), s.username(), r.name(), r.version(), Boolean.TRUE.equals(r.friendsCanJoin()));
			checkListing(s, r.visibility());
			worlds.listing(world, r.visibility(), r.description(), r.maxPlayers());
			worlds.mods(world, r.mods());
			Map<String, Object> out = new LinkedHashMap<>(worlds.view(world));
			out.put("hostKey", world.hostKey);
			out.put("relayHost", worlds.publicHost());
			out.put("relayPort", worlds.port());
			JsonHttp.writeJson(ex, 200, out);
		});
		route(server, registry, "/v1/worlds/close", "POST", (ex, s) -> {
			worlds.close(s.uuid());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		route(server, registry, "/v1/worlds/mine", "GET", (ex, s) -> {
			WorldHostService.World world = worlds.worldOf(s.uuid());
			JsonHttp.writeJson(ex, 200, world == null ? Map.of("world", false) : Map.of("world", worlds.view(world)));
		});
		route(server, registry, "/v1/worlds/settings", "POST", (ex, s) -> {
			WorldHostService.World world = mine(worlds, s);
			WorldOpen r = JsonHttp.readBody(ex, WorldOpen.class);
			if (r.friendsCanJoin() != null) {
				world.friendsCanJoin = r.friendsCanJoin();
			}
			if (r.name() != null && CloudBackupService.clean(r.name(), 48) != null) {
				world.name = CloudBackupService.clean(r.name(), 48);
			}
			checkListing(s, r.visibility());
			worlds.listing(world, r.visibility(), r.description(), r.maxPlayers());
			JsonHttp.writeJson(ex, 200, worlds.view(world));
		});
		route(server, registry, "/v1/worlds/invite", "POST", (ex, s) -> {
			WorldHostService.World world = mine(worlds, s);
			worlds.invite(world, VeloServerApp.normalizeUuid(JsonHttp.readBody(ex, WorldMember.class).uuid()));
			JsonHttp.writeJson(ex, 200, worlds.view(world));
		});
		route(server, registry, "/v1/worlds/remove", "POST", (ex, s) -> {
			WorldHostService.World world = mine(worlds, s);
			WorldMember r = JsonHttp.readBody(ex, WorldMember.class);
			worlds.remove(world, VeloServerApp.normalizeUuid(r.uuid()), Boolean.TRUE.equals(r.ban()));
			JsonHttp.writeJson(ex, 200, worlds.view(world));
		});
		route(server, registry, "/v1/worlds/invite-party", "POST", (ex, s) -> {
			WorldHostService.World world = mine(worlds, s);
			int invited = worlds.invitePartyOf(world);
			Map<String, Object> out = new LinkedHashMap<>(worlds.view(world));
			out.put("invited", invited);
			JsonHttp.writeJson(ex, 200, out);
		});
		route(server, registry, "/v1/worlds/code", "POST", (ex, s) -> {
			WorldHostService.World world = mine(worlds, s);
			WorldCode r = JsonHttp.readBody(ex, WorldCode.class);
			if (Boolean.FALSE.equals(r.enabled())) {
				world.code = null;
			} else {
				worlds.newCode(world);
			}
			JsonHttp.writeJson(ex, 200, worlds.view(world));
		});
		route(server, registry, "/v1/worlds/knock", "POST", (ex, s) -> {
			limit(s, "world-knock", new RateLimiter.Window(5, 60_000));
			WorldHostService.World world = worlds.byCode(JsonHttp.readBody(ex, WorldCode.class).code());
			if (world == null) {
				throw new ApiError(404, "No open world has that code");
			}
			worlds.knock(world, s.uuid(), s.username());
			JsonHttp.writeJson(ex, 200, Map.of("world", world.id, "name", world.name, "hostName", world.hostName,
					"allowed", worlds.mayJoin(world, s.uuid())));
		});
		route(server, registry, "/v1/worlds/accept", "POST", (ex, s) -> {
			WorldHostService.World world = mine(worlds, s);
			worlds.let(world, VeloServerApp.normalizeUuid(JsonHttp.readBody(ex, WorldMember.class).uuid()));
			JsonHttp.writeJson(ex, 200, worlds.view(world));
		});
		// Discover: public worlds - open to anyone (no sign-in needed to look, the launcher shows them too).
		server.createContext("/v1/worlds/public", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			List<Map<String, Object>> out = new java.util.ArrayList<>();
			for (WorldHostService.World world : worlds.discover()) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("id", world.id);
				row.put("name", world.name);
				row.put("description", world.description);
				row.put("host", world.hostUuid);
				row.put("hostName", world.hostName);
				row.put("version", world.version == null ? "" : world.version);
				row.put("guests", world.guests.get());
				row.put("maxPlayers", world.maxPlayers);
				row.put("ask", "request".equals(world.visibility));
				out.add(row);
			}
			JsonHttp.writeJson(ex, 200, Map.of("worlds", out));
		}));
		// Ask-to-join from Discover (no code needed): the host gets a request.
		route(server, registry, "/v1/worlds/ask", "POST", (ex, s) -> {
			limit(s, "world-knock", new RateLimiter.Window(5, 60_000));
			WorldHostService.World world = worlds.byId(JsonHttp.readBody(ex, WorldJoin.class).world());
			if (world == null || "friends".equals(world.visibility)) {
				throw new ApiError(404, "That world isn't open any more");
			}
			worlds.knock(world, s.uuid(), s.username());
			JsonHttp.writeJson(ex, 200, Map.of("allowed", worlds.mayJoin(world, s.uuid())));
		});
		route(server, registry, "/v1/worlds/joinable", "GET", (ex, s) -> {
			List<Map<String, Object>> out = new java.util.ArrayList<>();
			for (WorldHostService.World world : worlds.joinableFor(s.uuid())) {
				out.add(Map.of("id", world.id, "name", world.name, "host", world.hostUuid, "hostName", world.hostName,
						"version", world.version == null ? "" : world.version, "guests", world.guests.get()));
			}
			JsonHttp.writeJson(ex, 200, Map.of("worlds", out));
		});
		route(server, registry, "/v1/worlds/join", "POST", (ex, s) -> {
			WorldJoin join = JsonHttp.readBody(ex, WorldJoin.class);
			requireFixedClient(join.client());
			WorldHostService.World world = worlds.byId(join.world());
			if (world == null) {
				throw new ApiError(404, "That world isn't open any more");
			}
			String ticket = worlds.ticket(world, s.uuid(), s.username());
			JsonHttp.writeJson(ex, 200, Map.of("ticket", ticket, "relayHost", worlds.publicHost(), "relayPort", worlds.port(),
					"name", world.name, "version", world.version == null ? "" : world.version, "hostName", world.hostName, "mods", world.mods));
		});
	}

	private record PartnerList(List<PartnerServers.Partner> partners) {
	}

	private record PolicyRequest(String minimum, String message) {
	}

	/** {@code GET /v1/update-policy} for everyone (launchers check it on start), {@code POST /v1/admin/update-policy} for the owner. */
	static void registerUpdatePolicy(HttpServer server, SessionRegistry registry, StoreService store, UpdatePolicy policy) {
		server.createContext("/v1/update-policy", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			UpdatePolicy.Policy p = policy.get();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("minimum", p.minimum());
			out.put("message", p.message());
			JsonHttp.writeJson(ex, 200, out);
		}));
		server.createContext("/v1/admin/update-policy", exchange -> VeloServerApp.handle(exchange, exchange.getRequestMethod(), ex -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(ex));
			if (session == null) {
				session = StoreRoutes.panelSession(ex);
			}
			if (session == null || !store.isOwner(session.uuid(), session.username())) {
				JsonHttp.writeError(ex, 403, "Only the server owner can do that");
				return;
			}
			UpdatePolicy.Policy p = policy.get();
			if ("POST".equals(ex.getRequestMethod())) {
				PolicyRequest r = JsonHttp.readBody(ex, PolicyRequest.class);
				p = policy.set(r.minimum(), r.message());
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("minimum", p.minimum());
			out.put("message", p.message());
			JsonHttp.writeJson(ex, 200, out);
		}));
	}

	/** Partner servers: {@code GET /v1/partners} for everyone, {@code GET/POST /v1/admin/partners} for the owner. */
	static void registerPartners(HttpServer server, SessionRegistry registry, StoreService store, PartnerServers partners) {
		server.createContext("/v1/partners", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			String path = ex.getRequestURI().getPath();
			// GET /v1/partners/icon/<id>?s=<px> - the owner's upload (up to 512x512), else the server's own 64x64 icon.
			if (path.startsWith("/v1/partners/icon/")) {
				byte[] icon = partners.icon(path.substring("/v1/partners/icon/".length()),
						parseInt(StoreRoutes.query(ex).getOrDefault("s", String.valueOf(PartnerServers.ICON_DEFAULT))));
				if (icon == null) {
					JsonHttp.writeError(ex, 404, "No icon");
					return;
				}
				ex.getResponseHeaders().set("Content-Type", "image/png");
				ex.getResponseHeaders().set("Cache-Control", "public, max-age=600");
				ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
				ex.sendResponseHeaders(200, icon.length);
				try (OutputStream out = ex.getResponseBody()) {
					out.write(icon);
				}
				return;
			}
			JsonHttp.writeJson(ex, 200, Map.of("partners", partners.views()));
		}));
		server.createContext("/v1/admin/partners/icon", exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(ex));
			if (session == null) {
				session = StoreRoutes.panelSession(ex);
			}
			if (session == null || !store.isOwner(session.uuid(), session.username())) {
				JsonHttp.writeError(ex, 403, "Only the server owner can do that");
				return;
			}
			byte[] png = JsonHttp.readRawBody(ex, 4 * 1024 * 1024);
			partners.setIcon(StoreRoutes.query(ex).get("id"), png);
			JsonHttp.writeJson(ex, 200, Map.of("partners", partners.views()));
		}));
		server.createContext("/v1/admin/partners", exchange -> VeloServerApp.handle(exchange, exchange.getRequestMethod(), ex -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(ex));
			if (session == null) {
				session = StoreRoutes.panelSession(ex);
			}
			if (session == null || !store.isOwner(session.uuid(), session.username())) {
				JsonHttp.writeError(ex, 403, "Only the server owner can do that");
				return;
			}
			if ("POST".equals(ex.getRequestMethod())) {
				PartnerList body = JsonHttp.readBody(ex, PartnerList.class);
				partners.save(body.partners());
				Thread.ofVirtual().start(partners::refreshStatus);
				JsonHttp.writeJson(ex, 200, Map.of("partners", partners.views()));
			} else {
				JsonHttp.writeJson(ex, 200, Map.of("partners", partners.views()));
			}
		}));
	}

	private static WorldHostService.World mine(WorldHostService worlds, SessionRegistry.Session s) throws ApiError {
		WorldHostService.World world = worlds.worldOf(s.uuid());
		if (world == null) {
			throw new ApiError(404, "You aren't hosting a world");
		}
		return world;
	}

	private static final RateLimiter LIMITS = new RateLimiter();

	static void register(HttpServer server, SessionRegistry registry, StoreService store, CloudBackupService cloud, ClipShareService clips,
			String siteUrl) {
		// ---- Cloud Sync ----
		route(server, registry, "/v1/cloud/backups", "GET", (ex, s) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("plus", store.isPlus(s.uuid()));
			out.put("backups", cloud.list(s.uuid()));
			out.put("usedBytes", cloud.usedBytes(s.uuid()));
			out.put("maxBytes", CloudBackupService.MAX_TOTAL_BYTES);
			out.put("maxCount", CloudBackupService.MAX_COUNT);
			out.put("maxPinned", CloudBackupService.MAX_PINNED);
			JsonHttp.writeJson(ex, 200, out);
		});
		route(server, registry, "/v1/cloud/backups/upload", "POST", (ex, s) -> {
			requirePlus(store, s);
			limit(s, "cloud-upload", new RateLimiter.Window(6, 60_000), new RateLimiter.Window(120, 86_400_000));
			Map<String, String> q = StoreRoutes.query(ex);
			byte[] zip = JsonHttp.readRawBody(ex, CloudBackupService.MAX_BACKUP_BYTES);
			JsonHttp.writeJson(ex, 200, cloud.add(s.uuid(), zip, q.get("device"), q.get("label"), "1".equals(q.get("auto"))));
		});
		route(server, registry, "/v1/cloud/backups/file", "GET", (ex, s) -> {
			requirePlus(store, s);
			JsonHttp.writeBytes(ex, 200, "application/zip", cloud.file(s.uuid(), StoreRoutes.query(ex).get("id")), "no-store");
		});
		route(server, registry, "/v1/cloud/backups/delete", "POST", (ex, s) -> {
			cloud.delete(s.uuid(), JsonHttp.readBody(ex, IdRequest.class).id());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		route(server, registry, "/v1/cloud/backups/pin", "POST", (ex, s) -> {
			PinRequest r = JsonHttp.readBody(ex, PinRequest.class);
			JsonHttp.writeJson(ex, 200, cloud.pin(s.uuid(), r.id(), r.pinned(), r.label()));
		});

		// ---- Clips Pro ----
		clipService = clips;
		route(server, registry, "/v1/clips/upload", "POST", (ex, s) -> {
			requirePlus(store, s);
			if (moderation != null) {
				moderation.check(ModerationService.CLIP, s.uuid());
			}
			limit(s, "clip-upload", new RateLimiter.Window(3, 60_000), new RateLimiter.Window(60, 86_400_000));
			Map<String, String> q = StoreRoutes.query(ex);
			String lengthHeader = ex.getRequestHeaders().getFirst("Content-Length");
			long length = lengthHeader == null ? -1 : Long.parseLong(lengthHeader.trim());
			ClipShareService.Meta meta = new ClipShareService.Meta(q.get("title"), parseDouble(q.get("seconds")), parseInt(q.get("width")),
					parseInt(q.get("height")), parseInt(q.get("fps")), q.get("trigger"), q.get("server"));
			ClipShareService.Clip clip = clips.upload(s.uuid(), s.username(), meta, ex.getRequestBody(), length);
			JsonHttp.writeJson(ex, 200, view(clip, siteUrl));
		});
		route(server, registry, "/v1/clips/thumb", "POST", (ex, s) -> {
			byte[] jpeg = JsonHttp.readRawBody(ex, ClipShareService.MAX_THUMB_BYTES);
			JsonHttp.writeJson(ex, 200, view(clips.thumbnail(s.uuid(), StoreRoutes.query(ex).get("id"), jpeg), siteUrl));
		});
		route(server, registry, "/v1/clips/mine", "GET", (ex, s) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("plus", store.isPlus(s.uuid()));
			out.put("clips", clips.of(s.uuid()).stream().map(c -> view(c, siteUrl)).toList());
			out.put("usedBytes", clips.usedBytes(s.uuid()));
			out.put("maxBytes", ClipShareService.MAX_USER_BYTES);
			out.put("maxClipBytes", ClipShareService.MAX_CLIP_BYTES);
			out.put("retentionDays", clips.retentionDays());
			JsonHttp.writeJson(ex, 200, out);
		});
		route(server, registry, "/v1/clips/delete", "POST", (ex, s) -> {
			clips.delete(s.uuid(), JsonHttp.readBody(ex, IdRequest.class).id(), store.isOwner(s.uuid(), s.username()));
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		route(server, registry, "/v1/clips/rename", "POST", (ex, s) -> {
			RenameRequest r = JsonHttp.readBody(ex, RenameRequest.class);
			JsonHttp.writeJson(ex, 200, view(clips.rename(s.uuid(), r.id(), r.title()), siteUrl));
		});
		server.createContext("/v1/clips/info", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			ClipShareService.Clip clip = clips.get(StoreRoutes.query(ex).get("id"));
			if (clip == null) {
				JsonHttp.writeError(ex, 404, "No such clip");
				return;
			}
			JsonHttp.writeJson(ex, 200, view(clip, siteUrl));
		}));

		// ---- Share page + media ----
		server.createContext("/c/", exchange -> {
			String method = exchange.getRequestMethod();
			VeloServerApp.handle(exchange, "HEAD".equals(method) ? "HEAD" : "GET", ex -> serveShare(ex, clips, siteUrl));
		});
	}

	private static volatile ClipShareService clipService;

	/** What the game and the launcher get for a clip: everything plus the share link and when it's deleted. */
	static Map<String, Object> view(ClipShareService.Clip clip, String siteUrl) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", clip.id());
		out.put("url", siteUrl + "/c/" + clip.id());
		out.put("title", clip.title());
		out.put("username", clip.username());
		out.put("createdAt", clip.createdAt());
		out.put("seconds", clip.seconds());
		out.put("width", clip.width());
		out.put("height", clip.height());
		out.put("fps", clip.fps());
		out.put("size", clip.size());
		out.put("views", clip.views());
		out.put("thumb", clip.thumb());
		ClipShareService service = clipService;
		if (service != null) {
			out.put("expiresAt", service.expiresAt(clip));
		}
		return out;
	}

	private static void serveShare(HttpExchange ex, ClipShareService clips, String siteUrl) throws IOException {
		String name = ex.getRequestURI().getPath().substring("/c/".length());
		if (name.endsWith(".mp4")) {
			Path video = clips.video(name.substring(0, name.length() - 4));
			if (video == null || !Files.exists(video)) {
				JsonHttp.writeError(ex, 404, "No such clip");
				return;
			}
			serveFile(ex, video, "video/mp4");
			return;
		}
		if (name.endsWith(".jpg")) {
			Path thumb = clips.thumb(name.substring(0, name.length() - 4));
			if (thumb == null || !Files.exists(thumb)) {
				JsonHttp.writeError(ex, 404, "No thumbnail");
				return;
			}
			serveFile(ex, thumb, "image/jpeg");
			return;
		}
		ClipShareService.Clip clip = name.matches("[0-9A-Za-z]{10}") ? clips.get(name) : null;
		if (clip != null && "GET".equals(ex.getRequestMethod())) {
			clips.countView(clip.id());
		}
		byte[] html = (clip == null ? ClipPage.missing(siteUrl) : ClipPage.render(clip, siteUrl)).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		ex.getResponseHeaders().set("Cache-Control", "no-cache");
		ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; media-src 'self'; style-src 'self' 'unsafe-inline';"
				+ " script-src 'self'; frame-ancestors 'none'; base-uri 'none'");
		ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		int status = clip == null ? 404 : 200;
		if ("HEAD".equals(ex.getRequestMethod())) {
			ex.sendResponseHeaders(status, -1);
			return;
		}
		ex.sendResponseHeaders(status, html.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(html);
		}
	}

	/** A file with HTTP range support (browsers need 206 answers to seek in a video). */
	private static void serveFile(HttpExchange ex, Path file, String contentType) throws IOException {
		long size = Files.size(file);
		long start = 0;
		long end = size - 1;
		String range = ex.getRequestHeaders().getFirst("Range");
		boolean partial = false;
		if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
			String[] parts = range.substring(6).trim().split("-", -1);
			try {
				if (parts[0].isEmpty()) {
					start = Math.max(0, size - Long.parseLong(parts[1]));
				} else {
					start = Long.parseLong(parts[0]);
					if (parts.length > 1 && !parts[1].isEmpty()) {
						end = Math.min(end, Long.parseLong(parts[1]));
					}
				}
				partial = true;
			} catch (NumberFormatException e) {
				partial = false;
				start = 0;
			}
			if (partial && (start > end || start >= size)) {
				ex.getResponseHeaders().set("Content-Range", "bytes */" + size);
				ex.sendResponseHeaders(416, -1);
				return;
			}
		}
		long length = end - start + 1;
		ex.getResponseHeaders().set("Content-Type", contentType);
		ex.getResponseHeaders().set("Accept-Ranges", "bytes");
		ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
		ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		if (partial) {
			ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + size);
		}
		if ("HEAD".equals(ex.getRequestMethod())) {
			ex.getResponseHeaders().set("Content-Length", String.valueOf(length));
			ex.sendResponseHeaders(partial ? 206 : 200, -1);
			return;
		}
		ex.sendResponseHeaders(partial ? 206 : 200, length);
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ); OutputStream out = ex.getResponseBody()) {
			var target = Channels.newChannel(out);
			long position = start;
			long left = length;
			while (left > 0) {
				long sent = channel.transferTo(position, Math.min(left, 1024 * 1024), target);
				if (sent <= 0) {
					break;
				}
				position += sent;
				left -= sent;
			}
		} catch (IOException e) {
			// The viewer closed the tab or seeked elsewhere mid-transfer - normal for video.
		}
	}

	private static void requirePlus(StoreService store, SessionRegistry.Session s) throws ApiError {
		if (!store.isPlus(s.uuid())) {
			throw new ApiError(403, "This is part of Velo+");
		}
	}

	private static void limit(SessionRegistry.Session s, String action, RateLimiter.Window... windows) throws ApiError {
		long wait = LIMITS.tryAcquire(s.uuid(), action, windows);
		if (wait > 0) {
			throw new ApiError(429, "Slow down - try again in " + Math.max(1, wait / 1000) + " s");
		}
	}

	private static int parseInt(String value) {
		try {
			return value == null ? 0 : Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static double parseDouble(String value) {
		try {
			return value == null ? 0 : Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return 0;
		}
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
			handler.handle(ex, session);
		}));
	}

	/** The share page: the video big, who clipped it, copy/download buttons and a nudge to get Velo. */
	static final class ClipPage {

		private ClipPage() {
		}

		static String render(ClipShareService.Clip clip, String siteUrl) {
			String page = siteUrl + "/c/" + clip.id();
			String video = page + ".mp4";
			String image = clip.thumb() ? page + ".jpg" : siteUrl + "/assets/og-image.jpg";
			String quality = clip.height() > 0 ? clip.height() + "p" + (clip.fps() > 0 ? clip.fps() : "") : "";
			String length = duration(clip.seconds());
			String date = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(Instant.ofEpochMilli(clip.createdAt()).atZone(ZoneOffset.UTC));
			String description = length + " clip by " + clip.username() + (quality.isEmpty() ? "" : " · " + quality) + " · Velo Client";
			StringBuilder meta = new StringBuilder();
			meta.append(tag("og:type", "video.other")).append(tag("og:site_name", "Velo Client")).append(tag("og:title", clip.title()))
					.append(tag("og:description", description)).append(tag("og:url", page)).append(tag("og:image", image))
					.append(tag("og:video", video)).append(tag("og:video:secure_url", video)).append(tag("og:video:type", "video/mp4"));
			if (clip.width() > 0) {
				meta.append(tag("og:video:width", String.valueOf(clip.width()))).append(tag("og:video:height", String.valueOf(clip.height())));
			}
			meta.append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n");
			String chips = chip(length) + (quality.isEmpty() ? "" : chip(quality)) + chip(clip.views() + (clip.views() == 1 ? " view" : " views")) + chip(date);
			ClipShareService service = clipService;
			if (service != null) {
				chips += chip("Kept until " + DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
						.format(Instant.ofEpochMilli(service.expiresAt(clip)).atZone(ZoneOffset.UTC)));
			}
			return HEAD.replace("{{title}}", esc(clip.title()) + " · Velo Client").replace("{{description}}", esc(description))
					.replace("{{meta}}", meta.toString()).replace("{{canonical}}", esc(page))
					+ """
					<main class="wrap">
					  <div class="player"><video src="{{video}}" poster="{{poster}}" controls playsinline preload="metadata"{{ratio}}></video></div>
					  <section class="info">
					    <div class="who">
					      <img class="head" src="/v1/skins/{{uuid}}/head.png" width="48" height="48" alt="">
					      <div><h1>{{titleText}}</h1><p class="by">Clipped by <b>{{user}}</b> with Velo Client</p></div>
					    </div>
					    <div class="chips">{{chips}}</div>
					    <div class="actions">
					      <button class="btn primary" type="button" id="copy">Copy link</button>
					      <a class="btn" href="{{video}}" download="{{file}}">Download</a>
					      <button class="btn ghost" type="button" id="report" data-clip="{{id}}" title="Report this clip">Report</button>
					    </div>
					  </section>
					  <dialog id="report-dialog">
					    <form method="dialog" id="report-form">
					      <h2>Report this clip</h2>
					      <p class="by">Velo staff look at every report. Clips that break the rules are removed.</p>
					      <select name="category" required>
					        <option value="inappropriate">Inappropriate or NSFW</option><option value="harassment">Harassment or hate</option>
					        <option value="cheating">Shows cheating</option><option value="scam">Scam or phishing</option>
					        <option value="copyright">Not theirs / copyright</option><option value="spam">Spam</option><option value="other">Something else</option>
					      </select>
					      <textarea name="reason" maxlength="500" rows="3" placeholder="What's wrong with it? (optional)"></textarea>
					      <div class="actions"><button class="btn" value="cancel" formnovalidate>Cancel</button><button class="btn primary" value="send">Send report</button></div>
					      <p class="by" id="report-status"></p>
					    </form>
					  </dialog>
					  <aside class="cta">
					    <div><span class="pill">CLIPS PRO</span><h2>The last five minutes, always saved.</h2>
					    <p>Velo Client keeps a replay buffer at 1080p60, clips your kills and wins on its own and turns them into links like this one.</p></div>
					    <a class="btn primary big" href="/download">Get Velo Client - free</a>
					  </aside>
					</main>
					<script src="/assets/clip.js" defer></script>
					</body></html>
					""".replace("{{video}}", esc(video)).replace("{{poster}}", esc(image))
							.replace("{{ratio}}", clip.width() > 0 && clip.height() > 0 ? " style=\"aspect-ratio:" + clip.width() + "/" + clip.height() + "\"" : "")
							.replace("{{uuid}}", esc(clip.uuid())).replace("{{titleText}}", esc(clip.title())).replace("{{user}}", esc(clip.username()))
							.replace("{{chips}}", chips).replace("{{file}}", esc("velo-clip-" + clip.id() + ".mp4")).replace("{{id}}", esc(clip.id()));
		}

		static String missing(String siteUrl) {
			return HEAD.replace("{{title}}", "Clip not found · Velo Client").replace("{{description}}", "This clip was deleted or never existed.")
					.replace("{{meta}}", "<meta name=\"robots\" content=\"noindex\">\n").replace("{{canonical}}", esc(siteUrl))
					+ """
					<main class="wrap gone"><h1>This clip is gone.</h1><p>It was deleted, or the link is wrong.</p>
					<a class="btn primary big" href="/">Go to velo-client.com</a></main></body></html>
					""";
		}

		private static String tag(String property, String content) {
			return "<meta property=\"" + property + "\" content=\"" + esc(content) + "\">\n";
		}

		private static String chip(String text) {
			return "<span class=\"chip\">" + esc(text) + "</span>";
		}

		private static String duration(double seconds) {
			long s = Math.round(seconds);
			return s / 60 + ":" + String.format(Locale.ROOT, "%02d", s % 60);
		}

		static String esc(String text) {
			if (text == null) {
				return "";
			}
			StringBuilder sb = new StringBuilder(text.length());
			for (char c : text.toCharArray()) {
				switch (c) {
					case '<' -> sb.append("&lt;");
					case '>' -> sb.append("&gt;");
					case '&' -> sb.append("&amp;");
					case '"' -> sb.append("&quot;");
					case '\'' -> sb.append("&#39;");
					default -> sb.append(c);
				}
			}
			return sb.toString();
		}

		private static final String HEAD = """
				<!doctype html>
				<html lang="en"><head>
				<meta charset="utf-8">
				<meta name="viewport" content="width=device-width, initial-scale=1">
				<title>{{title}}</title>
				<meta name="description" content="{{description}}">
				<meta name="theme-color" content="#e0262e">
				<link rel="canonical" href="{{canonical}}">
				<link rel="icon" href="/assets/favicon-32.png">
				{{meta}}<style>
				@font-face { font-family: Inter; font-weight: 400; font-display: swap; src: url(/assets/fonts/inter-regular.woff2) format("woff2"); }
				@font-face { font-family: Inter; font-weight: 600; font-display: swap; src: url(/assets/fonts/inter-semibold.woff2) format("woff2"); }
				@font-face { font-family: Sora; font-weight: 700 800; font-display: swap; src: url(/assets/fonts/sora-bold.woff2) format("woff2"); }
				:root { color-scheme: dark; --bg: #0b0b0e; --surface: #121217; --surface-2: #17171d; --line: rgba(255,255,255,.07); --line-2: rgba(255,255,255,.12);
				  --text: #f4f4f6; --text-2: #b4b4be; --red: #e0262e; --red-hi: #f0353d; --red-glow: rgba(224,38,46,.45); }
				* { box-sizing: border-box; }
				body { margin: 0; min-height: 100vh; background: radial-gradient(1200px 600px at 50% -10%, rgba(224,38,46,.16), transparent 60%), var(--bg);
				  color: var(--text); font: 400 16px/1.55 Inter, system-ui, sans-serif; }
				header { display: flex; align-items: center; justify-content: space-between; max-width: 1180px; margin: 0 auto; padding: 18px 16px; }
				.brand { display: flex; align-items: center; gap: 10px; color: var(--text); text-decoration: none; font: 800 18px Sora, Inter, sans-serif; }
				.brand img { width: 30px; height: 30px; }
				.wrap { max-width: 1180px; margin: 0 auto; padding: 8px 16px 64px; }
				.player { border-radius: 20px; overflow: hidden; background: #000; border: 1px solid var(--line-2);
				  box-shadow: 0 30px 90px -30px var(--red-glow), 0 0 0 1px rgba(0,0,0,.4); }
				video { display: block; width: 100%; max-height: 78vh; background: #000; }
				.info { display: grid; gap: 18px; grid-template-columns: 1fr auto; align-items: center; margin-top: 22px; }
				.who { display: flex; gap: 14px; align-items: center; min-width: 0; }
				.head { border-radius: 10px; image-rendering: pixelated; background: var(--surface-2); flex: none; }
				h1 { margin: 0; font: 800 clamp(1.3rem, 2.6vw, 1.9rem)/1.2 Sora, Inter, sans-serif; overflow-wrap: anywhere; }
				.by { margin: 2px 0 0; color: var(--text-2); }
				.chips { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: 8px; order: 3; }
				.chip { padding: 5px 12px; border-radius: 999px; background: var(--surface-2); border: 1px solid var(--line); color: var(--text-2); font-size: 13.5px; }
				.actions { display: flex; gap: 10px; }
				.btn { display: inline-flex; align-items: center; justify-content: center; height: 44px; padding: 0 18px; border-radius: 12px; border: 1px solid var(--line-2);
				  background: var(--surface-2); color: var(--text); font: 600 15px Inter, sans-serif; text-decoration: none; cursor: pointer; }
				.btn:hover { border-color: rgba(255,255,255,.25); }
				.btn.primary { background: linear-gradient(180deg, var(--red-hi), var(--red)); border-color: transparent; box-shadow: 0 10px 30px -10px var(--red-glow); }
				.btn.big { height: 52px; padding: 0 24px; font-size: 16px; }
				.cta { margin-top: 40px; display: flex; gap: 24px; align-items: center; justify-content: space-between; padding: 26px 28px; border-radius: 20px;
				  background: linear-gradient(135deg, rgba(224,38,46,.14), rgba(255,255,255,.02)); border: 1px solid var(--line-2); }
				.cta h2 { margin: 8px 0 6px; font: 800 1.4rem Sora, Inter, sans-serif; }
				.cta p { margin: 0; color: var(--text-2); max-width: 620px; }
				.pill { font: 800 11px Inter, sans-serif; letter-spacing: .12em; color: #fff; background: var(--red); padding: 4px 9px; border-radius: 6px; }
				.btn.ghost { background: transparent; color: var(--text-2); }
				dialog { max-width: 440px; width: calc(100% - 32px); padding: 24px; border-radius: 18px; border: 1px solid var(--line-2); background: var(--surface); color: var(--text); }
				dialog::backdrop { background: rgba(0,0,0,.6); backdrop-filter: blur(4px); }
				dialog h2 { margin: 0 0 4px; font: 800 1.25rem Sora, Inter, sans-serif; }
				dialog select, dialog textarea { width: 100%; margin-top: 14px; padding: 10px 12px; border-radius: 10px; border: 1px solid var(--line-2);
				  background: var(--surface-2); color: var(--text); font: 400 15px Inter, sans-serif; resize: vertical; }
				dialog .actions { margin-top: 16px; justify-content: flex-end; }
				.gone { text-align: center; padding-top: 18vh; }
				.gone p { color: var(--text-2); margin-bottom: 28px; }
				@media (max-width: 720px) { .info { grid-template-columns: 1fr; } .actions .btn { flex: 1; } .cta { flex-direction: column; align-items: stretch; text-align: left; } .player { border-radius: 14px; } }
				</style>
				</head><body>
				<header><a class="brand" href="/"><img src="/assets/logo.png" alt="">Velo Client</a><a class="btn" href="/download">Download</a></header>
				""";
	}
}
