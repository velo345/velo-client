package net.veloclient.velo.client.modules.pvp;

import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Says "gg" when a game ends - recognised from the end-of-game lines minigame servers print
 * (Hypixel, and most Bed Wars / SkyWars / Duels servers word them the same way). Once per game,
 * after a short delay; your own phrases can be added. Sends a real chat message, so it's tagged
 * "check server rules".
 */
public final class AutoGgModule extends AbstractModule implements Configurable {

	private static final List<String> DEFAULT_TRIGGERS = List.of("1st killer", "winner:", "winners:", "winning team", "you won",
			"victory!", "game over", "won the game", "reward summary", "1st place", "won the duel", "match complete");

	private String message = "gg";
	private String extraTriggers = "";
	private double delaySeconds = 1.5;
	private long cooldownUntil;
	private long sendAt = -1;

	public AutoGgModule() {
		super("auto-gg", "Auto GG", "Sends \"gg\" in chat when a minigame ends (Hypixel, Bed Wars, SkyWars, Duels...).",
				ModuleCategory.PVP, SafetyTag.CHECK_SERVER_RULES, false);
		PvpCompat.onChatText(this::onChat);
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (sendAt > 0 && System.currentTimeMillis() >= sendAt) {
				sendAt = -1;
				if (isEnabled() && !message.isBlank()) {
					PvpCompat.sendChat(message.trim());
				}
			}
		});
	}

	private void onChat(String text) {
		if (!isEnabled() || System.currentTimeMillis() < cooldownUntil) {
			return;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		// Ignore what players say (someone typing "you won" shouldn't trigger it).
		if (lower.contains(": ") && lower.indexOf(": ") < 20 && !lower.startsWith("winner")) {
			return;
		}
		List<String> triggers = new ArrayList<>(DEFAULT_TRIGGERS);
		for (String extra : extraTriggers.split(",")) {
			if (!extra.isBlank()) {
				triggers.add(extra.trim().toLowerCase(Locale.ROOT));
			}
		}
		for (String trigger : triggers) {
			if (lower.contains(trigger)) {
				cooldownUntil = System.currentTimeMillis() + 15_000;
				sendAt = System.currentTimeMillis() + Math.round(delaySeconds * 1000);
				return;
			}
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.TextField("Message", "gg", () -> message, v -> message = v),
				new ConfigField.SliderField("Delay", 0, 5, () -> delaySeconds, v -> delaySeconds = Math.round(v * 2) / 2.0,
						v -> String.format(Locale.ROOT, "%.1fs", v)),
				new ConfigField.TextField("Extra Triggers (comma separated)", "e.g. you have been eliminated", () -> extraTriggers,
						v -> extraTriggers = v));
	}
}
