package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind: a player's name tag can be hidden in a clip.
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindNameMixin {
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.entity.EntityRenderer.class)
public abstract class RewindNameMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "shouldShowName", at = @org.spongepowered.asm.mixin.injection.At("RETURN"), cancellable = true)
	private void velo$rewindName(net.minecraft.world.entity.Entity entity, double distance, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && !net.veloclient.velo.devbuild.rewind.RewindApply.showName(entity)) {
			cir.setReturnValue(false);
		}
	}
}
*///?}
