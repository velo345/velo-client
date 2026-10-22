package net.veloclient.server;

import com.sun.net.httpserver.HttpServer;
import net.veloclient.server.store.StoreService;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Reports of shared clips and public worlds, and the staff tools + monitoring around them (see {@link ModerationService}).
 * <ul>
 * <li>{@code POST /v1/clips/report} - anyone (signed in, or the share page: rate-limited by address)</li>
 * <li>{@code POST /v1/worlds/report} - signed-in players</li>
 * <li>Every staff member (helper and up), in game ({@code /velostaff clips}), the launcher and the owner panel:
 * {@code GET /v1/staff/overview}, {@code GET /v1/staff/moderation}, {@code POST /v1/staff/moderation/resolve},
 * {@code GET /v1/staff/clips}, {@code POST /v1/staff/clips/remove}, {@code GET /v1/staff/worlds},
 * {@code POST /v1/staff/worlds/takedown}, {@code GET /v1/staff/capes}, {@code POST /v1/staff/capes/review}</li>
 * <li>Admin and up: {@code GET/POST /v1/staff/blocks}, {@code GET /v1/staff/log}</li>
 * </ul>
 */
final class ModerationRoutes {

	private ModerationRoutes() {
	}

	private record ClipReport(String id, String category, String reason) {
	}

	private record WorldReport(String world, String category, String reason) {
	}

	/** {@code action}: "remove" or "dismiss"; {@code blockDays}: also stop sharing / public worlds (0 = until lifted, null = no). */
	private record Resolve(String kind, String target, String action, String note, Integer blockDays) {
	}

	private record Takedown(String id, String note, Integer blockDays) {
	}

	private record BlockRequest(String what, String target, int days, String reason) {
	}

	private record CapeReview(String uuid, String action) {
	}

	private static final RateLimiter LIMITS = new RateLimiter();
	private static final long START = System.currentTimeMillis();

	static void register(HttpServer server, SessionRegistry registry, ModerationService moderation, StaffService staff, SocialService social,
			StoreService store, ClipShareService clips, WorldHostService worlds, CapeStore capes, ReportService bugReports, String siteUrl) {
		Function<String, String> levelOf = uuid -> store.isOwner(uuid, social.usernameOf(uuid)) ? StaffService.ADMIN : staff.level(uuid);

		// ---- Players report ----
		server.createContext("/v1/clips/report", exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(ex));
			String reporter;
			String reporterName;
			if (session != null) {
				reporter = session.uuid();
				reporterName = session.username();
			} else {
				reporter = "web:" + Integer.toHexString(WebRoutes.clientIp(ex).hashCode());
				reporterName = "Share page visitor";
			}
			long wait = LIMITS.tryAcquire(reporter, "clip-report", new RateLimiter.Window(5, 3_600_000L));
			if (wait > 0) {
				throw new ApiError(429, "You've sent a lot of reports - try again later");
			}
			ClipReport r = JsonHttp.readBody(ex, ClipReport.class);
			ClipShareService.Clip clip = clips.get(r.id());
			if (clip == null) {
				throw new ApiError(404, "No such clip");
			}
			moderation.report(ModerationService.CLIP, clip.id(), clip.title(), clip.uuid(), clip.username(), reporter, reporterName,
					r.category(), r.reason());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		}));
		SocialRoutes.route(server, registry, "/v1/worlds/report", "POST", (ex, me) -> {
			WorldReport r = JsonHttp.readBody(ex, WorldReport.class);
			WorldHostService.World world = worlds.byId(r.world());
			if (world == null) {
				throw new ApiError(404, "That world isn't open any more");
			}
			moderation.report(ModerationService.WORLD, world.id, world.name, world.hostUuid, world.hostName, me, social.usernameOf(me),
					r.category(), r.reason());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});

		// ---- Staff ----
		SocialRoutes.route(server, registry, "/v1/staff/overview", "GET", (ex, me) -> {
			requireTeam(levelOf.apply(me));
			JsonHttp.writeJson(ex, 200, overview(registry, moderation, staff, clips, worlds, capes, bugReports));
		});
		SocialRoutes.route(server, registry, "/v1/staff/moderation", "GET", (ex, me) -> {
			requireTeam(levelOf.apply(me));
			Map<String, String> q = StoreRoutes.query(ex);
			List<Map<String, Object>> out = new ArrayList<>();
			for (ModerationService.Report r : moderation.reports(q.getOrDefault("kind", "all"), q.getOrDefault("status", "open"))) {
				out.add(reportView(r, clips, worlds, moderation, siteUrl));
				if (out.size() >= 300) {
					break;
				}
			}
			JsonHttp.writeJson(ex, 200, Map.of("reports", out, "openClips", moderation.openCount(ModerationService.CLIP),
					"openWorlds", moderation.openCount(ModerationService.WORLD)));
		});
		SocialRoutes.route(server, registry, "/v1/staff/moderation/resolve", "POST", (ex, me) -> {
			String level = requireTeam(levelOf.apply(me));
			Resolve r = JsonHttp.readBody(ex, Resolve.class);
			String by = social.usernameOf(me);
			boolean remove = "remove".equals(r.action());
			String kind = ModerationService.WORLD.equals(r.kind()) ? ModerationService.WORLD : ModerationService.CLIP;
			ModerationService.Report any = moderation.reports(kind, "all").stream().filter(x -> x.target.equals(r.target())).findFirst().orElse(null);
			if (any == null) {
				throw new ApiError(404, "No report for that");
			}
			if (remove) {
				if (kind.equals(ModerationService.CLIP)) {
					if (clips.get(r.target()) != null) {
						clips.removeByStaff(r.target());
					}
				} else {
					worlds.takedown(r.target());
				}
			}
			int closed = moderation.close(kind, r.target(), remove ? "removed" : "dismissed", by, r.note());
			if (remove && r.blockDays() != null && any.ownerUuid != null) {
				requireRank(level, StaffService.MOD, "Only moderators and up can block players");
				moderation.block(kind, any.ownerUuid, r.blockDays(), r.note(), by);
			}
			moderation.log(by, (remove ? "removed " : "dismissed reports on ") + kind, r.target() + " (" + any.ownerName + ")", r.note());
			JsonHttp.writeJson(ex, 200, Map.of("closed", closed));
		});
		SocialRoutes.route(server, registry, "/v1/staff/clips", "GET", (ex, me) -> {
			requireTeam(levelOf.apply(me));
			Map<String, String> q = StoreRoutes.query(ex);
			String search = q.getOrDefault("q", "").toLowerCase(Locale.ROOT).strip();
			int limit = Math.max(1, Math.min(500, parseInt(q.get("limit"), 100)));
			List<Map<String, Object>> list = new ArrayList<>();
			for (ClipShareService.Clip clip : clips.all()) {
				if (!search.isEmpty() && !clip.username().toLowerCase(Locale.ROOT).contains(search) && !clip.id().toLowerCase(Locale.ROOT).equals(search)
						&& !clip.title().toLowerCase(Locale.ROOT).contains(search)) {
					continue;
				}
				Map<String, Object> row = PlusFeatureRoutes.view(clip, siteUrl);
				row.put("uuid", clip.uuid());
				row.put("reports", moderation.openAgainst(ModerationService.CLIP, clip.id()));
				list.add(row);
				if (list.size() >= limit) {
					break;
				}
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("clips", list);
			out.put("stats", clipStats(clips));
			JsonHttp.writeJson(ex, 200, out);
		});
		SocialRoutes.route(server, registry, "/v1/staff/clips/remove", "POST", (ex, me) -> {
			String level = requireTeam(levelOf.apply(me));
			Takedown r = JsonHttp.readBody(ex, Takedown.class);
			ClipShareService.Clip clip = clips.removeByStaff(r.id());
			String by = social.usernameOf(me);
			moderation.close(ModerationService.CLIP, clip.id(), "removed", by, r.note());
			if (r.blockDays() != null) {
				requireRank(level, StaffService.MOD, "Only moderators and up can block players");
				moderation.block(ModerationService.CLIP, clip.uuid(), r.blockDays(), r.note(), by);
			}
			moderation.log(by, "removed clip", clip.id() + " (" + clip.username() + ")", r.note());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		SocialRoutes.route(server, registry, "/v1/staff/worlds", "GET", (ex, me) -> {
			requireTeam(levelOf.apply(me));
			List<Map<String, Object>> list = new ArrayList<>();
			for (WorldHostService.World world : worlds.all()) {
				list.add(worldView(world, moderation));
			}
			JsonHttp.writeJson(ex, 200, Map.of("worlds", list, "relayBytes", worlds.totalBytes()));
		});
		SocialRoutes.route(server, registry, "/v1/staff/worlds/takedown", "POST", (ex, me) -> {
			String level = requireTeam(levelOf.apply(me));
			Takedown r = JsonHttp.readBody(ex, Takedown.class);
			WorldHostService.World world = worlds.takedown(r.id());
			if (world == null) {
				throw new ApiError(404, "That world isn't open any more");
			}
			String by = social.usernameOf(me);
			moderation.close(ModerationService.WORLD, world.id, "removed", by, r.note());
			if (r.blockDays() != null) {
				requireRank(level, StaffService.MOD, "Only moderators and up can block players");
				moderation.block(ModerationService.WORLD, world.hostUuid, r.blockDays(), r.note(), by);
			}
			moderation.log(by, "took down world", world.name + " (" + world.hostName + ")", r.note());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		SocialRoutes.route(server, registry, "/v1/staff/capes", "GET", (ex, me) -> {
			requireTeam(levelOf.apply(me));
			String filter = StoreRoutes.query(ex).getOrDefault("filter", "unreviewed");
			List<Map<String, Object>> list = new ArrayList<>();
			List<Map.Entry<String, CapeStore.StoredCape>> entries = new ArrayList<>(capes.all().entrySet());
			entries.sort(Comparator.comparingLong((Map.Entry<String, CapeStore.StoredCape> e) -> e.getValue().at()).reversed());
			for (Map.Entry<String, CapeStore.StoredCape> e : entries) {
				CapeStore.StoredCape cape = e.getValue();
				boolean reviewed = moderation.capeReviewed(cape.hash());
				boolean animated = "gif".equals(cape.type());
				if (filter.equals("unreviewed") && reviewed || filter.equals("animated") && !animated) {
					continue;
				}
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("uuid", e.getKey());
				row.put("username", social.usernameOf(e.getKey()));
				row.put("hash", cape.hash());
				row.put("animated", animated);
				row.put("at", cape.at());
				row.put("size", capes.fileSize(cape));
				row.put("reviewed", reviewed);
				row.put("url", "/v1/cape/" + cape.hash());
				list.add(row);
				if (list.size() >= 300) {
					break;
				}
			}
			long animatedCount = capes.all().values().stream().filter(c -> "gif".equals(c.type())).count();
			JsonHttp.writeJson(ex, 200, Map.of("capes", list, "total", capes.all().size(), "animated", animatedCount));
		});
		SocialRoutes.route(server, registry, "/v1/staff/capes/review", "POST", (ex, me) -> {
			requireTeam(levelOf.apply(me));
			CapeReview r = JsonHttp.readBody(ex, CapeReview.class);
			String uuid = VeloServerApp.normalizeUuid(r.uuid());
			CapeStore.StoredCape cape = uuid == null ? null : capes.all().get(uuid);
			if (cape == null) {
				throw new ApiError(404, "That player has no custom cape");
			}
			String by = social.usernameOf(me);
			if ("remove".equals(r.action())) {
				capes.removeAndBan(uuid);
				moderation.log(by, "removed custom cape", social.usernameOf(uuid) + " (" + cape.type() + ")", null);
			} else {
				moderation.markCapeReviewed(cape.hash());
			}
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		SocialRoutes.route(server, registry, "/v1/staff/blocks", "GET", (ex, me) -> {
			requireRank(levelOf.apply(me), StaffService.ADMIN, "Admins only");
			JsonHttp.writeJson(ex, 200, Map.of("clips", blockViews(moderation.blocks(ModerationService.CLIP), social),
					"worlds", blockViews(moderation.blocks(ModerationService.WORLD), social)));
		});
		SocialRoutes.route(server, registry, "/v1/staff/blocks/set", "POST", (ex, me) -> {
			requireRank(levelOf.apply(me), StaffService.MOD, "Only moderators and up can block players");
			BlockRequest r = JsonHttp.readBody(ex, BlockRequest.class);
			SocialService.PlayerRef ref = social.resolvePlayer(r.target());
			String what = ModerationService.WORLD.equals(r.what()) ? ModerationService.WORLD : ModerationService.CLIP;
			moderation.block(what, ref.uuid(), r.days(), r.reason(), social.usernameOf(me));
			moderation.log(social.usernameOf(me), r.days() < 0 ? "lifted " + what + " block" : "blocked " + what, ref.username(), r.reason());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		SocialRoutes.route(server, registry, "/v1/staff/log", "GET", (ex, me) -> {
			requireRank(levelOf.apply(me), StaffService.ADMIN, "Admins only");
			JsonHttp.writeJson(ex, 200, Map.of("log", moderation.log(300)));
		});
	}

	/** Everything at a glance: who's online, worlds, clips, reports, capes, the machine. */
	private static Map<String, Object> overview(SessionRegistry registry, ModerationService moderation, StaffService staff, ClipShareService clips,
			WorldHostService worlds, CapeStore capes, ReportService bugReports) {
		Map<String, Object> out = new LinkedHashMap<>();
		int[] live = registry.countsByKind();
		out.put("online", Map.of("game", live[0], "launcher", live[1], "web", live[2], "players", registry.onlineCount()));
		List<WorldHostService.World> open = worlds.all();
		long publicWorlds = open.stream().filter(w -> !"friends".equals(w.visibility)).count();
		int guests = open.stream().mapToInt(w -> w.guests.get()).sum();
		out.put("worlds", Map.of("open", open.size(), "public", publicWorlds, "guests", guests, "relayBytes", worlds.totalBytes()));
		out.put("clips", clipStats(clips));
		long animated = capes.all().values().stream().filter(c -> "gif".equals(c.type())).count();
		long unreviewed = capes.all().values().stream().filter(c -> !moderation.capeReviewed(c.hash())).count();
		out.put("capes", Map.of("custom", capes.all().size(), "animated", animated, "unreviewed", unreviewed));
		long newBugs = bugReports.list().stream().filter(s -> "new".equals(s.status())).count();
		out.put("reports", Map.of("clips", moderation.openCount(ModerationService.CLIP), "worlds", moderation.openCount(ModerationService.WORLD),
				"chat", staff.reports("open").size(), "bugs", newBugs));
		Runtime rt = Runtime.getRuntime();
		Map<String, Object> machine = new LinkedHashMap<>();
		machine.put("uptimeMs", System.currentTimeMillis() - START);
		machine.put("heapUsed", rt.totalMemory() - rt.freeMemory());
		machine.put("heapMax", rt.maxMemory());
		machine.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
		machine.put("cpus", rt.availableProcessors());
		machine.put("load", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
		machine.put("diskFree", clips.freeDisk());
		out.put("machine", machine);
		return out;
	}

	private static Map<String, Object> clipStats(ClipShareService clips) {
		ClipShareService.Stats stats = clips.stats();
		List<ClipShareService.Clip> all = clips.all();
		long now = System.currentTimeMillis();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("kept", all.size());
		out.put("usedBytes", clips.totalBytes());
		out.put("maxBytes", clips.maxTotalBytes());
		out.put("diskFree", clips.freeDisk());
		out.put("retentionDays", clips.retentionDays());
		out.put("uploadsTotal", stats.uploads);
		out.put("uploadedBytesTotal", stats.uploadedBytes);
		out.put("expired", stats.expired);
		out.put("evicted", stats.evicted);
		out.put("removedByStaff", stats.removedByStaff);
		out.put("views", all.stream().mapToLong(ClipShareService.Clip::views).sum());
		out.put("uploaders", all.stream().map(ClipShareService.Clip::uuid).distinct().count());
		out.put("expiringThisWeek", all.stream().filter(c -> clips.expiresAt(c) - now < 7L * 86_400_000L).count());
		long day = 86_400_000L;
		out.put("last24h", all.stream().filter(c -> now - c.createdAt() < day).count());
		out.put("last7d", sumDays(stats, 7));
		out.put("last30d", sumDays(stats, 30));
		// Per day for the chart (oldest first): {day, clips, bytes}.
		List<Map<String, Object>> days = new ArrayList<>();
		java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
		for (int i = 29; i >= 0; i--) {
			String key = today.minusDays(i).toString();
			long[] d = stats.byDay.getOrDefault(key, new long[2]);
			days.add(Map.of("day", key, "clips", d[0], "bytes", d[1]));
		}
		out.put("byDay", days);
		// Who uses the most space.
		Map<String, long[]> byUser = new LinkedHashMap<>();
		Map<String, String> names = new LinkedHashMap<>();
		for (ClipShareService.Clip c : all) {
			long[] u = byUser.computeIfAbsent(c.uuid(), k -> new long[2]);
			u[0]++;
			u[1] += c.size();
			names.put(c.uuid(), c.username());
		}
		List<Map<String, Object>> top = new ArrayList<>();
		byUser.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(10)
				.forEach(e -> top.add(Map.of("uuid", e.getKey(), "username", names.get(e.getKey()), "clips", e.getValue()[0], "bytes", e.getValue()[1])));
		out.put("topUploaders", top);
		return out;
	}

	private static long sumDays(ClipShareService.Stats stats, int days) {
		java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
		long n = 0;
		for (int i = 0; i < days; i++) {
			long[] d = stats.byDay.get(today.minusDays(i).toString());
			n += d == null ? 0 : d[0];
		}
		return n;
	}

	private static Map<String, Object> reportView(ModerationService.Report r, ClipShareService clips, WorldHostService worlds,
			ModerationService moderation, String siteUrl) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", r.id);
		out.put("kind", r.kind);
		out.put("target", r.target);
		out.put("title", r.targetTitle);
		out.put("owner", r.ownerUuid);
		out.put("ownerName", r.ownerName);
		out.put("reporterName", r.reporterName);
		out.put("category", r.category);
		out.put("reason", r.reason);
		out.put("at", r.at);
		out.put("status", r.status);
		out.put("handledBy", r.handledBy);
		out.put("handledAt", r.handledAt);
		out.put("note", r.note);
		out.put("reportsOnTarget", moderation.openAgainst(r.kind, r.target));
		if (ModerationService.CLIP.equals(r.kind)) {
			ClipShareService.Clip clip = clips.get(r.target);
			out.put("exists", clip != null);
			if (clip != null) {
				out.put("clip", PlusFeatureRoutes.view(clip, siteUrl));
			}
		} else {
			WorldHostService.World world = worlds.byId(r.target);
			out.put("exists", world != null);
			if (world != null) {
				out.put("world", worldView(world, moderation));
			}
		}
		return out;
	}

	private static Map<String, Object> worldView(WorldHostService.World world, ModerationService moderation) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", world.id);
		row.put("name", world.name);
		row.put("description", world.description);
		row.put("host", world.hostUuid);
		row.put("hostName", world.hostName);
		row.put("version", world.version);
		row.put("visibility", world.visibility);
		row.put("online", world.online());
		row.put("guests", world.guests.get());
		row.put("maxPlayers", world.maxPlayers);
		row.put("visitors", world.visitors.size());
		row.put("mods", world.mods.size());
		row.put("bytes", world.bytes.get());
		row.put("openedAt", world.openedAt);
		row.put("reports", moderation.openAgainst(ModerationService.WORLD, world.id));
		return row;
	}

	private static List<Map<String, Object>> blockViews(Map<String, ModerationService.Block> blocks, SocialService social) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map.Entry<String, ModerationService.Block> e : blocks.entrySet()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("uuid", e.getKey());
			row.put("username", social.usernameOf(e.getKey()));
			row.put("reason", e.getValue().reason);
			row.put("by", e.getValue().by);
			row.put("at", e.getValue().at);
			row.put("until", e.getValue().until);
			out.add(row);
		}
		return out;
	}

	/** Any staff level (helpers included); returns the level. */
	private static String requireTeam(String level) throws ApiError {
		if (level == null) {
			throw new ApiError(403, "Velo staff only");
		}
		return level;
	}

	private static void requireRank(String level, String needed, String message) throws ApiError {
		if (level == null || StaffService.rank(level) < StaffService.rank(needed)) {
			throw new ApiError(403, message);
		}
	}

	private static int parseInt(String value, int fallback) {
		try {
			return value == null ? fallback : Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
