package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import net.veloclient.velo.client.modules.performance.PassBarriers;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/**
 * Cheaper render pass boundaries (Vulkan, 26.2). Vanilla ends EVERY render pass with a full
 * ALL_COMMANDS -> ALL_COMMANDS memory barrier, which drains the GPU completely ~14 times a frame -
 * dropping them all (unsafely, as an experiment) was worth +22% FPS. Two things replace them:
 * <ul>
 * <li><b>Pass merging.</b> Most of a frame is consecutive passes drawing into the main render
 * target. When the next pass draws into exactly the same attachments and area without clearing,
 * and nothing else was recorded in between, the dynamic-rendering instance simply stays open: the
 * GPU keeps the draws in order on its own (rasterization order), so no end, no barrier, no begin.
 * Anything else that records into the command buffer (an upload, a clear, a copy, the end of the
 * command buffer for submit or the screen blit) first closes the open instance properly.</li>
 * <li><b>Precise barriers</b> where an instance does end ({@link PassBarriers}): after main-target
 * passes the barrier the following work actually needs; after clears, uploads and copies a barrier
 * sourced from that transfer only. Passes into anything else keep vanilla's full barrier.</li>
 * </ul>
 * A pass that sampled the attachments it draws into would be a feedback loop in vanilla already,
 * so merging adds no new hazard. {@code -Dvelo.fullPassBarriers=true} turns both off.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanPassBarrierMixin {

	@Unique
	private boolean velo$mainTargetPass;
	// An ended main-target pass whose dynamic rendering is still open, and what it was drawing into.
	@Unique
	private boolean velo$open;
	@Unique
	private Object velo$openKey;
	@Unique
	private boolean velo$mergeNext;
	// Inside createRenderPass / submitRenderPass, whose own commandBuffer() calls must not close it.
	@Unique
	private boolean velo$inPassOp;

	@Shadow
	private VkCommandBuffer currentCommandBuffer;

	@Shadow
	private void memoryBarrier(MemoryStack stack) {
		throw new AssertionError();
	}

	@Shadow
	private VkCommandBuffer commandBuffer() {
		throw new AssertionError();
	}

	@Unique
	private void velo$closeOpen() {
		if (!velo$open) {
			return;
		}
		velo$open = false;
		velo$openKey = null;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			KHRDynamicRendering.vkCmdEndRenderingKHR(currentCommandBuffer);
			PassBarriers.afterMainTargetPass(currentCommandBuffer, stack);
		}
	}

	@Inject(method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)Lcom/mojang/blaze3d/systems/RenderPassBackend;", at = @At("HEAD"))
	private void velo$beginPass(RenderPassDescriptor descriptor, CallbackInfoReturnable<?> cir) {
		velo$mainTargetPass = PassBarriers.onlyMainTarget(descriptor);
		velo$mergeNext = false;
		if (net.veloclient.velo.client.devtools.RenderProfiler.ENABLED) {
			net.veloclient.velo.client.devtools.RenderProfiler.op("pass:" + descriptor.label().get() + (velo$mainTargetPass ? "[main]" : "[other]")
					+ (velo$open ? (PassBarriers.canContinue(descriptor, velo$openKey) ? "+merge" : "+close") : ""));
		}
		if (velo$open) {
			if (velo$mainTargetPass && PassBarriers.canContinue(descriptor, velo$openKey)) {
				velo$mergeNext = true;
				velo$open = false;
			} else {
				velo$closeOpen();
			}
		}
		velo$inPassOp = true;
	}

	@Inject(method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)Lcom/mojang/blaze3d/systems/RenderPassBackend;", at = @At("RETURN"))
	private void velo$beginPassDone(RenderPassDescriptor descriptor, CallbackInfoReturnable<?> cir) {
		velo$inPassOp = false;
		if (velo$mainTargetPass) {
			velo$openKey = PassBarriers.key(descriptor);
		}
	}

	@Redirect(method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)Lcom/mojang/blaze3d/systems/RenderPassBackend;", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRDynamicRendering;vkCmdBeginRenderingKHR(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkRenderingInfo;)V"))
	private void velo$maybeContinue(VkCommandBuffer commandBuffer, VkRenderingInfo info) {
		if (velo$mergeNext) {
			velo$mergeNext = false;
			PassBarriers.countMerged();
			return; // still inside the previous pass's rendering instance
		}
		KHRDynamicRendering.vkCmdBeginRenderingKHR(commandBuffer, info);
	}

	@Inject(method = "submitRenderPass", at = @At("HEAD"))
	private void velo$endPass(CallbackInfo ci) {
		velo$inPassOp = true;
	}

	@Inject(method = "submitRenderPass", at = @At("RETURN"))
	private void velo$endPassDone(CallbackInfo ci) {
		velo$inPassOp = false;
	}

	@Redirect(method = "submitRenderPass", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRDynamicRendering;vkCmdEndRenderingKHR(Lorg/lwjgl/vulkan/VkCommandBuffer;)V"))
	private void velo$maybeKeepOpen(VkCommandBuffer commandBuffer) {
		if (velo$mainTargetPass && PassBarriers.enabled() && velo$openKey != null) {
			velo$open = true; // closed (end + barrier) by whatever records next, unless it's a mergeable pass
			return;
		}
		KHRDynamicRendering.vkCmdEndRenderingKHR(commandBuffer);
	}

	@Redirect(method = "submitRenderPass", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"))
	private void velo$preciseBarrier(VulkanCommandEncoder self, MemoryStack stack) {
		if (velo$open) {
			return; // deferred to velo$closeOpen
		}
		if (velo$mainTargetPass && PassBarriers.enabled()) {
			PassBarriers.afterMainTargetPass(commandBuffer(), stack);
		} else {
			memoryBarrier(stack);
		}
	}

	@Inject(method = "commandBuffer", at = @At("HEAD"))
	private void velo$closeBeforeOtherWork(CallbackInfoReturnable<VkCommandBuffer> cir) {
		if (velo$open && !velo$inPassOp) {
			velo$closeOpen();
		}
	}

	@Inject(method = "endCommandBuffer", at = @At("HEAD"))
	private void velo$closeBeforeEnd(CallbackInfo ci) {
		velo$closeOpen();
	}

	@Redirect(method = {"clearColorTexture", "clearColorAndDepthTextures", "clearDepthTexture", "writeToBuffer", "copyToBuffer",
			"writeToTexture", "copyBufferToTexture", "copyTextureToBuffer", "copyTextureToTexture"}, at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"), require = 0)
	private void velo$transferBarrier(VulkanCommandEncoder self, MemoryStack stack) {
		if (net.veloclient.velo.client.devtools.RenderProfiler.ENABLED) {
			net.veloclient.velo.client.devtools.RenderProfiler.op("xfer:" + StackWalker.getInstance().walk(f -> f.skip(1).findFirst().map(StackWalker.StackFrame::getMethodName).orElse("?"))
					+ "<-" + StackWalker.getInstance().walk(f -> f.skip(2).filter(fr -> !fr.getClassName().contains("VulkanCommandEncoder") && !fr.getClassName().contains("CommandEncoder"))
					.findFirst().map(fr -> fr.getClassName().substring(fr.getClassName().lastIndexOf('.') + 1) + "." + fr.getMethodName()).orElse("?")));
		}
		if (PassBarriers.enabled()) {
			PassBarriers.afterTransfer(commandBuffer(), stack);
		} else {
			memoryBarrier(stack);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanPassBarrierMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
