package net.veloclient.velo.dev.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Velo Bridge teammates glow (outline through walls) for the player they were set for. */
//? if <26.1 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class BridgeGlowMixin {

	@Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true)
	private void velo$teammateOutline(net.minecraft.entity.Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (net.veloclient.velo.dev.bridge.VeloBridgeClient.teammateColor(entity.getUuid()) != null) {
			cir.setReturnValue(true);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.Minecraft.class)
public abstract class BridgeGlowMixin {

	@Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
	private void velo$teammateOutline(net.minecraft.world.entity.Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (net.veloclient.velo.dev.bridge.VeloBridgeClient.teammateColor(entity.getUUID()) != null) {
			cir.setReturnValue(true);
		}
	}
}
*///?}
