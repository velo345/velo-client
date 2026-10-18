package net.veloclient.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Giveaways: {@code /giveaway start} posts a prize card (a cosmetic with its picture, Velo Coins or Velo+
 * days) with an Enter button. Only members with a linked Velo account can enter, so when it ends the
 * winners are drawn at random and the prize is put straight on their account - nobody has to claim
 * anything. End early, reroll or cancel with the other subcommands; everything survives a bot restart.
 */
final class Giveaways {

	static final String ENTER = "gw:enter:";
	private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([smhdw])");
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int COLOR = 0xFF4D9A;
	private static final int ENDED_COLOR = 0x5C5F66;

	private final JDA jda;
	private final BotConfig config;
	private final ServerApi server;
	private final Set<String> dirty = ConcurrentHashMap.newKeySet();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "velo-giveaways");
		t.setDaemon(true);
		return t;
	});
	private volatile Set<String> linked = Set.of();
	private volatile long linkedAt;

	Giveaways(JDA jda, BotConfig config, ServerApi server) {
		this.jda = jda;
		this.config = config;
		this.server = server;
	}

	void start() {
		scheduler.scheduleWithFixedDelay(() -> {
			try {
				tick();
			} catch (Exception e) {
				System.err.println("Giveaway tick failed: " + e.getMessage());
			}
		}, 10, 15, TimeUnit.SECONDS);
	}

	// ---- /giveaway ----

	void command(SlashCommandInteractionEvent event, String sub) {
		switch (sub) {
			case "start" -> startGiveaway(event);
			case "end" -> {
				BotConfig.Giveaway g = find(event);
				if (g == null) {
					return;
				}
				if (g.ended) {
					event.reply("That giveaway already ended - `/giveaway reroll` draws new winners.").setEphemeral(true).queue();
					return;
				}
				event.deferReply(true).queue();
				scheduler.execute(() -> {
					finish(g);
					event.getHook().sendMessage("Ended - winners announced.").queue();
				});
			}
			case "reroll" -> {
				BotConfig.Giveaway g = find(event);
				if (g == null) {
					return;
				}
				if (!g.ended) {
					event.reply("It's still running - `/giveaway end` first.").setEphemeral(true).queue();
					return;
				}
				int count = event.getOption("winners") == null ? 1 : Math.max(1, event.getOption("winners").getAsInt());
				event.deferReply(true).queue();
				scheduler.execute(() -> {
					List<String> fresh = draw(g, count);
					announce(g, fresh, true);
					event.getHook().sendMessage(fresh.isEmpty() ? "Nobody left to draw." : "Drew " + fresh.size() + " new winner(s).").queue();
				});
			}
			case "cancel" -> {
				BotConfig.Giveaway g = find(event);
				if (g == null) {
					return;
				}
				g.ended = true;
				config.save();
				TextChannel channel = jda.getTextChannelById(g.channelId);
				if (channel != null) {
					channel.deleteMessageById(g.messageId).queue(null, e -> { });
				}
				config.giveaways.remove(g.id);
				config.save();
				event.reply("Cancelled and removed - no prizes were given.").setEphemeral(true).queue();
			}
			case "list" -> {
				StringBuilder out = new StringBuilder();
				for (BotConfig.Giveaway g : config.giveaways.values()) {
					out.append(g.ended ? "✅ " : "🎁 ").append("`").append(g.id).append("` **").append(g.title).append("** - ").append(prizeLabel(g))
							.append(" - ").append(g.entrants.size()).append(" entries - ")
							.append(g.ended ? "ended" : "ends <t:" + g.endsAt / 1000 + ":R>").append(" in <#").append(g.channelId).append(">\n");
				}
				event.reply(out.length() == 0 ? "No giveaways yet - `/giveaway start`." : out.toString()).setEphemeral(true).queue();
			}
			default -> event.reply("Unknown subcommand").setEphemeral(true).queue();
		}
	}

	private BotConfig.Giveaway find(SlashCommandInteractionEvent event) {
		String id = event.getOption("id").getAsString().trim();
		BotConfig.Giveaway g = config.giveaways.get(id);
		if (g == null) {
			event.reply("No giveaway `" + id + "` - see `/giveaway list`.").setEphemeral(true).queue();
		}
		return g;
	}

	private void startGiveaway(SlashCommandInteractionEvent event) {
		TextChannel channel = event.getOption("channel") != null && event.getOption("channel").getAsChannel() instanceof TextChannel picked ? picked
				: event.getChannel() instanceof TextChannel here ? here : null;
		if (channel == null) {
			event.reply("Pick a text channel.").setEphemeral(true).queue();
			return;
		}
		String type = event.getOption("prize-type").getAsString();
		String value = event.getOption("prize").getAsString().trim();
		long duration = parseDuration(event.getOption("duration").getAsString());
		if (duration < 60_000 || duration > 60L * 24 * 3600_000) {
			event.reply("Duration like `30m`, `12h`, `3d` or `1w` (1 minute to 60 days).").setEphemeral(true).queue();
			return;
		}
		if (!type.equals("cosmetic")) {
			try {
				int amount = Integer.parseInt(value.replace(",", "").replace(".", ""));
				if (amount <= 0 || type.equals("plus") && amount > 365) {
					throw new NumberFormatException();
				}
				value = String.valueOf(amount);
			} catch (NumberFormatException e) {
				event.reply(type.equals("coins") ? "The prize is the number of coins, e.g. `1000`." : "The prize is the number of Velo+ days (1-365), e.g. `30`.")
						.setEphemeral(true).queue();
				return;
			}
		}
		event.deferReply(true).queue();
		BotConfig.Giveaway g = new BotConfig.Giveaway();
		g.id = newId();
		g.guildId = channel.getGuild().getId();
		g.channelId = channel.getId();
		g.hostId = event.getUser().getId();
		g.prizeType = type;
		g.prizeValue = value;
		g.winners = event.getOption("winners") == null ? 1 : Math.max(1, Math.min(20, event.getOption("winners").getAsInt()));
		g.endsAt = System.currentTimeMillis() + duration;
		g.requiredRoleId = event.getOption("role") == null ? null : event.getOption("role").getAsRole().getId();
		g.description = event.getOption("description") == null ? null : event.getOption("description").getAsString();
		String imageUrl = event.getOption("image") == null ? null : event.getOption("image").getAsAttachment().getUrl();
		String finalValue = value;
		scheduler.execute(() -> {
			g.prizeName = switch (type) {
				case "cosmetic" -> server.itemName(finalValue);
				case "coins" -> String.format(Locale.ROOT, "%,d Velo Coins", Integer.parseInt(finalValue));
				default -> finalValue + (finalValue.equals("1") ? " day" : " days") + " of Velo+";
			};
			g.title = event.getOption("title") != null ? event.getOption("title").getAsString() : g.prizeName;
			boolean unknownItem = type.equals("cosmetic") && !server.items().containsKey(finalValue);
			// Re-upload the picture: Discord attachment links expire, the message's own file doesn't.
			byte[] image = imageUrl != null ? ServerApi.download(imageUrl) : type.equals("cosmetic") ? server.card(finalValue) : null;
			g.hasImage = image != null;
			var action = channel.sendMessageEmbeds(embed(g)).setComponents(ActionRow.of(enterButton(g)));
			if (image != null) {
				action = action.setFiles(FileUpload.fromData(image, "giveaway.png"));
			}
			action.queue(message -> {
				g.messageId = message.getId();
				config.giveaways.put(g.id, g);
				config.save();
				event.getHook().sendMessage("🎁 Giveaway `" + g.id + "` is live in " + channel.getAsMention() + " - ends <t:" + g.endsAt / 1000 + ":R>."
						+ (unknownItem ? "\n⚠️ The Velo server doesn't list a cosmetic `" + finalValue + "` - check the id (`/giveaway cancel " + g.id + "` if it's wrong)." : "")).queue();
			}, e -> event.getHook().sendMessage("Couldn't post it: " + e.getMessage()).queue());
		});
	}

	/** Item ids for the cosmetic prize, matching what's typed (by id or name). */
	void autocomplete(CommandAutoCompleteInteractionEvent event) {
		String typed = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
		var type = event.getOption("prize-type");
		if (type != null && !type.getAsString().equals("cosmetic")) {
			event.replyChoices(List.of()).queue();
			return;
		}
		List<Command.Choice> choices = new ArrayList<>();
		for (Map.Entry<String, String> item : server.items().entrySet()) {
			if (item.getKey().toLowerCase(Locale.ROOT).contains(typed) || item.getValue().toLowerCase(Locale.ROOT).contains(typed)) {
				choices.add(new Command.Choice(clip(item.getValue() + " (" + item.getKey() + ")", 100), item.getKey()));
				if (choices.size() == 25) {
					break;
				}
			}
		}
		event.replyChoices(choices).queue(null, e -> { });
	}

	// ---- Entering ----

	void enter(ButtonInteractionEvent event) {
		BotConfig.Giveaway g = config.giveaways.get(event.getComponentId().substring(ENTER.length()));
		if (g == null || g.ended) {
			event.reply("This giveaway is over.").setEphemeral(true).queue();
			return;
		}
		String id = event.getUser().getId();
		if (g.entrants.contains(id)) {
			synchronized (config) {
				g.entrants.remove(id);
			}
			dirty.add(g.id);
			config.save();
			event.reply("You left the giveaway. Press **Enter** again to rejoin.").setEphemeral(true).queue();
			return;
		}
		Member member = event.getMember();
		if (g.requiredRoleId != null && (member == null || member.getRoles().stream().noneMatch(r -> r.getId().equals(g.requiredRoleId)))) {
			event.reply("This giveaway is for <@&" + g.requiredRoleId + "> only.").setEphemeral(true).queue();
			return;
		}
		event.deferReply(true).queue();
		scheduler.execute(() -> {
			if (!isLinked(id)) {
				event.getHook().sendMessage("🔗 **Link your Velo account first** - the prize goes straight onto it.\n"
						+ "Open the Velo Launcher → **Store** → **Copy link code**, paste it in "
						+ (config.linkChannelId == null ? "the link channel" : "<#" + config.linkChannelId + ">") + ", then press **Enter** again.").queue();
				return;
			}
			synchronized (config) {
				if (!g.entrants.contains(id)) {
					g.entrants.add(id);
				}
			}
			dirty.add(g.id);
			config.save();
			event.getHook().sendMessage("🎉 You're in! Winners are drawn <t:" + g.endsAt / 1000 + ":R>. Good luck!").queue();
		});
	}

	private boolean isLinked(String discordId) {
		if (System.currentTimeMillis() - linkedAt > 30_000 || !linked.contains(discordId)) {
			try {
				Set<String> fresh = new HashSet<>();
				for (ServerApi.LinkInfo link : server.links()) {
					fresh.add(link.discordId());
				}
				linked = fresh;
				linkedAt = System.currentTimeMillis();
			} catch (ServerApi.ApiException e) {
				System.err.println("Couldn't check giveaway entrant link: " + e.getMessage());
				return true; // the draw skips anyone who turns out not to be linked
			}
		}
		return linked.contains(discordId);
	}

	// ---- Ending ----

	private void tick() {
		long now = System.currentTimeMillis();
		for (BotConfig.Giveaway g : new ArrayList<>(config.giveaways.values())) {
			if (!g.ended && g.endsAt <= now) {
				finish(g);
			} else if (!g.ended && dirty.remove(g.id)) {
				TextChannel channel = jda.getTextChannelById(g.channelId);
				if (channel != null && g.messageId != null) {
					// The original attachment stays on the message, so the edited embed still shows it.
					channel.editMessageEmbedsById(g.messageId, embed(g)).queue(null, e -> { });
				}
			}
		}
		// Ended giveaways are kept 30 days for rerolls.
		config.giveaways.values().removeIf(g -> g.ended && now - g.endsAt > 30L * 24 * 3600_000);
	}

	private synchronized void finish(BotConfig.Giveaway g) {
		if (g.ended) {
			return;
		}
		g.ended = true;
		g.endsAt = Math.min(g.endsAt, System.currentTimeMillis());
		config.save();
		List<String> winners = draw(g, g.winners);
		announce(g, winners, false);
	}

	/** Picks up to {@code count} new winners and gives each the prize; skips anyone the server can't deliver to. */
	private List<String> draw(BotConfig.Giveaway g, int count) {
		List<String> pool;
		synchronized (config) {
			pool = new ArrayList<>(g.entrants);
		}
		pool.removeAll(g.winnerIds);
		Collections.shuffle(pool, RANDOM);
		List<String> won = new ArrayList<>();
		for (String id : pool) {
			if (won.size() >= count) {
				break;
			}
			try {
				String name = server.grant(id, g.prizeType.equals("cosmetic") ? "item" : g.prizeType, g.prizeValue, "giveaway " + g.id);
				if (name != null) {
					won.add(id);
				}
			} catch (ServerApi.ApiException e) {
				modLog("⚠️ Giveaway `" + g.id + "`: couldn't give <@" + id + "> the prize - " + e.getMessage() + ". Give it by hand from the owner panel.");
				won.add(id);
			}
		}
		g.winnerIds.addAll(won);
		config.save();
		return won;
	}

	private void announce(BotConfig.Giveaway g, List<String> winners, boolean reroll) {
		TextChannel channel = jda.getTextChannelById(g.channelId);
		if (channel == null) {
			return;
		}
		if (g.messageId != null) {
			channel.editMessageEmbedsById(g.messageId, embed(g))
					.setComponents(ActionRow.of(enterButton(g).asDisabled().withLabel("Ended"))).queue(null, e -> { });
		}
		String text;
		if (winners.isEmpty()) {
			text = reroll ? "Nobody left to draw for **" + g.title + "**." : "😔 The giveaway for **" + g.title + "** ended without (linked) entries.";
		} else {
			String mentions = String.join(" ", winners.stream().map(id -> "<@" + id + ">").toList());
			text = "🎉 " + (reroll ? "New winner" + (winners.size() > 1 ? "s" : "") + ": " : "Congratulations ") + mentions + "! You won **" + prizeLabel(g)
					+ "** - it's already on your Velo account" + (g.prizeType.equals("cosmetic") ? ", equip it in **Cosmetics**." : ".");
		}
		var action = channel.sendMessage(text).setAllowedMentions(List.of(Message.MentionType.USER));
		if (g.messageId != null) {
			action = action.setMessageReference(g.messageId).failOnInvalidReply(false);
		}
		action.queue(null, e -> System.err.println("Couldn't announce giveaway winners: " + e.getMessage()));
	}

	// ---- Looks ----

	private MessageEmbed embed(BotConfig.Giveaway g) {
		long ends = g.endsAt / 1000;
		StringBuilder text = new StringBuilder();
		if (g.description != null && !g.description.isBlank()) {
			text.append(g.description).append("\n\n");
		}
		text.append("**Prize** · ").append(prizeLabel(g)).append('\n');
		text.append("**Winners** · ").append(g.winners).append('\n');
		if (g.ended) {
			text.append("**Ended** · <t:").append(ends).append(":R>\n");
			text.append("**Entries** · ").append(g.entrants.size()).append('\n');
			text.append("**Won by** · ").append(g.winnerIds.isEmpty() ? "nobody" : String.join(" ", g.winnerIds.stream().map(id -> "<@" + id + ">").toList()));
		} else {
			text.append("**Ends** · <t:").append(ends).append(":R> (<t:").append(ends).append(":f>)\n");
			text.append("**Entries** · ").append(g.entrants.size()).append('\n');
			if (g.requiredRoleId != null) {
				text.append("**Who can enter** · <@&").append(g.requiredRoleId).append(">\n");
			}
			text.append("\nPress **Enter** to join. You need a linked Velo account - the prize lands on it automatically.");
		}
		EmbedBuilder embed = new EmbedBuilder().setColor(g.ended ? ENDED_COLOR : COLOR)
				.setAuthor(g.ended ? "Giveaway ended" : "Giveaway").setTitle((g.ended ? "🎉 " : "🎁 ") + clip(g.title, 240))
				.setDescription(text).setFooter("Giveaway " + g.id + " · hosted by " + hostName(g));
		if (g.hasImage) {
			embed.setImage("attachment://giveaway.png");
		}
		return embed.build();
	}

	private Button enterButton(BotConfig.Giveaway g) {
		return Button.success(ENTER + g.id, "Enter").withEmoji(Emoji.fromUnicode("🎉"));
	}

	private String hostName(BotConfig.Giveaway g) {
		var user = jda.getUserById(g.hostId);
		return user == null ? "the team" : user.getName();
	}

	static String prizeLabel(BotConfig.Giveaway g) {
		return switch (g.prizeType) {
			case "cosmetic" -> "✨ " + g.prizeName;
			case "coins" -> "🪙 " + g.prizeName;
			default -> "⭐ " + g.prizeName;
		};
	}

	private void modLog(String text) {
		String id = config.modLogChannelId != null ? config.modLogChannelId : config.ticketLogChannelId;
		TextChannel channel = id == null ? null : jda.getTextChannelById(id);
		if (channel != null) {
			channel.sendMessage(text).queue(null, e -> { });
		} else {
			System.err.println(text);
		}
	}

	static long parseDuration(String text) {
		Matcher m = DURATION.matcher(text.toLowerCase(Locale.ROOT));
		long total = 0;
		while (m.find()) {
			long n = Long.parseLong(m.group(1));
			total += n * switch (m.group(2)) {
				case "s" -> 1_000L;
				case "m" -> 60_000L;
				case "h" -> 3_600_000L;
				case "d" -> 86_400_000L;
				default -> 7 * 86_400_000L;
			};
		}
		return total;
	}

	private String newId() {
		String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
		String id;
		do {
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < 5; i++) {
				b.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
			}
			id = b.toString();
		} while (config.giveaways.containsKey(id));
		return id;
	}

	private static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
