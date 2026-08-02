package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/** Shows the name (and count) of the item currently held in the main hand. */
public final class HeldItemModule extends AbstractModule implements HudModule {

	private final HudPosition position = new HudPosition(0.5f, 0.85f);

	public HeldItemModule() {
		super("held-item", "Held Item", "Shows the name of the item currently held in your main hand.",
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
		String text = stack.getCount() > 1 ? stack.getName().getString() + " x" + stack.getCount() : stack.getName().getString();
		context.drawTextWithShadow(client.textRenderer, text, x, y, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		return 160;
	}

	@Override
	public int height() {
		return MinecraftClient.getInstance().textRenderer.fontHeight;
	}
}
