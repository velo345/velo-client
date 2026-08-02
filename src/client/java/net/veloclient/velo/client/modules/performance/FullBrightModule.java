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
public final class FullBrightModule extends AbstractModule {

	private static volatile boolean active;
	private double savedGamma = -1;

	public FullBrightModule() {
		super("full-bright", "Full Bright", "Makes everything lit at all read as fully bright (vanilla Night Vision rendering, always on).",
				ModuleCategory.RENDERING, SafetyTag.ALWAYS_SAFE, false);
	}

	public static boolean isActive() {
		return active;
	}

	@Override
	public void onEnable() {
		active = true;
		var gamma = MinecraftClient.getInstance().options.getGamma();
		savedGamma = gamma.getValue();
		gamma.setValue(1.0);
	}

	@Override
	public void onDisable() {
		active = false;
		if (savedGamma >= 0) {
			MinecraftClient.getInstance().options.getGamma().setValue(savedGamma);
			savedGamma = -1;
		}
	}
}
