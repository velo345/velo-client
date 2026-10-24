package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import net.veloclient.velo.client.devtools.RenderProfiler;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Dev-only probe (-Dvelo.renderProfile=true): counts vanilla's full ALL_COMMANDS -> ALL_COMMANDS
 * memory barriers per frame (one before every render pass, clear, upload and copy), and with
 * -Dvelo.experimentNoBarrier=true skips them - unsafe, only to measure what they cost the GPU.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanBarrierProbeMixin {

	private static final boolean VELO_SKIP = Boolean.getBoolean("velo.experimentNoBarrier");

	@Inject(method = "memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V", at = @At("HEAD"), cancellable = true)
	private void velo$probe(MemoryStack stack, CallbackInfo ci) {
		if (RenderProfiler.ENABLED) {
			RenderProfiler.barriers++;
			RenderProfiler.barrierFrom(StackWalker.getInstance().walk(f -> f.skip(1)
					.filter(fr -> !fr.getMethodName().contains("memoryBarrier") && !fr.getMethodName().contains("velo$"))
					.findFirst().map(fr -> fr.getMethodName()).orElse("?")));
			if (VELO_SKIP) {
				ci.cancel();
			}
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanBarrierProbeMixin {
	// 26.2 dev probe only (VeloMixinPlugin skips it elsewhere).
}
//?}
