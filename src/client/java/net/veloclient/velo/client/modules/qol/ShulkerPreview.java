package net.veloclient.velo.client.modules.qol;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.mixin.HandledScreenAccessor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Shulker Preview: hold Shift over a shulker box or bundle in any inventory to see what's inside
 * (vanilla-style container panel, tinted in the box's color). Ctrl+Shift pins an edit panel next
 * to the inventory where you can take items out and put items in by clicking - in singleplayer
 * (done on the integrated server, so it's saved like any inventory click) and in the Creative
 * inventory (creative item packets, works on servers too). Survival on a server can't change a
 * shulker in your inventory, so there it's preview only.
 */
public final class ShulkerPreview {

	private static final Identifier SHULKER_GUI = Identifier.of("minecraft", "textures/gui/container/shulker_box.png");
	private static final int PANEL_W = 176;

	/** The pinned edit panel: which slot's shulker it edits (null = not pinned). */
	private static Object pinnedSlot;
	private static Screen pinnedScreen;
	private static String status = "";
	/** Dev-only (screenshot tour): a slot shown as if hovered with Shift held. */
	private static Object tourSlot;

	/** Dev-only (screenshot tour): left-click the first slot of the pinned panel. */
	public static void tourClickFirst(Screen screen) {
		if (pinnedSlot != null && pinnedScreen == screen) {
			int[] at = pinnedPosition(screen, 3);
			click(screen, at[0] + 8 + 8, at[1] + 18 + 8, 0);
		}
	}

	/** Dev-only (screenshot tour): show the panel for {@code slot} - hover preview, or pinned for editing. */
	public static void tourShow(Screen screen, Object slot, boolean pinned) {
		tourSlot = pinned ? null : slot;
		if (pinned) {
			pinnedSlot = slot;
			pinnedScreen = screen;
			status = "Click to take items out, or put the item on your cursor in.";
		}
	}

	private ShulkerPreview() {
	}

	private static ShulkerPreviewModule module() {
		return net.veloclient.velo.module.ModuleRegistry.get("shulker-preview")
				.filter(m -> m instanceof ShulkerPreviewModule && m.isEnabled()).map(m -> (ShulkerPreviewModule) m).orElse(null);
	}

	private static boolean down(int key) {
		long handle = MinecraftClient.getInstance().getWindow().getHandle();
		return GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
	}

	private static boolean shift() {
		return tourSlot != null || down(GLFW.GLFW_KEY_LEFT_SHIFT) || down(GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	private static boolean ctrl() {
		return down(GLFW.GLFW_KEY_LEFT_CONTROL) || down(GLFW.GLFW_KEY_RIGHT_CONTROL);
	}

	// ---- Hooks ----

	/** Inventory tooltips are skipped while our panel shows for the hovered item. */
	public static boolean suppressTooltip(Screen screen, int mouseX, int mouseY) {
		if (module() == null) {
			return false;
		}
		if (pinnedSlot != null && pinnedScreen == screen) {
			return true;
		}
		ItemStack stack = hoveredStack(screen);
		return stack != null && shift() && (isShulker(stack) || isBundle(stack) && module().bundles());
	}

	/** Draws the hover preview or the pinned edit panel (after the screen). */
	public static void render(Screen screen, DrawContext context, int mouseX, int mouseY) {
		ShulkerPreviewModule module = module();
		if (module == null || !isContainerScreen(screen)) {
			pinnedSlot = null;
			return;
		}
		if (pinnedScreen != screen) {
			pinnedSlot = null;
		}
		if (pinnedSlot != null) {
			ItemStack stack = slotStack(pinnedSlot);
			if (stack == null || !isShulker(stack)) {
				pinnedSlot = null;
			} else {
				int[] at = pinnedPosition(screen, rows(stack));
				drawPanel(context, stack, at[0], at[1], mouseX, mouseY, true, module.tint());
				// The item on the cursor stays on top of the panel.
				ItemStack cursor = cursorStack(screen);
				if (cursor != null && !cursor.isEmpty()) {
					drawItem(context, cursor, mouseX - 8, mouseY - 8);
				}
				return;
			}
		}
		ItemStack stack = hoveredStack(screen);
		if (stack == null || !shift()) {
			return;
		}
		boolean shulker = isShulker(stack);
		boolean bundle = !shulker && isBundle(stack) && module.bundles();
		if (!shulker && !bundle) {
			return;
		}
		if (shulker && ctrl() && module.editing()) {
			pinnedSlot = hoveredSlot(screen);
			pinnedScreen = screen;
			status = canEdit(screen) ? "Click to take items out, or put the item on your cursor in." : "Servers don't allow editing in survival - preview only.";
			return;
		}
		int rows = rows(stack);
		int h = panelHeight(rows);
		int x = Math.min(mouseX + 12, screen.width - PANEL_W - 4);
		int y = Math.max(4, Math.min(mouseY - 12, screen.height - h - 4));
		drawPanel(context, stack, x, y, -1, -1, false, module.tint());
	}

	/** Clicks inside the pinned panel are ours. Returns true when handled. */
	public static boolean click(Screen screen, double mouseX, double mouseY, int button) {
		if (module() == null || pinnedSlot == null || pinnedScreen != screen) {
			return false;
		}
		ItemStack stack = slotStack(pinnedSlot);
		if (stack == null || !isShulker(stack)) {
			pinnedSlot = null;
			return false;
		}
		int[] at = pinnedPosition(screen, 3);
		int x = at[0];
		int y = at[1];
		if (mouseX < x || mouseX >= x + PANEL_W || mouseY < y || mouseY >= y + panelHeight(3)) {
			return false;
		}
		// Close button (top right).
		if (mouseX >= x + PANEL_W - 14 && mouseY < y + 14) {
			pinnedSlot = null;
			return true;
		}
		int index = slotAt(x, y, mouseX, mouseY);
		if (index >= 0) {
			if (!canEdit(screen)) {
				status = "Servers don't allow editing in survival - preview only.";
			} else {
				edit(screen, index, button == 1);
			}
		}
		return true;
	}

	/** Esc closes the pinned panel first (instead of the whole inventory). */
	public static boolean escape(Screen screen) {
		if (pinnedSlot != null && pinnedScreen == screen) {
			pinnedSlot = null;
			return true;
		}
		return false;
	}

	// ---- Drawing ----

	private static int rows(ItemStack stack) {
		if (isShulker(stack)) {
			return 3;
		}
		return Math.max(1, (contents(stack).size() + 8) / 9);
	}

	private static int panelHeight(int rows) {
		return 17 + rows * 18 + 7 + 11;
	}

	private static int[] pinnedPosition(Screen screen, int rows) {
		HandledScreenAccessor a = (HandledScreenAccessor) screen;
		int right = a.velo$left() + a.velo$width() + 4;
		int x = right + PANEL_W <= screen.width - 2 ? right : Math.max(2, a.velo$left() - PANEL_W - 4);
		return new int[] {x, Math.max(2, a.velo$top())};
	}

	private static int slotAt(int x, int y, double mouseX, double mouseY) {
		int col = (int) Math.floor((mouseX - (x + 7)) / 18);
		int row = (int) Math.floor((mouseY - (y + 17)) / 18);
		if (col < 0 || col > 8 || row < 0 || row > 2) {
			return -1;
		}
		return row * 9 + col;
	}

	/** Vanilla's shulker box window: title bar, the slot rows, bottom edge - tinted in the box's color. */
	private static void drawPanel(DrawContext context, ItemStack stack, int x, int y, int mouseX, int mouseY, boolean editable, boolean tint) {
		List<ItemStack> items = contents(stack);
		int rows = rows(stack);
		int color = tint ? tintColor(stack) : 0xFFFFFFFF;
		int slotsH = rows * 18;
		context.getMatrices().pushMatrix();
		// Above inventory items, below nothing.
		context.drawTexture(RenderPipelines.GUI_TEXTURED, SHULKER_GUI, x, y, 0f, 0f, PANEL_W, 17 + Math.min(3, rows) * 18, 256, 256, color);
		for (int extra = 3; extra < rows; extra++) {
			context.drawTexture(RenderPipelines.GUI_TEXTURED, SHULKER_GUI, x, y + 17 + extra * 18, 0f, 17f, PANEL_W, 18, 256, 256, color);
		}
		// Footer: plain background rows from under the title, then the window's bottom edge.
		context.drawTexture(RenderPipelines.GUI_TEXTURED, SHULKER_GUI, x, y + 17 + slotsH, 0f, 4f, PANEL_W, 12, 256, 256, color);
		context.drawTexture(RenderPipelines.GUI_TEXTURED, SHULKER_GUI, x, y + 29 + slotsH, 0f, 160f, PANEL_W, 6, 256, 256, color);
		MinecraftClient client = MinecraftClient.getInstance();
		var renderer = client.textRenderer;
		Text title = name(stack);
		context.drawText(renderer, title, x + 8, y + 6, 0xFF404040, false);
		if (editable) {
			context.drawText(renderer, "x", x + PANEL_W - 11, y + 5, 0xFF404040, false);
		}
		int hovered = editable ? slotAt(x, y, mouseX, mouseY) : -1;
		for (int i = 0; i < items.size() && i < rows * 9; i++) {
			int sx = x + 8 + (i % 9) * 18;
			int sy = y + 18 + (i / 9) * 18;
			if (i == hovered) {
				context.fill(sx, sy, sx + 16, sy + 16, 0x80FFFFFF);
			}
			ItemStack item = items.get(i);
			if (!item.isEmpty()) {
				drawItem(context, item, sx, sy);
			}
		}
		String footer;
		if (editable) {
			footer = hovered >= 0 && hovered < items.size() && !items.get(hovered).isEmpty() ? name(items.get(hovered)).getString() : status;
		} else {
			int used = (int) items.stream().filter(s -> !s.isEmpty()).count();
			footer = isShulker(stack) ? used + "/27 used  ·  Ctrl+Shift: edit" : used + " stacks";
		}
		context.drawText(renderer, trim(footer, PANEL_W - 14), x + 8, y + 20 + slotsH, 0xFF404040, false);
		context.getMatrices().popMatrix();
	}

	private static String trim(String text, int width) {
		var renderer = MinecraftClient.getInstance().textRenderer;
		if (renderer.getWidth(text) <= width) {
			return text;
		}
		while (!text.isEmpty() && renderer.getWidth(text + "...") > width) {
			text = text.substring(0, text.length() - 1);
		}
		return text + "...";
	}

	// ---- Editing ----

	/** Left click: take/put/merge/swap. Right click: take half / put one. Same rules as a chest slot. */
	private static void edit(Screen screen, int index, boolean right) {
		Object slot = pinnedSlot;
		if (isCreativeScreen(screen)) {
			ItemStack shulker = slotStack(slot).copy();
			ItemStack cursor = cursorStack(screen).copy();
			List<ItemStack> items = contents(shulker);
			ItemStack[] result = apply(items, index, cursor, right);
			if (result == null) {
				return;
			}
			ItemStack updated = withContents(shulker, items);
			setCreativeSlot(screen, slot, updated);
			setCursor(screen, result[0]);
			return;
		}
		editOnServer(slot, index, right);
	}

	/**
	 * Applies one click to {@code items} (mutated) with {@code cursor}. Returns {new cursor} or null when
	 * nothing changes (e.g. putting a shulker box into a shulker box).
	 */
	static ItemStack[] apply(List<ItemStack> items, int index, ItemStack cursor, boolean right) {
		ItemStack inSlot = items.get(index);
		if (cursor.isEmpty()) {
			if (inSlot.isEmpty()) {
				return null;
			}
			int take = right ? (inSlot.getCount() + 1) / 2 : inSlot.getCount();
			ItemStack taken = inSlot.copyWithCount(take);
			items.set(index, inSlot.getCount() - take <= 0 ? ItemStack.EMPTY : inSlot.copyWithCount(inSlot.getCount() - take));
			return new ItemStack[] {taken};
		}
		if (!fitsInShulker(cursor)) {
			status = "That can't go into a shulker box.";
			return null;
		}
		if (inSlot.isEmpty()) {
			int put = right ? 1 : cursor.getCount();
			items.set(index, cursor.copyWithCount(put));
			return new ItemStack[] {cursor.getCount() - put <= 0 ? ItemStack.EMPTY : cursor.copyWithCount(cursor.getCount() - put)};
		}
		if (sameItem(inSlot, cursor)) {
			int room = maxCount(inSlot) - inSlot.getCount();
			int put = Math.min(room, right ? 1 : cursor.getCount());
			if (put <= 0) {
				return null;
			}
			items.set(index, inSlot.copyWithCount(inSlot.getCount() + put));
			return new ItemStack[] {cursor.getCount() - put <= 0 ? ItemStack.EMPTY : cursor.copyWithCount(cursor.getCount() - put)};
		}
		items.set(index, cursor.copy());
		return new ItemStack[] {inSlot.copy()};
	}

	// ---- Version-specific bits ----

	private static boolean isContainerScreen(Screen screen) {
		return screen instanceof HandledScreenAccessor;
	}

	private static Object hoveredSlot(Screen screen) {
		if (tourSlot != null) {
			return tourSlot;
		}
		return isContainerScreen(screen) ? ((HandledScreenAccessor) screen).velo$hoveredSlot() : null;
	}

	private static ItemStack hoveredStack(Screen screen) {
		Object slot = hoveredSlot(screen);
		return slot == null ? null : slotStack(slot);
	}

	//? if <26.1 {
	private static ItemStack slotStack(Object slot) {
		return ((net.minecraft.screen.slot.Slot) slot).getStack();
	}

	public static boolean isShulker(ItemStack stack) {
		return stack.getItem() instanceof net.minecraft.item.BlockItem item && item.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock;
	}

	static boolean isBundle(ItemStack stack) {
		return stack.get(net.minecraft.component.DataComponentTypes.BUNDLE_CONTENTS) != null;
	}

	private static boolean fitsInShulker(ItemStack stack) {
		return !isShulker(stack) && stack.getItem().canBeNested();
	}

	static List<ItemStack> contents(ItemStack stack) {
		if (isShulker(stack)) {
			net.minecraft.util.collection.DefaultedList<ItemStack> list = net.minecraft.util.collection.DefaultedList.ofSize(27, ItemStack.EMPTY);
			var container = stack.get(net.minecraft.component.DataComponentTypes.CONTAINER);
			if (container != null) {
				container.copyTo(list);
			}
			return new ArrayList<>(list);
		}
		List<ItemStack> list = new ArrayList<>();
		var bundle = stack.get(net.minecraft.component.DataComponentTypes.BUNDLE_CONTENTS);
		if (bundle != null) {
			bundle.iterateCopy().forEach(list::add);
		}
		return list;
	}

	static ItemStack withContents(ItemStack shulker, List<ItemStack> items) {
		ItemStack copy = shulker.copy();
		copy.set(net.minecraft.component.DataComponentTypes.CONTAINER, net.minecraft.component.type.ContainerComponent.fromStacks(items));
		return copy;
	}

	private static int tintColor(ItemStack stack) {
		if (stack.getItem() instanceof net.minecraft.item.BlockItem item && item.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock box
				&& box.getColor() != null) {
			return lighten(box.getColor().getEntityColor());
		}
		return 0xFFFFFFFF;
	}

	private static Text name(ItemStack stack) {
		return stack.getName();
	}

	private static boolean sameItem(ItemStack a, ItemStack b) {
		return ItemStack.areItemsAndComponentsEqual(a, b);
	}

	private static int maxCount(ItemStack stack) {
		return stack.getMaxCount();
	}

	private static void drawItem(DrawContext context, ItemStack stack, int x, int y) {
		context.drawItem(stack, x, y);
		context.drawStackOverlay(MinecraftClient.getInstance().textRenderer, stack, x, y);
	}

	private static ItemStack cursorStack(Screen screen) {
		return ((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) screen).getScreenHandler().getCursorStack();
	}

	private static void setCursor(Screen screen, ItemStack stack) {
		((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) screen).getScreenHandler().setCursorStack(stack);
	}

	private static boolean isCreativeScreen(Screen screen) {
		return screen instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
	}

	/** Singleplayer (any mode) or the Creative inventory. */
	private static boolean canEdit(Screen screen) {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.getServer() != null || isCreativeScreen(screen) && client.player != null && client.player.isInCreativeMode();
	}

	/** Creative: change the item client-side and tell the server with a creative item packet. */
	private static void setCreativeSlot(Screen screen, Object slot, ItemStack updated) {
		net.minecraft.screen.slot.Slot s = (net.minecraft.screen.slot.Slot) slot;
		MinecraftClient client = MinecraftClient.getInstance();
		if (!(s.inventory instanceof net.minecraft.entity.player.PlayerInventory)) {
			status = "Only shulkers in your own inventory can be edited here.";
			return;
		}
		int index = s.getIndex();
		int handlerSlot = index < 9 ? 36 + index : index < 36 ? index : index == 40 ? 45 : -1;
		if (handlerSlot < 0) {
			return;
		}
		s.setStack(updated);
		client.player.getInventory().setStack(index, updated);
		client.interactionManager.clickCreativeStack(updated, handlerSlot);
	}

	/** Singleplayer: do the same click on the integrated server, which then syncs the inventory back. */
	private static void editOnServer(Object clientSlot, int index, boolean right) {
		MinecraftClient client = MinecraftClient.getInstance();
		var server = client.getServer();
		if (server == null || client.player == null) {
			return;
		}
		net.minecraft.screen.slot.Slot slot = (net.minecraft.screen.slot.Slot) clientSlot;
		int syncId = client.player.currentScreenHandler.syncId;
		java.util.UUID uuid = client.player.getUuid();
		int slotId = slot.id;
		server.execute(() -> {
			var sp = server.getPlayerManager().getPlayer(uuid);
			if (sp == null || sp.currentScreenHandler.syncId != syncId || slotId >= sp.currentScreenHandler.slots.size()) {
				return;
			}
			var handler = sp.currentScreenHandler;
			net.minecraft.screen.slot.Slot serverSlot = handler.getSlot(slotId);
			ItemStack shulker = serverSlot.getStack();
			if (!isShulker(shulker)) {
				return;
			}
			List<ItemStack> items = contents(shulker);
			ItemStack[] result = apply(items, index, handler.getCursorStack().copy(), right);
			if (result == null) {
				return;
			}
			serverSlot.setStack(withContents(shulker, items));
			handler.setCursorStack(result[0]);
			handler.sendContentUpdates();
			handler.syncState();
		});
	}
	//?} else {
	/*private static ItemStack slotStack(Object slot) {
		return ((net.minecraft.world.inventory.Slot) slot).getItem();
	}

	public static boolean isShulker(ItemStack stack) {
		return stack.getItem() instanceof net.minecraft.world.item.BlockItem item && item.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
	}

	static boolean isBundle(ItemStack stack) {
		return stack.get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS) != null;
	}

	private static boolean fitsInShulker(ItemStack stack) {
		return !isShulker(stack) && stack.getItem().canFitInsideContainerItems();
	}

	static List<ItemStack> contents(ItemStack stack) {
		if (isShulker(stack)) {
			net.minecraft.core.NonNullList<ItemStack> list = net.minecraft.core.NonNullList.withSize(27, ItemStack.EMPTY);
			var container = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
			if (container != null) {
				container.copyInto(list);
			}
			return new ArrayList<>(list);
		}
		List<ItemStack> list = new ArrayList<>();
		var bundle = stack.get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) {
			bundle.itemCopyStream().forEach(list::add);
		}
		return list;
	}

	static ItemStack withContents(ItemStack shulker, List<ItemStack> items) {
		ItemStack copy = shulker.copy();
		copy.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(items));
		return copy;
	}

	private static int tintColor(ItemStack stack) {
		if (stack.getItem() instanceof net.minecraft.world.item.BlockItem item && item.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock box
				&& box.getColor() != null) {
			return lighten(box.getColor().getTextureDiffuseColor());
		}
		return 0xFFFFFFFF;
	}

	private static Text name(ItemStack stack) {
		return stack.getHoverName();
	}

	private static boolean sameItem(ItemStack a, ItemStack b) {
		return ItemStack.isSameItemSameComponents(a, b);
	}

	private static int maxCount(ItemStack stack) {
		return stack.getMaxStackSize();
	}

	private static void drawItem(DrawContext context, ItemStack stack, int x, int y) {
		context.item(stack, x, y);
		context.itemDecorations(MinecraftClient.getInstance().textRenderer, stack, x, y);
	}

	private static ItemStack cursorStack(Screen screen) {
		return ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) screen).getMenu().getCarried();
	}

	private static void setCursor(Screen screen, ItemStack stack) {
		((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) screen).getMenu().setCarried(stack);
	}

	private static boolean isCreativeScreen(Screen screen) {
		return screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
	}

	private static boolean canEdit(Screen screen) {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.getServer() != null || isCreativeScreen(screen) && client.player != null && client.player.hasInfiniteMaterials();
	}

	private static void setCreativeSlot(Screen screen, Object slot, ItemStack updated) {
		net.minecraft.world.inventory.Slot s = (net.minecraft.world.inventory.Slot) slot;
		MinecraftClient client = MinecraftClient.getInstance();
		if (!(s.container instanceof net.minecraft.world.entity.player.Inventory)) {
			status = "Only shulkers in your own inventory can be edited here.";
			return;
		}
		int index = s.getContainerSlot();
		int handlerSlot = index < 9 ? 36 + index : index < 36 ? index : index == 40 ? 45 : -1;
		if (handlerSlot < 0) {
			return;
		}
		s.set(updated);
		client.player.getInventory().setItem(index, updated);
		client.gameMode.handleCreativeModeItemAdd(updated, handlerSlot);
	}

	private static void editOnServer(Object clientSlot, int index, boolean right) {
		MinecraftClient client = MinecraftClient.getInstance();
		var server = client.getServer();
		if (server == null || client.player == null) {
			return;
		}
		net.minecraft.world.inventory.Slot slot = (net.minecraft.world.inventory.Slot) clientSlot;
		int syncId = client.player.containerMenu.containerId;
		java.util.UUID uuid = client.player.getUUID();
		int slotId = slot.index;
		server.execute(() -> {
			var sp = server.getPlayerList().getPlayer(uuid);
			if (sp == null || sp.containerMenu.containerId != syncId || slotId >= sp.containerMenu.slots.size()) {
				return;
			}
			var handler = sp.containerMenu;
			net.minecraft.world.inventory.Slot serverSlot = handler.getSlot(slotId);
			ItemStack shulker = serverSlot.getItem();
			if (!isShulker(shulker)) {
				return;
			}
			List<ItemStack> items = contents(shulker);
			ItemStack[] result = apply(items, index, handler.getCarried().copy(), right);
			if (result == null) {
				return;
			}
			serverSlot.set(withContents(shulker, items));
			handler.setCarried(result[0]);
			handler.broadcastChanges();
			handler.sendAllDataToRemote();
		});
	}
	*///?}

	/** The dye color brightened a lot, so the gray GUI texture is tinted gently like a real shulker box. */
	private static int lighten(int rgb) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		r = 150 + r * 105 / 255;
		g = 150 + g * 105 / 255;
		b = 150 + b * 105 / 255;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
}
