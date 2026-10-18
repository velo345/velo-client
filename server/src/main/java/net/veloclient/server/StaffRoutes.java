package net.veloclient.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.veloclient.server.store.CosmeticsService;
import net.veloclient.server.store.StoreService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Staff tools and player reports - see {@link StaffService}.
 * <ul>
 * <li>{@code GET /v1/staff/me} - your staff level (null if none)</li>
 * <li>{@code POST /v1/staff/lookup}, {@code /v1/staff/censor-cape} - mod and up</li>
 * <li>{@code POST /v1/staff/ban}, {@code /v1/staff/unban}, {@code GET /v1/staff/reports}, {@code POST /v1/staff/reports/resolve} - admin and up</li>
 * <li>{@code POST /v1/social/report} - any signed-in player reports a chat (friends or party), optionally attaching recent messages</li>
 * <li>bot ({@code X-Velo-Admin-Token}): {@code /v1/admin/staff/sync}, {@code /v1/admin/discord/booster}, {@code /v1/admin/discord/grant}</li>
 * <li>{@code GET /v1/cosmetics/card?id=} - a picture of a cosmetic for Discord embeds (giveaways)</li>
 * </ul>
 */
final class StaffRoutes {

	private StaffRoutes() {
	}

	private record TargetRequest(String target) {
	}

	private record BanRequest(String target, List<String> services, int days, String reason) {
	}

	private record ResolveRequest(String id, String status, String note, BanRequest ban) {
	}

	private record ReportRequest(String target, String category, String reason, String context, boolean includeHistory) {
	}

	private record SyncMember(String discordId, String level) {
	}

	private record SyncRequest(List<SyncMember> members) {
	}

	private record BoostCreditRequest(String discordId, String boostId, int count) {
	}

	/** Velo+ days per boost - several boosts (at once or one after another) add up. */
	private static final int DAYS_PER_BOOST = 14;

	private record BoosterRequest(String discordId, long boostedAt) {
	}

	private record GrantRequest(String discordId, String type, String value, String note) {
	}

	@FunctionalInterface
	private interface Admin {
		void handle(HttpExchange exchange) throws Exception;
	}

	static void register(HttpServer server, SessionRegistry registry, StaffService staff, SocialService social, PartyService parties, CapeStore capes,
			StoreService store, CosmeticsService cosmetics, DiscordLinks links, DiscordAnnouncer announcer, String adminToken) {
		java.util.function.Function<String, String> levelOf = uuid -> store.isOwner(uuid, social.usernameOf(uuid)) ? StaffService.ADMIN : staff.level(uuid);

		SocialRoutes.route(server, registry, "/v1/staff/me", "GET", (ex, me) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			String level = levelOf.apply(me);
			// "level" unlocks the staff tools in the client (/velostaff): helpers have none, so theirs is null; "team" says they're on it.
			out.put("level", StaffService.rank(level) > 0 ? level : null);
			out.put("team", level);
			JsonHttp.writeJson(ex, 200, out);
		});
		SocialRoutes.route(server, registry, "/v1/staff/lookup", "POST", (ex, me) -> {
			require(levelOf.apply(me), StaffService.MOD);
			SocialService.PlayerRef ref = social.resolvePlayer(JsonHttp.readBody(ex, TargetRequest.class).target());
			JsonHttp.writeJson(ex, 200, lookup(ref, staff, social, capes, levelOf));
		});
		SocialRoutes.route(server, registry, "/v1/staff/censor-cape", "POST", (ex, me) -> {
			require(levelOf.apply(me), StaffService.MOD);
			SocialService.PlayerRef ref = social.resolvePlayer(JsonHttp.readBody(ex, TargetRequest.class).target());
			if (!capes.removeAndBan(ref.uuid())) {
				throw new SocialService.SocialException(404, ref.username() + " has no custom cape uploaded");
			}
			System.out.println("[staff] " + social.usernameOf(me) + " censored the custom cape of " + ref.username());
			JsonHttp.writeJson(ex, 200, lookup(ref, staff, social, capes, levelOf));
		});
		SocialRoutes.route(server, registry, "/v1/staff/ban", "POST", (ex, me) -> {
			require(levelOf.apply(me), StaffService.ADMIN);
			BanRequest r = JsonHttp.readBody(ex, BanRequest.class);
			SocialService.PlayerRef ref = social.resolvePlayer(r.target());
			if (staff.isStaff(ref.uuid()) || StaffService.rank(levelOf.apply(ref.uuid())) > 0) {
				throw new SocialService.SocialException(403, "Staff can't be banned here - remove their staff role first");
			}
			staff.setBan(ref.uuid(), r.services() == null ? List.of() : r.services(), r.days(), r.reason(), social.usernameOf(me));
			JsonHttp.writeJson(ex, 200, lookup(ref, staff, social, capes, levelOf));
		});
		SocialRoutes.route(server, registry, "/v1/staff/unban", "POST", (ex, me) -> {
			require(levelOf.apply(me), StaffService.ADMIN);
			SocialService.PlayerRef ref = social.resolvePlayer(JsonHttp.readBody(ex, TargetRequest.class).target());
			staff.lift(ref.uuid());
			JsonHttp.writeJson(ex, 200, lookup(ref, staff, social, capes, levelOf));
		});
		SocialRoutes.route(server, registry, "/v1/staff/reports", "GET", (ex, me) -> {
			require(levelOf.apply(me), StaffService.ADMIN);
			String status = StoreRoutes.query(ex).getOrDefault("status", "open");
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("reports", staff.reports(status));
			JsonHttp.writeJson(ex, 200, out);
		});
		SocialRoutes.route(server, registry, "/v1/staff/reports/resolve", "POST", (ex, me) -> {
			require(levelOf.apply(me), StaffService.ADMIN);
			ResolveRequest r = JsonHttp.readBody(ex, ResolveRequest.class);
			String status = "actioned".equals(r.status()) ? "actioned" : "dismissed";
			StaffService.Report report = staff.resolve(r.id(), status, social.usernameOf(me), r.note());
			if (report == null) {
				throw new SocialService.SocialException(404, "No such report");
			}
			if (r.ban() != null && r.ban().services() != null && !r.ban().services().isEmpty()) {
				staff.setBan(report.target, r.ban().services(), r.ban().days(), r.ban().reason(), social.usernameOf(me));
			}
			JsonHttp.writeJson(ex, 200, report);
		});

		// ---- reports from players ----
		SocialRoutes.route(server, registry, "/v1/social/report", "POST", (ex, me) -> {
			ReportRequest r = JsonHttp.readBody(ex, ReportRequest.class);
			if (staff.recentReportsBy(me, 3_600_000L) >= 6) {
				throw new SocialService.SocialException(429, "You've sent a lot of reports - try again in a while");
			}
			SocialService.PlayerRef ref = social.resolvePlayer(r.target());
			if (ref.uuid().equals(me)) {
				throw new SocialService.SocialException(400, "You can't report yourself");
			}
			StaffService.Report report = new StaffService.Report();
			report.reporter = me;
			report.reporterName = social.usernameOf(me);
			report.target = ref.uuid();
			report.targetName = ref.username();
			report.category = r.category();
			report.reason = r.reason() == null ? "" : r.reason().strip().substring(0, Math.min(500, r.reason().strip().length()));
			report.context = "party".equals(r.context()) ? "party" : "dm";
			if (r.includeHistory()) {
				// Only with the reporter's consent: the last messages of that chat, as the server stored them.
				if (report.context.equals("party")) {
					for (PartyService.ChatLine line : parties.recentChat(me, 25)) {
						report.messages.add(new StaffService.Line(line.name(), line.text(), line.time()));
					}
				} else {
					for (SocialService.MessageView m : social.recentConversation(me, ref.uuid(), 25)) {
						if (m.text() != null) {
							report.messages.add(new StaffService.Line(social.usernameOf(m.from()), m.text(), m.time()));
						}
					}
				}
			}
			staff.addReport(report);
			announcer.playerReport(report);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("id", report.id);
			JsonHttp.writeJson(ex, 200, out);
		});

		// ---- the bot ----
		admin(server, adminToken, "/v1/admin/staff/sync", ex -> {
			SyncRequest r = JsonHttp.readBody(ex, SyncRequest.class);
			Map<String, StaffService.Member> fresh = new LinkedHashMap<>();
			if (r.members() != null) {
				for (SyncMember m : r.members()) {
					DiscordLinks.Link link = links.byDiscordId(m.discordId());
					String level = StaffService.normalizeLevel(m.level());
					if (link == null || level == null) {
						continue;
					}
					StaffService.Member member = new StaffService.Member();
					StaffService.Member previous = fresh.get(link.uuid());
					member.level = previous != null && StaffService.rank(previous.level) > StaffService.rank(level) ? previous.level : level;
					member.name = link.username();
					member.discordId = m.discordId();
					fresh.put(link.uuid(), member);
				}
			}
			List<List<String>> changes = staff.replaceStaff(fresh);
			// Every sync makes ownership match the team exactly (not only on changes): a revoke that failed once, or a grant
			// from before a lost staff.json, can't leave someone with the staff cape after they left - or without it while on the team.
			int revoked = 0;
			for (String uuid : store.database().ownersOf(StaffService.STAFF_CAPE, "grant:staff")) {
				if (!fresh.containsKey(uuid) && store.database().revokeItem(uuid, StaffService.STAFF_CAPE, "staff ended")) {
					revoked++;
				}
			}
			for (String uuid : fresh.keySet()) {
				if (!store.database().owns(uuid, StaffService.STAFF_CAPE)) {
					store.database().touchWallet(uuid, fresh.get(uuid).name);
					store.database().grantItem(uuid, StaffService.STAFF_CAPE, "staff");
				}
			}
			if (revoked > 0 || !changes.get(1).isEmpty()) {
				System.out.println("[staff] Left the team: " + changes.get(1).size() + ", staff capes taken back: " + revoked);
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("staff", fresh.size());
			out.put("added", changes.get(0).size());
			out.put("removed", changes.get(1).size());
			JsonHttp.writeJson(ex, 200, out);
		});
		// A boost Discord announced (one system message per boost): recorded once, and given right away when the member is linked.
		admin(server, adminToken, "/v1/admin/discord/boost-credit", ex -> {
			BoostCreditRequest r = JsonHttp.readBody(ex, BoostCreditRequest.class);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("recorded", r.boostId() != null && staff.addBoostCredit(r.discordId(), r.boostId(), r.count()));
			DiscordLinks.Link link = links.byDiscordId(r.discordId());
			out.put("linked", link != null);
			if (link != null) {
				rewardBoosts(out, link, staff.takeBoostCredits(r.discordId()), "boost:" + r.boostId(), store);
			}
			JsonHttp.writeJson(ex, 200, out);
		});
		// The Claim button: every boost not rewarded yet. Boosts from before boost credits existed fall back to the boost start time.
		admin(server, adminToken, "/v1/admin/discord/booster", ex -> {
			BoosterRequest r = JsonHttp.readBody(ex, BoosterRequest.class);
			DiscordLinks.Link link = links.byDiscordId(r.discordId());
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("linked", link != null);
			if (link == null) {
				JsonHttp.writeJson(ex, 200, out);
				return;
			}
			out.put("username", link.username());
			int boosts = staff.takeBoostCredits(r.discordId());
			if (boosts == 0 && !staff.hasBoostCredits(r.discordId()) && staff.claimBoost(r.discordId(), r.boostedAt())) {
				boosts = 1;
			}
			if (boosts == 0) {
				out.put("granted", false);
				out.put("alreadyClaimed", true);
				JsonHttp.writeJson(ex, 200, out);
				return;
			}
			rewardBoosts(out, link, boosts, "boost:" + r.boostedAt(), store);
			JsonHttp.writeJson(ex, 200, out);
		});
		admin(server, adminToken, "/v1/admin/discord/grant", ex -> {
			GrantRequest r = JsonHttp.readBody(ex, GrantRequest.class);
			DiscordLinks.Link link = links.byDiscordId(r.discordId());
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("linked", link != null);
			if (link == null) {
				JsonHttp.writeJson(ex, 200, out);
				return;
			}
			String note = r.note() == null ? "discord" : r.note();
			switch (r.type() == null ? "" : r.type()) {
				case "coins" -> {
					store.database().touchWallet(link.uuid(), link.username());
					store.database().adjust(link.uuid(), Integer.parseInt(r.value()), "admin_grant", note, "discord-bot");
				}
				case "plus" -> store.grantPlus(link.uuid(), link.username(), Integer.parseInt(r.value()), "giveaway", null, note);
				case "item" -> {
					if (!store.knownItem(r.value())) {
						throw new SocialService.SocialException(404, "Unknown item " + r.value());
					}
					store.database().touchWallet(link.uuid(), link.username());
					store.database().grantItem(link.uuid(), r.value(), "discord:" + note);
				}
				default -> throw new SocialService.SocialException(400, "type must be coins, plus or item");
			}
			out.put("username", link.username());
			out.put("granted", true);
			JsonHttp.writeJson(ex, 200, out);
		});

		// ---- a picture of a cosmetic, for Discord embeds ----
		server.createContext("/v1/cosmetics/card", ex -> VeloServerApp.handle(ex, "GET", e -> {
			String id = StoreRoutes.query(e).get("id");
			byte[] png = card(cosmetics, id);
			if (png == null) {
				JsonHttp.writeError(e, 404, "No picture for that item");
				return;
			}
			JsonHttp.writeBytes(e, 200, "image/png", png, "public, max-age=3600");
		}));
	}

	/** Wings: their preview picture. Capes: the front of the cape, scaled up crisply. */
	private static byte[] card(CosmeticsService cosmetics, String id) throws Exception {
		CosmeticsService.Item item = id == null ? null : cosmetics.item(id);
		if (item == null || item.files == null) {
			return null;
		}
		if (item.files.containsKey("preview")) {
			return cosmetics.file(item.files.get("preview"));
		}
		String gif = item.files.get("gif");
		if (gif == null) {
			return null;
		}
		byte[] bytes = cosmetics.file(gif);
		java.awt.image.BufferedImage frame = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
		if (frame == null) {
			return null;
		}
		int s = Math.max(1, frame.getWidth() / 64);
		java.awt.image.BufferedImage face = frame.getSubimage(s, s, 10 * s, 16 * s);
		int scale = Math.max(1, 512 / face.getHeight());
		java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(face.getWidth() * scale, face.getHeight() * scale,
				java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.drawImage(face, 0, 0, out.getWidth(), out.getHeight(), null);
		g.dispose();
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(out, "png", buffer);
		return buffer.toByteArray();
	}

	/** {@code boosts} x 14 days of Velo+ (added on top of any Velo+ they have) and the Booster cape. */
	private static void rewardBoosts(Map<String, Object> out, DiscordLinks.Link link, int boosts, String reference, StoreService store) throws Exception {
		out.put("username", link.username());
		if (boosts <= 0) {
			out.put("granted", false);
			return;
		}
		long until = store.grantPlus(link.uuid(), link.username(), boosts * DAYS_PER_BOOST, "booster", null, reference);
		boolean cape = store.database().grantItem(link.uuid(), "cape_booster", "booster");
		out.put("granted", true);
		out.put("boosts", boosts);
		out.put("days", boosts * DAYS_PER_BOOST);
		out.put("plusUntil", until);
		out.put("newCape", cape);
	}

	private static void require(String level, String needed) throws SocialService.SocialException {
		if (StaffService.rank(level) < StaffService.rank(needed)) {
			throw new SocialService.SocialException(403, StaffService.ADMIN.equals(needed) ? "Only Velo Client admins can do that"
					: "Only Velo Client staff can do that");
		}
	}

	private static Map<String, Object> lookup(SocialService.PlayerRef ref, StaffService staff, SocialService social, CapeStore capes,
			java.util.function.Function<String, String> levelOf) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("uuid", ref.uuid());
		out.put("username", ref.username());
		out.put("level", levelOf.apply(ref.uuid()));
		SocialService.Activity activity = social.activityOf(ref.uuid());
		out.put("online", activity != null);
		out.put("activity", activity);
		String hash = capes.hashFor(ref.uuid());
		out.put("customCape", hash);
		out.put("ban", staff.ban(ref.uuid()));
		out.put("reports", staff.reportsAgainst(ref.uuid()));
		return out;
	}

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
