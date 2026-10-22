package net.veloclient.velo.client.modules.hud;

import java.util.List;
import net.veloclient.velo.client.gui.widget.VeloDraw;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
//? if <26.1 {
import net.minecraft.client.gui.hud.InGameHud;
//?} else if <26.2 {
/*import net.minecraft.client.gui.Gui;
*///?} else {
/*import net.minecraft.client.gui.Hud;
*///?}
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
public final class PotionTimersModule extends AbstractModule implements HudModule, net.veloclient.velo.module.Configurable {

	private static final int ICON_SIZE = 18;
	private static final int ROW_HEIGHT = 20;
	private static final List<String> STYLES = List.of("List", "Compact");
	private final HudPosition position = new HudPosition(0.02f, 0.24f);
	private String style = "List";

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
		var effects = client.player.getStatusEffects();
		boolean compact = style.equals("Compact");
		int row = compact ? 20 : ROW_HEIGHT + 2;
		int pad = net.veloclient.velo.client.hud.HudLook.hasBackground() ? 5 : 0;
		if (pad > 0 && !effects.isEmpty()) {
			net.veloclient.velo.client.hud.HudLook.panel(context, x, y, width(), effects.size() * row + pad * 2);
		}
		int rowY = y + pad;
		int iconX = x + pad;
		for (StatusEffectInstance effect : effects) {
			int iconY = rowY + (row - ICON_SIZE) / 2;
			//? if <26.1 {
			context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, InGameHud.getEffectTexture(effect.getEffectType()), iconX, iconY, ICON_SIZE, ICON_SIZE);
			//?} else if <26.2 {
			/*context.blitSprite(RenderPipelines.GUI_TEXTURED, Gui.getMobEffectSprite(effect.getEffect()), iconX, iconY, ICON_SIZE, ICON_SIZE);
			*///?} else {
			/*context.blitSprite(RenderPipelines.GUI_TEXTURED, Hud.getMobEffectSprite(effect.getEffect()), iconX, iconY, ICON_SIZE, ICON_SIZE);
			*///?}
			int duration = effect.getDuration();
			String time = duration < 0 ? "∞" : formatDuration(duration);
			// The last 10 seconds: the time turns red.
			int timeColor = duration >= 0 && duration < 200 ? 0xFFFF6B73 : 0xFFD4D4DA;
			int textX = iconX + ICON_SIZE + 5;
			if (compact) {
				net.veloclient.velo.client.hud.HudLook.draw(context, time, textX, rowY + (row - 9) / 2, duration >= 0 && duration < 200 ? 0xFFFF6B73 : 0xFFFFFFFF);
			} else {
				String name = effect.getEffectType().value().getName().getString() + roman(effect.getAmplifier() + 1);
				int top = rowY + (row - 19) / 2;
				net.veloclient.velo.client.hud.HudLook.draw(context, name, textX, top, 0xFFFFFFFF);
				net.veloclient.velo.client.hud.HudLook.draw(context, time, textX, top + 10, timeColor);
			}
			rowY += row;
		}
	}

	/** " II", " III"... after an effect's name (nothing for level 1). */
	private static String roman(int level) {
		return switch (level) {
			case 1 -> "";
			case 2 -> " II";
			case 3 -> " III";
			case 4 -> " IV";
			case 5 -> " V";
			default -> " " + level;
		};
	}

	private static String formatDuration(int ticks) {
		int totalSeconds = ticks / 20;
		return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
	}

	@Override
	public int width() {
		int pad = net.veloclient.velo.client.hud.HudLook.hasBackground() ? 10 : 0;
		return (style.equals("Compact") ? 60 : 130) + pad;
	}

	@Override
	public List<net.veloclient.velo.module.ConfigField> configFields() {
		return List.of(new net.veloclient.velo.module.ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = v));
	}

	@Override
	public int height() {
		MinecraftClient client = MinecraftClient.getInstance();
		int count = client.player != null ? client.player.getStatusEffects().size() : 0;
		int row = style.equals("Compact") ? 20 : ROW_HEIGHT + 2;
		return Math.max(1, count) * row + (net.veloclient.velo.client.hud.HudLook.hasBackground() ? 10 : 0);
	}
}
