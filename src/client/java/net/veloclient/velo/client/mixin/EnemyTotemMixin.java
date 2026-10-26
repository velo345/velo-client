package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.pvp.EnemyTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity event 35 is a Totem of Undying pop - Enemy Effects resets that player's effects to the totem's, Totem Pops
 * counts it. Event 3 (death) ends a Totem Pops count.
 */
//? if <26.1 {
@Mixin(net.minecraft.entity.LivingEntity.class)
public abstract class EnemyTotemMixin {

	@Inject(method = "handleStatus", at = @At("HEAD"), require = 0)
	private void velo$totem(byte status, CallbackInfo ci) {
		int id = ((net.minecraft.entity.LivingEntity) (Object) this).getId();
		if (status == 35) {
			EnemyTracker.onTotem(id);
		}
		net.veloclient.velo.client.pvp.TotemPopTracker.onEntityEvent(id, status);
	}
}
//?} else {
/*@Mixin(net.minecraft.world.entity.LivingEntity.class)
public abstract class EnemyTotemMixin {

	@Inject(method = "handleEntityEvent", at = @At("HEAD"), require = 0)
	private void velo$totem(byte status, CallbackInfo ci) {
		int id = ((net.minecraft.world.entity.LivingEntity) (Object) this).getId();
		if (status == 35) {
			EnemyTracker.onTotem(id);
		}
		net.veloclient.velo.client.pvp.TotemPopTracker.onEntityEvent(id, status);
	}
}
*///?}
