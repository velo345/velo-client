package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Inventory HUD: your main inventory (the 27 slots above the hotbar) as a small grid on screen, so mid-fight you can see
 * how many pearls, gaps, potions or blocks you still have without opening it. Counts and durability bars are drawn like
 * in the real inventory. Pure local read of your own inventory.
 */
public final class InventoryHudModule extends AbstractModule implements HudModule, Configurable {

	private static final int SLOT = 18;

	private final HudPosition position = new HudPosition(0.5f, 0.70f, HudPosition.Corner.BOTTOM_RIGHT);
	private boolean includeHotbar = false;
	private boolean showBackground = true;
	private boolean hideWhenEmpty = true;
	private boolean slotFrames = true;

	public InventoryHudModule() {
		super("inventory-hud", "Inventory HUD", "Shows your inventory on screen so you see what you still have mid-fight.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		List<ItemStack> stacks = ClientCompat.inventoryStacks();
		boolean sample = net.veloclient.velo.client.hud.HudSample.active();
		if (stacks.size() < 36 && !sample) {
			return;
		}
		boolean any = false;
		// with the hotbar shown, items there count too
		for (int i = includeHotbar ? 0 : 9; i < Math.min(36, stacks.size()); i++) {
			if (!stacks.get(i).isEmpty()) {
				any = true;
				break;
			}
		}
		if (!any && hideWhenEmpty && !sample) {
			return;
		}
		if (showBackground) {
			VeloDraw.fillRounded(context, x, y, width(), height(), 5, 0x90101014);
		}
		int rows = includeHotbar ? 4 : 3;
		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < 9; col++) {
				// rows 1-3 of the real inventory are slots 9..35, the hotbar (0..8) goes under them like on screen
				int index = row < 3 ? 9 + row * 9 + col : col;
				int sx = x + 3 + col * SLOT;
				int sy = y + 3 + row * SLOT + (row == 3 ? 3 : 0);
				if (slotFrames) {
					context.fill(sx, sy, sx + SLOT - 1, sy + SLOT - 1, 0x30FFFFFF);
				}
				ItemStack stack = index < stacks.size() ? stacks.get(index) : ItemStack.EMPTY;
				if (stack.isEmpty()) {
					continue;
				}
				context.drawItemWithoutEntity(stack, sx, sy);
				//? if <26.1 {
				context.drawStackOverlay(client.textRenderer, stack, sx, sy);
				//?} else {
				/*context.itemDecorations(client.font, stack, sx, sy);
				*///?}
			}
		}
	}

	@Override
	public int width() {
		return 9 * SLOT + 5;
	}

	@Override
	public int height() {
		return (includeHotbar ? 4 * SLOT + 3 : 3 * SLOT) + 5;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Include Hotbar", () -> includeHotbar, v -> includeHotbar = v),
				new ConfigField.ToggleField("Hide When Empty", () -> hideWhenEmpty, v -> hideWhenEmpty = v),
				new ConfigField.ToggleField("Slot Frames", () -> slotFrames, v -> slotFrames = v),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
