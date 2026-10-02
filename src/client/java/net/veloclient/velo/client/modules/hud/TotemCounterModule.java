package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
 * How many Totems of Undying you're carrying in total (inventory, hotbar and offhand) as the totem
 * icon plus a count - green while you have plenty, amber at one, red at none. Pure local inventory
 * read, nothing the server didn't already send.
 */
public final class TotemCounterModule extends AbstractModule implements HudModule, Configurable {

	// Created on first render, not at class load - item components aren't bound yet while modules register.
	private static ItemStack totem;

	private final HudPosition position = new HudPosition(0.66f, 0.86f);
	private boolean hideWhenNone = false;
	private boolean showBackground = true;
	private boolean colorByCount = true;

	public TotemCounterModule() {
		super("totem-counter", "Totem Counter", "Shows how many Totems of Undying you carry, with the totem icon.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		int count = ClientCompat.countInInventory(Items.TOTEM_OF_UNDYING);
		if (count == 0 && hideWhenNone) {
			return;
		}
		String label = String.valueOf(count);
		int textWidth = client.textRenderer.getWidth(label);
		if (showBackground) {
			VeloDraw.fillRounded(context, x, y, width(), 22, 6, 0x90101014);
		}
		if (totem == null) {
			totem = new ItemStack(Items.TOTEM_OF_UNDYING);
		}
		context.drawItemWithoutEntity(totem, x + 4, y + 3);
		int color = !colorByCount ? 0xFFFFFFFF : count == 0 ? 0xFFFF5555 : count == 1 ? 0xFFFFC53D : 0xFF6FE39A;
		context.drawTextWithShadow(client.textRenderer, label, x + 24 + (width() - 28 - textWidth) / 2, y + 7, color);
	}

	@Override
	public int width() {
		return 44;
	}

	@Override
	public int height() {
		return 22;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Hide When You Have None", () -> hideWhenNone, v -> hideWhenNone = v),
				new ConfigField.ToggleField("Color By Count", () -> colorByCount, v -> colorByCount = v),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
