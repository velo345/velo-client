package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/** Shows left/right clicks-per-second, purely a readout of your own inputs (design spec section 6.2). */
public final class CpsCounterModule extends AbstractModule implements HudModule {

	private final HudPosition position = new HudPosition(0.02f, 0.10f);

	public CpsCounterModule() {
		super("cps-counter", "CPS Counter", "Shows left/right click rate.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
		CpsTracker.ensureRegistered();
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		String text = "CPS: " + CpsTracker.leftCps() + " / " + CpsTracker.rightCps();
		context.drawTextWithShadow(client.textRenderer, text, x, y, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		return MinecraftClient.getInstance().textRenderer.getWidth("CPS: 00 / 00");
	}

	@Override
	public int height() {
		return MinecraftClient.getInstance().textRenderer.fontHeight;
	}
}
