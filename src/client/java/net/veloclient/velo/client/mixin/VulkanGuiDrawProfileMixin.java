package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.veloclient.velo.client.devtools.GpuPassTimer;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Dev-only ({@code -Dvelo.gpuPassProfile=true}, 26.2): GPU time per GUI draw call - a timestamp before every draw,
 * labelled with its pipeline, first texture and quad count, so the GUI pass's GPU time can be split by element.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.gui.render.GuiRenderer.class)
public abstract class VulkanGuiDrawProfileMixin {

	@Unique
	private static String velo$pipeline = "?";
	@Unique
	private static String velo$texture = "-";

	@WrapOperation(method = "executeDraw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
	private void velo$pipeline(RenderPass pass, RenderPipeline pipeline, Operation<Void> original) {
		if (GpuPassTimer.ENABLED) {
			velo$pipeline = pipeline.getLocation().getPath();
			velo$texture = "-";
		}
		original.call(pass, pipeline);
	}

	@WrapOperation(method = "executeDraw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V", ordinal = 0))
	private void velo$texture(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler, Operation<Void> original) {
		if (GpuPassTimer.ENABLED && view != null) {
			velo$texture = view.texture().getLabel() + " " + view.getWidth(0) + "x" + view.getHeight(0);
		}
		original.call(pass, name, view, sampler);
	}

	@WrapOperation(method = "executeDraw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIIII)V"))
	private void velo$draw(RenderPass pass, int a, int b, int count, int d, int e, Operation<Void> original) {
		if (GpuPassTimer.ENABLED) {
			GpuPassTimer.markInPass("gui:" + velo$pipeline + " | " + velo$texture + " | " + (a / 6) + "q");
		}
		original.call(pass, a, b, count, d, e);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VulkanGuiDrawProfileMixin {
	// Dev profiling, 26.2 only.
}
//?}
