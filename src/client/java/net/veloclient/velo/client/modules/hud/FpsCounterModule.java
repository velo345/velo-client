package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/** Renders the client's current FPS, exactly like the vanilla F3 counter but always visible. */
public final class FpsCounterModule extends AbstractModule implements HudModule {

	private final HudPosition position = new HudPosition(0.02f, 0.02f);

	public FpsCounterModule() {
		super("fps-counter", "FPS Counter", "Shows current frames per second.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, true);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		String text = client.getCurrentFps() + " FPS";
		context.drawTextWithShadow(client.textRenderer, text, x, y, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		return MinecraftClient.getInstance().textRenderer.getWidth("000 FPS");
	}

	@Override
	public int height() {
		return MinecraftClient.getInstance().textRenderer.fontHeight;
	}
}
