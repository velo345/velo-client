package net.veloclient.velo.devbuild.slots;

import java.util.ArrayList;
import java.util.List;

/** The open container screen's slots with their numbers (version-specific part of Numbered Inventories). */
final class SlotList {

	record Numbered(int x, int y, int number, boolean player, boolean empty) {
	}

	private SlotList() {
	}

	//? if <26.1 {
	static boolean isContainer(Object screen) {
		return screen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>
				&& !(screen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen);
	}

	// One slot's number, its x / y inside the screen's slot area (where the slot pass draws), or null.
	static Numbered numbered(Object screen, Object target) {
		int container = 0;
		for (var slot : ((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) screen).getScreenHandler().slots) {
			boolean player = slot.inventory instanceof net.minecraft.entity.player.PlayerInventory;
			int number = player ? slot.getIndex() + 1 : ++container;
			if (slot == target) {
				return player && (slot.getIndex() < 0 || slot.getIndex() >= 36) ? null : new Numbered(slot.x, slot.y, number, player, slot.getStack().isEmpty());
			}
		}
		return null;
	}

	static List<Numbered> of(Object screen) {
		List<Numbered> out = new ArrayList<>();
		var accessor = (net.veloclient.velo.client.mixin.HandledScreenAccessor) screen;
		int container = 0;
		for (var slot : ((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) screen).getScreenHandler().slots) {
			int x = accessor.velo$left() + slot.x;
			int y = accessor.velo$top() + slot.y;
			if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory) {
				int index = slot.getIndex();
				if (index >= 0 && index < 36) {
					out.add(new Numbered(x, y, index + 1, true, slot.getStack().isEmpty()));
				}
			} else {
				out.add(new Numbered(x, y, ++container, false, slot.getStack().isEmpty()));
			}
		}
		return out;
	}
	//?} else {
	/*static boolean isContainer(Object screen) {
		return screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
				&& !(screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen);
	}

	static Numbered numbered(Object screen, Object target) {
		int container = 0;
		for (var slot : ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) screen).getMenu().slots) {
			boolean player = slot.container instanceof net.minecraft.world.entity.player.Inventory;
			int number = player ? slot.getContainerSlot() + 1 : ++container;
			if (slot == target) {
				return player && (slot.getContainerSlot() < 0 || slot.getContainerSlot() >= 36) ? null
						: new Numbered(slot.x, slot.y, number, player, slot.getItem().isEmpty());
			}
		}
		return null;
	}

	static List<Numbered> of(Object screen) {
		List<Numbered> out = new ArrayList<>();
		var accessor = (net.veloclient.velo.client.mixin.HandledScreenAccessor) screen;
		int container = 0;
		for (var slot : ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) screen).getMenu().slots) {
			int x = accessor.velo$left() + slot.x;
			int y = accessor.velo$top() + slot.y;
			if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
				int index = slot.getContainerSlot();
				if (index >= 0 && index < 36) {
					out.add(new Numbered(x, y, index + 1, true, slot.getItem().isEmpty()));
				}
			} else {
				out.add(new Numbered(x, y, ++container, false, slot.getItem().isEmpty()));
			}
		}
		return out;
	}
	*///?}
}
