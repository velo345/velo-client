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

/** Arrows you're carrying (normal, spectral and tipped), with the arrow icon - optionally only while holding a bow. */
public final class ArrowCounterModule extends AbstractModule implements HudModule, Configurable {

	private final HudPosition position = new HudPosition(0.72f, 0.86f);
	private boolean onlyWithBow = false;
	private boolean showBackground = true;

	public ArrowCounterModule() {
		super("arrow-counter", "Arrow Counter", "Shows how many arrows you have left.", ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		var player = PvpCompat.localPlayer();
		if (player == null) {
			return;
		}
		boolean editing = ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;
		if (onlyWithBow && !editing && !PvpCompat.isBow(PvpCompat.mainHand(player)) && !PvpCompat.isBow(PvpCompat.offHand(player))) {
			return;
		}
		int count = 0;
		ItemStack icon = null;
		for (ItemStack stack : ClientCompat.inventoryStacks()) {
			String id = stack.isEmpty() ? "" : PvpCompat.itemId(stack);
			if (id.equals("minecraft:arrow") || id.equals("minecraft:spectral_arrow") || id.equals("minecraft:tipped_arrow")) {
				count += stack.getCount();
				if (icon == null) {
					icon = stack;
				}
			}
		}
		if (icon == null) {
			icon = PvpCompat.itemStack("minecraft:arrow");
		}
		if (showBackground) {
			VeloDraw.fillRounded(context, x, y, width(), 22, 6, 0x90101014);
		}
		context.drawItemWithoutEntity(icon, x + 4, y + 3);
		String label = String.valueOf(count);
		int color = count == 0 ? 0xFFFF5555 : count < 16 ? 0xFFFFC53D : 0xFFFFFFFF;
		context.drawTextWithShadow(client.textRenderer, label, x + 24 + (width() - 28 - client.textRenderer.getWidth(label)) / 2, y + 7, color);
	}

	@Override
	public int width() {
		return 48;
	}

	@Override
	public int height() {
		return 22;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Only While Holding A Bow", () -> onlyWithBow, v -> onlyWithBow = v),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
