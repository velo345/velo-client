package net.veloclient.velo.client.modules.pvp;

import net.veloclient.velo.client.pvp.CombatTracker;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Extra particles when a hit lands, with separate looks for normal hits and critical hits (your
 * crits are recognised by vanilla's own crit rule when you attack), optionally on mobs and on hits
 * other players land. Purely client-side particles; only confirmed hits (the target's hurt
 * animation restarted) spawn them, so whiffs stay clean.
 */
public final class HitParticlesModule extends AbstractModule implements Configurable {

	private static final List<String> STYLES = List.of("Off", "Crit", "Magic", "Crit + Magic", "Blood", "Hearts", "Flame", "Soul",
			"Totem", "Sparks", "Notes", "Cloud", "Dust");

	private String hitStyle = "Crit";
	private double hitAmount = 6;
	private int hitColor = 0xFFFF3B3B;
	private String critStyle = "Crit + Magic";
	private double critAmount = 14;
	private int critColor = 0xFFFFD84A;
	private boolean onPlayers = true;
	private boolean onMobs = true;
	private String othersStyle = "Off";

	public HitParticlesModule() {
		super("hit-particles", "Hit Particles", "Extra particles when your hits land - a separate look for normal hits and crits.",
				ModuleCategory.PVP, SafetyTag.COSMETIC_ONLY, false);
		CombatTracker.addListener(this::onHit);
	}

	private void onHit(net.minecraft.entity.LivingEntity target, boolean ownHit, boolean crit) {
		if (!isEnabled()) {
			return;
		}
		boolean player = PvpCompat.isPlayer(target);
		if ((player && !onPlayers) || (!player && !onMobs)) {
			return;
		}
		if (!ownHit) {
			spawn(target, othersStyle, (int) Math.round(hitAmount), hitColor);
		} else if (crit) {
			spawn(target, critStyle, (int) Math.round(critAmount), critColor);
		} else {
			spawn(target, hitStyle, (int) Math.round(hitAmount), hitColor);
		}
	}

	private static void spawn(net.minecraft.entity.LivingEntity target, String style, int count, int color) {
		switch (style) {
			case "Off" -> {
			}
			case "Crit + Magic" -> {
				PvpCompat.spawnHitParticles(target, "Crit", count, color);
				PvpCompat.spawnHitParticles(target, "Magic", count, color);
			}
			default -> PvpCompat.spawnHitParticles(target, style, count, color);
		}
	}

	private static ConfigField amount(String label, java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set) {
		return new ConfigField.SliderField(label, 1, 40, get, v -> set.accept(Math.round(v)), v -> String.valueOf((int) Math.round(v)));
	}

	@Override
	public List<ConfigField> configFields() {
		List<ConfigField> fields = new ArrayList<>();
		fields.add(new ConfigField.HeaderField("Normal Hits"));
		fields.add(new ConfigField.ChoiceField("Style", STYLES, () -> hitStyle, v -> hitStyle = v));
		fields.add(amount("Particles", () -> hitAmount, v -> hitAmount = v));
		fields.add(new ConfigField.ColorField("Dust Color", () -> hitColor, v -> hitColor = v, false));
		fields.add(new ConfigField.HeaderField("Critical Hits"));
		fields.add(new ConfigField.ChoiceField("Crit Style", STYLES, () -> critStyle, v -> critStyle = v));
		fields.add(amount("Crit Particles", () -> critAmount, v -> critAmount = v));
		fields.add(new ConfigField.ColorField("Crit Dust Color", () -> critColor, v -> critColor = v, false));
		fields.add(new ConfigField.HeaderField("Targets"));
		fields.add(new ConfigField.ToggleField("On Players", () -> onPlayers, v -> onPlayers = v));
		fields.add(new ConfigField.ToggleField("On Mobs", () -> onMobs, v -> onMobs = v));
		fields.add(new ConfigField.ChoiceField("Other Players' Hits", STYLES, () -> othersStyle, v -> othersStyle = v));
		return fields;
	}
}
