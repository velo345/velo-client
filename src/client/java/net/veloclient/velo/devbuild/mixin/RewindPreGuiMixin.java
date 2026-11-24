package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind export: frames are taken right before the GUI is drawn - the video has the world only, while the editor
// (with the export window and its progress bar) stays on screen.
//? if >=1.21.11 && <26.1 {
import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind export: frames are taken right before the GUI is drawn - the video has the world only, while the editor
// (with the export window and its progress bar) stays on screen.
@Mixin(net.minecraft.client.render.GameRenderer.class)
public abstract class RewindPreGuiMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "render", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
	private void velo$rewindBeforeGui(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		net.veloclient.velo.devbuild.rewind.RewindExport.beforeGui();
	}

	// the FOV track (26.2 has it on the camera, 1.21.11 in GameRenderer)
	@org.spongepowered.asm.mixin.injection.Inject(method = "getFov", at = @org.spongepowered.asm.mixin.injection.At("RETURN"), cancellable = true)
	private void velo$rewindFov(net.minecraft.client.render.Camera camera, float tickDelta, boolean changingFov,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
		Float fov = net.veloclient.velo.devbuild.rewind.RewindApply.fov();
		if (fov != null) {
			cir.setReturnValue(fov);
		}
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindPreGuiMixin {
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public abstract class RewindPreGuiMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "render", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
	private void velo$rewindBeforeGui(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		net.veloclient.velo.devbuild.rewind.RewindExport.beforeGui();
	}
}
*///?}
