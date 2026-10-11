package net.veloclient.velo.client.modules.pvp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every running item cooldown (ender pearl, wind charge, chorus fruit, a shield an axe just
 * disabled, goat horns, modded items...) as its item icon with circular progress around it and the
 * seconds left. Read straight from the local player's own cooldown table, so the time is exact.
 *
 * <p>Styles: <b>Ring</b> - a progress ring that empties clockwise around the icon; <b>Icon
 * Fill</b> - the icon itself is the progress bar (the part still cooling down is faded out);
 * <b>Pie</b> - a dark clock sweep over the icon like vanilla's hotbar; <b>Bar</b> - a thin bar
 * under the icon.
 */
public final class CooldownsModule extends AbstractModule implements HudModule, Configurable {

	private static final List<String> STYLES = List.of("Ring", "Icon Fill", "Pie", "Bar");
	private static final List<String> LAYOUTS = List.of("Horizontal", "Vertical");
	private static final int CELL = 26;

	private final HudPosition position = new HudPosition(0.5f, 0.72f);
	private String style = "Ring";
	private String layout = "Horizontal";
	private boolean showSeconds = true;
	private boolean showDecimal = true;
	private boolean showBackground = true;
	private boolean colorByProgress = true;
	private int ringColor = 0xFF7C5CFF;
	private int lastCount = 1;

	public CooldownsModule() {
		super("cooldowns", "Cooldowns", "Circular cooldown timers for ender pearls, wind charges, shields and every other item cooldown.",
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
		List<PvpCompat.CooldownView> cooldowns = new ArrayList<>(PvpCompat.cooldowns());
		boolean preview = cooldowns.isEmpty() && net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;
		if (preview) {
			cooldowns.add(new PvpCompat.CooldownView("minecraft:ender_pearl", PvpCompat.itemStack("minecraft:ender_pearl"), 14, 20));
			cooldowns.add(new PvpCompat.CooldownView("minecraft:wind_charge", PvpCompat.itemStack("minecraft:wind_charge"), 4, 10));
		}
		lastCount = Math.max(1, cooldowns.size());
		cooldowns.sort((a, b) -> Integer.compare(a.remainingTicks(), b.remainingTicks()));
		boolean vertical = layout.equals("Vertical");
		for (int i = 0; i < cooldowns.size(); i++) {
			int cx = vertical ? x : x + i * (CELL + 4);
			int cy = vertical ? y + i * (CELL + (showSeconds ? 10 : 4)) : y;
			drawOne(context, client, cooldowns.get(i), cx, cy, tickDelta);
		}
	}

	private void drawOne(DrawContext context, MinecraftClient client, PvpCompat.CooldownView cooldown, int x, int y, float tickDelta) {
		float remaining = Math.max(0f, cooldown.remainingTicks() - tickDelta);
		float progress = Math.max(0f, Math.min(1f, remaining / cooldown.totalTicks()));
		float cx = x + CELL / 2f;
		float cy = y + CELL / 2f;
		int accent = colorByProgress ? progressColor(progress) : ringColor;
		if (showBackground) {
			VeloDraw.fillCircle(context, cx, cy, CELL / 2f, 0x99101014);
		}
		int iconX = x + (CELL - 16) / 2;
		int iconY = y + (CELL - 16) / 2;
		switch (style) {
			case "Icon Fill" -> {
				// Draw the icon, then fade the part that's still cooling down (top to bottom drains).
				context.drawItemWithoutEntity(cooldown.icon(), iconX, iconY);
				int covered = Math.round(16 * progress);
				if (covered > 0) {
					context.fill(iconX, iconY, iconX + 16, iconY + covered, 0xB0101014);
				}
				VeloDraw.strokeArc(context, cx, cy, CELL / 2f - 1.5f, 1.5f, 0, 360, accent & 0x60FFFFFF);
			}
			case "Pie" -> {
				context.drawItemWithoutEntity(cooldown.icon(), iconX, iconY);
				VeloDraw.fillPie(context, cx, cy, CELL / 2f - 1f, 360f * (1f - progress), 360f * progress, 0xA0000000);
			}
			case "Bar" -> {
				context.drawItemWithoutEntity(cooldown.icon(), iconX, iconY);
				int barWidth = CELL - 4;
				context.fill(x + 2, y + CELL - 3, x + 2 + barWidth, y + CELL - 1, 0x80000000);
				context.fill(x + 2, y + CELL - 3, x + 2 + Math.round(barWidth * progress), y + CELL - 1, accent);
			}
			default -> {
				context.drawItemWithoutEntity(cooldown.icon(), iconX, iconY);
				VeloDraw.strokeArc(context, cx, cy, CELL / 2f - 1.5f, 2f, 0, 360, 0x40FFFFFF);
				VeloDraw.strokeArc(context, cx, cy, CELL / 2f - 1.5f, 2f, 360f * (1f - progress), 360f * progress, accent);
			}
		}
		if (showSeconds) {
			float seconds = remaining / 20f;
			String label = showDecimal && seconds < 10f ? String.format(Locale.ROOT, "%.1fs", seconds) : String.format(Locale.ROOT, "%.0fs", Math.ceil(seconds));
			int width = client.textRenderer.getWidth(label);
			context.drawTextWithShadow(client.textRenderer, label, x + (CELL - width) / 2, y + CELL + 1, 0xFFFFFFFF);
		}
	}

	/** Green when nearly ready, through amber, to red right after use. */
	private static int progressColor(float progress) {
		int r = (int) (0x6F + (0xFF - 0x6F) * progress);
		int g = (int) (0xE3 + (0x55 - 0xE3) * progress);
		int b = (int) (0x9A + (0x55 - 0x9A) * progress);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	@Override
	public int width() {
		return layout.equals("Vertical") ? CELL : lastCount * (CELL + 4) - 4;
	}

	@Override
	public int height() {
		int cell = CELL + (showSeconds ? 10 : 0);
		return layout.equals("Vertical") ? lastCount * (CELL + (showSeconds ? 10 : 4)) : cell;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = v),
				new ConfigField.ChoiceField("Layout", LAYOUTS, () -> layout, v -> layout = v),
				new ConfigField.ToggleField("Show Seconds", () -> showSeconds, v -> showSeconds = v),
				new ConfigField.ToggleField("Show Decimal (1.2s)", () -> showDecimal, v -> showDecimal = v),
				new ConfigField.ToggleField("Color By Progress", () -> colorByProgress, v -> colorByProgress = v),
				new ConfigField.ColorField("Ring Color", () -> ringColor, v -> ringColor = v, true),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
