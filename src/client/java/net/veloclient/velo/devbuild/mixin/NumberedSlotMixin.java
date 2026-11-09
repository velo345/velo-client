package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Numbered Inventories: the number goes into the slot right before its item is drawn - behind the item, and inside the
// slot pass, so Container Animations' slide / scale moves it along.
//? if <1.21.11 {
/*@Mixin(net.minecraft.client.gui.screen.ingame.HandledScreen.class)
public abstract class NumberedSlotMixin {

	@Inject(method = "drawSlot", at = @At("HEAD"))
	private void velo$slotNumber(net.minecraft.client.gui.DrawContext context, net.minecraft.screen.slot.Slot slot, CallbackInfo ci) {
		net.veloclient.velo.devbuild.slots.NumberedInventoryModule.drawBehind(this, context, slot);
	}
}
*///?} else if <26.1 {
@Mixin(net.minecraft.client.gui.screen.ingame.HandledScreen.class)
public abstract class NumberedSlotMixin {

	@Inject(method = "drawSlot", at = @At("HEAD"))
	private void velo$slotNumber(net.minecraft.client.gui.DrawContext context, net.minecraft.screen.slot.Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
		net.veloclient.velo.devbuild.slots.NumberedInventoryModule.drawBehind(this, context, slot);
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class)
public abstract class NumberedSlotMixin {

	@Inject(method = "extractSlot", at = @At("HEAD"))
	private void velo$slotNumber(net.minecraft.client.gui.GuiGraphicsExtractor context, net.minecraft.world.inventory.Slot slot, int mouseX, int mouseY,
			CallbackInfo ci) {
		net.veloclient.velo.devbuild.slots.NumberedInventoryModule.drawBehind(this, context, slot);
	}
}
*///?}
