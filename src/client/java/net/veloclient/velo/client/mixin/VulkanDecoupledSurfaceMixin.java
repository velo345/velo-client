package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import net.veloclient.velo.client.modules.performance.DecoupledPresent;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Decoupled present, part 2: when the non-waiting acquire found no free swapchain image, the
 * surface reports "not acquired", so vanilla's frame loop skips the blit and the present for this
 * frame (it checks {@code isAcquired()} before both) and tries again next frame. Also counts the
 * frames that really reach the screen.
 */
//? if >=26.3 {
/*@Mixin(com.mojang.renderpearl.frontend.FrontendGpuSurface.class)
*///?} else if >=26.2 {
/*@Mixin(com.mojang.blaze3d.systems.GpuSurface.class)
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
//?}
public abstract class VulkanDecoupledSurfaceMixin {
	//? if >=26.2 {
	/*@Shadow
	private boolean hasImageAcquired;

	@Inject(method = "acquireNextTexture", at = @At("TAIL"))
	private void velo$noImageNoFrame(CallbackInfo ci) {
		if (DecoupledPresent.missed) {
			DecoupledPresent.missed = false;
			hasImageAcquired = false;
		}
	}

	@Inject(method = "present", at = @At("HEAD"))
	private void velo$countShown(CallbackInfo ci) {
		if (hasImageAcquired) {
			DecoupledPresent.onPresent();
		}
	}
	*///?}
}
