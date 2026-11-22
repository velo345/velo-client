package net.veloclient.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the VIP role in step with Velo+: every minute it asks the Velo server who is linked and who has an
 * active Velo+, then gives the role to members who do (however they got Velo+ - the shop, or the owner panel)
 * and takes it away from everyone who doesn't - linked members whose Velo+ ended, and (when
 * {@code vipStrict}, the default) anyone holding the role without an active membership. Also run for one
 * member right after {@code /link}, so someone who links after getting Velo+ gets the role at once.
 * Also hands out the OG role ({@code ogRoleId}) to linked members with the OG badge - only given, never taken - and
 * every 5 minutes sends the tester role's holders ({@code testerRoleId}) to the server's tester programme.
 */
final class VipSync {

	private final JDA jda;
	private final BotConfig config;
	private final ServerApi server;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "velo-vip-sync");
		t.setDaemon(true);
		return t;
	});

	VipSync(JDA jda, BotConfig config, ServerApi server) {
		this.jda = jda;
		this.config = config;
		this.server = server;
	}

	void start() {
		scheduler.scheduleWithFixedDelay(this::runSafely, 20, 60, TimeUnit.SECONDS);
	}

	/** Right now, off the caller's thread (after /link, /unlink and /setup vip-role). */
	void runNow() {
		scheduler.execute(this::runSafely);
	}

	private void runSafely() {
		try {
			run();
		} catch (Exception e) {
			System.err.println("VIP sync failed: " + e.getMessage());
		}
	}

	private int testerTicks;

	private void run() throws Exception {
		// testers: every 5 minutes (and right after a change), the role holders go to the server
		if (config.testerRoleId != null && testerTicks++ % 5 == 0) {
			java.util.Set<String> holders = new java.util.HashSet<>();
			for (Guild guild : HomeGuild.guilds(jda)) {
				holders.addAll(RoleHolders.of(jda, guild.getId(), Set.of(config.testerRoleId)).keySet());
			}
			try {
				server.syncTesters(holders);
			} catch (ServerApi.ApiException e) {
				System.err.println("Tester sync failed: " + e.getMessage());
			}
		}
		if (config.vipRoleId == null && config.ogRoleId == null) {
			return;
		}
		List<ServerApi.LinkInfo> links = server.links();
		if (config.ogRoleId != null) {
			giveOg(links);
		}
		if (config.vipRoleId == null) {
			return;
		}
		Set<String> plusIds = new HashSet<>();
		for (ServerApi.LinkInfo link : links) {
			if (link.plus()) {
				plusIds.add(link.discordId());
			}
		}
		for (Guild guild : HomeGuild.guilds(jda)) {
			Role role = guild.getRoleById(config.vipRoleId);
			if (role == null || !guild.getSelfMember().canInteract(role)) {
				continue;
			}
			for (ServerApi.LinkInfo link : links) {
				guild.retrieveMemberById(link.discordId()).queue(member -> adjust(guild, member, role, link.plus()), error -> { });
			}
			if (config.vipStrict) {
				for (String holder : RoleHolders.of(jda, guild.getId(), Set.of(role.getId())).keySet()) {
					if (!plusIds.contains(holder)) {
						guild.removeRoleFromMember(net.dv8tion.jda.api.entities.UserSnowflake.fromId(holder), role).reason("Velo+ is not active")
								.queue(null, e -> System.err.println("Couldn't remove the VIP role: " + e.getMessage()));
					}
				}
			}
		}
	}

	private void giveOg(List<ServerApi.LinkInfo> links) throws Exception {
		for (Guild guild : HomeGuild.guilds(jda)) {
			Role role = guild.getRoleById(config.ogRoleId);
			if (role == null || !guild.getSelfMember().canInteract(role)) {
				continue;
			}
			Set<String> holders = RoleHolders.of(jda, guild.getId(), Set.of(role.getId())).keySet();
			for (ServerApi.LinkInfo link : links) {
				if (link.og() && !holders.contains(link.discordId())) {
					guild.addRoleToMember(net.dv8tion.jda.api.entities.UserSnowflake.fromId(link.discordId()), role).reason("Velo OG (first two weeks)")
							.queue(null, e -> { });
				}
			}
		}
	}

	private static void adjust(Guild guild, Member member, Role role, boolean shouldHave) {
		boolean has = member.getRoles().contains(role);
		if (shouldHave && !has) {
			guild.addRoleToMember(member, role).reason("Velo+ is active").queue(null, e -> System.err.println("Couldn't give the VIP role: " + e.getMessage()));
		} else if (!shouldHave && has) {
			guild.removeRoleFromMember(member, role).reason("Velo+ is not active").queue(null, e -> System.err.println("Couldn't remove the VIP role: " + e.getMessage()));
		}
	}
}
