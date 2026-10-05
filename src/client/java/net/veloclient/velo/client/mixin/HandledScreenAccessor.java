package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Hovered slot and on-screen position/size of an inventory screen (Shulker Preview). */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.screen.ingame.HandledScreen.class)
public interface HandledScreenAccessor {
	@Accessor("focusedSlot")
	net.minecraft.screen.slot.Slot velo$hoveredSlot();

	@Accessor("x")
	int velo$left();

	@Accessor("y")
	int velo$top();

	@Accessor("backgroundWidth")
	int velo$width();
}
//?} else {
/*@Mixin(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class)
public interface HandledScreenAccessor {
	@Accessor("hoveredSlot")
	net.minecraft.world.inventory.Slot velo$hoveredSlot();

	@Accessor("leftPos")
	int velo$left();

	@Accessor("topPos")
	int velo$top();

	@Accessor("imageWidth")
	int velo$width();
}
*///?}
