package net.veloclient.velo.devbuild.mixin;

import net.veloclient.velo.devbuild.ShieldTint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shield Indicator: remembers who holds the item while its special model (the shield) extracts its render data. */
//? if <1.21.2 {
/*// 1.21.1 draws shields through the old built-in item renderer - the indicator starts with 1.21.10.
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class ShieldHolderMixin {
}
*///?} else if <26.1 {
@Mixin(net.minecraft.client.render.item.model.SpecialItemModel.class)
public abstract class ShieldHolderMixin {

	@Inject(method = "update", at = @At("HEAD"))
	private void velo$holder(net.minecraft.client.render.item.ItemRenderState state, net.minecraft.item.ItemStack stack,
			net.minecraft.client.item.ItemModelManager resolver, net.minecraft.item.ItemDisplayContext context,
			net.minecraft.client.world.ClientWorld world, net.minecraft.util.HeldItemContext held, int seed, CallbackInfo ci) {
		ShieldTint.holder = held == null ? null : held.getEntity();
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void velo$holderDone(net.minecraft.client.render.item.ItemRenderState state, net.minecraft.item.ItemStack stack,
			net.minecraft.client.item.ItemModelManager resolver, net.minecraft.item.ItemDisplayContext context,
			net.minecraft.client.world.ClientWorld world, net.minecraft.util.HeldItemContext held, int seed, CallbackInfo ci) {
		ShieldTint.holder = null;
	}
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.item.SpecialModelWrapper.class)
public abstract class ShieldHolderMixin {

	@Inject(method = "update", at = @At("HEAD"))
	private void velo$holder(net.minecraft.client.renderer.item.ItemStackRenderState state, net.minecraft.world.item.ItemStack stack,
			net.minecraft.client.renderer.item.ItemModelResolver resolver, net.minecraft.world.item.ItemDisplayContext context,
			net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.entity.ItemOwner owner, int seed, CallbackInfo ci) {
		ShieldTint.holder = owner == null ? null : owner.asLivingEntity();
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void velo$holderDone(net.minecraft.client.renderer.item.ItemStackRenderState state, net.minecraft.world.item.ItemStack stack,
			net.minecraft.client.renderer.item.ItemModelResolver resolver, net.minecraft.world.item.ItemDisplayContext context,
			net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.world.entity.ItemOwner owner, int seed, CallbackInfo ci) {
		ShieldTint.holder = null;
	}
}
*///?}
