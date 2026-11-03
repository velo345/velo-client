package net.veloclient.velo.client.modules.pvp;

import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/** Lowers the fire overlay when you're burning so it doesn't cover half the screen mid-fight (or hides it with Fire Resistance). */
public final class LowFireModule extends AbstractModule implements Configurable {

	private static LowFireModule instance;

	private double lower = 0.25;
	private boolean hideWithFireRes = false;
	/** How opaque the fire overlay is drawn (1 = vanilla); dev build only for now. */
	private double opacity = 1.0;

	public LowFireModule() {
		super("low-fire", "Low Fire", "Lowers the fire overlay while you burn so you can still see - or hides it with Fire Resistance.",
				ModuleCategory.PVP, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
	}

	/** How far down to move the fire overlay (screen units), or 0. */
	public static float offset() {
		LowFireModule m = instance;
		return m == null || !m.isEnabled() ? 0f : (float) m.lower;
	}

	/** Multiplier for the fire overlay's alpha (1 = vanilla). */
	public static float alpha() {
		LowFireModule m = instance;
		return m == null || !m.isEnabled() || !net.veloclient.velo.client.Preview.ON ? 1f : (float) m.opacity;
	}

	public static double opacitySetting() {
		return instance == null ? 1.0 : instance.opacity;
	}

	public static void setOpacitySetting(double value) {
		if (instance != null) {
			instance.opacity = Math.max(0.1, Math.min(1.0, value));
		}
	}

	public static double lowerSetting() {
		return instance == null ? 0.25 : instance.lower;
	}

	public static void setLowerSetting(double value) {
		if (instance != null) {
			instance.lower = Math.max(0, Math.min(0.6, value));
		}
	}

	/** True when the overlay should not be drawn at all. */
	public static boolean hide() {
		LowFireModule m = instance;
		var player = PvpCompat.localPlayer();
		return m != null && m.isEnabled() && m.hideWithFireRes && player != null && PvpCompat.hasFireResistance(player);
	}

	@Override
	public List<ConfigField> configFields() {
		List<ConfigField> fields = new java.util.ArrayList<>(List.of(
				new ConfigField.SliderField("Lower By", 0, 0.6, () -> lower, v -> lower = Math.round(v * 100) / 100.0,
						v -> Math.round(v / 0.6 * 100) + "%")));
		if (net.veloclient.velo.client.Preview.ON) {
			fields.add(new ConfigField.SliderField("Opacity", 0.1, 1.0, () -> opacity, v -> opacity = Math.round(v * 100) / 100.0,
					v -> Math.round(v * 100) + "%"));
		}
		fields.add(new ConfigField.ToggleField("Hide Fire Completely With Fire Resistance", () -> hideWithFireRes, v -> hideWithFireRes = v));
		return fields;
	}
}
