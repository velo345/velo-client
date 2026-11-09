package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind backgrounds: no chests, signs, beds ... when the terrain is hidden (green screen).
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindBlockEntityMixin {
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher.class)
public abstract class RewindBlockEntityMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "tryExtractRenderState", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
	private void velo$rewindNoBlockEntities(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Object> cir) {
		if (net.veloclient.velo.devbuild.rewind.RewindApply.hideTerrain()) {
			cir.setReturnValue(null);
		}
	}
}
*///?}
