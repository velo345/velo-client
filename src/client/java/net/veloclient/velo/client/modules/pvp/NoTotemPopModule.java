package net.veloclient.velo.client.modules.pvp;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * No Totem Pop: the big totem that flies into your face when one pops is gone - optionally the totem particles and the
 * pop sound too (yours and other players'). Only what you see and hear; the pop itself still happens.
 */
public final class NoTotemPopModule extends AbstractModule implements Configurable {

	private static NoTotemPopModule instance;

	private boolean hideAnimation = true;
	private boolean hideParticles = false;
	private boolean muteSound = false;

	public NoTotemPopModule() {
		super("no-totem-pop", "No Totem Pop", "No totem flying into your face when one pops - you still hear it. "
				+ "Turn off the particles and the sound too if you like.", ModuleCategory.PVP, SafetyTag.CHECK_SERVER_RULES, false);
		instance = this;
	}

	private static boolean on() {
		return instance != null && instance.isEnabled();
	}

	public static boolean hideAnimation() {
		return on() && instance.hideAnimation;
	}

	public static boolean hideParticles() {
		return on() && instance.hideParticles;
	}

	public static boolean muteSound(String soundId) {
		return on() && instance.muteSound && soundId != null && soundId.endsWith("item.totem.use");
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Hide Totem Animation", () -> hideAnimation, v -> hideAnimation = v),
				new ConfigField.ToggleField("Hide Totem Particles", () -> hideParticles, v -> hideParticles = v),
				new ConfigField.ToggleField("Mute Totem Sound", () -> muteSound, v -> muteSound = v));
	}
}
