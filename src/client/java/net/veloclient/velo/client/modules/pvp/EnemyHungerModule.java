package net.veloclient.velo.client.modules.pvp;

import net.veloclient.velo.client.pvp.EnemyTracker;
import net.veloclient.velo.client.pvp.NameTagLines;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Locale;

/**
 * An estimate of other players' hunger and saturation above their head. The server never sends
 * anyone else's food, so this is simulated from what's visible (see {@link EnemyTracker}) - good
 * for "are they about to run out of sprint" and "did they just eat", never exact.
 */
public final class EnemyHungerModule extends AbstractModule implements Configurable {

	private static final List<String> STYLES = List.of("Icons", "Numbers", "Icons + Saturation");
	private static EnemyHungerModule instance;

	private String style = "Icons + Saturation";
	private boolean onlyWhenNotFull = false;

	public EnemyHungerModule() {
		super("enemy-hunger", "Enemy Hunger", "Estimates other players' hunger and saturation and shows it above their heads.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
		NameTagLines.register(10, EnemyHungerModule::line);
	}

	@Override
	public void onEnable() {
		EnemyTracker.acquire();
	}

	@Override
	public void onDisable() {
		EnemyTracker.release();
	}

	private static Object line(int entityId, boolean hasScoreLine) {
		EnemyHungerModule m = instance;
		if (m == null || !m.isEnabled()) {
			return null;
		}
		EnemyTracker.Tracked tracked = EnemyTracker.get(entityId);
		if (tracked == null) {
			return null;
		}
		float hunger = tracked.hunger;
		float saturation = tracked.saturation;
		if (m.onlyWhenNotFull && hunger >= 20f && saturation > 0f) {
			return null;
		}
		var text = PvpCompat.literal("≈ ", 0xB8B8B8);
		if (m.style.equals("Numbers")) {
			text.append(PvpCompat.sprite("hud/food_full"));
			text.append(PvpCompat.literal(String.format(Locale.ROOT, " %.0f  ", hunger), hunger <= 6f ? 0xFF7070 : 0xFFFFFF));
			text.append(PvpCompat.literal(String.format(Locale.ROOT, "%.1f sat", saturation), 0xFFD84A));
			return text;
		}
		int full = (int) Math.floor(hunger / 2f);
		boolean half = hunger - full * 2 >= 1f;
		for (int i = 0; i < 10; i++) {
			text.append(PvpCompat.sprite(i < full ? "hud/food_full" : i == full && half ? "hud/food_half" : "hud/food_empty"));
		}
		if (m.style.equals("Icons + Saturation")) {
			text.append(PvpCompat.literal(String.format(Locale.ROOT, " +%.1f", saturation), 0xFFD84A));
		}
		return text;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = v),
				new ConfigField.ToggleField("Only When Not Full", () -> onlyWhenNotFull, v -> onlyWhenNotFull = v));
	}
}
