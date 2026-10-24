package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.3 {
/*import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
*///?} else if >=26.2 {
/*import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.SurfaceException;
*///?}
//? if >=26.2 {
/*import net.minecraft.client.Minecraft;
import net.veloclient.velo.client.modules.performance.DecoupledPresent;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
*///?}

/**
 * Decoupled present, part 3: when the frame started without a swapchain image, try once more
 * (still without waiting) right before the finished frame would be shown - an image often frees up
 * while the frame renders, and showing this frame then beats showing none. Targets the
 * {@code isAcquired()} check in front of the blit (the second of the three in renderFrame).
 */
//? if >=26.3 {
/*@Mixin(Minecraft.class)
public abstract class VulkanDecoupledFrameMixin {

	@Redirect(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;isAcquired()Z", ordinal = 1))
	private boolean velo$lateAcquire(GpuSurface surface) {
		if (!surface.isAcquired() && DecoupledPresent.active() && surface.currentConfiguration().isPresent()) {
			try {
				surface.acquireNextTexture();
			} catch (SurfaceException | IllegalStateException e) {
				// Out of date / resizing: vanilla reconfigures at the start of the next frame.
			}
		}
		return surface.isAcquired();
	}
}
*///?} else if >=26.2 {
/*@Mixin(Minecraft.class)
public abstract class VulkanDecoupledFrameMixin {

	@Redirect(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;isAcquired()Z", ordinal = 1))
	private boolean velo$lateAcquire(GpuSurface surface) {
		if (!surface.isAcquired() && DecoupledPresent.active() && surface.currentConfiguration().isPresent()) {
			try {
				surface.acquireNextTexture();
			} catch (SurfaceException | IllegalStateException e) {
				// Out of date / resizing: vanilla reconfigures at the start of the next frame.
			}
		}
		return surface.isAcquired();
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanDecoupledFrameMixin {
	// No Vulkan renderer before 26.2 - never applied (VeloMixinPlugin skips it).
}
//?}
