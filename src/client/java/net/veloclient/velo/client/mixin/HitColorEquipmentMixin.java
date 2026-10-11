package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Hit Color on armor and trims: their shaders ignore the hurt overlay, so while {@code
 * HitColorArmorMixin} has a tint set for the current (hurt) entity, it's multiplied into the tint
 * color every armor, trim and glint layer is submitted with.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.entity.equipment.EquipmentRenderer.class)
public abstract class HitColorEquipmentMixin {

	@ModifyArg(method = "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/command/RenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/texture/Sprite;ILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V"),
			index = 6, require = 0)
	private int velo$tint(int tint) {
		int hit = net.veloclient.velo.client.pvp.HitColorEquipment.armorTint;
		return hit == -1 ? tint : net.veloclient.velo.client.modules.pvp.HitColorModule.multiply(tint, hit);
	}
}
//?} else if <26.3 {
/*@Mixin(net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer.class)
public abstract class HitColorEquipmentMixin {

	@ModifyArg(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"),
			index = 6, require = 0)
	private int velo$tint(int tint) {
		int hit = net.veloclient.velo.client.pvp.HitColorEquipment.armorTint;
		return hit == -1 ? tint : net.veloclient.velo.client.modules.pvp.HitColorModule.multiply(tint, hit);
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer.class)
public abstract class HitColorEquipmentMixin {

	@ModifyArg(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"),
			index = 6, require = 0)
	private int velo$tint(int tint) {
		int hit = net.veloclient.velo.client.pvp.HitColorEquipment.armorTint;
		return hit == -1 ? tint : net.veloclient.velo.client.modules.pvp.HitColorModule.multiply(tint, hit);
	}
}
*///?}
