package net.veloclient.velo.client.modules.pvp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/** How many healing splash potions ("pots") you have left - or every splash potion - with the potion icon. */
public final class PotCounterModule extends AbstractModule implements HudModule, Configurable {

	private static final List<String> COUNT = List.of("Healing Splash", "All Splash Potions", "All Potions");

	private final HudPosition position = new HudPosition(0.60f, 0.86f);
	private String counting = "Healing Splash";
	private boolean hideWhenNone = false;
	private boolean showBackground = true;
	private double warnBelow = 3;

	public PotCounterModule() {
		super("pot-counter", "Pot Counter", "Shows how many healing splash potions you have left.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
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
		int count = 0;
		ItemStack icon = null;
		for (ItemStack stack : ClientCompat.inventoryStacks()) {
			boolean match = switch (counting) {
				case "All Splash Potions" -> PvpCompat.isSplashPotion(stack);
				case "All Potions" -> PvpCompat.isPotion(stack);
				default -> PvpCompat.isHealingSplash(stack);
			};
			if (match) {
				count += stack.getCount();
				if (icon == null) {
					icon = stack;
				}
			}
		}
		if (count == 0 && hideWhenNone) {
			return;
		}
		if (icon == null) {
			icon = PvpCompat.itemStack("minecraft:splash_potion");
		}
		if (showBackground) {
			VeloDraw.fillRounded(context, x, y, width(), 22, 6, 0x90101014);
		}
		context.drawItemWithoutEntity(icon, x + 4, y + 3);
		String label = String.valueOf(count);
		int color = count == 0 ? 0xFFFF5555 : count < warnBelow ? 0xFFFFC53D : 0xFF6FE39A;
		int textWidth = client.textRenderer.getWidth(label);
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
				new ConfigField.ChoiceField("Count", COUNT, () -> counting, v -> counting = v),
				new ConfigField.SliderField("Warn Below", 0, 16, () -> warnBelow, v -> warnBelow = Math.round(v), v -> String.valueOf((int) Math.round(v))),
				new ConfigField.ToggleField("Hide When You Have None", () -> hideWhenNone, v -> hideWhenNone = v),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
