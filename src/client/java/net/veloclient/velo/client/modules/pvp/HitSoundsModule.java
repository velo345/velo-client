package net.veloclient.velo.client.modules.pvp;

import net.veloclient.velo.client.pvp.CombatTracker;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A sound only you hear when your hits land - a different one for crits. */
public final class HitSoundsModule extends AbstractModule implements Configurable {

	private static final Map<String, String> SOUNDS = new LinkedHashMap<>();

	static {
		SOUNDS.put("Off", "");
		SOUNDS.put("Ding (Arrow Hit)", "minecraft:entity.arrow.hit_player");
		SOUNDS.put("XP Orb", "minecraft:entity.experience_orb.pickup");
		SOUNDS.put("Bell", "minecraft:block.bell.use");
		SOUNDS.put("Pling", "minecraft:block.note_block.pling");
		SOUNDS.put("Chime", "minecraft:block.amethyst_block.chime");
		SOUNDS.put("Click", "minecraft:ui.button.click");
		SOUNDS.put("Anvil", "minecraft:block.anvil.land");
		SOUNDS.put("Crit (Vanilla)", "minecraft:entity.player.attack.crit");
		SOUNDS.put("Strong Hit", "minecraft:entity.player.attack.strong");
		SOUNDS.put("Bass", "minecraft:block.note_block.bass");
	}

	private String hitSound = "Ding (Arrow Hit)";
	private String critSound = "Pling";
	private double volume = 0.7;
	private double pitch = 1.0;
	private boolean pitchUpWithCombo = true;
	private boolean onMobs = false;

	public HitSoundsModule() {
		super("hit-sounds", "Hit Sounds", "Plays a sound only you hear when your hits land - another one for crits.",
				ModuleCategory.PVP, SafetyTag.COSMETIC_ONLY, false);
		CombatTracker.addListener((target, ownHit, crit) -> {
			if (!isEnabled() || !ownHit || (!onMobs && !PvpCompat.isPlayer(target))) {
				return;
			}
			String id = SOUNDS.getOrDefault(crit ? critSound : hitSound, "");
			if (id.isEmpty()) {
				return;
			}
			float p = (float) pitch;
			if (pitchUpWithCombo) {
				p += Math.min(0.6f, Math.max(0, CombatTracker.combo() - 1) * 0.08f);
			}
			PvpCompat.playUiSound(id, p, (float) volume);
		});
	}

	@Override
	public List<ConfigField> configFields() {
		List<String> names = new ArrayList<>(SOUNDS.keySet());
		return List.of(
				new ConfigField.ChoiceField("Hit Sound", names, () -> hitSound, v -> hitSound = v),
				new ConfigField.ChoiceField("Crit Sound", names, () -> critSound, v -> critSound = v),
				new ConfigField.SliderField("Volume", 0.1, 1.0, () -> volume, v -> volume = Math.round(v * 20) / 20.0, v -> Math.round(v * 100) + "%"),
				new ConfigField.SliderField("Pitch", 0.5, 2.0, () -> pitch, v -> pitch = Math.round(v * 20) / 20.0,
						v -> String.format(java.util.Locale.ROOT, "%.2f", v)),
				new ConfigField.ToggleField("Higher Pitch With Combo", () -> pitchUpWithCombo, v -> pitchUpWithCombo = v),
				new ConfigField.ToggleField("Also On Mobs", () -> onMobs, v -> onMobs = v));
	}
}
