package net.veloclient.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Velo staff come from Discord roles: every 5 minutes (and right after {@code /setup staff-role}, and when a
 * member's roles change or they leave) the bot sends the Velo server who holds a staff role and at which level
 * (helper, mod, admin). The server then gives them the Velo Staff cape and Velo+, unlocks the staff tools in
 * the client for mods and admins, and takes it all back when the role goes.
 * Only linked members count - the server maps Discord ids to Minecraft accounts.
 */
final class StaffSync {

	private final JDA jda;
	private final BotConfig config;
	private final ServerApi server;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "velo-staff-sync");
		t.setDaemon(true);
		return t;
	});
	/** Whether the last sync sent anyone - so removing the last staff role still clears the list once. */
	private boolean hadStaff = true;

	StaffSync(JDA jda, BotConfig config, ServerApi server) {
		this.jda = jda;
		this.config = config;
		this.server = server;
	}

	void start() {
		scheduler.scheduleWithFixedDelay(this::runSafely, 30, 300, TimeUnit.SECONDS);
	}

	private final java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean();

	/** A sync a few seconds from now; several role changes in a row (someone being re-roled) make one sync. */
	void soon() {
		if (queued.compareAndSet(false, true)) {
			scheduler.schedule(() -> {
				queued.set(false);
				runSafely();
			}, 5, TimeUnit.SECONDS);
		}
	}

	void runNow() {
		scheduler.execute(this::runSafely);
	}

	private void runSafely() {
		try {
			run();
		} catch (Exception e) {
			System.err.println("Staff sync failed: " + e.getMessage());
		}
	}

	static int rank(String level) {
		return switch (level == null ? "" : level) {
			case "admin" -> 2;
			case "mod" -> 1;
			default -> 0;
		};
	}

	private void run() throws Exception {
		if (config.staffRoles.isEmpty() && !hadStaff) {
			return;
		}
		Map<String, String> levels = new LinkedHashMap<>();
		for (Guild guild : jda.getGuilds()) {
			// A failed lookup throws, so a Discord hiccup never wipes the staff list.
			Map<String, java.util.Set<String>> holders = RoleHolders.of(jda, guild.getId(), config.staffRoles.keySet());
			for (Map.Entry<String, java.util.Set<String>> member : holders.entrySet()) {
				for (String roleId : member.getValue()) {
					// Several staff roles: the highest level wins (admin > mod > helper).
					String level = config.staffRoles.get(roleId);
					String previous = levels.get(member.getKey());
					if (previous == null || rank(level) > rank(previous)) {
						levels.put(member.getKey(), level);
					}
				}
			}
		}
		var answer = server.syncStaff(levels);
		hadStaff = !levels.isEmpty();
		if (answer.has("added") && (answer.get("added").getAsInt() > 0 || answer.get("removed").getAsInt() > 0)) {
			System.out.println("Staff sync: " + answer);
		}
	}
}
