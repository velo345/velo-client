package net.veloclient.velo.client.modules.performance;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * TNT Optimizer (26.2+, default off): the two TNT-farm optimizations, kept out of Velo Turbo so nobody gets them without
 * choosing to.
 * <ul>
 * <li>Merge Explosion Effects ({@link ExplosionMerge}): where dozens of TNT go off in the same few blocks every second,
 * later explosions join the smoke cloud of a recent one instead of each spawning its own (knockback and blocks untouched;
 * single explosions, creepers and crystals in a fight keep their full effect).</li>
 * <li>Reuse TNT Block Models (TurboBlockModelCacheMixin): primed TNT, falling blocks and block displays build their block
 * model once per block state instead of every frame (never for models Fabric's renderer fills itself).</li>
 * </ul>
 */
public final class TntOptimizerModule extends AbstractModule implements Configurable {

	private static TntOptimizerModule instance;
	private boolean mergeExplosions = true;
	private boolean reuseModels = true;

	public TntOptimizerModule() {
		super("tnt-optimizer", "TNT Optimizer", "For TNT farms and cannons: merges stacked explosion effects and reuses TNT block models. Off by default.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
	}

	public static boolean mergeExplosions() {
		TntOptimizerModule m = instance;
		return m != null && m.isEnabled() && m.mergeExplosions;
	}

	public static boolean reuseModels() {
		TntOptimizerModule m = instance;
		return m != null && m.isEnabled() && m.reuseModels;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Merge Explosion Effects", () -> mergeExplosions, v -> mergeExplosions = v),
				new ConfigField.ToggleField("Reuse TNT Block Models", () -> reuseModels, v -> reuseModels = v));
	}
}
