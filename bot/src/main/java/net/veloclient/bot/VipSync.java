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

	private void run() throws Exception {
		if (config.vipRoleId == null) {
			return;
		}
		List<ServerApi.LinkInfo> links = server.links();
		Set<String> plusIds = new HashSet<>();
		for (ServerApi.LinkInfo link : links) {
			if (link.plus()) {
				plusIds.add(link.discordId());
			}
		}
		for (Guild guild : jda.getGuilds()) {
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

	private static void adjust(Guild guild, Member member, Role role, boolean shouldHave) {
		boolean has = member.getRoles().contains(role);
		if (shouldHave && !has) {
			guild.addRoleToMember(member, role).reason("Velo+ is active").queue(null, e -> System.err.println("Couldn't give the VIP role: " + e.getMessage()));
		} else if (!shouldHave && has) {
			guild.removeRoleFromMember(member, role).reason("Velo+ is not active").queue(null, e -> System.err.println("Couldn't remove the VIP role: " + e.getMessage()));
		}
	}
}
