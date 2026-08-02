package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Shows each equipped armor piece stacked vertically like the vanilla
 * inventory's armor column (head to feet), with an icon, a hand-drawn
 * durability bar, and remaining/max text - each independently toggleable so
 * this can be as minimal as "just a bar" or as detailed as all three.
 */
public final class ArmorDurabilityModule extends AbstractModule implements HudModule, Configurable {

	private static final EquipmentSlot[] SLOTS = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};
	private static final int ICON_SIZE = 16;
	private static final int ROW_GAP = 3;

	private final HudPosition position = new HudPosition(0.02f, 0.17f);
	private boolean showIcon = true;
	private boolean showBar = true;
	private boolean showText = true;
	private int barWidth = 60;
	private int barHeight = 3;
	private float textScale = 1.0f;

	public ArmorDurabilityModule() {
		super("armor-durability", "Armor & Durability", "Shows each equipped armor piece's durability, inventory-style.",
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
		int rowHeight = rowHeight();
		int rowY = y;
		for (EquipmentSlot slot : SLOTS) {
			ItemStack stack = client.player.getEquippedStack(slot);
			if (stack.isEmpty()) {
				continue;
			}
			int cursorX = x;
			int centerY = rowY + rowHeight / 2;
			if (showIcon) {
				context.getMatrices().pushMatrix();
				context.getMatrices().translate(cursorX, centerY - ICON_SIZE / 2f);
				context.drawItemWithoutEntity(stack, 0, 0);
				context.getMatrices().popMatrix();
				cursorX += ICON_SIZE + 5;
			}
			if (stack.isDamageable()) {
				int maxDamage = stack.getMaxDamage();
				int remaining = maxDamage - stack.getDamage();
				float fraction = maxDamage > 0 ? (float) remaining / maxDamage : 1f;
				int color = durabilityColor(fraction);
				if (showBar) {
					int barY = centerY - barHeight / 2;
					VeloDraw.fillRounded(context, cursorX, barY, barWidth, barHeight, Math.min(1, barHeight / 2), 0x66000000);
					int filled = Math.round(barWidth * fraction);
					if (filled > 0) {
						VeloDraw.fillRounded(context, cursorX, barY, filled, barHeight, Math.min(1, barHeight / 2), color);
					}
					if (showText) {
						String text = remaining + "/" + maxDamage;
						drawScaledText(context, client, text, cursorX + barWidth + 4, centerY, 0xFFFFFFFF);
					}
				} else if (showText) {
					String text = stack.getName().getString() + ": " + remaining + "/" + maxDamage;
					drawScaledText(context, client, text, cursorX, centerY, color);
				}
			} else if (showText) {
				drawScaledText(context, client, stack.getName().getString(), cursorX, centerY, 0xFFFFFFFF);
			}
			rowY += rowHeight + ROW_GAP;
		}
	}

	private static int durabilityColor(float fraction) {
		if (fraction < 0.2f) {
			return 0xFFFF5555;
		}
		if (fraction < 0.5f) {
			return 0xFFFFFF55;
		}
		return 0xFF55FF55;
	}

	/** Draws text vertically centered on {@code centerY}, scaled by {@link #textScale}. */
	private void drawScaledText(DrawContext context, MinecraftClient client, String text, int x, int centerY, int color) {
		int scaledHeight = Math.round(client.textRenderer.fontHeight * textScale);
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(x, centerY - scaledHeight / 2f);
		context.getMatrices().scale(textScale, textScale);
		context.drawTextWithShadow(client.textRenderer, text, 0, 0, color);
		context.getMatrices().popMatrix();
	}

	private int textLineHeight() {
		return Math.round(MinecraftClient.getInstance().textRenderer.fontHeight * textScale);
	}

	private int rowHeight() {
		int textHeight = textLineHeight();
		int contentHeight = showIcon ? ICON_SIZE : (showBar ? barHeight : textHeight);
		return Math.max(contentHeight, textHeight);
	}

	@Override
	public int width() {
		int w = 0;
		if (showIcon) {
			w += ICON_SIZE + 5;
		}
		w += showBar ? barWidth : 90;
		if (showBar && showText) {
			w += 44;
		}
		return w;
	}

	@Override
	public int height() {
		int rowHeight = rowHeight();
		return SLOTS.length * rowHeight + (SLOTS.length - 1) * ROW_GAP;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show Icon", () -> showIcon, v -> showIcon = v),
				new ConfigField.ToggleField("Show Bar", () -> showBar, v -> showBar = v),
				new ConfigField.ToggleField("Show Text", () -> showText, v -> showText = v),
				new ConfigField.SliderField("Bar Width", 20, 120, () -> barWidth, v -> barWidth = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Bar Height", 2, 10, () -> barHeight, v -> barHeight = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Text Size", 0.5, 2.0, () -> textScale, v -> textScale = (float) v, v -> Math.round(v * 100) + "%"));
	}
}
