package net.veloclient.velo.client.modules.debug;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.util.ModuleProfiler;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Locale;

/**
 * Live per-module processing time, for tracking down exactly which module is actually
 * responsible for a frame-time regression instead of guessing from overall FPS. Every tick/
 * world-render/HUD-render module hook is individually timed with {@link System#nanoTime()}
 * (see {@link ModuleProfiler}) around plain Java module code - this never touches GPU state or
 * the renderer backend, so the numbers shown here mean the same thing whether the world is being
 * drawn through Sodium/OpenGL or a Vulkan backend: this only measures how much CPU time this
 * mod's own modules cost, not GPU frame time.
 */
public final class ModuleProfilerOverlayModule extends AbstractModule implements HudModule, Configurable {

	private final HudPosition position = new HudPosition(0.5f, 0.02f);
	private int maxRows = 12;

	public ModuleProfilerOverlayModule() {
		super("module-profiler", "Module Profiler",
				"Shows live processing time (avg/peak ms) per module, broken down by tick/world-render/HUD-render, to track down performance regressions.",
				ModuleCategory.DEBUG, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public void onEnable() {
		ModuleProfiler.enabled = true;
	}

	@Override
	public void onDisable() {
		ModuleProfiler.enabled = false;
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		int lineHeight = client.textRenderer.fontHeight + 1;

		List<ModuleProfiler.Entry> entries = ModuleProfiler.snapshot();
		context.drawTextWithShadow(client.textRenderer, "Module Profiler (avg / peak ms)", x, y, 0xFFFFFFFF);
		int rowY = y + lineHeight + 1;
		int shown = Math.min(maxRows, entries.size());
		for (int i = 0; i < shown; i++) {
			ModuleProfiler.Entry entry = entries.get(i);
			String line = String.format(Locale.ROOT, "[%s] %s: %.2f / %.2f",
					entry.phase().label(), entry.moduleId(), entry.avgMs(), entry.maxMs());
			int color = entry.avgMs() > 2.0 ? 0xFFFF5555 : (entry.avgMs() > 0.5 ? 0xFFFFFF55 : 0xFFC8C8C8);
			context.drawTextWithShadow(client.textRenderer, line, x, rowY, color);
			rowY += lineHeight;
		}
		if (entries.isEmpty()) {
			context.drawTextWithShadow(client.textRenderer, "No samples yet - enable some modules.", x, rowY, 0xFFC8C8C8);
		}
	}

	@Override
	public int width() {
		return 260;
	}

	@Override
	public int height() {
		int lineHeight = MinecraftClient.getInstance().textRenderer.fontHeight + 1;
		int rows = Math.max(1, Math.min(maxRows, ModuleProfiler.snapshot().size()));
		return lineHeight + 1 + rows * lineHeight;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(new ConfigField.SliderField("Rows Shown", 3, 25,
				() -> maxRows, v -> maxRows = (int) v, v -> String.valueOf((int) v)));
	}
}
