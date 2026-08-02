package net.veloclient.velo.client.modules.performance;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Independent sliders for vanilla's three separate camera-effect options -
 * movement FOV kick (sprint/speed potion/spyglass), nausea screen
 * distortion, and the damage hit-tilt - each a genuinely distinct vanilla
 * {@code SimpleOption}, not one shared value.
 */
public final class FovModule extends AbstractModule implements Configurable {

	private double savedFovScale = -1;
	private double savedDistortionScale = -1;
	private double savedDamageTilt = -1;

	private double fovPercent = 0;
	private double nauseaPercent = 0;
	private double damageTiltPercent = 0;

	public FovModule() {
		super("fov", "FOV", "Independent sliders for movement FOV kick, nausea distortion and damage tilt.",
				ModuleCategory.RENDERING, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public void onEnable() {
		var options = MinecraftClient.getInstance().options;
		savedFovScale = options.getFovEffectScale().getValue();
		savedDistortionScale = options.getDistortionEffectScale().getValue();
		savedDamageTilt = options.getDamageTiltStrength().getValue();

		options.getFovEffectScale().setValue(fovPercent / 100.0);
		options.getDistortionEffectScale().setValue(nauseaPercent / 100.0);
		options.getDamageTiltStrength().setValue(damageTiltPercent / 100.0);
	}

	@Override
	public void onDisable() {
		if (savedFovScale >= 0) {
			var options = MinecraftClient.getInstance().options;
			options.getFovEffectScale().setValue(savedFovScale);
			options.getDistortionEffectScale().setValue(savedDistortionScale);
			options.getDamageTiltStrength().setValue(savedDamageTilt);
			savedFovScale = -1;
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Movement FOV (sprint/speed/spyglass)", 0, 100,
						() -> fovPercent,
						v -> {
							fovPercent = v;
							if (isEnabled()) {
								MinecraftClient.getInstance().options.getFovEffectScale().setValue(v / 100.0);
							}
						},
						v -> Math.round(v) + "%"),
				new ConfigField.SliderField("Nausea Distortion", 0, 100,
						() -> nauseaPercent,
						v -> {
							nauseaPercent = v;
							if (isEnabled()) {
								MinecraftClient.getInstance().options.getDistortionEffectScale().setValue(v / 100.0);
							}
						},
						v -> Math.round(v) + "%"),
				new ConfigField.SliderField("Damage Tilt", 0, 100,
						() -> damageTiltPercent,
						v -> {
							damageTiltPercent = v;
							if (isEnabled()) {
								MinecraftClient.getInstance().options.getDamageTiltStrength().setValue(v / 100.0);
							}
						},
						v -> Math.round(v) + "%"));
	}
}
