package net.veloclient.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Server boosts: when Discord posts its "X just boosted the server!" message, the boost is recorded on the Velo
 * server (one reward per boost, so boosting again stacks another 14 days of Velo+; the Booster cape once) and
 * given right away to linked members; others get a Claim button, which also sits on the {@code /post boosters}
 * panel and pays out every boost not rewarded yet. Members who haven't linked their account are told how.
 */
final class Boosters {

	static final String CLAIM = "boost:claim";
	private static final Set<MessageType> BOOST_MESSAGES = EnumSet.of(MessageType.GUILD_MEMBER_BOOST, MessageType.GUILD_BOOST_TIER_1,
			MessageType.GUILD_BOOST_TIER_2, MessageType.GUILD_BOOST_TIER_3);

	private final BotConfig config;
	private final ServerApi server;

	Boosters(BotConfig config, ServerApi server) {
		this.config = config;
		this.server = server;
	}

	static Button claimButton() {
		return Button.primary(CLAIM, "Claim booster reward").withEmoji(Emoji.fromUnicode("💖"));
	}

	/**
	 * Discord's own boost message; returns true if it was one. Every boost gets one such message (its text is the number of boosts
	 * when someone boosts several times at once), and each one is a reward of its own: boosting again adds another 14 days on top.
	 */
	boolean onMessage(MessageReceivedEvent event) {
		if (!BOOST_MESSAGES.contains(event.getMessage().getType()) || event.getGuild() == null) {
			return false;
		}
		Guild guild = event.getGuild();
		TextChannel target = config.boostChannelId != null ? guild.getTextChannelById(config.boostChannelId) : null;
		String who = event.getAuthor().getId();
		int count = 1;
		try {
			count = Math.max(1, Integer.parseInt(event.getMessage().getContentRaw().strip()));
		} catch (NumberFormatException ignored) {
			// a single boost has no number
		}
		int boosts = count;
		String boostId = event.getMessageId();
		CompletableFuture.runAsync(() -> {
			ServerApi.BoostCredit credit = null;
			try {
				credit = server.creditBoost(who, boostId, boosts);
			} catch (ServerApi.ApiException e) {
				System.err.println("Couldn't record a boost: " + e.getMessage());
			}
			String reward = (boosts > 1 ? "**" + boosts * 14 + " days of Velo+** (14 per boost)" : "**14 days of Velo+**") + " and the exclusive **Booster cape**";
			String body;
			boolean showClaim;
			if (credit != null && credit.granted()) {
				body = "<@" + who + "> just boosted **" + guild.getName() + "**" + (boosts > 1 ? " **" + boosts + " times**" : "") + " - you're awesome!\n\n"
						+ "Already on your account **" + credit.username() + "**: +" + credit.days() + " days of Velo+ (now until <t:" + credit.plusUntil() / 1000
						+ ":D>)" + (credit.newCape() ? " and the **Booster cape**" : "") + ". Every boost adds another 14 days on top.";
				showClaim = false;
			} else {
				body = "<@" + who + "> just boosted **" + guild.getName() + "**" + (boosts > 1 ? " **" + boosts + " times**" : "") + " - you're awesome!\n\n"
						+ "Your thank-you: " + reward + ". Press the button to claim it"
						+ (config.linkChannelId == null ? "." : " (link your account in <#" + config.linkChannelId + "> first if you haven't).")
						+ " Every boost adds another 14 days on top.";
				showClaim = true;
			}
			var embed = new EmbedBuilder().setColor(0xF47FFF).setTitle("💖  Thank you for boosting!").setDescription(body)
					.setThumbnail(event.getAuthor().getEffectiveAvatarUrl()).build();
			var action = target != null ? target.sendMessage("<@" + who + ">").setEmbeds(embed)
					: event.getChannel().sendMessage("<@" + who + ">").setEmbeds(embed).setMessageReference(event.getMessage()).failOnInvalidReply(false);
			if (showClaim) {
				action = action.setComponents(ActionRow.of(claimButton()));
			}
			action.setAllowedMentions(List.of(Message.MentionType.USER)).queue(null, e -> System.err.println("Couldn't thank a booster: " + e.getMessage()));
		});
		return true;
	}

	void claim(ButtonInteractionEvent event) {
		Member member = event.getMember();
		if (member == null || member.getTimeBoosted() == null) {
			event.reply("💖 This reward is for server boosters. Boost the server, then press the button again!").setEphemeral(true).queue();
			return;
		}
		long boostedAt = member.getTimeBoosted().toInstant().toEpochMilli();
		event.deferReply(true).queue();
		CompletableFuture.runAsync(() -> {
			try {
				ServerApi.BoostClaim claim = server.claimBoost(member.getId(), boostedAt);
				String reply;
				if (!claim.linked()) {
					reply = "🔗 **Link your Velo account first** so we know where to put the reward:\nVelo Launcher → **Store** → **Copy link code**, then paste it in "
							+ (config.linkChannelId == null ? "the link channel" : "<#" + config.linkChannelId + ">") + ". Then press **Claim** again.";
				} else if (claim.alreadyClaimed()) {
					reply = "You already claimed the reward for this boost on **" + claim.username() + "** - thank you again! 💖";
				} else {
					reply = "✨ Done! " + (claim.days() > 14 ? "+" + claim.days() + " days for your boosts - " : "") + "**" + claim.username()
							+ "** has Velo+ until <t:" + claim.plusUntil() / 1000 + ":D>"
							+ (claim.newCape() ? " and the **Booster cape** is in your Cosmetics." : ".") + " Thank you for boosting! 💖";
				}
				event.getHook().sendMessage(reply).queue();
			} catch (ServerApi.ApiException e) {
				event.getHook().sendMessage("❌ " + e.getMessage()).queue();
			}
		});
	}
}
