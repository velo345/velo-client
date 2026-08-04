package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Shows the player's own position. This is information the client already
 * knows about itself (identical to the vanilla F3 position line) - not a
 * scan of the world, so it's safe on every server (design spec section 6.2).
 */
public final class CoordinatesModule extends AbstractModule implements HudModule {

	private static final String SAMPLE = "XYZ: -0000 / -000 / -0000";
	private final HudPosition position = new HudPosition(0.02f, 0.07f);

	public CoordinatesModule() {
		super("coordinates", "Coordinates", "Displays your current X/Y/Z position.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, true);
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
		String text = String.format("XYZ: %.1f / %.1f / %.1f",
				client.player.getX(), client.player.getY(), client.player.getZ());
		context.drawTextWithShadow(client.textRenderer, text, x, y, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		return MinecraftClient.getInstance().textRenderer.getWidth(SAMPLE);
	}

	@Override
	public int height() {
		return MinecraftClient.getInstance().textRenderer.fontHeight;
	}
}
