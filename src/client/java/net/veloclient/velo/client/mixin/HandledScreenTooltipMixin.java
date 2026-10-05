package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.qol.ShulkerPreview;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips the item tooltip while Shulker Preview shows its own panel for the hovered item. */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.screen.ingame.HandledScreen.class)
public abstract class HandledScreenTooltipMixin {
	@Inject(method = "drawMouseoverTooltip", at = @At("HEAD"), cancellable = true)
	private void velo$hideForPreview(net.minecraft.client.gui.DrawContext context, int x, int y, CallbackInfo ci) {
		if (ShulkerPreview.suppressTooltip((net.minecraft.client.gui.screen.Screen) (Object) this, x, y)) {
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class)
public abstract class HandledScreenTooltipMixin {
	@Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
	private void velo$hideForPreview(net.minecraft.client.gui.GuiGraphicsExtractor context, int x, int y, CallbackInfo ci) {
		if (ShulkerPreview.suppressTooltip((net.minecraft.client.gui.screens.Screen) (Object) this, x, y)) {
			ci.cancel();
		}
	}
}
*///?}
