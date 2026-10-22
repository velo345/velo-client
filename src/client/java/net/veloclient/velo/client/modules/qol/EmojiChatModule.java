package net.veloclient.velo.client.modules.qol;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Emoji in chat, like Discord: emoji show as colour pictures (Twemoji), {@code :shortcodes:} turn into emoji when you
 * send them (so everyone sees a real emoji, Velo or not), and typing {@code :na} lists matching emoji - Tab picks one.
 * See util/EmojiData, util/EmojiStyler, EmojiChatMixin, EmojiSendMixin, EmojiSuggestMixin.
 */
public final class EmojiChatModule extends AbstractModule implements Configurable {

	private static EmojiChatModule instance;
	private boolean showInChat = true;
	private boolean convertOnSend = true;
	private boolean suggestions = true;

	public EmojiChatModule() {
		super("emoji-chat", "Emoji Chat",
				"Shows emoji as colour pictures in chat, turns :shortcodes: into emoji when you send them and suggests emoji as you type - Tab completes.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
	}

	public static boolean showInChat() {
		return instance != null && instance.isEnabled() && instance.showInChat;
	}

	public static boolean convertOnSend() {
		return instance != null && instance.isEnabled() && instance.convertOnSend;
	}

	public static boolean suggestions() {
		return instance != null && instance.isEnabled() && instance.suggestions;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show emoji as pictures in chat", () -> showInChat, v -> showInChat = v),
				new ConfigField.ToggleField("Turn :shortcodes: into emoji when sending", () -> convertOnSend, v -> convertOnSend = v),
				new ConfigField.ToggleField("Suggest emoji while typing (Tab completes)", () -> suggestions, v -> suggestions = v));
	}
}
