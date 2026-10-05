package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Boss bars as a movable, scalable HUD element (or hidden altogether). Draws the same sprites as
 * vanilla, so colors and notches look identical. With no boss around, the HUD editor shows an
 * example bar so it can still be placed.
 */
public final class BossBarModule extends AbstractModule implements HudModule, Configurable {

	private static final String[] COLORS = {"pink", "blue", "red", "green", "yellow", "purple", "white"};
	private static final String[] NOTCHES = {"notched_6", "notched_10", "notched_12", "notched_20"};
	private static final int BAR_WIDTH = 182;

	private final HudPosition position = new HudPosition(0.5f, 0.0f, HudPosition.Corner.TOP_CENTER);
	private boolean hidden;
	private boolean showNames = true;
	private int maxBars = 4;

	/** One bar to draw: name, 0..1 progress, color index, style index (0 = solid). */
	private record Bar(Text name, float progress, int color, int style) {
	}

	public BossBarModule() {
		super("boss-bar", "Boss Bar", "Move, scale or hide boss bars - in the HUD editor you get an example bar when no boss is around.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	/** Vanilla's boss bars are skipped while this is on (we draw them, or nothing when hidden). */
	public boolean replacesVanilla() {
		return isEnabled();
	}

	private static boolean editing() {
		return net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;
	}

	private List<Bar> bars() {
		List<Bar> bars = new ArrayList<>();
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		Object hud = client.inGameHud.getBossBarHud();
		//?} else if <26.2 {
		/*Object hud = client.gui.getBossOverlay();
		*///?} else {
		/*Object hud = client.gui.hud.getBossOverlay();
		*///?}
		for (Object value : ((net.veloclient.velo.client.mixin.BossBarHudAccessor) hud).velo$bars().values()) {
			if (bars.size() >= maxBars) {
				break;
			}
			//? if <26.1 {
			net.minecraft.entity.boss.BossBar bar = (net.minecraft.entity.boss.BossBar) value;
			bars.add(new Bar(bar.getName(), bar.getPercent(), bar.getColor().ordinal(), bar.getStyle().ordinal()));
			//?} else {
			/*net.minecraft.world.BossEvent bar = (net.minecraft.world.BossEvent) value;
			bars.add(new Bar(bar.getName(), bar.getProgress(), bar.getColor().ordinal(), bar.getOverlay().ordinal()));
			*///?}
		}
		if (bars.isEmpty() && editing()) {
			bars.add(new Bar(Text.literal("Ender Dragon"), 0.72f, 5, 0));
		}
		return bars;
	}

	private int rowHeight() {
		return showNames ? 19 : 9;
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public int width() {
		if (hidden && !editing()) {
			return 0;
		}
		List<Bar> bars = bars();
		if (bars.isEmpty()) {
			return 0;
		}
		int width = BAR_WIDTH;
		if (showNames) {
			for (Bar bar : bars) {
				width = Math.max(width, MinecraftClient.getInstance().textRenderer.getWidth(bar.name()));
			}
		}
		return width;
	}

	@Override
	public int height() {
		if (hidden && !editing()) {
			return 0;
		}
		int count = bars().size();
		return count == 0 ? 0 : count * rowHeight() - 4;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		if (hidden && !editing()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		int width = width();
		int rowY = y;
		for (Bar bar : bars()) {
			int barX = x + (width - BAR_WIDTH) / 2;
			int barY = rowY;
			if (showNames) {
				int textWidth = client.textRenderer.getWidth(bar.name());
				context.drawTextWithShadow(client.textRenderer, bar.name(), x + (width - textWidth) / 2, rowY, 0xFFFFFFFF);
				barY += 10;
			}
			drawBar(context, barX, barY, bar);
			rowY += rowHeight();
		}
		if (hidden) {
			context.drawTextWithShadow(client.textRenderer, "(hidden in game)", x, y + height() + 2, 0xFFAAAAAA);
		}
	}

	private static void drawBar(DrawContext context, int x, int y, Bar bar) {
		String color = COLORS[Math.max(0, Math.min(COLORS.length - 1, bar.color()))];
		int filled = Math.round(Math.max(0, Math.min(1, bar.progress())) * BAR_WIDTH);
		Identifier background = Identifier.of("minecraft", "boss_bar/" + color + "_background");
		Identifier progress = Identifier.of("minecraft", "boss_bar/" + color + "_progress");
		context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, background, BAR_WIDTH, 5, 0, 0, x, y, BAR_WIDTH, 5);
		if (bar.style() > 0 && bar.style() <= NOTCHES.length) {
			context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "boss_bar/" + NOTCHES[bar.style() - 1] + "_background"),
					BAR_WIDTH, 5, 0, 0, x, y, BAR_WIDTH, 5);
		}
		if (filled > 0) {
			context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, progress, BAR_WIDTH, 5, 0, 0, x, y, filled, 5);
			if (bar.style() > 0 && bar.style() <= NOTCHES.length) {
				context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "boss_bar/" + NOTCHES[bar.style() - 1] + "_progress"),
						BAR_WIDTH, 5, 0, 0, x, y, filled, 5);
			}
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Hide Boss Bars", () -> hidden, v -> hidden = v),
				new ConfigField.ToggleField("Show Boss Names", () -> showNames, v -> showNames = v),
				new ConfigField.SliderField("Max Bars", 1, 10, () -> maxBars, v -> maxBars = (int) Math.round(v), v -> String.valueOf((int) Math.round(v))));
	}
}
