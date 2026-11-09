package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.2 && <26.2 {
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
//?}

/**
 * Velo Turbo (the 26.2 VulkanOutlineSkipMixin, for the OpenGL versions): vanilla counts "a player exists" as "entity
 * outlines on", so every frame it clears the outline target (full screen, color + depth) inside the main pass and blends
 * it over the picture afterwards - for an empty texture. The frame already knows whether any entity glows; only then do
 * the clear and the blend run (the frame that gains a glowing entity clears first as usual). Entity collection still
 * uses the vanilla check, so detection is unchanged. 26.3 does this itself, 26.2 has its own mixin.
 */
//? if >=1.21.2 && <26.1 {
@Mixin(net.minecraft.client.render.WorldRenderer.class)
public abstract class TurboGlOutlineSkipMixin {

	@Shadow
	@Final
	private net.minecraft.client.render.state.WorldRenderState worldRenderState;

	@ModifyExpressionValue(method = {"method_62214", "drawEntityOutlinesFramebuffer"}, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/render/WorldRenderer;canDrawEntityOutlines()Z"))
	private boolean velo$onlyWithOutlines(boolean draw) {
		return draw && (worldRenderState.hasOutline || !PerformanceBoostModule.turbo);
	}
}
//?} else if >=26.1 && <26.2 {
/*@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public abstract class TurboGlOutlineSkipMixin {

	@Shadow
	@Final
	private net.minecraft.client.renderer.state.level.LevelRenderState levelRenderState;

	@ModifyExpressionValue(method = {"lambda$addMainPass$0", "doEntityOutline"}, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;shouldShowEntityOutlines()Z"))
	private boolean velo$onlyWithOutlines(boolean draw) {
		return draw && (levelRenderState.haveGlowingEntities || !PerformanceBoostModule.turbo);
	}
}
*///?} else {
/*@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class TurboGlOutlineSkipMixin {
	// 1.21.1 draws outlines the old way; 26.2 has VulkanOutlineSkipMixin, 26.3 skips it itself.
}
*///?}
