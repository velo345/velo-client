package net.veloclient.velo.client.modules.pvp;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.clips.ClipRecorder;
import net.veloclient.velo.client.gui.media.FightRecapRows;
import net.veloclient.velo.client.gui.media.FightRecapScreen;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.pvp.FightTracker;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * After each fight against a player: a small popup with the result and score, and a full recap
 * screen (K) - hit timeline, hits both ways, best combo, crits, accuracy, reach, damage. With the
 * Clips module on, won fights (or every fight) can be saved as a clip automatically.
 */
public final class FightRecapModule extends AbstractModule implements Configurable {

	public static final KeyBinding OPEN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.fight_recap", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.K, VeloKeybinds.CATEGORY));

	private static final List<String> AUTO_CLIP = List.of("Off", "Won fights", "All fights");

	private boolean popup = true;
	private String autoClip = "Won fights";

	public FightRecapModule() {
		super("fight-recap", "Fight Recap", "After a fight: hits, combo, reach and damage both ways, a hit timeline - and an automatic clip.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
		FightTracker.addListener(this::onFight);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_KEY.wasPressed()) {
				if (isEnabled() && net.veloclient.velo.client.util.ClientCompat.currentScreen() == null) {
					client.setScreen(new FightRecapScreen(null));
				}
			}
		});
	}

	private void onFight(FightTracker.Recap recap) {
		if (!isEnabled()) {
			return;
		}
		boolean clip = ClipRecorder.active() && switch (autoClip) {
			case "All fights" -> true;
			case "Won fights" -> recap.outcome() == FightTracker.Outcome.WON;
			default -> false;
		};
		if (clip) {
			// The whole fight plus a couple of seconds of lead-in.
			double seconds = (System.currentTimeMillis() - recap.startedAt()) / 1000.0 + 2;
			ClipRecorder.save("fight", recap, seconds, file -> {
				if (file != null) {
					FightRecapScreen.linkClip(recap, file);
				}
			});
		}
		if (popup) {
			String title = switch (recap.outcome()) {
				case WON -> "Won vs " + recap.opponent();
				case LOST -> "Lost vs " + recap.opponent();
				case ENDED -> "Fight vs " + recap.opponent();
			};
			String body = recap.hitsDealt() + "-" + recap.hitsTaken() + " hits  ·  combo " + recap.bestCombo()
					+ "  ·  " + FightRecapRows.hearts(recap.damageDealt()) + " dealt" + (clip ? "  ·  clip saved" : "");
			NotificationOverlay.show(new NotificationOverlay.Toast(recap.opponentUuid(), recap.opponent(), title, body, 6000,
					() -> MinecraftClient.getInstance().setScreen(new FightRecapScreen(null)), null, null));
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Open Recap Key", OPEN_KEY),
				new ConfigField.ToggleField("Popup After Each Fight", () -> popup, v -> popup = v),
				new ConfigField.ChoiceField("Auto-Clip (needs Clips on)", AUTO_CLIP, () -> autoClip, v -> autoClip = v));
	}
}
