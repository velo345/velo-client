package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.qol.CenteredInventoryModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Centered Inventory: with the recipe book open the container stays centered and the book moves left of it. */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.screen.recipebook.RecipeBookWidget.class)
public abstract class RecipeBookCenterMixin {

	@Shadow
	private int leftOffset;

	@Shadow
	private boolean narrow;

	@Shadow
	public abstract boolean isOpen();

	@Shadow
	private int parentWidth;

	// The book's widgets (search field, filter button, tabs, recipe page) are placed in reset() right after it sets
	// leftOffset = 86 - only moving the offset later (findLeftEdge) moved the book's background but left its widgets on
	// top of the inventory (owner, 2026-11-06). Set our offset there, before anything is placed.
	@Inject(method = "reset", at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/screen/recipebook/RecipeBookWidget;leftOffset:I",
			opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER), require = 0)
	private void velo$centerWidgets(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (narrow || !CenteredInventoryModule.on()) {
			return;
		}
		int offset = CenteredInventoryModule.bookOffset(parentWidth, 176);
		if (offset >= 0) {
			leftOffset = offset;
		}
	}

	@Inject(method = "findLeftEdge", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$center(int width, int backgroundWidth, CallbackInfoReturnable<Integer> cir) {
		if (!isOpen() || narrow) {
			return;
		}
		int offset = CenteredInventoryModule.on() ? CenteredInventoryModule.bookOffset(width, backgroundWidth) : -1;
		if (offset < 0) {
			leftOffset = 86;
			return;
		}
		leftOffset = offset;
		cir.setReturnValue((width - backgroundWidth) / 2);
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class)
public abstract class RecipeBookCenterMixin {

	@Shadow
	private int xOffset;

	@Shadow
	private boolean widthTooNarrow;

	@Shadow
	public abstract boolean isVisible();

	@Shadow
	private int width;

	// See the branch above: the widgets are placed in initVisuals() right after xOffset = 86.
	@Inject(method = "initVisuals", at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;xOffset:I",
			opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER), require = 0)
	private void velo$centerWidgets(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (widthTooNarrow || !CenteredInventoryModule.on()) {
			return;
		}
		int offset = CenteredInventoryModule.bookOffset(width, 176);
		if (offset >= 0) {
			xOffset = offset;
		}
	}

	@Inject(method = "updateScreenPosition", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$center(int width, int imageWidth, CallbackInfoReturnable<Integer> cir) {
		if (!isVisible() || widthTooNarrow) {
			return;
		}
		int offset = CenteredInventoryModule.on() ? CenteredInventoryModule.bookOffset(width, imageWidth) : -1;
		if (offset < 0) {
			xOffset = 86;
			return;
		}
		xOffset = offset;
		cir.setReturnValue((width - imageWidth) / 2);
	}
}
*///?}
