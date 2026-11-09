package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.qol.OwnNameTagModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Own Name Tag: vanilla's name check ends with "entity != the camera entity", so your own tag never shows - not even in
 * F5. With the module on, the local player's tag shows whenever the camera is in third person (HUD visible, not
 * invisible, nobody riding you - the rest of vanilla's rules).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.entity.LivingEntityRenderer.class)
public abstract class OwnNameTagMixin {

	//? if <1.21.2 {
	/*@Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("RETURN"), cancellable = true)
	private void velo$ownTag(net.minecraft.entity.LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
	*///?} else {
	@Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;D)Z", at = @At("RETURN"), cancellable = true)
	private void velo$ownTag(net.minecraft.entity.LivingEntity entity, double distance, CallbackInfoReturnable<Boolean> cir) {
	//?}
		if (cir.getReturnValueZ() || !OwnNameTagModule.active()) {
			return;
		}
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		if (entity == mc.player && mc.getCameraEntity() == entity && !mc.options.getPerspective().isFirstPerson()
				&& net.minecraft.client.MinecraftClient.isHudEnabled() && !entity.isInvisible() && !entity.hasPassengers()) {
			cir.setReturnValue(true);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.entity.LivingEntityRenderer.class)
public abstract class OwnNameTagMixin {

	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"), cancellable = true)
	private void velo$ownTag(net.minecraft.world.entity.LivingEntity entity, double distance, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() || !OwnNameTagModule.active()) {
			return;
		}
		var mc = net.minecraft.client.Minecraft.getInstance();
		//? if >=26.2 {
		/^boolean hudShown = !mc.gui.hud.isHidden();
		^///?} else {
		boolean hudShown = net.minecraft.client.Minecraft.renderNames();
		//?}
		if (entity == mc.player && mc.getCameraEntity() == entity && !mc.options.getCameraType().isFirstPerson()
				&& hudShown && !entity.isInvisible() && !entity.isVehicle()) {
			cir.setReturnValue(true);
		}
	}
}
*///?}
