package net.veloclient.velo.devbuild.mixin;

import net.veloclient.velo.devbuild.ShieldIndicatorModule;
import net.veloclient.velo.devbuild.ShieldTint;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.2 {
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?}

/**
 * Shield Indicator: the shield's render data gets the tint for its holder (as a dyed-colour component - the shield
 * renderer never reads one, so it's free to carry ours); while that data is drawn the tint replaces the model's colour,
 * and the "ready" flash adds full-bright light and the enchantment glint.
 */
//? if <1.21.2 {
/*@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class ShieldTintMixin {
}
*///?} else if <26.1 {
@Mixin(net.minecraft.client.render.item.model.special.ShieldModelRenderer.class)
public abstract class ShieldTintMixin {

	@Inject(method = "getData(Lnet/minecraft/item/ItemStack;)Lnet/minecraft/component/ComponentMap;", at = @At("RETURN"), cancellable = true)
	private void velo$tintData(net.minecraft.item.ItemStack stack, CallbackInfoReturnable<net.minecraft.component.ComponentMap> cir) {
		ShieldIndicatorModule module = ShieldIndicatorModule.active();
		if (module == null || !(ShieldTint.holder instanceof net.minecraft.entity.Entity holder)) {
			return;
		}
		int tint = module.tintFor(holder, stack);
		if (!ShieldIndicatorModule.hasTint(tint)) {
			return;
		}
		var builder = net.minecraft.component.ComponentMap.builder();
		if (cir.getReturnValue() != null) {
			builder.addAll(cir.getReturnValue());
		}
		builder.add(net.minecraft.component.DataComponentTypes.DYED_COLOR, new net.minecraft.component.type.DyedColorComponent(tint));
		cir.setReturnValue(builder.build());
	}

	@WrapMethod(method = "render(Lnet/minecraft/component/ComponentMap;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;IIZI)V")
	private void velo$draw(net.minecraft.component.ComponentMap data, net.minecraft.item.ItemDisplayContext context,
			net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
			int light, int overlay, boolean glint, int outline, Operation<Void> original) {
		var dyed = data == null || ShieldIndicatorModule.active() == null ? null : data.get(net.minecraft.component.DataComponentTypes.DYED_COLOR);
		int previous = ShieldTint.drawing;
		ShieldTint.drawing = dyed == null ? 0 : dyed.rgb();
		try {
			original.call(data, context, matrices, queue, ShieldTint.light(light), overlay, glint || ShieldTint.flash() > 0, outline);
		} finally {
			ShieldTint.drawing = previous;
		}
	}

	@ModifyArg(method = "render(Lnet/minecraft/component/ComponentMap;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;IIZI)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;submitModelPart(Lnet/minecraft/client/model/ModelPart;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IILnet/minecraft/client/texture/Sprite;ZZILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;I)V"),
			index = 8)
	private int velo$tint(int vanilla) {
		return ShieldTint.tintArg(vanilla);
	}
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.special.ShieldSpecialRenderer.class)
public abstract class ShieldTintMixin {

	@Inject(method = "extractArgument(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/core/component/DataComponentMap;", at = @At("RETURN"), cancellable = true)
	private void velo$tintData(net.minecraft.world.item.ItemStack stack, CallbackInfoReturnable<net.minecraft.core.component.DataComponentMap> cir) {
		ShieldIndicatorModule module = ShieldIndicatorModule.active();
		if (module == null || !(ShieldTint.holder instanceof net.minecraft.world.entity.Entity holder)) {
			return;
		}
		int tint = module.tintFor(holder, stack);
		if (!ShieldIndicatorModule.hasTint(tint)) {
			return;
		}
		var builder = net.minecraft.core.component.DataComponentMap.builder();
		if (cir.getReturnValue() != null) {
			builder.addAll(cir.getReturnValue());
		}
		builder.set(net.minecraft.core.component.DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(tint));
		cir.setReturnValue(builder.build());
	}

	@WrapMethod(method = "submit(Lnet/minecraft/core/component/DataComponentMap;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V")
	private void velo$draw(net.minecraft.core.component.DataComponentMap data, com.mojang.blaze3d.vertex.PoseStack pose,
			net.minecraft.client.renderer.SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline, Operation<Void> original) {
		var dyed = data == null || ShieldIndicatorModule.active() == null ? null : data.get(net.minecraft.core.component.DataComponents.DYED_COLOR);
		int previous = ShieldTint.drawing;
		ShieldTint.drawing = dyed == null ? 0 : dyed.rgb();
		try {
			original.call(data, pose, collector, ShieldTint.light(light), overlay, foil || ShieldTint.flash() > 0, outline);
		} finally {
			ShieldTint.drawing = previous;
		}
	}

	//? if >=26.3 {
	/^@ModifyArg(method = "submit(Lnet/minecraft/core/component/DataComponentMap;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;IIILnet/minecraft/client/resources/model/sprite/SpriteId;Lnet/minecraft/client/resources/model/sprite/SpriteGetter;I)V"),
			index = 5)
	^///?} else {
	@ModifyArg(method = "submit(Lnet/minecraft/core/component/DataComponentMap;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;IIILnet/minecraft/client/resources/model/sprite/SpriteId;Lnet/minecraft/client/resources/model/sprite/SpriteGetter;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"),
			index = 5)
	//?}
	private int velo$tint(int vanilla) {
		return ShieldTint.tintArg(vanilla);
	}
}
*///?}
