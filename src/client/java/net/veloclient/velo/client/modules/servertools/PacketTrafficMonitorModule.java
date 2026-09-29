package net.veloclient.velo.client.modules.servertools;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.util.PacketTrafficTracker;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Top packet types by count since the module was enabled/reset - useful for
 * diagnosing plugin packet spam on your own server (design spec section 6.3).
 * Purely observational: see {@link net.veloclient.velo.client.mixin.ClientConnectionMixin}.
 */
public final class PacketTrafficMonitorModule extends AbstractModule implements HudModule, Configurable {

	// Sorting/collecting the full packet-count tables into top-N lists on every single frame this
	// overlay is visible is wasted work when the counts only meaningfully change a few times a
	// second - cached for a short window well under one frame interval's worth of staleness.
	private static final long CACHE_WINDOW_NANOS = 100_000_000L;

	private int maxRows = 6;
	private final HudPosition position = new HudPosition(0.75f, 0.14f);

	private Map<String, Long> cachedInbound = Map.of();
	private Map<String, Long> cachedOutbound = Map.of();
	private long cachedAtNanos = Long.MIN_VALUE;

	public PacketTrafficMonitorModule() {
		super("packet-traffic-monitor", "Packet Traffic Monitor",
				"Shows inbound/outbound packet counts by type, for diagnosing plugin packet spam.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public void onEnable() {
		PacketTrafficTracker.reset();
		PacketTrafficTracker.setEnabled(true);
	}

	@Override
	public void onDisable() {
		PacketTrafficTracker.setEnabled(false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		int lineHeight = client.textRenderer.fontHeight + 1;

		refreshCacheIfStale();
		context.drawTextWithShadow(client.textRenderer, "Inbound:", x, y, 0xFFFFFFFF);
		int rowY = y + lineHeight;
		for (Map.Entry<String, Long> entry : cachedInbound.entrySet()) {
			context.drawTextWithShadow(client.textRenderer, "  " + entry.getKey() + ": " + entry.getValue(), x, rowY, 0xFFC8C8C8);
			rowY += lineHeight;
		}

		rowY += 2;
		context.drawTextWithShadow(client.textRenderer, "Outbound:", x, rowY, 0xFFFFFFFF);
		rowY += lineHeight;
		for (Map.Entry<String, Long> entry : cachedOutbound.entrySet()) {
			context.drawTextWithShadow(client.textRenderer, "  " + entry.getKey() + ": " + entry.getValue(), x, rowY, 0xFFC8C8C8);
			rowY += lineHeight;
		}
	}

	private void refreshCacheIfStale() {
		long now = System.nanoTime();
		if (now - cachedAtNanos < CACHE_WINDOW_NANOS) {
			return;
		}
		cachedAtNanos = now;
		cachedInbound = topEntries(PacketTrafficTracker.inboundSnapshot());
		cachedOutbound = topEntries(PacketTrafficTracker.outboundSnapshot());
	}

	private Map<String, Long> topEntries(Map<String, Long> source) {
		return source.entrySet().stream()
				.sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed())
				.limit(maxRows)
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
						(a, b) -> a, java.util.LinkedHashMap::new));
	}

	@Override
	public int width() {
		return 220;
	}

	@Override
	public int height() {
		return (2 + 2 * maxRows) * (MinecraftClient.getInstance().textRenderer.fontHeight + 1);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(new ConfigField.SliderField("Max Rows Shown", 1, 15,
				() -> maxRows, v -> maxRows = (int) v, v -> String.valueOf((int) v)));
	}
}
