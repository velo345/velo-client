package net.veloclient.velo.client.modules.rendering;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Clear Water & Lava: no (or less) fog when you're under water, in lava or in powder snow - its own module, apart from
 * "Time, Weather & Fog" (which only changes the open-air fog), so players find it. Purely visual and client-side; the
 * world past the fog was already sent to you.
 */
public final class ClearLiquidsModule extends AbstractModule implements Configurable {

	private static ClearLiquidsModule instance;

	private boolean water = true;
	private boolean lava = true;
	private boolean powderSnow = true;
	/** 0..100: how much of the fog goes (100 = see as far as your render distance). */
	private double clearness = 100;

	public ClearLiquidsModule() {
		super("clear-liquids", "Clear Water & Lava", "No fog under water, in lava or in powder snow - see as far as on land. "
				+ "Separate from the normal fog setting.", ModuleCategory.RENDERING, SafetyTag.CHECK_SERVER_RULES, false);
		instance = this;
	}

	/** The new fog end for a medium, or NaN to leave vanilla's. {@code medium}: "water", "lava" or "snow". */
	public static float end(String medium, float vanillaEnd, float renderEnd) {
		ClearLiquidsModule m = instance;
		if (m == null || !m.isEnabled()) {
			return Float.NaN;
		}
		boolean on = switch (medium) {
			case "water" -> m.water;
			case "lava" -> m.lava;
			default -> m.powderSnow;
		};
		if (!on) {
			return Float.NaN;
		}
		float k = (float) (m.clearness / 100.0);
		return vanillaEnd + (Math.max(vanillaEnd, renderEnd) - vanillaEnd) * k;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Water", () -> water, v -> water = v),
				new ConfigField.ToggleField("Lava", () -> lava, v -> lava = v),
				new ConfigField.ToggleField("Powder Snow", () -> powderSnow, v -> powderSnow = v),
				new ConfigField.SliderField("Clearness", 10, 100, () -> clearness, v -> clearness = Math.round(v), v -> Math.round(v) + "%"));
	}
}
