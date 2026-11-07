package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import net.veloclient.velo.client.modules.performance.ThreadedPresent;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Threaded present: every queue submit that isn't the handed-off end-of-frame one (uploads, readbacks, one-time
 * command buffers) first waits for the present thread, so queue calls never overlap and stay in submit order (see
 * {@link net.veloclient.velo.client.modules.performance.ThreadedPresent}). The worker's own submit skips the wait.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(com.mojang.blaze3d.vulkan.VulkanQueue.Submission.class)
public abstract class VulkanThreadedSubmissionMixin {

	@Inject(method = "close", at = @At("HEAD"))
	private void velo$drainFirst(CallbackInfo ci) {
		if (!ThreadedPresent.onWorker()) {
			ThreadedPresent.drain();
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanThreadedSubmissionMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
