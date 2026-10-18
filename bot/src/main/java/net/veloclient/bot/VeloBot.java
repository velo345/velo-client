package net.veloclient.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The Velo Client Discord bot: channel-based account linking (unlocks the Velo Neon cape), a ping-role
 * panel, a ticket system with per-type forms and roles, ticket logs, ready-made info posts, booster
 * rewards, giveaways, a bot-trap channel and the staff-role sync. Settings come from commands and from
 * {@code bot-config.json} (see bot/README.md). Run: {@code DISCORD_TOKEN=... VELO_SERVER_URL=...
 * VELO_ADMIN_TOKEN=... java -jar velo-bot.jar}.
 */
public final class VeloBot extends ListenerAdapter {

	private static final Pattern LINK_CODE = Pattern.compile("(?i)(?:VELO-)?[A-HJ-NP-Z2-9]{6}");

	private final BotConfig config;
	private final ServerApi server;
	private final Tickets tickets;
	private final Boosters boosters;
	private final Honeypot honeypot;
	private VipSync vip;
	private StaffSync staff;
	private Welcomer welcomer;
	private Giveaways giveaways;

	private VeloBot(BotConfig config, ServerApi server) {
		this.config = config;
		this.server = server;
		this.tickets = new Tickets(config);
		this.boosters = new Boosters(config, server);
		this.honeypot = new Honeypot(config);
		this.welcomer = new Welcomer(config);
	}

	public static void main(String[] args) throws Exception {
		String token = env("DISCORD_TOKEN", null);
		String serverUrl = env("VELO_SERVER_URL", "https://client.asteriasmp.net");
		String adminToken = env("VELO_ADMIN_TOKEN", null);
		if (token == null || adminToken == null) {
			System.err.println("Set DISCORD_TOKEN and VELO_ADMIN_TOKEN (and VELO_SERVER_URL) - see bot/README.md");
			System.exit(1);
		}
		BotConfig config = BotConfig.load(Path.of(env("BOT_DATA_DIR", "bot-data")));
		VeloBot bot = new VeloBot(config, new ServerApi(serverUrl, adminToken));
		// MESSAGE_CONTENT (a privileged intent - switch it on in the Discord developer portal) lets ticket transcripts include what was said.
		JDA jda = JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS)
				.setMemberCachePolicy(MemberCachePolicy.NONE).setActivity(Activity.playing("Velo Client")).addEventListeners(bot).build();
		jda.awaitReady();
		bot.registerCommands(jda, env("DISCORD_GUILD_ID", null));
		bot.vip = new VipSync(jda, config, bot.server);
		bot.vip.start();
		bot.staff = new StaffSync(jda, config, bot.server);
		bot.staff.start();
		bot.giveaways = new Giveaways(jda, config, bot.server);
		bot.giveaways.start();
		System.out.println("Velo bot online as " + jda.getSelfUser().getName());
	}

	private static String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	// ---- Commands ----

	private void registerCommands(JDA jda, String guildId) {
		DefaultMemberPermissions admin = DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER);
		List<SlashCommandData> commands = new ArrayList<>();
		commands.add(Commands.slash("unlink", "Unlink your Minecraft account (the Velo Neon cape is removed)"));
		commands.add(Commands.slash("roles", "Ping roles members can pick").setDefaultPermissions(admin).addSubcommands(
				new SubcommandData("add", "Put a role on the panel").addOptions(new OptionData(OptionType.ROLE, "role", "The role to give", true),
						new OptionData(OptionType.STRING, "label", "Button text, e.g. News", true),
						new OptionData(OptionType.STRING, "emoji", "Emoji on the button", false),
						new OptionData(OptionType.STRING, "description", "One line shown on the panel", false)),
				new SubcommandData("remove", "Take a role off the panel").addOptions(new OptionData(OptionType.ROLE, "role", "The role", true)),
				new SubcommandData("list", "Show what's on the panel"),
				new SubcommandData("panel", "Post (or update) the panel in a channel").addOptions(
						new OptionData(OptionType.CHANNEL, "channel", "Where to post it", true),
						new OptionData(OptionType.STRING, "title", "Panel title", false), new OptionData(OptionType.STRING, "text", "Text under the title", false))));
		commands.add(Commands.slash("ticket", "Tickets").setDefaultPermissions(admin).addSubcommands(
				new SubcommandData("panel", "Post the ticket panel in a channel").addOptions(new OptionData(OptionType.CHANNEL, "channel", "Where to post it", true),
						new OptionData(OptionType.STRING, "title", "Panel title", false), new OptionData(OptionType.STRING, "text", "Text under the title", false)),
				new SubcommandData("types", "List the ticket types and their settings"),
				new SubcommandData("staff-add", "Let a role handle a ticket type").addOptions(typeOption(), new OptionData(OptionType.ROLE, "role", "The role", true)),
				new SubcommandData("staff-remove", "Take a role off a ticket type").addOptions(typeOption(), new OptionData(OptionType.ROLE, "role", "The role", true)),
				new SubcommandData("category", "Where a ticket type's channels go").addOptions(typeOption(),
						new OptionData(OptionType.CHANNEL, "category", "The category", true)),
				new SubcommandData("add", "Add someone to this ticket").addOptions(new OptionData(OptionType.USER, "user", "Who", true)),
				new SubcommandData("remove", "Remove someone from this ticket").addOptions(new OptionData(OptionType.USER, "user", "Who", true)),
				new SubcommandData("close", "Close this ticket").addOptions(new OptionData(OptionType.STRING, "reason", "Why", false))));
		commands.add(Commands.slash("setup", "Set the bot up").setDefaultPermissions(admin).addSubcommands(
				new SubcommandData("link-channel", "Where members paste their one-time Velo Client link codes")
						.addOptions(new OptionData(OptionType.CHANNEL, "channel", "The link-code channel", true)),
				new SubcommandData("ticket-category", "The category tickets are created in").addOptions(new OptionData(OptionType.CHANNEL, "category", "The category", true)),
				new SubcommandData("ticket-log", "Where ticket logs and transcripts go").addOptions(new OptionData(OptionType.CHANNEL, "channel", "The channel", true)),
				new SubcommandData("support-role", "The role that handles every ticket").addOptions(new OptionData(OptionType.ROLE, "role", "The role", true)),
				new SubcommandData("linked-role", "A role for members who linked their Minecraft account").addOptions(new OptionData(OptionType.ROLE, "role", "The role", true)),
				new SubcommandData("vip-role", "The VIP role of Velo+ members (given and taken away automatically)").addOptions(new OptionData(OptionType.ROLE, "role", "The role", true)),
				new SubcommandData("webhooks", "Create the server's webhooks (the URLs go in the Velo server's settings)").addOptions(
						new OptionData(OptionType.CHANNEL, "donations", "Channel for purchase announcements", false),
						new OptionData(OptionType.CHANNEL, "reports", "Channel for bug / crash reports", false),
						new OptionData(OptionType.CHANNEL, "player-reports", "Channel for reported friend / party chat messages (staff only!)", false)),
				new SubcommandData("staff-role", "Members with this role are on the Velo team (staff cape, Velo+, maybe in-game staff tools)").addOptions(
						new OptionData(OptionType.ROLE, "role", "The role", true),
						new OptionData(OptionType.STRING, "level", "What they may do", true)
								.addChoice("Helper - staff cape and Velo+, no moderation tools", "helper")
								.addChoice("Moderator - look up players, censor capes", "mod")
								.addChoice("Admin - also bans and player reports", "admin")),
				new SubcommandData("staff-role-remove", "This role no longer makes someone Velo staff").addOptions(new OptionData(OptionType.ROLE, "role", "The role", true)),
				new SubcommandData("welcome", "Post a welcome card for every new member in a channel").addOptions(
						new OptionData(OptionType.CHANNEL, "channel", "Where welcomes go", true),
						new OptionData(OptionType.CHANNEL, "rules", "A channel to point new members to (e.g. the rules)", false)),
				new SubcommandData("welcome-off", "Stop posting welcome messages"),
				new SubcommandData("welcome-test", "Post your own welcome card here, to see how it looks"),
				new SubcommandData("boost-channel", "Where boost thank-yous with the Claim button go (default: Discord's boost message)")
						.addOptions(new OptionData(OptionType.CHANNEL, "channel", "The channel", true)),
				new SubcommandData("honeypot", "Make a bot-trap channel: anyone (not staff) writing there is removed").addOptions(
						new OptionData(OptionType.CHANNEL, "channel", "A new, empty channel - put it near the top", true),
						new OptionData(OptionType.STRING, "action", "What happens to them (default: softban)", false)
								.addChoice("Softban - kick and delete their last day of messages", "softban").addChoice("Ban", "ban")),
				new SubcommandData("mod-log", "Where honeypot catches and giveaway problems are logged (default: ticket log)")
						.addOptions(new OptionData(OptionType.CHANNEL, "channel", "The channel", true)),
				new SubcommandData("reload", "Re-read bot-config.json after editing it"),
				new SubcommandData("show", "Show the current settings")));
		commands.add(Commands.slash("announce", "Post an announcement that pings a role").setDefaultPermissions(admin)
				.addOptions(new OptionData(OptionType.CHANNEL, "channel", "Where", true), new OptionData(OptionType.ROLE, "role", "Who to ping", true),
						new OptionData(OptionType.STRING, "message", "The announcement", true), new OptionData(OptionType.STRING, "title", "Title", false)));
		OptionData postKind = new OptionData(OptionType.STRING, "post", "Which post", true).addChoice("📜 Rules", "rules")
				.addChoice("⬇️ How to download", "download").addChoice("🧥 Free Velo Neon cape", "free-cape").addChoice("💖 Booster perks (with Claim button)", "boosters");
		commands.add(Commands.slash("post", "Post a ready-made info message (rules, download, free cape, boosters)").setDefaultPermissions(admin)
				.addOptions(postKind, new OptionData(OptionType.CHANNEL, "channel", "Where", true)));
		commands.add(Commands.slash("giveaway", "Giveaways - prizes go straight onto the winners' Velo accounts").setDefaultPermissions(admin).addSubcommands(
				new SubcommandData("start", "Start a giveaway").addOptions(
						new OptionData(OptionType.STRING, "prize-type", "What they win", true).addChoice("✨ Cosmetic", "cosmetic")
								.addChoice("🪙 Velo Coins", "coins").addChoice("⭐ Velo+ days", "plus"),
						new OptionData(OptionType.STRING, "prize", "Cosmetic (type to search), number of coins, or number of days", true, true),
						new OptionData(OptionType.STRING, "duration", "How long, e.g. 30m, 12h, 3d, 1w", true),
						new OptionData(OptionType.INTEGER, "winners", "How many winners (default 1)", false).setRequiredRange(1, 20),
						new OptionData(OptionType.CHANNEL, "channel", "Where (default: here)", false),
						new OptionData(OptionType.STRING, "title", "Title (default: the prize)", false),
						new OptionData(OptionType.STRING, "description", "A few words under the title", false),
						new OptionData(OptionType.ATTACHMENT, "image", "A picture (cosmetics show theirs automatically)", false),
						new OptionData(OptionType.ROLE, "role", "Only members with this role can enter", false)),
				new SubcommandData("end", "End a giveaway now and draw the winners").addOptions(new OptionData(OptionType.STRING, "id", "Giveaway id", true)),
				new SubcommandData("reroll", "Draw new winner(s) for an ended giveaway").addOptions(new OptionData(OptionType.STRING, "id", "Giveaway id", true),
						new OptionData(OptionType.INTEGER, "winners", "How many (default 1)", false).setRequiredRange(1, 20)),
				new SubcommandData("cancel", "Delete a giveaway without giving prizes").addOptions(new OptionData(OptionType.STRING, "id", "Giveaway id", true)),
				new SubcommandData("list", "Running and recent giveaways")));
		if (guildId != null) {
			Guild guild = jda.getGuildById(guildId);
			if (guild != null) {
				guild.updateCommands().addCommands(commands).queue(
						registered -> System.out.println("Registered " + registered.size() + " slash commands in guild " + guildId),
						error -> System.err.println("Couldn't register slash commands in guild " + guildId + ": " + error.getMessage()));
				return;
			}
			System.err.println("DISCORD_GUILD_ID " + guildId + " isn't a guild the bot has joined; registering commands globally instead.");
		}
		jda.updateCommands().addCommands(commands).queue(
				registered -> System.out.println("Registered " + registered.size() + " global slash commands (Discord may take up to an hour to show them)"),
				error -> System.err.println("Couldn't register global slash commands: " + error.getMessage()));
	}

	private OptionData typeOption() {
		OptionData option = new OptionData(OptionType.STRING, "type", "The ticket type", true);
		for (BotConfig.TicketType type : config.ticketTypes) {
			option.addChoice(type.label, type.id);
		}
		return option;
	}

	@Override
	public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
		try {
			String sub = event.getSubcommandName();
			switch (event.getName()) {
				case "unlink" -> unlink(event);
				case "roles" -> roles(event, sub);
				case "ticket" -> ticket(event, sub);
				case "setup" -> setup(event, sub);
				case "announce" -> announce(event);
				case "post" -> post(event);
				case "giveaway" -> giveaways.command(event, sub);
				default -> event.reply("Unknown command").setEphemeral(true).queue();
			}
		} catch (RuntimeException e) {
			e.printStackTrace();
			if (!event.isAcknowledged()) {
				event.reply("Something went wrong: " + e.getMessage()).setEphemeral(true).queue();
			}
		}
	}

	// ---- Link channel, /unlink ----

	@Override
	public void onMessageReceived(MessageReceivedEvent event) {
		if (honeypot.onMessage(event) || boosters.onMessage(event)) {
			return;
		}
		if (event.getGuild() == null || event.getAuthor().isBot() || config.linkChannelId == null
				|| !event.getChannel().getId().equals(config.linkChannelId)) {
			return;
		}
		String code = event.getMessage().getContentRaw().strip();
		if (!LINK_CODE.matcher(code).matches()) {
			return;
		}
		event.getMessage().delete().queue(
				ignored -> link(event, code),
				error -> {
					System.err.println("Couldn't delete a Discord link-code message: " + error.getMessage());
					event.getChannel().sendMessage("I couldn't safely process that code. Please check that I have Manage Messages in this channel.")
							.queue(null, sendError -> System.err.println("Couldn't report link-code deletion failure: " + sendError.getMessage()));
				});
	}

	private void link(MessageReceivedEvent event, String code) {
		try {
			ServerApi.Linked linked = server.link(code, event.getAuthor().getId(), event.getAuthor().getName());
			Guild guild = event.getGuild();
			if (config.linkedRoleId != null && event.getMember() != null) {
				Role role = guild.getRoleById(config.linkedRoleId);
				if (role != null && guild.getSelfMember().canInteract(role)) {
					guild.addRoleToMember(event.getMember(), role).queue(null, e -> { });
				}
			}
			event.getChannel().sendMessage("✅ Linked to **" + linked.username()
					+ "**! The **Velo Neon** cape is now in your cosmetics.").queue(null,
					e -> System.err.println("Couldn't confirm Discord link: " + e.getMessage()));
			if (vip != null) {
				vip.runNow();
			}
		} catch (ServerApi.ApiException e) {
			event.getChannel().sendMessage("❌ " + e.getMessage()).queue(null,
					error -> System.err.println("Couldn't report Discord link failure: " + error.getMessage()));
		}
	}

	private void unlink(SlashCommandInteractionEvent event) {
		event.deferReply(true).queue();
		try {
			boolean removed = server.unlink(event.getUser().getId());
			if (removed && event.getGuild() != null && config.linkedRoleId != null && event.getMember() != null) {
				Role role = event.getGuild().getRoleById(config.linkedRoleId);
				if (role != null) {
					event.getGuild().removeRoleFromMember(event.getMember(), role).queue(null, e -> { });
				}
			}
			event.getHook().sendMessage(removed ? "Unlinked. The Velo Neon cape is removed - link again any time." : "You weren't linked.").queue();
			if (vip != null) {
				vip.runNow();
			}
		} catch (ServerApi.ApiException e) {
			event.getHook().sendMessage("❌ " + e.getMessage()).queue();
		}
	}

	// ---- /roles ----

	private void roles(SlashCommandInteractionEvent event, String sub) {
		Guild guild = event.getGuild();
		switch (sub) {
			case "add" -> {
				Role role = event.getOption("role").getAsRole();
				if (config.roles.size() >= RolePanel.MAX_ROLES && config.roles.stream().noneMatch(r -> r.roleId.equals(role.getId()))) {
					event.reply("A panel holds at most " + RolePanel.MAX_ROLES + " roles.").setEphemeral(true).queue();
					return;
				}
				config.roles.removeIf(r -> r.roleId.equals(role.getId()));
				BotConfig.PanelRole entry = new BotConfig.PanelRole();
				entry.roleId = role.getId();
				entry.label = event.getOption("label").getAsString();
				entry.emoji = event.getOption("emoji") == null ? null : event.getOption("emoji").getAsString();
				entry.description = event.getOption("description") == null ? null : event.getOption("description").getAsString();
				config.roles.add(entry);
				config.save();
				event.reply("Added " + role.getAsMention() + " to the panel. Run `/roles panel` to update the message.").setEphemeral(true).queue();
			}
			case "remove" -> {
				Role role = event.getOption("role").getAsRole();
				boolean removed = config.roles.removeIf(r -> r.roleId.equals(role.getId()));
				config.save();
				event.reply(removed ? "Removed " + role.getAsMention() + ". Run `/roles panel` to update the message." : "That role isn't on the panel.").setEphemeral(true).queue();
			}
			case "list" -> {
				StringBuilder list = new StringBuilder();
				for (BotConfig.PanelRole r : config.roles) {
					list.append(r.emoji == null ? "" : r.emoji + " ").append("**").append(r.label).append("** - <@&").append(r.roleId).append(">\n");
				}
				event.reply(list.length() == 0 ? "No roles yet - `/roles add`." : list.toString()).setEphemeral(true).queue();
			}
			case "panel" -> {
				if (!(event.getOption("channel").getAsChannel() instanceof TextChannel channel)) {
					event.reply("Pick a text channel.").setEphemeral(true).queue();
					return;
				}
				if (event.getOption("title") != null) {
					config.rolesPanelTitle = event.getOption("title").getAsString();
				}
				if (event.getOption("text") != null) {
					config.rolesPanelText = event.getOption("text").getAsString();
				}
				config.save();
				event.deferReply(true).queue();
				RolePanel.publish(guild, config, channel, () -> event.getHook().sendMessage("Panel posted in " + channel.getAsMention()).queue(),
						e -> event.getHook().sendMessage("Couldn't post it: " + e.getMessage()).queue());
			}
			default -> event.reply("Unknown subcommand").setEphemeral(true).queue();
		}
	}

	// ---- /ticket ----

	private void ticket(SlashCommandInteractionEvent event, String sub) {
		switch (sub) {
			case "panel" -> {
				if (!(event.getOption("channel").getAsChannel() instanceof TextChannel channel)) {
					event.reply("Pick a text channel.").setEphemeral(true).queue();
					return;
				}
				if (event.getOption("title") != null) {
					config.ticketPanelTitle = event.getOption("title").getAsString();
				}
				if (event.getOption("text") != null) {
					config.ticketPanelText = event.getOption("text").getAsString();
				}
				config.save();
				event.deferReply(true).queue();
				tickets.postPanel(channel, config.ticketPanelTitle, config.ticketPanelText, () -> event.getHook().sendMessage("Panel posted in " + channel.getAsMention()).queue(),
						e -> event.getHook().sendMessage("Couldn't post it: " + e.getMessage()).queue());
			}
			case "types" -> {
				StringBuilder out = new StringBuilder();
				for (BotConfig.TicketType t : config.ticketTypes) {
					out.append(t.emoji == null ? "" : t.emoji + " ").append("**").append(t.label).append("** (`").append(t.id).append("`)\n")
							.append("  questions: ").append(t.questions.size()).append("  ·  staff: ")
							.append(t.staffRoleIds.isEmpty() ? "general support role" : String.join(" ", t.staffRoleIds.stream().map(id -> "<@&" + id + ">").toList()))
							.append("  ·  category: ").append(t.categoryId == null ? "general" : "<#" + t.categoryId + ">").append("\n");
				}
				event.reply(out + "\nChange questions, colours, names and more in `bot-config.json`, then `/setup reload`.").setEphemeral(true).queue();
			}
			case "staff-add", "staff-remove" -> {
				BotConfig.TicketType type = config.type(event.getOption("type").getAsString());
				String roleId = event.getOption("role").getAsRole().getId();
				if (sub.equals("staff-add")) {
					if (!type.staffRoleIds.contains(roleId)) {
						type.staffRoleIds.add(roleId);
					}
				} else {
					type.staffRoleIds.remove(roleId);
				}
				config.save();
				event.reply("Done - applies to tickets opened from now on.").setEphemeral(true).queue();
			}
			case "category" -> {
				if (!(event.getOption("category").getAsChannel() instanceof Category category)) {
					event.reply("Pick a category (a channel group).").setEphemeral(true).queue();
					return;
				}
				config.type(event.getOption("type").getAsString()).categoryId = category.getId();
				config.save();
				event.reply("Tickets of that type now open in **" + category.getName() + "**.").setEphemeral(true).queue();
			}
			case "add", "remove" -> {
				BotConfig.Ticket ticket = config.tickets.get(event.getChannelId());
				if (ticket == null || !(event.getChannel() instanceof TextChannel channel)) {
					event.reply("Use this inside a ticket.").setEphemeral(true).queue();
					return;
				}
				var user = event.getOption("user").getAsUser();
				if (sub.equals("add")) {
					channel.upsertPermissionOverride(event.getGuild().getMember(user) != null ? event.getGuild().getMember(user) : event.getGuild().getSelfMember())
							.grant(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY, Permission.MESSAGE_ATTACH_FILES).queue();
					event.reply(user.getAsMention() + " was added to the ticket.").queue();
				} else {
					var member = event.getGuild().getMember(user);
					if (member != null) {
						channel.upsertPermissionOverride(member).deny(Permission.VIEW_CHANNEL).queue();
					}
					event.reply(user.getAsMention() + " was removed from the ticket.").queue();
				}
			}
			case "close" -> {
				BotConfig.Ticket ticket = config.tickets.get(event.getChannelId());
				if (ticket == null || !(event.getChannel() instanceof TextChannel channel)) {
					event.reply("Use this inside a ticket.").setEphemeral(true).queue();
					return;
				}
				if (!tickets.isStaff(event.getMember(), ticket) && !event.getUser().getId().equals(ticket.userId)) {
					event.reply("Only the person who opened the ticket or the team can close it.").setEphemeral(true).queue();
					return;
				}
				tickets.closeTicket(channel, ticket, event.getUser(), event.getOption("reason") == null ? "" : event.getOption("reason").getAsString(), event);
			}
			default -> event.reply("Unknown subcommand").setEphemeral(true).queue();
		}
	}

	// ---- /setup ----

	private void setup(SlashCommandInteractionEvent event, String sub) {
		Guild guild = event.getGuild();
		switch (sub) {
			case "link-channel" -> {
				if (!(event.getOption("channel").getAsChannel() instanceof TextChannel channel)) {
					event.reply("Pick a text channel.").setEphemeral(true).queue();
					return;
				}
				config.linkChannelId = channel.getId();
				config.save();
				event.reply("Link codes will be accepted in " + channel.getAsMention()
						+ " and deleted before they're redeemed.").setEphemeral(true).queue();
			}
			case "ticket-category" -> {
				if (!(event.getOption("category").getAsChannel() instanceof Category category)) {
					event.reply("Pick a category (a channel group).").setEphemeral(true).queue();
					return;
				}
				config.ticketCategoryId = category.getId();
				config.save();
				event.reply("Tickets open in **" + category.getName() + "** now.").setEphemeral(true).queue();
			}
			case "ticket-log" -> {
				config.ticketLogChannelId = event.getOption("channel").getAsChannel().getId();
				config.save();
				event.reply("Ticket logs go to <#" + config.ticketLogChannelId + "> now.").setEphemeral(true).queue();
			}
			case "support-role" -> {
				config.supportRoleId = event.getOption("role").getAsRole().getId();
				config.save();
				event.reply("<@&" + config.supportRoleId + "> handles every ticket now.").setEphemeral(true).queue();
			}
			case "linked-role" -> {
				config.linkedRoleId = event.getOption("role").getAsRole().getId();
				config.save();
				event.reply("Members who link their account get <@&" + config.linkedRoleId + ">.").setEphemeral(true).queue();
			}
			case "webhooks" -> {
				event.deferReply(true).queue();
				StringBuilder out = new StringBuilder("Put these in the Velo server's settings (environment):\n");
				List<Runnable> pending = new ArrayList<>();
				int[] left = {0};
				for (String[] pair : new String[][] {{"donations", "VELO_DISCORD_DONATIONS_WEBHOOK"}, {"reports", "VELO_DISCORD_REPORTS_WEBHOOK"},
						{"player-reports", "VELO_DISCORD_PLAYER_REPORTS_WEBHOOK"}}) {
					var option = event.getOption(pair[0]);
					if (option == null || !(option.getAsChannel() instanceof TextChannel channel)) {
						continue;
					}
					left[0]++;
					channel.createWebhook("Velo Client").queue(webhook -> {
						synchronized (out) {
							out.append("`").append(pair[1]).append("=").append(webhook.getUrl()).append("`\n");
							if (--left[0] == 0) {
								event.getHook().sendMessage(out.toString()).queue();
							}
						}
					}, e -> event.getHook().sendMessage("Couldn't create the " + pair[0] + " webhook: " + e.getMessage()).queue());
				}
				if (left[0] == 0) {
					event.getHook().sendMessage("Pick at least one channel.").queue();
				}
			}
			case "vip-role" -> {
				config.vipRoleId = event.getOption("role").getAsRole().getId();
				config.save();
				event.reply("<@&" + config.vipRoleId + "> is the VIP role now: Velo+ members (who linked their Discord) get it, and it's taken away when Velo+ ends. "
						+ "Checked every minute. Move my role above it in the server settings.").setEphemeral(true).queue();
				if (vip != null) {
					vip.runNow();
				}
			}
			case "staff-role" -> {
				Role role = event.getOption("role").getAsRole();
				String level = event.getOption("level").getAsString();
				config.staffRoles.put(role.getId(), level);
				config.save();
				String perks = level.equals("helper") ? "the Velo Staff cape and Velo+ (no moderation tools)"
						: "the Velo Staff cape, Velo+ and `/velostaff` in game";
				event.reply(role.getAsMention() + " now makes members Velo **" + switch (level) {
					case "admin" -> "admins";
					case "helper" -> "helpers";
					default -> "moderators";
				} + "** - they get " + perks + " within a minute (only once they've linked their account), for as long as they have the role.")
						.setEphemeral(true).queue();
				staff.runNow();
			}
			case "staff-role-remove" -> {
				Role role = event.getOption("role").getAsRole();
				boolean removed = config.staffRoles.remove(role.getId()) != null;
				config.save();
				event.reply(removed ? role.getAsMention() + " is no longer a staff role - its members lose staff perks within a minute." : "That role wasn't a staff role.")
						.setEphemeral(true).queue();
				staff.runNow();
			}
			case "welcome" -> {
				if (!(event.getOption("channel").getAsChannel() instanceof TextChannel channel)) {
					event.reply("Pick a text channel.").setEphemeral(true).queue();
					return;
				}
				config.welcomeChannelId = channel.getId();
				var rules = event.getOption("rules");
				config.welcomeRulesChannelId = rules == null ? null : rules.getAsChannel().getId();
				config.save();
				event.reply("New members get a welcome card in " + channel.getAsMention() + ". Try `/setup welcome-test` to see one.").setEphemeral(true).queue();
			}
			case "welcome-off" -> {
				config.welcomeChannelId = null;
				config.save();
				event.reply("Welcome messages are off.").setEphemeral(true).queue();
			}
			case "welcome-test" -> {
				if (!(event.getChannel() instanceof TextChannel channel)) {
					event.reply("Run this in a text channel.").setEphemeral(true).queue();
					return;
				}
				welcomer.test(channel, event.getGuild(), event.getUser());
				event.reply("Posting a test welcome...").setEphemeral(true).queue();
			}
			case "boost-channel" -> {
				config.boostChannelId = event.getOption("channel").getAsChannel().getId();
				config.save();
				event.reply("Boost thank-yous go to <#" + config.boostChannelId + "> now. Tip: `/post boosters` puts a permanent Claim button somewhere.")
						.setEphemeral(true).queue();
			}
			case "honeypot" -> {
				if (!(event.getOption("channel").getAsChannel() instanceof TextChannel channel)) {
					event.reply("Pick a text channel.").setEphemeral(true).queue();
					return;
				}
				config.honeypotChannelId = channel.getId();
				if (event.getOption("action") != null) {
					config.honeypotAction = event.getOption("action").getAsString();
				}
				config.save();
				event.deferReply(true).queue();
				Honeypot.postWarning(channel, () -> event.getHook().sendMessage("🪤 " + channel.getAsMention() + " is a bot trap now (" + config.honeypotAction
						+ "). Keep it visible and near the top of the channel list - bots post in the first channels they see. "
						+ "Staff and anyone with Manage Messages are never caught. Catches are logged in "
						+ (config.modLogChannelId != null ? "<#" + config.modLogChannelId + ">" : config.ticketLogChannelId != null ? "<#" + config.ticketLogChannelId + ">" : "the console - set `/setup mod-log`") + ".").queue(),
						e -> event.getHook().sendMessage("Saved, but couldn't post the warning: " + e.getMessage()).queue());
			}
			case "mod-log" -> {
				config.modLogChannelId = event.getOption("channel").getAsChannel().getId();
				config.save();
				event.reply("Honeypot catches and giveaway problems are logged in <#" + config.modLogChannelId + "> now.").setEphemeral(true).queue();
			}
			case "reload" -> {
				try {
					config.reload();
					event.reply("Reloaded bot-config.json (" + config.ticketTypes.size() + " ticket types, " + config.roles.size() + " roles). "
							+ "Run `/ticket panel` / `/roles panel` again to refresh the posted panels.").setEphemeral(true).queue();
				} catch (Exception e) {
					event.reply("Couldn't read bot-config.json: " + e.getMessage()).setEphemeral(true).queue();
				}
			}
			case "show" -> event.replyEmbeds(new EmbedBuilder().setTitle("Velo bot settings").setColor(0xFF4D4D)
					.addField("Ticket category", config.ticketCategoryId == null ? "not set" : "<#" + config.ticketCategoryId + ">", true)
					.addField("Ticket log", config.ticketLogChannelId == null ? "not set" : "<#" + config.ticketLogChannelId + ">", true)
					.addField("Link-code channel", config.linkChannelId == null ? "not set" : "<#" + config.linkChannelId + ">", true)
					.addField("Support role", config.supportRoleId == null ? "not set" : "<@&" + config.supportRoleId + ">", true)
					.addField("Linked role", config.linkedRoleId == null ? "not set" : "<@&" + config.linkedRoleId + ">", true)
					.addField("VIP role (Velo+)", config.vipRoleId == null ? "not set" : "<@&" + config.vipRoleId + ">", true)
					.addField("Ticket types", String.valueOf(config.ticketTypes.size()), true).addField("Ping roles", String.valueOf(config.roles.size()), true)
					.addField("Open tickets", String.valueOf(config.tickets.size()), true)
					.addField("Staff roles", config.staffRoles.isEmpty() ? "not set" : String.join("\n", config.staffRoles.entrySet().stream()
							.map(e -> "<@&" + e.getKey() + "> - " + e.getValue()).toList()), true)
					.addField("Boost channel", config.boostChannelId == null ? "Discord's boost message" : "<#" + config.boostChannelId + ">", true)
					.addField("Honeypot", config.honeypotChannelId == null ? "not set" : "<#" + config.honeypotChannelId + "> (" + config.honeypotAction + ")", true)
					.addField("Mod log", config.modLogChannelId == null ? "ticket log" : "<#" + config.modLogChannelId + ">", true)
					.addField("Giveaways", config.giveaways.values().stream().filter(g -> !g.ended).count() + " running", true).build()).setEphemeral(true).queue();
			default -> event.reply("Unknown subcommand").setEphemeral(true).queue();
		}
	}

	private void announce(SlashCommandInteractionEvent event) {
		if (!(event.getOption("channel").getAsChannel() instanceof TextChannel channel)) {
			event.reply("Pick a text channel.").setEphemeral(true).queue();
			return;
		}
		Role role = event.getOption("role").getAsRole();
		EmbedBuilder embed = new EmbedBuilder().setColor(0xFF4D4D).setDescription(event.getOption("message").getAsString()).setTimestamp(java.time.Instant.now());
		if (event.getOption("title") != null) {
			embed.setTitle(event.getOption("title").getAsString());
		}
		channel.sendMessage(role.getAsMention()).setEmbeds(embed.build()).setAllowedMentions(List.of(net.dv8tion.jda.api.entities.Message.MentionType.ROLE))
				.queue(m -> event.reply("Posted in " + channel.getAsMention()).setEphemeral(true).queue(),
						e -> event.reply("Couldn't post: " + e.getMessage()).setEphemeral(true).queue());
	}

	private void post(SlashCommandInteractionEvent event) {
		if (!(event.getOption("channel").getAsChannel() instanceof TextChannel channel)) {
			event.reply("Pick a text channel.").setEphemeral(true).queue();
			return;
		}
		event.deferReply(true).queue();
		InfoPosts.post(event.getOption("post").getAsString(), channel, config, () -> event.getHook().sendMessage("Posted in " + channel.getAsMention()).queue(),
				e -> event.getHook().sendMessage("Couldn't post: " + e.getMessage()).queue());
	}

	@Override
	public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
		if (event.getName().equals("giveaway") && event.getFocusedOption().getName().equals("prize") && giveaways != null) {
			giveaways.autocomplete(event);
		}
	}

	// ---- Buttons and forms ----

	@Override
	public void onButtonInteraction(ButtonInteractionEvent event) {
		String id = event.getComponentId();
		if (id.startsWith(RolePanel.PREFIX)) {
			RolePanel.toggle(event);
		} else if (id.startsWith(Tickets.OPEN)) {
			tickets.askSubject(event);
		} else if (id.equals(Tickets.CLAIM)) {
			tickets.claim(event);
		} else if (id.equals(Tickets.CLOSE)) {
			tickets.askClose(event);
		} else if (id.equals(Boosters.CLAIM)) {
			boosters.claim(event);
		} else if (id.startsWith(Giveaways.ENTER) && giveaways != null) {
			giveaways.enter(event);
		}
	}

	@Override
	public void onModalInteraction(ModalInteractionEvent event) {
		if (event.getModalId().startsWith(Tickets.MODAL)) {
			tickets.open(event);
		} else if (event.getModalId().equals(Tickets.CLOSE_MODAL)) {
			tickets.close(event);
		}
	}

	@Override
	public void onGuildMemberJoin(GuildMemberJoinEvent event) {
		welcomer.onJoin(event.getGuild(), event.getMember());
	}

	/** Leaving the server or losing a staff role ends staff perks right away instead of at the next 5-minute sync. */
	@Override
	public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
		if (staff != null && !config.staffRoles.isEmpty()) {
			staff.soon();
		}
	}

	@Override
	public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) {
		if (staff != null && event.getRoles().stream().anyMatch(r -> config.staffRoles.containsKey(r.getId()))) {
			staff.soon();
		}
	}

	@Override
	public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) {
		if (staff != null && event.getRoles().stream().anyMatch(r -> config.staffRoles.containsKey(r.getId()))) {
			staff.soon();
		}
	}

	@Override
	public void onReady(ReadyEvent event) {
		System.out.println("Connected - " + event.getGuildAvailableCount() + " server(s)");
	}
}
