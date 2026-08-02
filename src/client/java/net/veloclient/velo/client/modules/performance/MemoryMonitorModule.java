package net.veloclient.velo.client.modules.performance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * JVM heap usage and allocation-rate readout, same data vanilla's own F3
 * memory line shows ({@code MemoryDebugHudEntry}) - useful when running local
 * dev server instances alongside the client (design spec section 6.3).
 */
public final class MemoryMonitorModule extends AbstractModule implements HudModule {

	private static final List<GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans();
	private static final long SAMPLE_INTERVAL_MILLIS = 500;

	private final HudPosition position = new HudPosition(0.98f, 0.17f);
	private long lastSampleMillis;
	private long lastAllocatedBytes = -1;
	private long lastCollectionCount = -1;
	private long allocationRateBytesPerSec;

	public MemoryMonitorModule() {
		super("memory-monitor", "Memory & GC Monitor", "Shows JVM heap usage and allocation rate.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		Runtime runtime = Runtime.getRuntime();
		long max = runtime.maxMemory();
		long total = runtime.totalMemory();
		long free = runtime.freeMemory();
		long used = total - free;

		sampleAllocationRate(used);

		int lineHeight = client.textRenderer.fontHeight + 1;
		context.drawTextWithShadow(client.textRenderer,
				String.format(Locale.ROOT, "Mem: %d%% %dMB/%dMB", used * 100 / max, toMb(used), toMb(max)),
				x, y, 0xFFFFFFFF);
		context.drawTextWithShadow(client.textRenderer,
				String.format(Locale.ROOT, "Alloc: %dMB/s", toMb(allocationRateBytesPerSec)),
				x, y + lineHeight, 0xFFC8C8C8);
	}

	private void sampleAllocationRate(long usedBytes) {
		long now = System.currentTimeMillis();
		if (now - lastSampleMillis < SAMPLE_INTERVAL_MILLIS) {
			return;
		}
		long collections = totalCollectionCount();
		if (lastSampleMillis != 0 && collections == lastCollectionCount) {
			double perSecond = (double) TimeUnit.SECONDS.toMillis(1) / (now - lastSampleMillis);
			allocationRateBytesPerSec = Math.round((usedBytes - lastAllocatedBytes) * perSecond);
		}
		lastSampleMillis = now;
		lastAllocatedBytes = usedBytes;
		lastCollectionCount = collections;
	}

	private static long totalCollectionCount() {
		long total = 0;
		for (GarbageCollectorMXBean bean : GC_BEANS) {
			total += bean.getCollectionCount();
		}
		return total;
	}

	private static long toMb(long bytes) {
		return bytes / 1024 / 1024;
	}

	@Override
	public int width() {
		return 150;
	}

	@Override
	public int height() {
		return 2 * (MinecraftClient.getInstance().textRenderer.fontHeight + 1);
	}
}
