package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind: hidden name tags - cleared where every entity's render state is made (players' renderer overrides
// shouldShowName, so that hook alone missed them), together with the score line under the name.
//? if >=1.21.11 && <26.1 {
import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind: hidden name tags - cleared where every entity's render state is made (players' renderer overrides
// shouldShowName, so that hook alone missed them), together with the score line under the name.
@Mixin(net.minecraft.client.render.entity.EntityRenderer.class)
public abstract class RewindNameStateMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "getAndUpdateRenderState(Lnet/minecraft/entity/Entity;F)Lnet/minecraft/client/render/entity/state/EntityRenderState;",
			at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
	private void velo$rewindHideName(net.minecraft.entity.Entity entity, float partial,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.client.render.entity.state.EntityRenderState> cir) {
		if (net.veloclient.velo.devbuild.rewind.RewindApply.nameHidden(entity)) {
			var state = cir.getReturnValue();
			state.displayName = null;
			if (state instanceof net.minecraft.client.render.entity.state.PlayerEntityRenderState ps) {
				ps.playerName = null;
			}
		}
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindNameStateMixin {
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.entity.EntityRenderer.class)
public abstract class RewindNameStateMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
			at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
	private void velo$rewindHideName(net.minecraft.world.entity.Entity entity, float partial,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.client.renderer.entity.state.EntityRenderState> cir) {
		if (net.veloclient.velo.devbuild.rewind.RewindApply.nameHidden(entity)) {
			var state = cir.getReturnValue();
			state.nameTag = null;
			state.scoreText = null;
		}
	}
}
*///?}
