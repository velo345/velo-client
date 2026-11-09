package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind export: frames are taken right before the GUI is drawn - the video has the world only, while the editor
// (with the export window and its progress bar) stays on screen.
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindPreGuiMixin {
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public abstract class RewindPreGuiMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "render", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
	private void velo$rewindBeforeGui(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		net.veloclient.velo.devbuild.rewind.RewindExport.beforeGui();
	}
}
*///?}
