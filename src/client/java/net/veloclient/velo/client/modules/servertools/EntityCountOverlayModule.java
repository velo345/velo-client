package net.veloclient.velo.client.modules.servertools;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Counts loaded entities by type, broken down for lag diagnosis on the
 * operator's own server (design spec section 6.3). Only ever iterates
 * entities the client already has loaded and rendered - never queries
 * beyond render distance, so it can't be used to scout unloaded areas.
 *
 * <p>Walking every loaded entity is genuinely not free with a large crowd
 * loaded, so when Performance Boost's HUD Caching setting is on, the counts
 * are only recomputed about 20 times a second instead of on every one of
 * (potentially) hundreds of frames.
 */
public final class EntityCountOverlayModule extends AbstractModule implements HudModule, Configurable {

	private int maxRows = 5;
	private final HudPosition position = new HudPosition(0.02f, 0.44f);

	private List<Map.Entry<EntityType<?>, Integer>> cachedTop = List.of();
	private int cachedTotal;
	private long lastComputeMs;

	public EntityCountOverlayModule() {
		super("entity-count-overlay", "Entity Count Overlay",
				"Shows loaded entity counts by type, for diagnosing entity lag on your own server.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.CHECK_SERVER_RULES, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientWorld world = client.world;
		if (world == null) {
			return;
		}

		long now = System.currentTimeMillis();
		boolean stale = !PerformanceBoostModule.hudCachingEnabled || now - lastComputeMs >= 50;
		if (stale) {
			Map<EntityType<?>, Integer> counts = new LinkedHashMap<>();
			int total = 0;
			for (Entity entity : world.getEntities()) {
				counts.merge(entity.getType(), 1, Integer::sum);
				total++;
			}
			cachedTop = counts.entrySet().stream()
					.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
					.limit(maxRows)
					.collect(Collectors.toList());
			cachedTotal = total;
			lastComputeMs = now;
		}

		int lineHeight = client.textRenderer.fontHeight + 1;
		context.drawTextWithShadow(client.textRenderer, "Entities: " + cachedTotal, x, y, 0xFFFFFFFF);
		int rowY = y + lineHeight;
		for (Map.Entry<EntityType<?>, Integer> entry : cachedTop) {
			String line = "  " + entry.getKey().getName().getString() + ": " + entry.getValue();
			context.drawTextWithShadow(client.textRenderer, line, x, rowY, 0xFFC8C8C8);
			rowY += lineHeight;
		}
	}

	@Override
	public int width() {
		return 160;
	}

	@Override
	public int height() {
		return (maxRows + 1) * (MinecraftClient.getInstance().textRenderer.fontHeight + 1);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(new ConfigField.SliderField("Max Rows Shown", 1, 15,
				() -> maxRows, v -> maxRows = (int) v, v -> String.valueOf((int) v)));
	}
}
