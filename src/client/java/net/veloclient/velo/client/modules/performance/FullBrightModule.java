package net.veloclient.velo.client.modules.performance;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Makes everything already lit at all read as fully bright. Vanilla's own
 * gamma option is hard-clamped to [0, 1] (confirmed by testing - values
 * beyond that are rejected outright, unlike FOV), so this reuses vanilla's
 * own Night Vision rendering path instead: see {@link net.veloclient.velo.client.mixin.FullBrightMixin},
 * which makes the client's local player always read as having Night Vision.
 * That's the same rendering vanilla already ships (and grants via a normal
 * potion), just always-on - it cannot reveal anything with zero received
 * light as anything other than black, the same limit Night Vision itself has.
 */
public final class FullBrightModule extends AbstractModule implements net.veloclient.velo.module.Configurable {

	private static volatile boolean active;
	/** The lightning sky flash brightens a world that's already full-bright: it washes everything in an odd tint. */
	private static volatile boolean hideLightningFlash = true;
	private double savedGamma = -1;

	public FullBrightModule() {
		super("full-bright", "Full Bright", "Everything as bright as daylight - caves, nights, every corner - in its real colours.",
				ModuleCategory.RENDERING, SafetyTag.CHECK_SERVER_RULES, false);
	}

	public static boolean isActive() {
		return active;
	}

	/** Read by LightningFlashMixin: drop the sky flash while Full Bright is on. */
	public static boolean hideFlash() {
		return active && hideLightningFlash;
	}

	@Override
	public java.util.List<net.veloclient.velo.module.ConfigField> configFields() {
		return java.util.List.of(new net.veloclient.velo.module.ConfigField.ToggleField("Hide Lightning Flash",
				() -> hideLightningFlash, v -> hideLightningFlash = v));
	}

	/** How bright: far past vanilla's 1.0, so even light level 0 renders like daylight. */
	private static final double FULL = 16.0;

	@Override
	public void onEnable() {
		active = true;
		var gamma = MinecraftClient.getInstance().options.getGamma();
		double now = gamma.getValue();
		if (now <= 1.0) {
			savedGamma = now;
		}
		// the option's own setter refuses values above 1.0 - written straight into the field instead
		((net.veloclient.velo.client.mixin.GammaOptionAccessor) (Object) gamma).velo$setRawValue(FULL);
	}

	/** Night Vision is no longer faked (it tinted the world) - Full Bright is the brightness itself now. */
	public static boolean fakeNightVision() {
		return false;
	}

	@Override
	public void onDisable() {
		active = false;
		if (savedGamma >= 0) {
			((net.veloclient.velo.client.mixin.GammaOptionAccessor) (Object) MinecraftClient.getInstance().options.getGamma()).velo$setRawValue(savedGamma);
			savedGamma = -1;
		}
	}
}
