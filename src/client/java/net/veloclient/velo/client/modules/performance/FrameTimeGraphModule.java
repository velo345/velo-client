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
	/** The last ~2000 frames for the stats line (average FPS and 1% low = FPS of the slowest 1% of frames). */
	private final long[] statNanos = new long[2000];
	private int statCursor;
	private int statCount;
	private long statsAt;
	private String statsLine = "";

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
			if (now - lastFrameNanos < 1_000_000_000L) {
				statNanos[statCursor] = now - lastFrameNanos;
				statCursor = (statCursor + 1) % statNanos.length;
				statCount = Math.min(statNanos.length, statCount + 1);
			}
		}
		lastFrameNanos = now;
		if (now - statsAt > 500_000_000L) {
			statsAt = now;
			statsLine = stats();
		}

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
		net.veloclient.velo.client.hud.HudLook.draw(context, String.format(java.util.Locale.ROOT, "%.1f ms", lastMs) + statsLine, x, y + GRAPH_HEIGHT + 2, 0xFFFFFFFF);
	}

	/** " · avg 240 · 1% 180" - what smoothness actually feels like, not just the average. */
	private String stats() {
		if (statCount < 100) {
			return "";
		}
		long[] sorted = java.util.Arrays.copyOf(statNanos, statCount);
		java.util.Arrays.sort(sorted);
		long total = 0;
		for (long n : sorted) {
			total += n;
		}
		int worst = Math.max(1, statCount / 100);
		long worstTotal = 0;
		for (int i = statCount - worst; i < statCount; i++) {
			worstTotal += sorted[i];
		}
		double avgFps = 1e9 * statCount / Math.max(1, total);
		double lowFps = 1e9 * worst / Math.max(1, worstTotal);
		return String.format(java.util.Locale.ROOT, " \u00b7 avg %d \u00b7 1%% %d", Math.round(avgFps), Math.round(lowFps));
	}

	@Override
	public int width() {
		return Math.max(SAMPLES, net.veloclient.velo.client.hud.HudLook.measure("00.0 ms" + statsLine));
	}

	@Override
	public int height() {
		return GRAPH_HEIGHT + MinecraftClient.getInstance().textRenderer.fontHeight + 2;
	}
}
