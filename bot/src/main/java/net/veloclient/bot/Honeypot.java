package net.veloclient.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * The bot trap: a channel nobody is meant to write in. Spam bots (and hacked accounts) post their scam in
 * every channel they can, so anyone who isn't staff writing here is removed and their last day of
 * messages wiped everywhere - a "softban" by default (they can rejoin, handy when a real person's account
 * was hacked), or a ban. Every catch is logged.
 */
final class Honeypot {

	private final BotConfig config;

	Honeypot(BotConfig config) {
		this.config = config;
	}

	static void postWarning(TextChannel channel, Runnable done, java.util.function.Consumer<Throwable> failed) {
		channel.sendMessageEmbeds(new EmbedBuilder().setColor(0xED4245).setTitle("⛔  Do not write in this channel")
				.setDescription("This channel is a **trap for spam bots**.\n\n"
						+ "Anything posted here gets the author **removed from the server automatically** and their recent messages deleted.\n\n"
						+ "You're a human? Great - just ignore this channel. 🙂")
				.setFooter("Automatic anti-spam · no staff involved").build()).queue(m -> done.run(), failed::accept);
	}

	/** Returns true if the message was in the trap (and was handled). */
	boolean onMessage(MessageReceivedEvent event) {
		if (config.honeypotChannelId == null || !event.getChannel().getId().equals(config.honeypotChannelId) || event.getGuild() == null) {
			return false;
		}
		Member member = event.getMember();
		if (event.getAuthor().isBot() || event.isWebhookMessage() || member == null || member.hasPermission(Permission.MESSAGE_MANAGE)
				|| member.getRoles().stream().anyMatch(r -> config.staffRoles.containsKey(r.getId()))) {
			return true;
		}
		Guild guild = event.getGuild();
		User user = event.getAuthor();
		String content = event.getMessage().getContentRaw();
		boolean ban = "ban".equalsIgnoreCase(config.honeypotAction);
		event.getMessage().delete().queue(null, e -> { });
		if (!guild.getSelfMember().canInteract(member)) {
			log(guild, user, content, "⚠️ couldn't remove them - their role is above mine");
			return true;
		}
		String note = ban ? "You were banned from **" + guild.getName() + "** for posting in the anti-spam trap channel."
				: "You were removed from **" + guild.getName() + "** for posting in the anti-spam trap channel. If your account was hacked, "
						+ "change your password - you're welcome to rejoin afterwards.";
		user.openPrivateChannel().flatMap(dm -> dm.sendMessage(note)).queue(sent -> remove(guild, user, content, ban), failed -> remove(guild, user, content, ban));
		return true;
	}

	private void remove(Guild guild, User user, String content, boolean ban) {
		guild.ban(user, 1, TimeUnit.DAYS).reason("Honeypot: posted in the bot trap channel").queue(done -> {
			if (ban) {
				log(guild, user, content, "🔨 banned (last day of messages deleted)");
			} else {
				guild.unban(user).reason("Honeypot softban").queue(
						u -> log(guild, user, content, "👢 softbanned - kicked and last day of messages deleted, can rejoin"),
						e -> log(guild, user, content, "🔨 banned (the unban for the softban failed: " + e.getMessage() + ")"));
			}
		}, e -> log(guild, user, content, "⚠️ couldn't ban: " + e.getMessage()));
	}

	private void log(Guild guild, User user, String content, String action) {
		String id = config.modLogChannelId != null ? config.modLogChannelId : config.ticketLogChannelId;
		TextChannel channel = id == null ? null : guild.getTextChannelById(id);
		if (channel == null) {
			System.out.println("Honeypot caught " + user.getName() + ": " + action);
			return;
		}
		channel.sendMessageEmbeds(new EmbedBuilder().setColor(0xED4245).setTitle("🪤 Honeypot caught someone")
				.setDescription(user.getAsMention() + " (`" + user.getName() + "`, `" + user.getId() + "`)\n" + action)
				.addField("Message", content.isBlank() ? "*(no text - attachment or embed)*" : "```" + content.replace("```", "'''").substring(0, Math.min(1000, content.length())) + "```", false)
				.setThumbnail(user.getEffectiveAvatarUrl()).setTimestamp(Instant.now()).build()).queue(null, e -> { });
	}
}
