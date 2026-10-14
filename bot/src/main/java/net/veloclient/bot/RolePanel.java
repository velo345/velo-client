package net.veloclient.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * The ping-role panel: one button per role, press to get or drop it. The roles, their labels and
 * emojis are set with {@code /roles add} - and the panel message is rebuilt from them, in place,
 * whenever {@code /roles panel} runs (so changing a label never needs a new message).
 */
final class RolePanel {

	static final String PREFIX = "role:";
	static final int MAX_ROLES = 25;

	private RolePanel() {
	}

	static MessageCreateBuilder message(Guild guild, BotConfig config) {
		EmbedBuilder embed = new EmbedBuilder().setTitle("🔔  " + config.rolesPanelTitle).setColor(0xFF4D4D).setDescription(config.rolesPanelText);
		StringBuilder list = new StringBuilder();
		List<Button> buttons = new ArrayList<>();
		for (BotConfig.PanelRole entry : config.roles) {
			Role role = guild.getRoleById(entry.roleId);
			if (role == null) {
				continue;
			}
			Button button = Button.of(ButtonStyle.SECONDARY, PREFIX + entry.roleId, entry.label);
			if (entry.emoji != null && !entry.emoji.isBlank()) {
				try {
					button = button.withEmoji(Emoji.fromFormatted(entry.emoji));
				} catch (RuntimeException ignored) {
					// Not an emoji Discord understands - plain button.
				}
			}
			buttons.add(button);
			list.append(entry.emoji == null ? "" : entry.emoji + " ").append("**").append(entry.label).append("**");
			if (entry.description != null && !entry.description.isBlank()) {
				list.append(" - ").append(entry.description);
			}
			list.append("\n");
		}
		if (list.length() > 0) {
			embed.addField("Available", list.toString(), false);
		}
		embed.setFooter("Press again to remove a role");
		MessageCreateBuilder builder = new MessageCreateBuilder().setEmbeds(embed.build());
		List<ActionRow> rows = new ArrayList<>();
		for (int i = 0; i < buttons.size(); i += 5) {
			rows.add(ActionRow.of(buttons.subList(i, Math.min(buttons.size(), i + 5))));
		}
		return builder.setComponents(rows);
	}

	/** Posts the panel, or edits the existing message in place. */
	static void publish(Guild guild, BotConfig config, TextChannel channel, Runnable done, java.util.function.Consumer<Throwable> failed) {
		MessageCreateBuilder content = message(guild, config);
		if (config.rolesPanelChannelId != null && config.rolesPanelMessageId != null && config.rolesPanelChannelId.equals(channel.getId())) {
			channel.editMessageById(config.rolesPanelMessageId, new net.dv8tion.jda.api.utils.messages.MessageEditBuilder()
					.applyCreateData(content.build()).build()).queue(m -> done.run(), error -> post(config, channel, content, done, failed));
			return;
		}
		post(config, channel, content, done, failed);
	}

	private static void post(BotConfig config, TextChannel channel, MessageCreateBuilder content, Runnable done, java.util.function.Consumer<Throwable> failed) {
		channel.sendMessage(content.build()).queue(message -> {
			config.rolesPanelChannelId = channel.getId();
			config.rolesPanelMessageId = message.getId();
			config.save();
			done.run();
		}, failed::accept);
	}

	/** A button press: toggles the role. */
	static void toggle(ButtonInteractionEvent event) {
		String roleId = event.getComponentId().substring(PREFIX.length());
		Guild guild = event.getGuild();
		Member member = event.getMember();
		Role role = guild == null ? null : guild.getRoleById(roleId);
		if (role == null || member == null) {
			event.reply("That role doesn't exist any more.").setEphemeral(true).queue();
			return;
		}
		if (!guild.getSelfMember().canInteract(role)) {
			event.reply("I can't give that role - move my role above it in the server settings.").setEphemeral(true).queue();
			return;
		}
		if (member.getRoles().contains(role)) {
			guild.removeRoleFromMember(member, role).queue(v -> event.reply("Removed " + role.getAsMention() + " - you won't be pinged any more.").setEphemeral(true).queue(),
					e -> event.reply("Couldn't remove the role: " + e.getMessage()).setEphemeral(true).queue());
		} else {
			guild.addRoleToMember(member, role).queue(v -> event.reply("You now get " + role.getAsMention() + " pings. ✅").setEphemeral(true).queue(),
					e -> event.reply("Couldn't give the role: " + e.getMessage()).setEphemeral(true).queue());
		}
	}
}
