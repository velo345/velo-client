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
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
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

/**
 * The Velo Client Discord bot: {@code /link} (unlocks the Velo Neon cape), a ping-role panel, a ticket
 * system with per-type forms and roles, and ticket logs. Settings come from commands and from
 * {@code bot-config.json} (see bot/README.md). Run: {@code DISCORD_TOKEN=... VELO_SERVER_URL=...
 * VELO_ADMIN_TOKEN=... java -jar velo-bot.jar}.
 */
public final class VeloBot extends ListenerAdapter {

	private final BotConfig config;
	private final ServerApi server;
	private final Tickets tickets;

	private VeloBot(BotConfig config, ServerApi server) {
		this.config = config;
		this.server = server;
		this.tickets = new Tickets(config);
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
		commands.add(Commands.slash("link", "Link your Minecraft account to unlock the Velo Neon cape")
				.addOption(OptionType.STRING, "code", "The code from Velo Client (Store > Capes > Link Discord)", true));
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
				new SubcommandData("ticket-category", "The category tickets are created in").addOptions(new OptionData(OptionType.CHANNEL, "category", "The category", true)),
				new SubcommandData("ticket-log", "Where ticket logs and transcripts go").addOptions(new OptionData(OptionType.CHANNEL, "channel", "The channel", true)),
				new SubcommandData("support-role", "The role that handles every ticket").addOptions(new OptionData(OptionType.ROLE, "role", "The role", true)),
				new SubcommandData("linked-role", "A role for members who linked their Minecraft account").addOptions(new OptionData(OptionType.ROLE, "role", "The role", true)),
				new SubcommandData("webhooks", "Create the donation and bug-report webhooks (the URLs go in the server's settings)").addOptions(
						new OptionData(OptionType.CHANNEL, "donations", "Channel for purchase announcements", false),
						new OptionData(OptionType.CHANNEL, "reports", "Channel for bug / crash reports", false)),
				new SubcommandData("reload", "Re-read bot-config.json after editing it"),
				new SubcommandData("show", "Show the current settings")));
		commands.add(Commands.slash("announce", "Post an announcement that pings a role").setDefaultPermissions(admin)
				.addOptions(new OptionData(OptionType.CHANNEL, "channel", "Where", true), new OptionData(OptionType.ROLE, "role", "Who to ping", true),
						new OptionData(OptionType.STRING, "message", "The announcement", true), new OptionData(OptionType.STRING, "title", "Title", false)));
		if (guildId != null && jda.getGuildById(guildId) != null) {
			jda.getGuildById(guildId).updateCommands().addCommands(commands).queue();
		} else {
			jda.updateCommands().addCommands(commands).queue();
		}
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
				case "link" -> link(event);
				case "unlink" -> unlink(event);
				case "roles" -> roles(event, sub);
				case "ticket" -> ticket(event, sub);
				case "setup" -> setup(event, sub);
				case "announce" -> announce(event);
				default -> event.reply("Unknown command").setEphemeral(true).queue();
			}
		} catch (RuntimeException e) {
			e.printStackTrace();
			if (!event.isAcknowledged()) {
				event.reply("Something went wrong: " + e.getMessage()).setEphemeral(true).queue();
			}
		}
	}

	// ---- /link, /unlink ----

	private void link(SlashCommandInteractionEvent event) {
		String code = event.getOption("code").getAsString();
		event.deferReply(true).queue();
		try {
			ServerApi.Linked linked = server.link(code, event.getUser().getId(), event.getUser().getName());
			Guild guild = event.getGuild();
			if (guild != null && config.linkedRoleId != null && event.getMember() != null) {
				Role role = guild.getRoleById(config.linkedRoleId);
				if (role != null && guild.getSelfMember().canInteract(role)) {
					guild.addRoleToMember(event.getMember(), role).queue(null, e -> { });
				}
			}
			event.getHook().sendMessage("✅ Linked to **" + linked.username() + "**! You unlocked the **Velo Neon** cape - it's in your cosmetics next time you open Velo Client.").queue();
		} catch (ServerApi.ApiException e) {
			event.getHook().sendMessage("❌ " + e.getMessage()).queue();
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
				for (String[] pair : new String[][] {{"donations", "VELO_DISCORD_DONATIONS_WEBHOOK"}, {"reports", "VELO_DISCORD_REPORTS_WEBHOOK"}}) {
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
					event.getHook().sendMessage("Pick a donations and/or reports channel.").queue();
				}
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
					.addField("Support role", config.supportRoleId == null ? "not set" : "<@&" + config.supportRoleId + ">", true)
					.addField("Linked role", config.linkedRoleId == null ? "not set" : "<@&" + config.linkedRoleId + ">", true)
					.addField("Ticket types", String.valueOf(config.ticketTypes.size()), true).addField("Ping roles", String.valueOf(config.roles.size()), true)
					.addField("Open tickets", String.valueOf(config.tickets.size()), true).build()).setEphemeral(true).queue();
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
	public void onReady(ReadyEvent event) {
		System.out.println("Connected - " + event.getGuildAvailableCount() + " server(s)");
	}
}
