package net.veloclient.bot;

import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;

/**
 * The bot's own server (DISCORD_GUILD_ID). Partner servers may add the bot just to have it there - online, "Playing
 * Velo Client" - so everything it actually does (tickets, linking, boosts, welcome cards, honeypot, staff / VIP / partner
 * role sync, the staff panel bridge) is limited to the home server. Before this, a partner server would have rewarded
 * its boosters with Velo+, and a big partner server could stall the staff sync (one failed member lookup aborts it).
 * Without DISCORD_GUILD_ID every server counts as home (the old single-server behaviour).
 */
final class HomeGuild {

	static final String ID = env();

	private HomeGuild() {
	}

	private static String env() {
		String value = System.getenv("DISCORD_GUILD_ID");
		return value == null || value.isBlank() ? null : value.trim();
	}

	/** True for the home server (or for any server when no home is set). */
	static boolean is(Guild guild) {
		return guild != null && (ID == null || ID.equals(guild.getId()));
	}

	/** The servers the bot works in: just the home server, or every server when no home is set. */
	static List<Guild> guilds(JDA jda) {
		if (ID == null) {
			return jda.getGuilds();
		}
		Guild home = jda.getGuildById(ID);
		return home == null ? List.of() : List.of(home);
	}
}
