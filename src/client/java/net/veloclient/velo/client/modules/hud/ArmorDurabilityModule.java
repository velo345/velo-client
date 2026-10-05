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
	private static final int ICON_BAR_GAP = 2;

	private static final List<String> STYLES = List.of("Classic", "Inventory Slots");
	private static final int SLOT = 18;

	private final HudPosition position = new HudPosition(0.02f, 0.17f);
	// "Inventory Slots": the armor in vanilla-looking inventory slots, like many PvP YouTubers use -
	// only the item's own durability bar, no numbers.
	private String style = "Classic";
	private boolean slotsHorizontal = false;
	private boolean showEmptySlots = true;
	private boolean showIcon = true;
	private boolean showBar = true;
	// Off by default: the bar alone (exactly as wide as the armor icon, under it) reads at a glance;
	// the number is opt-in.
	private boolean showText = false;
	// When on (default), the bar is always exactly the icon's width - the Bar Width slider only
	// applies once this is turned off.
	private boolean barMatchesIcon = true;
	// Matches ICON_SIZE by default so the bar lines up directly under the
	// armor icon above it (the default layout, barBelowIcon=true) instead of
	// visibly overhanging both sides of a much narrower icon.
	private int barWidth = ICON_SIZE;
	private int barHeight = 3;
	private float textScale = 1.0f;
	// Default layout: bar sits below its armor icon (inventory-style stacked
	// rows read awkwardly wide when everything's crammed onto one line) -
	// the text position relative to the bar is still configurable since
	// "below" doesn't imply a side.
	private boolean barBelowIcon = true;
	private boolean textOnLeft = false;

	public ArmorDurabilityModule() {
		super("armor-durability", "Armor & Durability", "Shows each equipped armor piece's durability, inventory-style.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	private int effectiveBarWidth() {
		return barMatchesIcon ? ICON_SIZE : barWidth;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		if (slotStyle()) {
			renderSlots(context, client, x, y);
			return;
		}
		int barWidth = effectiveBarWidth();
		int rowHeight = rowHeight();
		int lineCenterYOffset = lineCenterYOffset();
		int lineXOffset = lineXOffset();
		int rowY = y;
		for (EquipmentSlot slot : SLOTS) {
			ItemStack stack = client.player.getEquippedStack(slot);
			if (stack.isEmpty()) {
				continue;
			}
			if (showIcon) {
				context.getMatrices().pushMatrix();
				context.getMatrices().translate(x, rowY);
				context.drawItemWithoutEntity(stack, 0, 0);
				context.getMatrices().popMatrix();
			}
			int lineX = x + lineXOffset;
			int centerY = rowY + lineCenterYOffset;
			if (stack.isDamageable()) {
				int maxDamage = stack.getMaxDamage();
				int remaining = maxDamage - stack.getDamage();
				float fraction = maxDamage > 0 ? (float) remaining / maxDamage : 1f;
				int color = durabilityColor(fraction);
				if (showBar) {
					String barText = String.valueOf(remaining);
					int barX = lineX;
					if (showText && textOnLeft) {
						drawScaledText(context, client, barText, lineX, centerY, textColor(remaining, maxDamage));
						barX = lineX + scaledTextWidth(client, barText) + 4;
					}
					int barY = centerY - barHeight / 2;
					VeloDraw.fillRounded(context, barX, barY, barWidth, barHeight, Math.min(1, barHeight / 2), 0x66000000);
					int filled = Math.round(barWidth * fraction);
					if (filled > 0) {
						VeloDraw.fillRounded(context, barX, barY, filled, barHeight, Math.min(1, barHeight / 2), color);
					}
					if (showText && !textOnLeft) {
						drawScaledText(context, client, barText, barX + barWidth + 4, centerY, textColor(remaining, maxDamage));
					}
				} else if (showText) {
					drawScaledText(context, client, String.valueOf(remaining), lineX, centerY, textColor(remaining, maxDamage));
				}
			} else if (showText) {
				drawScaledText(context, client, stack.getName().getString(), lineX, centerY, 0xFFFFFFFF);
			}
			rowY += rowHeight + ROW_GAP;
		}
	}

	private boolean slotStyle() {
		return style.equals("Inventory Slots");
	}

	private void renderSlots(DrawContext context, MinecraftClient client, int x, int y) {
		int i = 0;
		for (EquipmentSlot slot : SLOTS) {
			ItemStack stack = client.player.getEquippedStack(slot);
			if (stack.isEmpty() && !showEmptySlots) {
				continue;
			}
			int sx = slotsHorizontal ? x + i * SLOT : x;
			int sy = slotsHorizontal ? y : y + i * SLOT;
			// Vanilla's inventory slot: grey well, dark top/left edge, white bottom/right edge.
			context.fill(sx, sy, sx + SLOT, sy + SLOT, 0xFF8B8B8B);
			context.fill(sx, sy, sx + SLOT - 1, sy + 1, 0xFF373737);
			context.fill(sx, sy, sx + 1, sy + SLOT - 1, 0xFF373737);
			context.fill(sx + 1, sy + SLOT - 1, sx + SLOT, sy + SLOT, 0xFFFFFFFF);
			context.fill(sx + SLOT - 1, sy + 1, sx + SLOT, sy + SLOT, 0xFFFFFFFF);
			if (!stack.isEmpty()) {
				context.drawItemWithoutEntity(stack, sx + 1, sy + 1);
				//? if <26.1 {
				context.drawStackOverlay(client.textRenderer, stack, sx + 1, sy + 1);
				//?} else {
				/*context.itemDecorations(client.font, stack, sx + 1, sy + 1);
				*///?}
			}
			i++;
		}
	}

	private int visibleSlots() {
		if (showEmptySlots) {
			return SLOTS.length;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return SLOTS.length;
		}
		int count = 0;
		for (EquipmentSlot slot : SLOTS) {
			if (!client.player.getEquippedStack(slot).isEmpty()) {
				count++;
			}
		}
		return count;
	}

	/** Durability number color: white while undamaged, then green, orange and red as it wears down. */
	private static int textColor(int remaining, int max) {
		if (remaining >= max) {
			return 0xFFFFFFFF;
		}
		float fraction = max > 0 ? (float) remaining / max : 1f;
		if (fraction < 0.2f) {
			return 0xFFFF5555;
		}
		if (fraction < 0.5f) {
			return 0xFFFFA53D;
		}
		return 0xFF55FF55;
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

	private int scaledTextWidth(MinecraftClient client, String text) {
		return Math.round(client.textRenderer.getWidth(text) * textScale);
	}

	private int textLineHeight() {
		return Math.round(MinecraftClient.getInstance().textRenderer.fontHeight * textScale);
	}

	/** Height of the bar+text line alone, independent of the icon. */
	private int barTextRowHeight() {
		int h = 0;
		if (showBar) {
			h = Math.max(h, barHeight);
		}
		if (showText) {
			h = Math.max(h, textLineHeight());
		}
		return h;
	}

	private int rowHeight() {
		int iconRowHeight = showIcon ? ICON_SIZE : 0;
		int barTextHeight = barTextRowHeight();
		if (barBelowIcon) {
			int gap = showIcon && (showBar || showText) ? ICON_BAR_GAP : 0;
			return iconRowHeight + gap + barTextHeight;
		}
		return Math.max(iconRowHeight, barTextHeight);
	}

	/** Vertical offset from the row's top to the center of the bar/text line. */
	private int lineCenterYOffset() {
		int barTextHeight = barTextRowHeight();
		if (barBelowIcon) {
			int iconRowHeight = showIcon ? ICON_SIZE : 0;
			int gap = showIcon && (showBar || showText) ? ICON_BAR_GAP : 0;
			return iconRowHeight + gap + barTextHeight / 2;
		}
		return rowHeight() / 2;
	}

	/** Horizontal offset from the row's left edge to the bar/text line - only nonzero when the icon sits beside it, not above it. */
	private int lineXOffset() {
		return !barBelowIcon && showIcon ? ICON_SIZE + 5 : 0;
	}

	@Override
	public int width() {
		if (slotStyle()) {
			return slotsHorizontal ? visibleSlots() * SLOT : SLOT;
		}
		int barWidth = effectiveBarWidth();
		int numberWidth = Math.round(MinecraftClient.getInstance().textRenderer.getWidth("0000") * textScale);
		int barLineWidth = showBar ? barWidth : (showText ? numberWidth : 0);
		if (showBar && showText) {
			barLineWidth += 4 + numberWidth;
		}
		if (barBelowIcon) {
			return Math.max(showIcon ? ICON_SIZE : 0, barLineWidth);
		}
		return (showIcon ? ICON_SIZE + 5 : 0) + barLineWidth;
	}

	@Override
	public int height() {
		if (slotStyle()) {
			return slotsHorizontal ? SLOT : visibleSlots() * SLOT;
		}
		int rowHeight = rowHeight();
		return SLOTS.length * rowHeight + (SLOTS.length - 1) * ROW_GAP;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = v),
				new ConfigField.ChoiceField("Slot Direction", List.of("Vertical", "Horizontal"),
						() -> slotsHorizontal ? "Horizontal" : "Vertical", v -> slotsHorizontal = v.equals("Horizontal")),
				new ConfigField.ToggleField("Show Empty Slots", () -> showEmptySlots, v -> showEmptySlots = v),
				new ConfigField.ToggleField("Show Icon", () -> showIcon, v -> showIcon = v),
				new ConfigField.ToggleField("Show Bar", () -> showBar, v -> showBar = v),
				new ConfigField.ToggleField("Show Durability Number", () -> showText, v -> showText = v),
				new ConfigField.ToggleField("Bar Matches Icon Width", () -> barMatchesIcon, v -> barMatchesIcon = v),
				new ConfigField.SliderField("Custom Bar Width", 8, 120, () -> barWidth, v -> barWidth = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Bar Height", 2, 10, () -> barHeight, v -> barHeight = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Number Size", 0.5, 2.0, () -> textScale, v -> textScale = (float) v, v -> Math.round(v * 100) + "%"),
				new ConfigField.ToggleField("Bar Below Icon", () -> barBelowIcon, v -> barBelowIcon = v),
				new ConfigField.ChoiceField("Number Side", java.util.List.of("Right", "Left"),
						() -> textOnLeft ? "Left" : "Right", v -> textOnLeft = v.equals("Left")));
	}
}
