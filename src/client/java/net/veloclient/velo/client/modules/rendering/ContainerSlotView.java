package net.veloclient.velo.client.modules.rendering;

import java.util.List;

/** {@link ContainerAnimationsModule.SlotView} over a container screen's slots (one per version, reused every frame). */
public final class ContainerSlotView implements ContainerAnimationsModule.SlotView {

	//? if <26.1 {
	private List<net.minecraft.screen.slot.Slot> slots = List.of();
	//?} else {
	/*private List<net.minecraft.world.inventory.Slot> slots = List.of();
	*///?}
	private int left;
	private int top;
	private boolean creative;

	/** Points the view at this frame's screen: its menu, where its window is, and whether it's the creative inventory. */
	@SuppressWarnings("unchecked")
	public ContainerSlotView set(Object menu, int left, int top, boolean creative) {
		//? if <26.1 {
		this.slots = ((net.minecraft.screen.ScreenHandler) menu).slots;
		//?} else {
		/*this.slots = ((net.minecraft.world.inventory.AbstractContainerMenu) menu).slots;
		*///?}
		this.left = left;
		this.top = top;
		this.creative = creative;
		return this;
	}

	@Override
	public int size() {
		return slots.size();
	}

	@Override
	public boolean tracked(int i) {
		//? if <26.1 {
		return !creative || slots.get(i).inventory instanceof net.minecraft.entity.player.PlayerInventory;
		//?} else {
		/*return !creative || slots.get(i).container instanceof net.minecraft.world.entity.player.Inventory;
		*///?}
	}

	@Override
	public Object item(int i) {
		//? if <26.1 {
		net.minecraft.item.ItemStack stack = slots.get(i).getStack();
		//?} else {
		/*net.minecraft.world.item.ItemStack stack = slots.get(i).getItem();
		*///?}
		return stack.isEmpty() ? null : stack.getItem();
	}

	@Override
	public int count(int i) {
		//? if <26.1 {
		return slots.get(i).getStack().getCount();
		//?} else {
		/*return slots.get(i).getItem().getCount();
		*///?}
	}

	@Override
	public Object copy(int i) {
		//? if <26.1 {
		return slots.get(i).getStack().copy();
		//?} else {
		/*return slots.get(i).getItem().copy();
		*///?}
	}

	@Override
	public boolean sameStack(Object a, int i) {
		//? if <26.1 {
		return a != null && net.minecraft.item.ItemStack.areItemsAndComponentsEqual((net.minecraft.item.ItemStack) a, slots.get(i).getStack());
		//?} else {
		/*return a != null && net.minecraft.world.item.ItemStack.isSameItemSameComponents((net.minecraft.world.item.ItemStack) a, slots.get(i).getItem());
		*///?}
	}

	@Override
	public Object withCount(Object stack, int count) {
		//? if <26.1 {
		((net.minecraft.item.ItemStack) stack).setCount(count);
		//?} else {
		/*((net.minecraft.world.item.ItemStack) stack).setCount(count);
		*///?}
		return stack;
	}

	@Override
	public int x(int i) {
		return left + slots.get(i).x;
	}

	@Override
	public int y(int i) {
		return top + slots.get(i).y;
	}
}
