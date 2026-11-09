package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind: entities hidden in the clip aren't drawn while watching / exporting.
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindRenderMixin {
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class)
public abstract class RewindRenderMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "shouldRender", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
	private void velo$rewindHidden(net.minecraft.world.entity.Entity entity, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (!net.veloclient.velo.devbuild.rewind.RewindApply.renderEntity(entity)) {
			cir.setReturnValue(false);
		}
	}
}
*///?}
