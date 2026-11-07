package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Velo Turbo: no entity-outline work in frames without outlines. Vanilla treats "a player exists" as "show entity
 * outlines", so every frame it clears the outline target (color and depth, full screen) in the middle of the main pass
 * and blends the whole target over the screen afterwards - two full-screen GPU operations for an empty texture, and
 * the clear also splits the main pass (no pass merging across it). The frame already knows whether anything was
 * submitted with an outline (glowing entities, spectator highlight); only then do the clear and the blend run. When
 * outlines come back, that frame clears first as usual, so nothing stale is ever shown.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public abstract class VulkanOutlineSkipMixin {

	@Unique
	private boolean velo$anyOutline = true;

	@ModifyExpressionValue(method = "addMainPass", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;hasAnyOutline()Z"))
	private boolean velo$recordOutline(boolean any) {
		velo$anyOutline = any;
		return any;
	}

	@ModifyExpressionValue(method = {"lambda$addMainPass$0", "doEntityOutline"}, at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/renderer/state/level/LevelRenderState;shouldShowEntityOutlines:Z"))
	private boolean velo$onlyWithOutlines(boolean show) {
		return show && (velo$anyOutline || !PerformanceBoostModule.turbo);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanOutlineSkipMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
