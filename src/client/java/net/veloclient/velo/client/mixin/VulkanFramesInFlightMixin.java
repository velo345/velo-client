package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import net.veloclient.velo.client.modules.performance.FramesInFlight;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
*///?}

/**
 * Frames in flight (see {@link net.veloclient.velo.client.modules.performance.FramesInFlight}): vanilla's encoder
 * hard-codes 2 for its command pool ring, its loop over that ring, the destruction queue and the debug checkpoint
 * storage (all int 2s in the constructor and destroy), the pool index (submit index % 2L) and the wait after a submit
 * (submit index - 2L). Pools/queue become {@code FramesInFlight.pools()}; the wait distance is chosen per submit and
 * is always <= the pool count, so the pool reset after the wait never touches a buffer the GPU still reads.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(com.mojang.blaze3d.vulkan.VulkanCommandEncoder.class)
public abstract class VulkanFramesInFlightMixin {

	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 2))
	private int velo$ringSize(int two) {
		return FramesInFlight.pools();
	}

	@ModifyConstant(method = "destroy", constant = @Constant(intValue = 2))
	private int velo$destroyRing(int two) {
		return FramesInFlight.pools();
	}

	@ModifyConstant(method = "currentCommandPool", constant = @Constant(longValue = 2L))
	private long velo$poolIndex(long two) {
		return FramesInFlight.pools();
	}

	@ModifyConstant(method = "submit", constant = @Constant(longValue = 2L))
	private long velo$waitDistance(long two) {
		return FramesInFlight.distance();
	}

	// Times the submit throttle's GPU wait for the threaded-present gate.
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "submit", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;awaitSubmitCompletion(JJ)Z"))
	private boolean velo$timeGpuWait(com.mojang.blaze3d.vulkan.VulkanCommandEncoder encoder, long index, long timeout,
			com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original) {
		long start = System.nanoTime();
		boolean done = original.call(encoder, index, timeout);
		net.veloclient.velo.client.modules.performance.ThreadedPresent.recordGpuWait(System.nanoTime() - start);
		return done;
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanFramesInFlightMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
