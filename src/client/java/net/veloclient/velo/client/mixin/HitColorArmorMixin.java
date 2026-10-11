package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.pvp.HitColorModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers the Hit Color tint of the hurt entity whose armor is being drawn, so {@code
 * HitColorEquipmentMixin} can apply it to the armor layers and trims (vanilla always draws armor
 * untinted). Cleared right after, so nothing else ever picks it up.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.entity.feature.ArmorFeatureRenderer.class)
public abstract class HitColorArmorMixin {

	@Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/client/render/entity/state/BipedEntityRenderState;FF)V",
			at = @At("HEAD"), require = 0)
	private void velo$begin(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
			int light, net.minecraft.client.render.entity.state.BipedEntityRenderState state, float limbAngle, float limbDistance, CallbackInfo ci) {
		net.veloclient.velo.client.pvp.HitColorEquipment.armorTint = state.hurt
				? HitColorModule.armorTint(net.veloclient.velo.client.pvp.HitColorEquipment.category(state)) : -1;
	}

	@Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/client/render/entity/state/BipedEntityRenderState;FF)V",
			at = @At("RETURN"), require = 0)
	private void velo$end(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
			int light, net.minecraft.client.render.entity.state.BipedEntityRenderState state, float limbAngle, float limbDistance, CallbackInfo ci) {
		net.veloclient.velo.client.pvp.HitColorEquipment.armorTint = -1;
	}
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer.class)
public abstract class HitColorArmorMixin {

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
			at = @At("HEAD"), require = 0)
	private void velo$begin(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
			int light, net.minecraft.client.renderer.entity.state.HumanoidRenderState state, float yRot, float xRot, CallbackInfo ci) {
		net.veloclient.velo.client.pvp.HitColorEquipment.armorTint = state.hasRedOverlay
				? HitColorModule.armorTint(net.veloclient.velo.client.pvp.HitColorEquipment.category(state)) : -1;
	}

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
			at = @At("RETURN"), require = 0)
	private void velo$end(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
			int light, net.minecraft.client.renderer.entity.state.HumanoidRenderState state, float yRot, float xRot, CallbackInfo ci) {
		net.veloclient.velo.client.pvp.HitColorEquipment.armorTint = -1;
	}
}
*///?}
