package net.veloclient.velo.client.modules.qol;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small chat quality-of-life: the same message arriving again becomes one line with a counter
 * ("(5x)") instead of flooding chat, optional timestamps, your name highlighted with a ping when
 * someone mentions you, messages hidden by keyword, and a longer chat history than vanilla's 100
 * lines. Everything happens as lines are added, see {@code ChatTweaksMixin}.
 */
public final class ChatTweaksModule extends AbstractModule implements Configurable {

	private static final List<String> TIME_FORMATS = List.of("Off", "HH:mm", "HH:mm:ss", "h:mm a");
	private static ChatTweaksModule instance;
	/** True while the chat mixin re-adds a changed message - other chat hooks skip that second pass. */
	public static boolean reentry;

	private boolean stack = true;
	private boolean stackAnywhere = false;
	private String timestamps = "Off";
	private boolean mentions = true;
	private boolean mentionSound = true;
	private String hidden = "";
	private double history = 500;

	public ChatTweaksModule() {
		super("chat-tweaks", "Chat", "Stacks repeated messages as (5x), timestamps, highlights your name, hides spam words, longer history.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
	}

	private static ChatTweaksModule on() {
		ChatTweaksModule m = instance;
		return m != null && m.isEnabled() ? m : null;
	}

	public static boolean stacking() {
		ChatTweaksModule m = on();
		return m != null && m.stack;
	}

	/** Stack a repeat even when other messages came in between (it moves down to the newest spot). */
	public static boolean stackAnywhere() {
		ChatTweaksModule m = on();
		return m != null && m.stackAnywhere;
	}

	/** {@link java.time.format.DateTimeFormatter} pattern, or null for no timestamps. */
	public static String timestampPattern() {
		ChatTweaksModule m = on();
		return m == null || m.timestamps.equals("Off") ? null : m.timestamps;
	}

	public static boolean mentions() {
		ChatTweaksModule m = on();
		return m != null && m.mentions;
	}

	public static boolean mentionSound() {
		ChatTweaksModule m = on();
		return m != null && m.mentionSound;
	}

	/** Lowercase words; a message containing any of them isn't shown. */
	public static List<String> hiddenWords() {
		ChatTweaksModule m = on();
		if (m == null || m.hidden.isBlank()) {
			return List.of();
		}
		List<String> words = new ArrayList<>();
		for (String word : m.hidden.split(",")) {
			String w = word.strip().toLowerCase(Locale.ROOT);
			if (!w.isEmpty()) {
				words.add(w);
			}
		}
		return words;
	}

	/** Chat lines kept (vanilla: 100). */
	public static int historyLimit() {
		ChatTweaksModule m = on();
		return m == null ? 100 : (int) Math.round(m.history);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Stack Repeated Messages (5x)", () -> stack, v -> stack = v),
				new ConfigField.ToggleField("Stack Even With Messages Between", () -> stackAnywhere, v -> stackAnywhere = v),
				new ConfigField.ChoiceField("Timestamps", TIME_FORMATS, () -> timestamps, v -> timestamps = v),
				new ConfigField.ToggleField("Highlight My Name", () -> mentions, v -> mentions = v),
				new ConfigField.ToggleField("Sound When Mentioned", () -> mentionSound, v -> mentionSound = v),
				new ConfigField.TextField("Hide Messages With", "e.g. joined the lobby, [AD]", () -> hidden, v -> hidden = v == null ? "" : v),
				new ConfigField.SliderField("Chat History (lines)", 100, 2000, () -> history, v -> history = Math.round(v / 50) * 50,
						v -> String.valueOf((int) Math.round(v))));
	}
}
