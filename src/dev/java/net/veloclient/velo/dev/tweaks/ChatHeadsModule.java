package net.veloclient.velo.dev.tweaks;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat Heads (dev build): the sender's head in front of their chat message. The sender is the first online player
 * whose name shows up in the first few words of the message - that catches vanilla chat, server chat formats
 * ({@code [Rank] Name: hi}, {@code Name » hi}) and join/death messages alike. Every line of such a message is
 * indented by the head's width; the head itself is drawn by the chat's backends (ChatHeadsBackendMixin and ChatHeadsFocusedMixin).
 */
public final class ChatHeadsModule extends AbstractModule implements Configurable {

	/** The indent in front of a message with a head: three spaces, 12px. */
	private static final String INDENT = "   ";
	private static final int INDENT_WIDTH = 12;
	private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]{3,16}");

	private static ChatHeadsModule instance;

	private int scanWords = 4;
	private boolean ownMessages = true;

	public ChatHeadsModule() {
		super("chat-heads", "Chat Heads", "Shows the player's head in front of their chat messages.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
	}

	private static boolean active() {
		ChatHeadsModule m = instance;
		return m != null && m.isEnabled();
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Look For The Name In The First", 1, 8, () -> scanWords, v -> scanWords = (int) Math.round(v),
						v -> (int) Math.round(v) + " words"),
				new ConfigField.ToggleField("On Your Own Messages", () -> ownMessages, v -> ownMessages = v));
	}

	//? if <26.1 {
	/** A chat line with the indent in front, and - on the message's first line - whose head to draw there. */
	public record HeadLine(net.minecraft.text.OrderedText line, net.minecraft.client.network.PlayerListEntry sender)
			implements net.minecraft.text.OrderedText {

		private static final net.minecraft.text.OrderedText SPACER = net.minecraft.text.OrderedText.styledForwardsVisitedString(INDENT,
				net.minecraft.text.Style.EMPTY);

		@Override
		public boolean accept(net.minecraft.text.CharacterVisitor visitor) {
			return SPACER.accept(visitor) && line.accept(visitor);
		}
	}

	/** Splits a chat message into lines like vanilla, with room for the head if it has a sender. */
	public static List<net.minecraft.text.OrderedText> split(net.minecraft.text.Text content, int width,
			IntFunction<List<net.minecraft.text.OrderedText>> vanilla) {
		var sender = active() ? sender(content.getString()) : null;
		if (sender == null) {
			return vanilla.apply(width);
		}
		List<net.minecraft.text.OrderedText> lines = vanilla.apply(Math.max(20, width - INDENT_WIDTH));
		List<net.minecraft.text.OrderedText> out = new ArrayList<>(lines.size());
		for (int i = 0; i < lines.size(); i++) {
			out.add(new HeadLine(lines.get(i), i == 0 ? sender : null));
		}
		return out;
	}

	private static net.minecraft.client.network.PlayerListEntry sender(String plain) {
		var client = net.minecraft.client.MinecraftClient.getInstance();
		var handler = client.getNetworkHandler();
		if (handler == null) {
			return null;
		}
		String own = client.player == null ? "" : client.player.getGameProfile().name();
		Matcher words = WORD.matcher(plain);
		for (int i = 0; i < instance.scanWords && words.find(); i++) {
			String word = words.group();
			if (!instance.ownMessages && word.equals(own)) {
				return null;
			}
			var entry = handler.getPlayerListEntry(word);
			if (entry != null) {
				return entry;
			}
		}
		return null;
	}

	/** Called by the chat backends right before a line is drawn at (0, y) in chat space. */
	public static void drawHead(net.minecraft.client.gui.DrawContext context, int y, float opacity, net.minecraft.text.OrderedText line) {
		if (line instanceof HeadLine head && head.sender() != null) {
			int alpha = Math.round(Math.max(0f, Math.min(1f, opacity)) * 255);
			if (alpha > 3) {
				net.minecraft.client.gui.PlayerSkinDrawer.draw(context, head.sender().getSkinTextures(), 0, y, 8, (alpha << 24) | 0xFFFFFF);
			}
		}
	}
	//?} else {
	/*public record HeadLine(net.minecraft.util.FormattedCharSequence line, net.minecraft.client.multiplayer.PlayerInfo sender)
			implements net.minecraft.util.FormattedCharSequence {

		private static final net.minecraft.util.FormattedCharSequence SPACER = net.minecraft.util.FormattedCharSequence.forward(INDENT,
				net.minecraft.network.chat.Style.EMPTY);

		@Override
		public boolean accept(net.minecraft.util.FormattedCharSink sink) {
			return SPACER.accept(sink) && line.accept(sink);
		}
	}

	public static List<net.minecraft.util.FormattedCharSequence> split(net.minecraft.network.chat.Component content, int width,
			IntFunction<List<net.minecraft.util.FormattedCharSequence>> vanilla) {
		var sender = active() ? sender(content.getString()) : null;
		if (sender == null) {
			return vanilla.apply(width);
		}
		List<net.minecraft.util.FormattedCharSequence> lines = vanilla.apply(Math.max(20, width - INDENT_WIDTH));
		List<net.minecraft.util.FormattedCharSequence> out = new ArrayList<>(lines.size());
		for (int i = 0; i < lines.size(); i++) {
			out.add(new HeadLine(lines.get(i), i == 0 ? sender : null));
		}
		return out;
	}

	private static net.minecraft.client.multiplayer.PlayerInfo sender(String plain) {
		var client = net.minecraft.client.Minecraft.getInstance();
		var connection = client.getConnection();
		if (connection == null) {
			return null;
		}
		String own = client.player == null ? "" : client.player.getGameProfile().name();
		Matcher words = WORD.matcher(plain);
		for (int i = 0; i < instance.scanWords && words.find(); i++) {
			String word = words.group();
			if (!instance.ownMessages && word.equals(own)) {
				return null;
			}
			var info = connection.getPlayerInfo(word);
			if (info != null) {
				return info;
			}
		}
		return null;
	}

	public static void drawHead(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int y, float opacity,
			net.minecraft.util.FormattedCharSequence line) {
		if (line instanceof HeadLine head && head.sender() != null) {
			int alpha = Math.round(Math.max(0f, Math.min(1f, opacity)) * 255);
			if (alpha > 3) {
				net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(graphics, head.sender().getSkin(), 0, y, 8,
						(alpha << 24) | 0xFFFFFF);
			}
		}
	}
	*///?}
}
