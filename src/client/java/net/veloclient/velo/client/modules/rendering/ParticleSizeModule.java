package net.veloclient.velo.client.modules.rendering;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/** Particle Size: every billboard particle (crits, smoke, potions, block dust...) drawn bigger or smaller. Visual only. */
public final class ParticleSizeModule extends AbstractModule implements Configurable {

	private static ParticleSizeModule instance;
	private double scale = 0.6;

	public ParticleSizeModule() {
		super("particle-size", "Particle Size", "Makes particles smaller (less clutter in fights) or bigger.",
				ModuleCategory.RENDERING, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
	}

	/** Factor for a particle's drawn size (1 = vanilla). */
	public static float factor() {
		ParticleSizeModule m = instance;
		return m == null || !m.isEnabled() ? 1f : (float) m.scale;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(new ConfigField.SliderField("Particle Size", 0.1, 3.0, () -> scale, v -> scale = Math.round(v * 20) / 20.0,
				v -> Math.round(v * 100) + "%"));
	}
}
