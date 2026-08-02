package net.veloclient.velo.client.modules.servertools;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/** Active particle count/type, same string vanilla's F3 "P:" line shows (design spec section 6.3). */
public final class ParticleDebugOverlayModule extends AbstractModule implements HudModule {

	private final HudPosition position = new HudPosition(0.75f, 0.02f);

	public ParticleDebugOverlayModule() {
		super("particle-debug-overlay", "Particle Debug Overlay", "Shows active particle count and type breakdown.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		context.drawTextWithShadow(client.textRenderer, "P: " + client.particleManager.getDebugString(), x, y, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		return 220;
	}

	@Override
	public int height() {
		return MinecraftClient.getInstance().textRenderer.fontHeight;
	}
}
