package net.veloclient.bot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A small HTTP API on this machine only, for the Velo server's staff panel (staff.velo-client.com): open tickets and
 * their messages, replying / claiming / closing as a team member, and changing someone's staff level (their Discord
 * role - the staff sync then carries it to the server). Every call needs the admin token the bot already shares with
 * the server.
 */
final class StaffBridge {

	private static final Gson GSON = new Gson();

	private final JDA jda;
	private final BotConfig config;
	private final Tickets tickets;
	private final StaffSync staff;
	private final byte[] token;

	StaffBridge(JDA jda, BotConfig config, Tickets tickets, StaffSync staff, String adminToken) {
		this.jda = jda;
		this.config = config;
		this.tickets = tickets;
		this.staff = staff;
		this.token = adminToken.getBytes(StandardCharsets.UTF_8);
	}

	private static final class Fail extends Exception {
		final int status;

		Fail(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private interface Handler {
		Object handle(HttpExchange ex) throws Exception;
	}

	void start(String bind, int port) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
			route(server, "/health", ex -> Map.of("ok", true));
			route(server, "/tickets", ex -> list(Viewer.of(query(ex, "viewer"), query(ex, "all"))));
			route(server, "/tickets/messages", ex -> messages(query(ex, "id"), Viewer.of(query(ex, "viewer"), query(ex, "all"))));
			route(server, "/tickets/reply", ex -> reply(body(ex)));
			route(server, "/tickets/claim", ex -> claim(body(ex)));
			route(server, "/tickets/close", ex -> close(body(ex)));
			route(server, "/staff/level", ex -> setLevel(body(ex)));
			route(server, "/posts/refresh", ex -> InfoPosts.refreshAll(jda, config));
			server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
			server.start();
			System.out.println("Staff panel bridge on " + bind + ":" + port);
		} catch (IOException e) {
			System.err.println("Couldn't start the staff panel bridge: " + e.getMessage());
		}
	}

	private void route(HttpServer server, String path, Handler handler) {
		server.createContext(path, ex -> {
			try (ex) {
				String given = ex.getRequestHeaders().getFirst("X-Velo-Admin-Token");
				if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token)) {
					send(ex, 403, Map.of("error", "Forbidden"));
					return;
				}
				try {
					send(ex, 200, handler.handle(ex));
				} catch (Fail f) {
					send(ex, f.status, Map.of("error", f.getMessage()));
				} catch (Exception e) {
					System.err.println("Staff bridge " + path + ": " + e);
					send(ex, 500, Map.of("error", "The bot hit an error: " + e.getClass().getSimpleName()));
				}
			}
		});
	}

	private static void send(HttpExchange ex, int status, Object body) throws IOException {
		byte[] out = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(status, out.length);
		try (OutputStream o = ex.getResponseBody()) {
			o.write(out);
		}
	}

	private static JsonObject body(HttpExchange ex) throws IOException {
		return JsonParser.parseString(new String(ex.getRequestBody().readNBytes(64 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static String query(HttpExchange ex, String name) {
		String raw = ex.getRequestURI().getRawQuery();
		if (raw != null) {
			for (String part : raw.split("&")) {
				if (part.startsWith(name + "=")) {
					return java.net.URLDecoder.decode(part.substring(name.length() + 1), StandardCharsets.UTF_8);
				}
			}
		}
		return null;
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	// ---- Tickets ----

	/**
	 * Who's asking: the staff member's Discord id, or everything for managers and the owner. A team member sees a
	 * ticket only when one of their Discord roles handles its type - the same rule as the ticket channels on Discord,
	 * so a helper never sees bug reports or purchases. Types without staff roles are for managers only.
	 */
	private record Viewer(String discordId, boolean all) {
		static Viewer of(String discordId, String all) {
			return new Viewer(discordId != null && discordId.matches("\\d{5,25}") ? discordId : null, "1".equals(all));
		}

		static Viewer of(JsonObject r) {
			return of(str(r, "staffDiscordId"), r.has("all") && !r.get("all").isJsonNull() && r.get("all").getAsBoolean() ? "1" : null);
		}
	}

	private final Map<String, CachedRoles> rolesCache = new java.util.concurrent.ConcurrentHashMap<>();

	private record CachedRoles(java.util.Set<String> ids, long at) {
	}

	/** The member's role ids across the bot's guilds (cached for a minute; members leave the cache, so they're fetched). */
	private java.util.Set<String> rolesOf(String discordId) {
		CachedRoles hit = rolesCache.get(discordId);
		if (hit != null && System.currentTimeMillis() - hit.at < 60_000) {
			return hit.ids;
		}
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (Guild g : jda.getGuilds()) {
			Member m = g.getMemberById(discordId);
			if (m == null) {
				try {
					m = g.retrieveMemberById(discordId).submit().get(10, TimeUnit.SECONDS);
				} catch (Exception e) {
					continue;
				}
			}
			for (Role role : m.getRoles()) {
				ids.add(role.getId());
			}
		}
		rolesCache.put(discordId, new CachedRoles(ids, System.currentTimeMillis()));
		return ids;
	}

	private boolean sees(Viewer v, BotConfig.Ticket t) {
		if (v.all) {
			return true;
		}
		if (v.discordId == null) {
			return false;
		}
		BotConfig.TicketType type = config.type(t.type);
		List<String> handlers = new ArrayList<>(type.staffRoleIds);
		if (config.supportRoleId != null) {
			handlers.add(config.supportRoleId);
		}
		java.util.Set<String> mine = rolesOf(v.discordId);
		return handlers.stream().anyMatch(mine::contains);
	}

	private BotConfig.Ticket ticket(String id, Viewer v) throws Fail {
		BotConfig.Ticket t = ticket(id);
		if (!sees(v, t)) {
			throw new Fail(404, "That ticket is closed or doesn't exist");
		}
		return t;
	}

	private Object list(Viewer v) {
		List<Map<String, Object>> out = new ArrayList<>();
		List<BotConfig.Ticket> open;
		synchronized (config) {
			open = new ArrayList<>(config.tickets.values());
		}
		for (BotConfig.Ticket t : open) {
			if (!sees(v, t)) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", t.channelId);
			row.put("number", t.number);
			row.put("type", config.type(t.type).label);
			row.put("emoji", config.type(t.type).emoji);
			row.put("subject", t.subject);
			row.put("user", t.userName);
			row.put("userId", t.userId);
			row.put("claimedBy", claimedName(t));
			row.put("createdAt", t.createdAt);
			row.put("answers", t.answers);
			TextChannel channel = jda.getTextChannelById(t.channelId);
			row.put("exists", channel != null);
			row.put("lastMessageAt", channel == null || channel.getLatestMessageIdLong() == 0 ? t.createdAt
					: net.dv8tion.jda.api.utils.TimeUtil.getTimeCreated(channel.getLatestMessageIdLong()).toInstant().toEpochMilli());
			out.add(row);
		}
		out.sort((a, b) -> Long.compare((long) b.get("lastMessageAt"), (long) a.get("lastMessageAt")));
		return Map.of("tickets", out);
	}

	private String claimedName(BotConfig.Ticket t) {
		if (t.claimedBy == null) {
			return null;
		}
		if (t.claimedBy.startsWith("panel:")) {
			return t.claimedBy.substring(6);
		}
		var user = jda.getUserById(t.claimedBy);
		return user != null ? user.getName() : "a team member";
	}

	private BotConfig.Ticket ticket(String id) throws Fail {
		BotConfig.Ticket t;
		synchronized (config) {
			t = id == null ? null : config.tickets.get(id);
		}
		if (t == null) {
			throw new Fail(404, "That ticket is closed or doesn't exist");
		}
		return t;
	}

	private TextChannel channel(BotConfig.Ticket t) throws Fail {
		TextChannel channel = jda.getTextChannelById(t.channelId);
		if (channel == null) {
			throw new Fail(404, "The ticket's channel is gone");
		}
		return channel;
	}

	private Object messages(String id, Viewer v) throws Exception {
		BotConfig.Ticket t = ticket(id, v);
		List<Message> newestFirst = channel(t).getHistory().retrievePast(100).submit().get(15, TimeUnit.SECONDS);
		List<Message> messages = new ArrayList<>(newestFirst);
		Collections.reverse(messages);
		List<Map<String, Object>> out = new ArrayList<>();
		for (Message m : messages) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", m.getId());
			row.put("author", m.getMember() != null ? m.getMember().getEffectiveName() : m.getAuthor().getEffectiveName());
			row.put("avatar", m.getAuthor().getEffectiveAvatarUrl() + "?size=64");
			row.put("bot", m.getAuthor().isBot());
			row.put("opener", m.getAuthor().getId().equals(t.userId));
			row.put("text", m.getContentDisplay());
			row.put("at", m.getTimeCreated().toInstant().toEpochMilli());
			List<Map<String, String>> embeds = new ArrayList<>();
			for (var e : m.getEmbeds()) {
				Map<String, String> em = new LinkedHashMap<>();
				em.put("title", e.getTitle());
				em.put("description", e.getDescription());
				StringBuilder fields = new StringBuilder();
				for (var f : e.getFields()) {
					fields.append(f.getName()).append(": ").append(f.getValue()).append("\n");
				}
				em.put("fields", fields.toString().strip());
				embeds.add(em);
			}
			row.put("embeds", embeds);
			List<Map<String, String>> files = new ArrayList<>();
			for (var a : m.getAttachments()) {
				files.add(Map.of("name", a.getFileName(), "url", a.getUrl(), "image", String.valueOf(a.isImage())));
			}
			row.put("files", files);
			out.add(row);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ticket", ((List<?>) ((Map<?, ?>) list(v)).get("tickets")).stream()
				.filter(r -> id.equals(((Map<?, ?>) r).get("id"))).findFirst().orElse(null));
		result.put("messages", out);
		return result;
	}

	private Object reply(JsonObject r) throws Exception {
		BotConfig.Ticket t = ticket(str(r, "id"), Viewer.of(r));
		String text = str(r, "text");
		if (text == null || text.isBlank()) {
			throw new Fail(400, "Write a message");
		}
		String who = str(r, "staffDiscordId") != null ? "<@" + str(r, "staffDiscordId") + ">" : "**" + str(r, "staffName") + "**";
		channel(t).sendMessage(who + " · Velo Staff\n" + text.strip())
				.setAllowedMentions(java.util.EnumSet.noneOf(Message.MentionType.class)).submit().get(15, TimeUnit.SECONDS);
		return Map.of("ok", true);
	}

	private Object claim(JsonObject r) throws Fail {
		BotConfig.Ticket t = ticket(str(r, "id"), Viewer.of(r));
		String discordId = str(r, "staffDiscordId");
		String who = discordId != null ? "<@" + discordId + ">" : "**" + str(r, "staffName") + "**";
		if (!tickets.claimFor(channel(t), t, discordId, discordId != null ? who : str(r, "staffName"))) {
			throw new Fail(409, "Already claimed by " + claimedName(t));
		}
		return Map.of("ok", true);
	}

	private Object close(JsonObject r) throws Fail {
		BotConfig.Ticket t = ticket(str(r, "id"), Viewer.of(r));
		String discordId = str(r, "staffDiscordId");
		String name = str(r, "staffName");
		String reason = str(r, "reason");
		tickets.closeTicket(channel(t), t, discordId != null ? "<@" + discordId + ">" : name + " (staff panel)", name,
				reason == null ? "" : reason, notice -> channel(t, notice));
		return Map.of("ok", true);
	}

	private void channel(BotConfig.Ticket t, String notice) {
		TextChannel channel = jda.getTextChannelById(t.channelId);
		if (channel != null) {
			channel.sendMessage(notice).queue();
		}
	}

	// ---- Staff levels ----

	private Object setLevel(JsonObject r) throws Exception {
		String discordId = str(r, "discordId");
		String level = str(r, "level");
		if (discordId == null || !discordId.matches("\\d{5,25}")) {
			throw new Fail(400, "Unknown Discord member");
		}
		String roleId = null;
		if (level != null) {
			for (Map.Entry<String, String> e : config.staffRoles.entrySet()) {
				if (level.equals(e.getValue())) {
					roleId = e.getKey();
					break;
				}
			}
			if (roleId == null) {
				throw new Fail(409, "No Discord role makes someone " + level + " yet - set one with /setup staff-role");
			}
		}
		Guild guild = null;
		Member member = null;
		for (Guild g : jda.getGuilds()) {
			try {
				member = g.retrieveMemberById(discordId).submit().get(10, TimeUnit.SECONDS);
				guild = g;
				break;
			} catch (Exception e) {
				// Not in this guild.
			}
		}
		if (guild == null || member == null) {
			throw new Fail(404, "That member isn't on the Velo Discord");
		}
		List<Role> remove = new ArrayList<>();
		for (Role role : member.getRoles()) {
			if (config.staffRoles.containsKey(role.getId()) && !role.getId().equals(roleId)) {
				remove.add(role);
			}
		}
		List<Role> add = new ArrayList<>();
		String wanted = roleId;
		if (wanted != null && guild.getRoleById(wanted) != null && member.getRoles().stream().noneMatch(x -> x.getId().equals(wanted))) {
			add.add(guild.getRoleById(wanted));
		}
		String by = str(r, "by");
		guild.modifyMemberRoles(member, add, remove).reason("Staff panel: " + (by == null ? "a manager" : by))
				.submit().get(15, TimeUnit.SECONDS);
		staff.runNow();
		return Map.of("ok", true, "member", UserSnowflake.fromId(discordId).getId());
	}
}
