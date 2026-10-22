package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.hud.HudLook;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/** Renders the client's current FPS, exactly like the vanilla F3 counter but always visible. */
public final class FpsCounterModule extends AbstractModule implements HudModule {

	private final HudPosition position = new HudPosition(0.01f, 0.01f);

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
		if (HudLook.on()) {
			HudLook.stat(context, x, y, String.valueOf(client.getCurrentFps()), "FPS", 0xFFFFFFFF);
			return;
		}
		String text = client.getCurrentFps() + " FPS";
		net.veloclient.velo.client.hud.HudLook.draw(context, text, x, y, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		return HudLook.on() ? HudLook.statWidth("000", "FPS") : net.veloclient.velo.client.hud.HudLook.measure("000 FPS");
	}

	@Override
	public int height() {
		return HudLook.on() ? HudLook.statHeight() : MinecraftClient.getInstance().textRenderer.fontHeight;
	}
}
