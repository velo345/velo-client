package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.11 && <26.1 {
import net.veloclient.velo.client.modules.performance.AntiFarmLagModule;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if >=26.2 {
/*import net.veloclient.velo.client.modules.performance.AntiFarmLagModule;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Anti Farm Lag, item farms: on the client a dropped item runs full physics every tick (water / lava checks, a move with
 * block collisions, block effects, fluid sync) although the server owns where it really is. In item farm mode, items
 * further away than "Full Item Detail Within" only do that every third tick (spread over the items by their id); in the
 * ticks between they stay where they are until the next step or the server's next position update. Near items, and
 * every item outside item farm mode, tick exactly like vanilla. The server side is never touched.
 */
//? if >=1.21.11 && <26.1 {
@Mixin(net.minecraft.entity.ItemEntity.class)
public abstract class TurboFarmItemTickMixin {

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void velo$fewerTicksFarAway(CallbackInfo ci) {
		net.minecraft.entity.ItemEntity self = (net.minecraft.entity.ItemEntity) (Object) this;
		if (!self.getEntityWorld().isClient() || !AntiFarmLagModule.itemFarm()) {
			return;
		}
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		if (player == null || self.squaredDistanceTo(player) <= AntiFarmLagModule.itemDetailDistanceSq()) {
			return;
		}
		if ((self.getEntityWorld().getTime() + self.getId()) % 3 == 0) {
			return;
		}
		// standing still this tick: no interpolation from an old spot
		self.lastX = self.getX();
		self.lastY = self.getY();
		self.lastZ = self.getZ();
		ci.cancel();
	}
}
//?} else if >=26.2 {
/*@Mixin(net.minecraft.world.entity.item.ItemEntity.class)
public abstract class TurboFarmItemTickMixin {

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void velo$fewerTicksFarAway(CallbackInfo ci) {
		net.minecraft.world.entity.item.ItemEntity self = (net.minecraft.world.entity.item.ItemEntity) (Object) this;
		if (!self.level().isClientSide() || !AntiFarmLagModule.itemFarm()) {
			return;
		}
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player == null || self.distanceToSqr(player) <= AntiFarmLagModule.itemDetailDistanceSq()) {
			return;
		}
		if ((self.level().getGameTime() + self.getId()) % 3 == 0) {
			return;
		}
		// standing still this tick: no interpolation from an old spot
		self.xo = self.getX();
		self.yo = self.getY();
		self.zo = self.getZ();
		ci.cancel();
	}
}
*///?} else {
/*@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboFarmItemTickMixin {
	// Vulkan-era versions only (VeloMixinPlugin skips TurboFarm* elsewhere).
}
*///?}
