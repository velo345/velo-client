package net.veloclient.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tickets: a panel with one button per kind of help; pressing one asks for a subject, then opens a
 * private channel (you, the support role and the bot) with Claim / Close buttons. Closing saves a
 * transcript to the ticket-log channel (and your DMs) and deletes the channel. Opening, claiming and
 * closing are all logged there.
 */
final class Tickets {

	static final String OPEN = "ticket:open:";
	static final String MODAL = "ticket:modal:";
	static final String CLAIM = "ticket:claim";
	static final String CLOSE = "ticket:close";
	static final String CLOSE_MODAL = "ticket:closemodal";

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

	private final BotConfig config;

	Tickets(BotConfig config) {
		this.config = config;
	}

	private static int colour(String hex, int fallback) {
		try {
			return Integer.parseInt(hex.replace("#", ""), 16);
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static ButtonStyle style(String name) {
		return switch (name == null ? "" : name.toLowerCase()) {
			case "secondary", "grey", "gray" -> ButtonStyle.SECONDARY;
			case "success", "green" -> ButtonStyle.SUCCESS;
			case "danger", "red" -> ButtonStyle.DANGER;
			default -> ButtonStyle.PRIMARY;
		};
	}

	private static Emoji emoji(String text) {
		try {
			return text == null || text.isBlank() ? null : Emoji.fromFormatted(text);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---- The panel ----

	void postPanel(TextChannel channel, String title, String text, Runnable done, java.util.function.Consumer<Throwable> failed) {
		EmbedBuilder embed = new EmbedBuilder().setTitle("🎫  " + title).setColor(0xFF4D4D).setDescription(text);
		StringBuilder kinds = new StringBuilder();
		List<Button> buttons = new ArrayList<>();
		for (BotConfig.TicketType type : config.ticketTypes) {
			kinds.append(type.emoji == null ? "" : type.emoji + " ").append("**").append(type.label).append("**");
			if (type.description != null && !type.description.isBlank()) {
				kinds.append(" - ").append(type.description);
			}
			kinds.append("\n");
			Button button = Button.of(style(type.buttonStyle), OPEN + type.id, type.label);
			Emoji e = emoji(type.emoji);
			buttons.add(e == null ? button : button.withEmoji(e));
		}
		embed.addField("What can we help with?", kinds.toString(), false);
		embed.setFooter("Only you and the team can see your ticket");
		List<ActionRow> rows = new ArrayList<>();
		for (int i = 0; i < buttons.size(); i += 5) {
			rows.add(ActionRow.of(buttons.subList(i, Math.min(buttons.size(), i + 5))));
		}
		channel.sendMessageEmbeds(embed.build()).setComponents(rows).queue(m -> done.run(), failed::accept);
	}

	// ---- Opening ----

	private String categoryFor(BotConfig.TicketType type) {
		return type.categoryId != null && !type.categoryId.isBlank() ? type.categoryId : config.ticketCategoryId;
	}

	/** The button on the panel: the type's own form. */
	void askSubject(ButtonInteractionEvent event) {
		BotConfig.TicketType type = config.type(event.getComponentId().substring(OPEN.length()));
		long open = config.tickets.values().stream().filter(t -> t.userId.equals(event.getUser().getId())).count();
		long openOfType = config.tickets.values().stream().filter(t -> t.userId.equals(event.getUser().getId()) && t.type.equals(type.id)).count();
		if (open >= config.maxOpenTicketsPerUser || type.maxOpen > 0 && openOfType >= type.maxOpen) {
			event.reply("You already have an open ticket" + (open > 1 ? "s" : "") + " - please use that one (or close it) first.").setEphemeral(true).queue();
			return;
		}
		if (categoryFor(type) == null) {
			event.reply("Tickets aren't set up yet - an admin needs to run `/setup ticket-category` first.").setEphemeral(true).queue();
			return;
		}
		Modal.Builder modal = Modal.create(MODAL + type.id, ((type.emoji == null ? "" : type.emoji + " ") + type.label));
		List<BotConfig.Question> questions = type.questions.isEmpty()
				? List.of(new BotConfig.Question("message", "How can we help?", "paragraph", null, true, 1500)) : type.questions;
		// A Discord form holds five questions at most.
		for (BotConfig.Question q : questions.subList(0, Math.min(5, questions.size()))) {
			TextInput.Builder input = TextInput.create(q.id, q.label.length() > 45 ? q.label.substring(0, 45) : q.label,
					"paragraph".equalsIgnoreCase(q.style) ? TextInputStyle.PARAGRAPH : TextInputStyle.SHORT).setRequired(q.required)
					.setMaxLength(Math.max(1, Math.min(4000, q.maxLength)));
			if (q.placeholder != null && !q.placeholder.isBlank()) {
				input.setPlaceholder(q.placeholder.length() > 100 ? q.placeholder.substring(0, 100) : q.placeholder);
			}
			modal.addComponents(ActionRow.of(input.build()));
		}
		event.replyModal(modal.build()).queue();
	}

	/** The submitted form: open the channel. */
	void open(ModalInteractionEvent event) {
		Guild guild = event.getGuild();
		Member member = event.getMember();
		BotConfig.TicketType type = config.type(event.getModalId().substring(MODAL.length()));
		String categoryId = categoryFor(type);
		Category category = guild == null || categoryId == null ? null : guild.getCategoryById(categoryId);
		if (guild == null || member == null || category == null) {
			event.reply("Tickets aren't set up properly - please tell an admin.").setEphemeral(true).queue();
			return;
		}
		java.util.Map<String, String> answers = new java.util.LinkedHashMap<>();
		for (BotConfig.Question q : type.questions.subList(0, Math.min(5, type.questions.size()))) {
			var value = event.getValue(q.id);
			if (value != null && !value.getAsString().isBlank()) {
				answers.put(q.label, value.getAsString().strip());
			}
		}
		var onlyQuestion = type.questions.isEmpty() ? event.getValue("message") : null;
		if (onlyQuestion != null) {
			answers.put("How can we help?", onlyQuestion.getAsString().strip());
		}
		String subject = answers.isEmpty() ? type.label : firstLine(answers.values().iterator().next(), 80);
		int number;
		synchronized (config) {
			number = ++config.ticketCounter;
			config.save();
		}
		String channelName = type.channelName.replace("{number}", String.format("%04d", number)).replace("{user}", member.getUser().getName())
				.replace("{type}", type.id).toLowerCase().replaceAll("[^a-z0-9-_]", "");
		EnumSet<Permission> allow = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
				Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS);
		var action = guild.createTextChannel(channelName, category)
				.setTopic(type.label + " · " + subject + " · opened by " + member.getUser().getName())
				.addPermissionOverride(guild.getPublicRole(), null, EnumSet.of(Permission.VIEW_CHANNEL))
				.addMemberPermissionOverride(member.getIdLong(), allow, null)
				.addMemberPermissionOverride(guild.getSelfMember().getIdLong(), EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
						Permission.MANAGE_CHANNEL, Permission.MESSAGE_HISTORY, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ATTACH_FILES), null);
		for (String roleId : staffRoles(type)) {
			if (guild.getRoleById(roleId) != null) {
				action = action.addRolePermissionOverride(Long.parseLong(roleId), allow, null);
			}
		}
		event.deferReply(true).queue();
		int finalNumber = number;
		action.queue(channel -> {
			BotConfig.Ticket ticket = new BotConfig.Ticket();
			ticket.number = finalNumber;
			ticket.channelId = channel.getId();
			ticket.userId = member.getId();
			ticket.userName = member.getUser().getName();
			ticket.type = type.id;
			ticket.subject = subject;
			ticket.createdAt = System.currentTimeMillis();
			ticket.answers = answers;
			synchronized (config) {
				config.tickets.put(channel.getId(), ticket);
				config.save();
			}
			EmbedBuilder embed = new EmbedBuilder().setTitle((type.emoji == null ? "" : type.emoji + "  ") + type.label + "  ·  #" + String.format("%04d", finalNumber))
					.setColor(colour(type.color, 0xFF4D4D)).setThumbnail(member.getEffectiveAvatarUrl())
					.addField("Opened by", member.getAsMention(), true).addField("Ticket", "#" + String.format("%04d", finalNumber), true)
					.setFooter(type.welcome).setTimestamp(Instant.now());
			for (var answer : answers.entrySet()) {
				embed.addField(answer.getKey(), answer.getValue().length() > 1000 ? answer.getValue().substring(0, 1000) + "…" : answer.getValue(), false);
			}
			StringBuilder ping = new StringBuilder(member.getAsMention());
			List<String> pings = type.pingRoleIds.isEmpty() ? staffRoles(type) : type.pingRoleIds;
			for (String roleId : pings) {
				ping.append(" <@&").append(roleId).append(">");
			}
			channel.sendMessage(ping.toString()).setEmbeds(embed.build()).setComponents(ActionRow.of(
					Button.success(CLAIM, "Claim").withEmoji(Emoji.fromUnicode("🙋")),
					Button.danger(CLOSE, "Close").withEmoji(Emoji.fromUnicode("🔒")))).queue();
			log(guild, new EmbedBuilder().setTitle("🎫  Ticket opened  ·  #" + String.format("%04d", finalNumber)).setColor(0x57F287)
					.addField("User", member.getAsMention() + " (" + member.getUser().getName() + ")", true).addField("Type", type.label, true)
					.addField("Channel", channel.getAsMention(), true).addField("Subject", subject, false).setTimestamp(Instant.now()).build(), null);
			event.getHook().sendMessage("Your ticket is open: " + channel.getAsMention()).queue();
		}, error -> event.getHook().sendMessage("Couldn't open the ticket: " + error.getMessage()).queue());
	}

	/** The roles that handle this type: its own, plus the general support role. */
	private List<String> staffRoles(BotConfig.TicketType type) {
		List<String> roles = new ArrayList<>(type.staffRoleIds);
		if (config.supportRoleId != null && !roles.contains(config.supportRoleId)) {
			roles.add(config.supportRoleId);
		}
		return roles;
	}

	private static String firstLine(String text, int max) {
		String line = text.lines().findFirst().orElse(text).strip();
		return line.length() > max ? line.substring(0, max - 1) + "…" : line;
	}

	// ---- In a ticket ----

	boolean isTicket(String channelId) {
		return config.tickets.containsKey(channelId);
	}

	/** Whether this member handles tickets of the ticket's type (its staff roles, the general support role, or Manage Server). */
	boolean isStaff(Member member, BotConfig.Ticket ticket) {
		if (member == null) {
			return false;
		}
		if (member.hasPermission(Permission.MANAGE_SERVER)) {
			return true;
		}
		List<String> roles = staffRoles(config.type(ticket.type));
		return member.getRoles().stream().anyMatch(r -> roles.contains(r.getId()));
	}

	void claim(ButtonInteractionEvent event) {
		BotConfig.Ticket ticket = config.tickets.get(event.getChannelId());
		if (ticket == null) {
			event.reply("This isn't an open ticket.").setEphemeral(true).queue();
			return;
		}
		if (!isStaff(event.getMember(), ticket)) {
			event.reply("Only the team can claim tickets.").setEphemeral(true).queue();
			return;
		}
		if (ticket.claimedBy != null) {
			event.reply("Already claimed by <@" + ticket.claimedBy + ">.").setEphemeral(true).queue();
			return;
		}
		ticket.claimedBy = event.getUser().getId();
		config.save();
		event.reply("🙋 " + event.getUser().getAsMention() + " is taking care of this ticket.").queue();
		log(event.getGuild(), new EmbedBuilder().setTitle("🙋  Ticket claimed  ·  #" + String.format("%04d", ticket.number)).setColor(0x5865F2)
				.addField("By", event.getUser().getAsMention(), true).addField("Channel", "<#" + ticket.channelId + ">", true)
				.setTimestamp(Instant.now()).build(), null);
	}

	/** Close button: ask for an optional reason. */
	void askClose(ButtonInteractionEvent event) {
		BotConfig.Ticket ticket = config.tickets.get(event.getChannelId());
		if (ticket == null) {
			event.reply("This isn't an open ticket.").setEphemeral(true).queue();
			return;
		}
		if (!isStaff(event.getMember(), ticket) && !event.getUser().getId().equals(ticket.userId)) {
			event.reply("Only the person who opened the ticket or the team can close it.").setEphemeral(true).queue();
			return;
		}
		event.replyModal(Modal.create(CLOSE_MODAL, "Close ticket #" + String.format("%04d", ticket.number))
				.addComponents(ActionRow.of(TextInput.create("reason", "Reason (optional)", TextInputStyle.SHORT).setRequired(false).setMaxLength(200).build()))
				.build()).queue();
	}

	void close(ModalInteractionEvent event) {
		BotConfig.Ticket ticket = config.tickets.get(event.getChannelId());
		if (ticket == null || !(event.getChannel() instanceof TextChannel channel)) {
			event.reply("This isn't an open ticket.").setEphemeral(true).queue();
			return;
		}
		String reason = event.getValue("reason") == null ? "" : event.getValue("reason").getAsString().strip();
		closeTicket(channel, ticket, event.getUser(), reason, event);
	}

	/** Used by the Close modal and {@code /ticket close}. */
	void closeTicket(TextChannel channel, BotConfig.Ticket ticket, User closer, String reason, net.dv8tion.jda.api.interactions.callbacks.IReplyCallback reply) {
		reply.reply("🔒 Closing this ticket - saving the transcript...").queue();
		channel.getIterableHistory().takeAsync(3000).thenAccept(newestFirst -> {
			List<Message> messages = new ArrayList<>(newestFirst);
			Collections.reverse(messages);
			byte[] transcript = transcript(ticket, messages);
			EmbedBuilder embed = new EmbedBuilder().setTitle("🔒  Ticket closed  ·  #" + String.format("%04d", ticket.number)).setColor(0xED4245)
					.addField("Opened by", "<@" + ticket.userId + "> (" + ticket.userName + ")", true).addField("Type", config.type(ticket.type).label, true)
					.addField("Claimed by", ticket.claimedBy == null ? "nobody" : "<@" + ticket.claimedBy + ">", true)
					.addField("Closed by", closer.getAsMention(), true).addField("Messages", String.valueOf(messages.size()), true)
					.addField("Open for", duration(System.currentTimeMillis() - ticket.createdAt), true).addField("Subject", ticket.subject, false)
					.setTimestamp(Instant.now());
			if (!reason.isBlank()) {
				embed.addField("Reason", reason, false);
			}
			String fileName = "ticket-" + String.format("%04d", ticket.number) + ".txt";
			log(channel.getGuild(), embed.build(), FileUpload.fromData(transcript, fileName));
			// The person who opened it gets the transcript too (if their DMs are open).
			channel.getJDA().retrieveUserById(ticket.userId).queue(user -> user.openPrivateChannel().queue(dm -> dm.sendMessage(
					"Your ticket **#" + String.format("%04d", ticket.number) + "** (" + ticket.subject + ") was closed. Here is the transcript:")
					.addFiles(FileUpload.fromData(transcript, fileName)).queue(null, e -> { }), e -> { }), e -> { });
			synchronized (config) {
				config.tickets.remove(ticket.channelId);
				config.save();
			}
			channel.delete().reason("Ticket closed by " + closer.getName()).queueAfter(4, TimeUnit.SECONDS);
		}).exceptionally(error -> {
			channel.sendMessage("Couldn't save the transcript: " + error.getMessage()).queue();
			return null;
		});
	}

	byte[] transcript(BotConfig.Ticket ticket, List<Message> messages) {
		StringBuilder out = new StringBuilder();
		out.append("Ticket #").append(String.format("%04d", ticket.number)).append(" - ").append(ticket.subject).append("\n");
		out.append("Type: ").append(config.type(ticket.type).label).append("   Opened by: ").append(ticket.userName).append(" (").append(ticket.userId).append(")\n");
		out.append("Opened: ").append(TIME.format(Instant.ofEpochMilli(ticket.createdAt))).append(" UTC\n");
		for (var answer : ticket.answers.entrySet()) {
			out.append(answer.getKey()).append(": ").append(answer.getValue().replace("\n", "\n    ")).append("\n");
		}
		out.append("-".repeat(72)).append("\n");
		for (Message m : messages) {
			out.append("[").append(TIME.format(m.getTimeCreated())).append("] ").append(m.getAuthor().getName());
			if (m.getAuthor().isBot()) {
				out.append(" (bot)");
			}
			out.append(": ").append(m.getContentDisplay());
			for (var embed : m.getEmbeds()) {
				out.append("\n    [embed] ").append(embed.getTitle() == null ? "" : embed.getTitle()).append(embed.getDescription() == null ? "" : " - " + embed.getDescription());
			}
			for (var attachment : m.getAttachments()) {
				out.append("\n    [file] ").append(attachment.getFileName()).append(" ").append(attachment.getUrl());
			}
			out.append("\n");
		}
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static String duration(long millis) {
		long minutes = millis / 60_000;
		return minutes >= 120 ? (minutes / 60) + " h" : minutes + " min";
	}

	/** To the ticket-log channel (if set). */
	void log(Guild guild, net.dv8tion.jda.api.entities.MessageEmbed embed, FileUpload file) {
		if (guild == null || config.ticketLogChannelId == null) {
			return;
		}
		TextChannel channel = guild.getTextChannelById(config.ticketLogChannelId);
		if (channel == null) {
			return;
		}
		var send = channel.sendMessageEmbeds(embed);
		if (file != null) {
			send = send.addFiles(file);
		}
		send.queue(null, error -> System.err.println("Couldn't write to the ticket log: " + error.getMessage()));
	}
}
