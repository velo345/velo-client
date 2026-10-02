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
 * The item in your main hand as its real icon plus a count - how many are in the held stack, or
 * (optionally) how many of that item you carry in total - with an optional durability bar and name.
 */
public final class HeldItemModule extends AbstractModule implements HudModule, Configurable {

	private final HudPosition position = new HudPosition(0.5f, 0.85f);
	private boolean showName = false;
	private boolean countWholeInventory = false;
	private boolean showBackground = true;
	private boolean showDurability = true;

	public HeldItemModule() {
		super("held-item", "Held Item", "Shows the icon and count of the item in your main hand.",
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
		ItemStack stack = client.player.getMainHandStack();
		if (stack.isEmpty()) {
			return;
		}
		int count = countWholeInventory ? ClientCompat.countInInventory(stack.getItem()) : stack.getCount();
		String countLabel = count > 1 || countWholeInventory ? String.valueOf(count) : "";
		String name = showName ? stack.getName().getString() : "";
		var textRenderer = client.textRenderer;
		int contentWidth = 16 + (countLabel.isEmpty() ? 0 : 4 + textRenderer.getWidth(countLabel))
				+ (name.isEmpty() ? 0 : 6 + textRenderer.getWidth(name));
		int boxWidth = contentWidth + 8;
		if (showBackground) {
			VeloDraw.fillRounded(context, x, y, boxWidth, 22, 6, 0x90101014);
		}
		context.drawItemWithoutEntity(stack, x + 4, y + 3);
		int textX = x + 24;
		if (!countLabel.isEmpty()) {
			context.drawTextWithShadow(textRenderer, countLabel, textX, y + 7, 0xFFFFFFFF);
			textX += textRenderer.getWidth(countLabel) + 6;
		}
		if (!name.isEmpty()) {
			context.drawTextWithShadow(textRenderer, name, textX, y + 7, 0xFFBFC3CC);
		}
		if (showDurability && stack.isDamageable() && stack.getDamage() > 0) {
			float fraction = 1f - stack.getDamage() / (float) Math.max(1, stack.getMaxDamage());
			int color = fraction < 0.2f ? 0xFFFF5555 : fraction < 0.5f ? 0xFFFFC53D : 0xFF55FF55;
			context.fill(x + 4, y + 19, x + 20, y + 20, 0xFF000000);
			context.fill(x + 4, y + 19, x + 4 + Math.round(16 * fraction), y + 20, color);
		}
	}

	@Override
	public int width() {
		return 120;
	}

	@Override
	public int height() {
		return 22;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show Item Name", () -> showName, v -> showName = v),
				new ConfigField.ToggleField("Count Every One In Inventory", () -> countWholeInventory, v -> countWholeInventory = v),
				new ConfigField.ToggleField("Show Durability Bar", () -> showDurability, v -> showDurability = v),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
