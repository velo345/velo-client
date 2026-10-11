package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.pvp.EnemyTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Entity event 35 is a Totem of Undying pop - Enemy Effects resets that player's effects to the totem's. */
//? if <26.1 {
@Mixin(net.minecraft.entity.LivingEntity.class)
public abstract class EnemyTotemMixin {

	@Inject(method = "handleStatus", at = @At("HEAD"), require = 0)
	private void velo$totem(byte status, CallbackInfo ci) {
		if (status == 35) {
			EnemyTracker.onTotem(((net.minecraft.entity.LivingEntity) (Object) this).getId());
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.world.entity.LivingEntity.class)
public abstract class EnemyTotemMixin {

	@Inject(method = "handleEntityEvent", at = @At("HEAD"), require = 0)
	private void velo$totem(byte status, CallbackInfo ci) {
		if (status == 35) {
			EnemyTracker.onTotem(((net.minecraft.world.entity.LivingEntity) (Object) this).getId());
		}
	}
}
*///?}
