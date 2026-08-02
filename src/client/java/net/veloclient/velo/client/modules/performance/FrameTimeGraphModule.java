package net.veloclient.velo.client.modules.performance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/** Rolling per-frame render time graph, sampled from this module's own render calls. */
public final class FrameTimeGraphModule extends AbstractModule implements HudModule {

	private static final int SAMPLES = 120;
	private static final int GRAPH_HEIGHT = 40;

	private final HudPosition position = new HudPosition(0.98f, 0.25f);
	private final long[] frameTimesNanos = new long[SAMPLES];
	private int cursor;
	private long lastFrameNanos = -1;

	public FrameTimeGraphModule() {
		super("frame-time-graph", "Frame Time Graph", "Rolling graph of per-frame render time in milliseconds.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		long now = System.nanoTime();
		if (lastFrameNanos >= 0) {
			frameTimesNanos[cursor] = now - lastFrameNanos;
			cursor = (cursor + 1) % SAMPLES;
		}
		lastFrameNanos = now;

		context.fill(x, y, x + SAMPLES, y + GRAPH_HEIGHT, 0x66000000);
		for (int i = 0; i < SAMPLES; i++) {
			long nanos = frameTimesNanos[(cursor + i) % SAMPLES];
			double ms = nanos / 1_000_000.0;
			int barHeight = (int) Math.min(GRAPH_HEIGHT, ms * 2);
			int color = ms > 33 ? 0xFFFF5555 : (ms > 16 ? 0xFFFFFF55 : 0xFF55FF55);
			context.fill(x + i, y + GRAPH_HEIGHT - barHeight, x + i + 1, y + GRAPH_HEIGHT, color);
		}

		double lastMs = frameTimesNanos[(cursor + SAMPLES - 1) % SAMPLES] / 1_000_000.0;
		MinecraftClient client = MinecraftClient.getInstance();
		context.drawTextWithShadow(client.textRenderer, String.format("%.1f ms", lastMs), x, y + GRAPH_HEIGHT + 2, 0xFFFFFFFF);
	}

	@Override
	public int width() {
		return SAMPLES;
	}

	@Override
	public int height() {
		return GRAPH_HEIGHT + MinecraftClient.getInstance().textRenderer.fontHeight + 2;
	}
}
