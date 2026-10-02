package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.veloclient.velo.client.devtools.RenderProfiler;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/** Dev-only whole-frame / extract timing for {@code RenderProfiler} (26.2). */
//? if >=26.2 {
/*@Mixin(GameRenderer.class)
public abstract class VulkanFrameProfileMixin {

	@Inject(method = "extract", at = @At("HEAD"))
	private void velo$frameStart(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		if (RenderProfiler.ENABLED) {
			RenderProfiler.frameEnd();
			RenderProfiler.frameBegin();
			RenderProfiler.begin();
		}
	}

	@Inject(method = "extract", at = @At("RETURN"))
	private void velo$extractEnd(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		if (RenderProfiler.ENABLED) {
			RenderProfiler.endExtract();
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer")
public abstract class VulkanFrameProfileMixin {
}
//?}
