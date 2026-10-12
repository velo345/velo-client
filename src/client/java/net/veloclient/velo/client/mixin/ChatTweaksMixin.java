package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
//?} else {
/*import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
*///?}
import net.veloclient.velo.client.modules.qol.ChatTweaksModule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Chat module ({@link ChatTweaksModule}): hides messages by keyword, stacks a repeated message
 * into the previous line with a "(5x)" counter, adds timestamps and highlights your name - by
 * catching each message on its way in and, when it needs changing, re-adding the changed text
 * (guarded against re-entry). Also lifts vanilla's 100-line history limit.
 */
//? if <26.1 {
@Mixin(ChatHud.class)
public abstract class ChatTweaksMixin {

	@Shadow @Final private List<ChatHudLine> messages;

	@Shadow private void refresh() {
	}

	@Shadow public abstract void addMessage(Text message, MessageSignatureData signature, MessageIndicator indicator);

	/** Each line we added -> {original plain text, repeat count}. */
	@Unique private final Map<ChatHudLine, Object[]> velo$lines = new IdentityHashMap<>();

	@Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
			at = @At("HEAD"), cancellable = true)
	private void velo$tweak(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
		if (ChatTweaksModule.reentry || message == null) {
			return;
		}
		String plain = message.getString();
		String lower = plain.toLowerCase(Locale.ROOT);
		for (String word : ChatTweaksModule.hiddenWords()) {
			if (lower.contains(word)) {
				ci.cancel();
				return;
			}
		}
		int count = 1;
		boolean removed = false;
		if (ChatTweaksModule.stacking() && !plain.isBlank()) {
			int scan = ChatTweaksModule.stackAnywhere() ? Math.min(messages.size(), 50) : Math.min(messages.size(), 1);
			for (int i = 0; i < scan; i++) {
				Object[] info = velo$lines.get(messages.get(i));
				if (info != null && plain.equals(info[0])) {
					count = (int) info[1] + 1;
					velo$lines.remove(messages.remove(i));
					removed = true;
					break;
				}
			}
		}
		String pattern = ChatTweaksModule.timestampPattern();
		boolean mention = ChatTweaksModule.mentions() && velo$mentionsMe(plain);
		Text out = message;
		if (mention) {
			out = Text.literal("» ").formatted(Formatting.GOLD).append(out);
			if (ChatTweaksModule.mentionSound()) {
				net.veloclient.velo.client.pvp.PvpCompat.playUiSound("minecraft:block.note_block.pling", 2.0f, 0.6f);
			}
		}
		if (pattern != null) {
			out = Text.literal("[" + LocalTime.now().format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)) + "] ")
					.formatted(Formatting.GRAY).append(out);
		}
		if (count > 1) {
			out = out.copy().append(Text.literal(" (" + count + "x)").formatted(Formatting.GRAY));
		}
		if (removed) {
			refresh();
		}
		ci.cancel();
		ChatTweaksModule.reentry = true;
		try {
			addMessage(out, signature, indicator);
		} finally {
			ChatTweaksModule.reentry = false;
		}
		if (!messages.isEmpty()) {
			velo$lines.put(messages.get(0), new Object[] {plain, count});
		}
		if (velo$lines.size() > messages.size() * 2 + 50) {
			java.util.Set<ChatHudLine> live = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
			live.addAll(messages);
			velo$lines.keySet().retainAll(live);
		}
	}

	@ModifyConstant(method = {"addMessage(Lnet/minecraft/client/gui/hud/ChatHudLine;)V", "addVisibleMessage"}, constant = @Constant(intValue = 100),
			require = 0)
	private int velo$history(int vanilla) {
		return Math.max(vanilla, ChatTweaksModule.historyLimit());
	}

	@Unique
	private static boolean velo$mentionsMe(String plain) {
		String me = net.veloclient.velo.client.serverpacks.ServerData.localName();
		return !me.isEmpty() && !plain.startsWith("<" + me + ">")
				&& Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(me) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE).matcher(plain).find();
	}
}
//?} else {
/*@Mixin(ChatComponent.class)
public abstract class ChatTweaksMixin {

	@Shadow @Final private List<GuiMessage> allMessages;

	@Shadow private void refreshTrimmedMessages() {
	}

	@Shadow private void addMessage(Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag) {
	}

	@Unique private final Map<GuiMessage, Object[]> velo$lines = new IdentityHashMap<>();

	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"), cancellable = true)
	private void velo$tweak(Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
		if (ChatTweaksModule.reentry || message == null) {
			return;
		}
		String plain = message.getString();
		String lower = plain.toLowerCase(Locale.ROOT);
		for (String word : ChatTweaksModule.hiddenWords()) {
			if (lower.contains(word)) {
				ci.cancel();
				return;
			}
		}
		int count = 1;
		boolean removed = false;
		if (ChatTweaksModule.stacking() && !plain.isBlank()) {
			int scan = ChatTweaksModule.stackAnywhere() ? Math.min(allMessages.size(), 50) : Math.min(allMessages.size(), 1);
			for (int i = 0; i < scan; i++) {
				Object[] info = velo$lines.get(allMessages.get(i));
				if (info != null && plain.equals(info[0])) {
					count = (int) info[1] + 1;
					velo$lines.remove(allMessages.remove(i));
					removed = true;
					break;
				}
			}
		}
		String pattern = ChatTweaksModule.timestampPattern();
		boolean mention = ChatTweaksModule.mentions() && velo$mentionsMe(plain);
		Component out = message;
		if (mention) {
			out = Component.literal("» ").withStyle(ChatFormatting.GOLD).append(out);
			if (ChatTweaksModule.mentionSound()) {
				net.veloclient.velo.client.pvp.PvpCompat.playUiSound("minecraft:block.note_block.pling", 2.0f, 0.6f);
			}
		}
		if (pattern != null) {
			out = Component.literal("[" + LocalTime.now().format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)) + "] ")
					.withStyle(ChatFormatting.GRAY).append(out);
		}
		if (count > 1) {
			out = out.copy().append(Component.literal(" (" + count + "x)").withStyle(ChatFormatting.GRAY));
		}
		if (removed) {
			refreshTrimmedMessages();
		}
		ci.cancel();
		ChatTweaksModule.reentry = true;
		try {
			addMessage(out, signature, source, tag);
		} finally {
			ChatTweaksModule.reentry = false;
		}
		if (!allMessages.isEmpty()) {
			velo$lines.put(allMessages.get(0), new Object[] {plain, count});
		}
		if (velo$lines.size() > allMessages.size() * 2 + 50) {
			java.util.Set<GuiMessage> live = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
			live.addAll(allMessages);
			velo$lines.keySet().retainAll(live);
		}
	}

	@ModifyConstant(method = {"addMessageToQueue", "addMessageToDisplayQueue"}, constant = @Constant(intValue = 100), require = 0)
	private int velo$history(int vanilla) {
		return Math.max(vanilla, ChatTweaksModule.historyLimit());
	}

	@Unique
	private static boolean velo$mentionsMe(String plain) {
		String me = net.veloclient.velo.client.serverpacks.ServerData.localName();
		return !me.isEmpty() && !plain.startsWith("<" + me + ">")
				&& Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(me) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE).matcher(plain).find();
	}
}
*///?}
