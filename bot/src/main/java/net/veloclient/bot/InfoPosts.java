package net.veloclient.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The ready-made info posts ({@code /post}): server rules, how to download Velo Client, how to get the
 * free Velo Neon cape, and the booster perks. Pictures ship inside the bot jar ({@code posts/*.png}) and are
 * attached to the message, so they never break.
 */
final class InfoPosts {

	static final String SITE = "https://velo-client.com/";
	static final String[] KINDS = {"rules", "download", "free-cape", "boosters"};

	private static final int RED = 0xFF4D4D;

	private InfoPosts() {
	}

	/** What one post is made of. */
	private record Built(List<MessageEmbed> embeds, List<FileUpload> files, List<ActionRow> rows) {
	}

	/**
	 * Posts it - or, when the bot already posted this kind in the channel (its banner is attached), edits that message
	 * so pictures and texts update without a second copy.
	 */
	static void post(String kind, TextChannel channel, BotConfig config, Runnable done, Consumer<Throwable> failed) {
		Built built;
		try {
			built = build(kind, config);
		} catch (IllegalArgumentException e) {
			failed.accept(e);
			return;
		}
		channel.getHistory().retrievePast(100).queue(history -> {
			Message old = history.stream().filter(m -> isKind(m, kind)).findFirst().orElse(null);
			if (old != null) {
				old.editMessageEmbeds(built.embeds()).setFiles(built.files()).setComponents(built.rows()).queue(m -> done.run(), failed::accept);
				return;
			}
			var action = channel.sendMessageEmbeds(built.embeds()).setFiles(built.files());
			if (!built.rows().isEmpty()) {
				action = action.setComponents(built.rows());
			}
			action.queue(m -> done.run(), failed::accept);
		}, failed::accept);
	}

	/**
	 * Edits every info post the bot made in any channel (last 100 messages each). Returns how many were updated and,
	 * per channel, the files on the bot's own messages there (to see why a post wasn't found).
	 */
	static java.util.Map<String, Object> refreshAll(JDA jda, BotConfig config) {
		int updated = 0;
		java.util.Map<String, Object> seen = new java.util.TreeMap<>();
		for (TextChannel channel : jda.getTextChannels()) {
			List<Message> history;
			try {
				history = channel.getHistory().retrievePast(100).submit().get(15, TimeUnit.SECONDS);
			} catch (Exception e) {
				seen.put(channel.getName(), "can't read: " + e.getClass().getSimpleName());
				continue;
			}
			List<String> files = history.stream().filter(m -> m.getAuthor().getIdLong() == jda.getSelfUser().getIdLong())
					.flatMap(m -> m.getAttachments().stream().map(Message.Attachment::getFileName)).toList();
			if (!files.isEmpty()) {
				seen.put(channel.getName(), files);
			}
			for (String kind : KINDS) {
				for (Message m : history) {
					if (isKind(m, kind)) {
						try {
							Built built = build(kind, config);
							m.editMessageEmbeds(built.embeds()).setFiles(built.files()).setComponents(built.rows()).submit().get(20, TimeUnit.SECONDS);
							updated++;
						} catch (Exception e) {
							System.err.println("Couldn't update the " + kind + " post in #" + channel.getName() + ": " + e);
						}
					}
				}
			}
		}
		return java.util.Map.of("updated", updated, "seen", seen);
	}

	/** A post of this kind by the bot: its banner picture ({@code <kind>.png}) is attached. */
	private static boolean isKind(Message m, String kind) {
		return m.getAuthor().getIdLong() == m.getJDA().getSelfUser().getIdLong()
				&& m.getAttachments().stream().anyMatch(a -> a.getFileName().equals(kind + ".png"));
	}

	private static Built build(String kind, BotConfig config) {
		List<MessageEmbed> embeds = new ArrayList<>();
		List<FileUpload> files = new ArrayList<>();
		List<ActionRow> rows = new ArrayList<>();
		String link = config.linkChannelId == null ? "the link channel" : "<#" + config.linkChannelId + ">";
		String tickets = "a ticket";
		switch (kind) {
			case "rules" -> {
				files.add(file("rules.png"));
				embeds.add(new EmbedBuilder().setColor(RED).setImage("attachment://rules.png").build());
				embeds.add(new EmbedBuilder().setColor(RED).setTitle("📜  Server rules")
						.setDescription("Welcome to the Velo Client community! Keep it fun for everyone - by being here you agree to these rules.")
						.addField("1 · Be respectful", "Treat everyone kindly. No harassment, hate speech, slurs, threats or discrimination of any kind.", false)
						.addField("2 · No spam", "No message floods, walls of text, mass pings, emoji spam or ghost pings. Don't ping staff without a reason.", false)
						.addField("3 · No NSFW", "No sexual, gory or shocking content - in messages, images, names, avatars or statuses.", false)
						.addField("4 · Stay on topic", "Use the right channel for the right thing. Help questions go in " + tickets + ".", false)
						.addField("5 · No self-promotion", "No ads for servers, channels or products - also not in DMs. Want to work with us? Open a **Partner inquiry** ticket.", false)
						.addField("6 · Follow Discord's rules", "[Terms of Service](https://discord.com/terms) and [Community Guidelines](https://discord.com/guidelines) apply here. No ban evasion with alts.", false)
						.addField("7 · Listen to staff", "Staff decisions are final in the moment. Disagree? Open a ticket calmly instead of arguing in chat.", false)
						.addField("8 · Be honest", "No scamming, impersonating staff or other people, fake reports, or trading/selling accounts.", false)
						.addField("9 · Fair play", "Velo Client is a fair-play client. Don't share, sell or ask for cheats, hacked clients or exploits.", false)
						.addField("10 · Stay safe", "Staff will **never** ask for your password or Microsoft login. Don't click strange links - report them to the team.", false)
						.addField("​", "Breaking the rules can get you a warning, timeout, kick or ban - depending on how bad it is. "
								+ "These rules also apply to friends and party chat inside Velo Client: use the **Report** button there.", false)
						.setFooter("Velo Client · rules can change, the latest version is always here").build());
			}
			case "download" -> {
				files.add(file("download.png"));
				embeds.add(new EmbedBuilder().setColor(RED).setImage("attachment://download.png").build());
				embeds.add(new EmbedBuilder().setColor(RED).setTitle("⬇️  Get Velo Client", SITE)
						.setDescription("Free, fast and made for PvP. Windows and Linux.")
						.addField("1 · Download", "Open **[" + SITE.replace("https://", "").replace("/", "") + "](" + SITE + ")** and press **Download** - it picks the right installer for your system.", false)
						.addField("2 · Install", "Run the installer. On Windows press **More info → Run anyway** if SmartScreen asks; on Linux open the .deb in your software centre (or `sudo apt install ./` + the file).", false)
						.addField("3 · Sign in", "Open the **Velo Launcher** and sign in with your Microsoft account - the same one you play Minecraft with.", false)
						.addField("4 · Play", "Pick a version (or keep the default profile) and press **Play**. Mods, settings and updates are handled for you.", false)
						.addField("Switching clients?", "Press **Switch from another client** at the top of the launcher to bring your servers, keybinds, mods and worlds over.", false)
						.addField("Stuck?", "Open " + tickets + " and we'll help.", false)
						.setFooter("Only download Velo Client from the official site.").build());
				rows.add(ActionRow.of(Button.link(SITE, "Download Velo Client").withEmoji(Emoji.fromUnicode("⬇️"))));
			}
			case "free-cape" -> {
				files.add(file("free-cape.png"));
				files.add(file("link-steps.png"));
				embeds.add(new EmbedBuilder().setColor(0xFF4D5A).setImage("attachment://free-cape.png").build());
				embeds.add(new EmbedBuilder().setColor(0xFF4D5A).setTitle("🧥  Claim the free Velo Neon cloak")
						.setDescription("Link your Minecraft account to this Discord and the animated **Velo Neon** cloak is yours - it's not sold anywhere else.")
						.addField("1 · Open the Store", "In the **Velo Launcher** go to **Store** (in game: **Store → Link Discord**).", false)
						.addField("2 · Copy your code", "Press **Copy link code**. You get a one-time code like `VELO-AB12CD`.", false)
						.addField("3 · Paste it here", "Paste the code in " + link + " (or use **/link**). The message is deleted right away and the bot answers you privately.", false)
						.addField("4 · Wear it", "Done! Open **Cosmetics** and equip **Velo Neon**. Everyone with Velo Client sees it.", false)
						.setImage("attachment://link-steps.png")
						.setFooter("Unlinking (/unlink) removes the cloak again.").build());
			}
			case "boosters" -> {
				files.add(file("boosters.png"));
				embeds.add(new EmbedBuilder().setColor(0xF47FFF).setImage("attachment://boosters.png").build());
				embeds.add(new EmbedBuilder().setColor(0xF47FFF).setTitle("💖  Boost the server, get rewarded")
						.setDescription("Boosting keeps Velo Client growing - and as a thank-you you get:")
						.addField("⭐ 14 days of Velo+", "Every perk of Velo+ on your account, for every boost.", true)
						.addField("💎 Booster cloak", "An exclusive animated cloak - yours to keep.", true)
						.addField("🎀 Booster role", "A shiny name in the member list.", true)
						.addField("How to claim", "1. Boost the server.\n2. Make sure your account is linked (paste your code from **Store → Copy link code** in " + link + ").\n"
								+ "3. Press **Claim booster reward** below.", false)
						.setFooter("The reward can be claimed once per boost.").build());
				rows.add(ActionRow.of(Boosters.claimButton()));
			}
			default -> throw new IllegalArgumentException("Unknown post " + kind);
		}
		files.removeIf(f -> f == null);
		return new Built(embeds, files, rows);
	}

	/** A picture from the jar, or null if it's missing (the embed just shows without it). */
	private static FileUpload file(String name) {
		InputStream in = InfoPosts.class.getResourceAsStream("/posts/" + name);
		return in == null ? null : FileUpload.fromData(in, name);
	}
}
