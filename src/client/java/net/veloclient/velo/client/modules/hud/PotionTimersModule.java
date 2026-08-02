package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Lists active potion effects with their real icon (the same texture vanilla's
 * own inventory-screen status effect list uses, via
 * {@link InGameHud#getEffectTexture}) and remaining duration. The vanilla HUD's
 * own status effect icons (top-right corner) are suppressed while this is
 * enabled - see {@code HudManager} - so effects aren't shown twice.
 */
public final class PotionTimersModule extends AbstractModule implements HudModule {

	private static final int ICON_SIZE = 18;
	private static final int ROW_HEIGHT = 20;
	private final HudPosition position = new HudPosition(0.02f, 0.24f);

	public PotionTimersModule() {
		super("potion-timers", "Potion Effect Timers", "Shows active potion effects with icon and remaining duration.",
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
		int rowY = y;
		for (StatusEffectInstance effect : client.player.getStatusEffects()) {
			context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, InGameHud.getEffectTexture(effect.getEffectType()),
					x, rowY + (ROW_HEIGHT - ICON_SIZE) / 2, ICON_SIZE, ICON_SIZE);

			String name = effect.getEffectType().value().getName().getString();
			String amplifier = effect.getAmplifier() > 0 ? " " + (effect.getAmplifier() + 1) : "";
			String line = name + amplifier;
			int textY = rowY + (ROW_HEIGHT / 2) - client.textRenderer.fontHeight - 1;
			context.drawTextWithShadow(client.textRenderer, line, x + ICON_SIZE + 4, textY, 0xFFFFFFFF);
			context.drawTextWithShadow(client.textRenderer, formatDuration(effect.getDuration()),
					x + ICON_SIZE + 4, textY + client.textRenderer.fontHeight + 1, 0xFFAAAAAA);
			rowY += ROW_HEIGHT;
		}
	}

	private static String formatDuration(int ticks) {
		int totalSeconds = ticks / 20;
		return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
	}

	@Override
	public int width() {
		return 150;
	}

	@Override
	public int height() {
		MinecraftClient client = MinecraftClient.getInstance();
		int count = client.player != null ? client.player.getStatusEffects().size() : 0;
		return Math.max(1, count) * ROW_HEIGHT;
	}
}
