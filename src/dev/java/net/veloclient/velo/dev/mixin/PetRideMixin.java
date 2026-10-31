package net.veloclient.velo.dev.mixin;

import net.veloclient.velo.dev.pets.PetsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pets: riding your pet draws you sitting (vanilla's riding pose) on its back - the model is lifted by the saddle
 * height through the render offset; the real player, its hitbox and the camera stay where they are.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.entity.PlayerEntityRenderer.class)
public abstract class PetRideMixin {

	@Inject(method = "getPositionOffset(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)Lnet/minecraft/util/math/Vec3d;",
			at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$ride(net.minecraft.client.render.entity.state.PlayerEntityRenderState state,
			CallbackInfoReturnable<net.minecraft.util.math.Vec3d> cir) {
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		if (player == null || state.id != player.getId() || !PetsModule.riding()) {
			return;
		}
		state.hasVehicle = true;
		cir.setReturnValue(cir.getReturnValue().add(0, PetsModule.seatHeight(), 0));
	}
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.entity.player.AvatarRenderer.class)
public abstract class PetRideMixin {

	@Inject(method = "getRenderOffset(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/world/phys/Vec3;",
			at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$ride(net.minecraft.client.renderer.entity.state.AvatarRenderState state,
			CallbackInfoReturnable<net.minecraft.world.phys.Vec3> cir) {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player == null || state.id != player.getId() || !PetsModule.riding()) {
			return;
		}
		state.isPassenger = true;
		cir.setReturnValue(cir.getReturnValue().add(0, PetsModule.seatHeight(), 0));
	}
}
*///?}
