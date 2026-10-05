package net.veloclient.velo.client.modules.servertools;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.util.ModuleProfiler;
import net.veloclient.velo.client.util.WorldInfo;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows vanilla's mob spawning distances as spheres around you: no mob spawns within 24 blocks,
 * mobs may despawn randomly beyond 32, and they despawn instantly beyond 128 (64 for fish and
 * other water mobs). Spawning only happens in chunks the server simulates, so the legend also
 * shows the server's simulation distance and warns when it's the real limit.
 */
public final class SpawnRadiusModule extends AbstractModule implements HudModule, Configurable {

	private boolean showNoSpawn = true;
	private boolean showDespawnChance = true;
	private boolean showDespawn = true;
	private boolean showWater;
	private boolean legend = true;
	private int noSpawnColor = 0xFF5CE08A;
	private int despawnChanceColor = 0xFFFFD45C;
	private int despawnColor = 0xFFFF5A5A;
	private int waterColor = 0xFF4FA8FF;
	private double lineWidth = 1.5;
	private double detail = 16;

	private final HudPosition position = new HudPosition(0.0f, 0.42f);

	public SpawnRadiusModule() {
		super("spawn-radius", "Spawn Radius", "Spheres around you showing where mobs can spawn and despawn (24 / 32 / 128 blocks), "
				+ "with your server's simulation distance in the legend.", ModuleCategory.SERVER_TOOLS, SafetyTag.ALWAYS_SAFE, false);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> ModuleProfiler.time(id(), ModuleProfiler.Phase.WORLD_RENDER, this::renderWorld));
	}

	private void renderWorld() {
		if (!isEnabled() || !WorldInfo.inWorld()) {
			return;
		}
		double[] p = WorldInfo.position();
		int rings = (int) Math.round(detail);
		float width = (float) lineWidth;
		if (showNoSpawn) {
			RadiusShapes.sphere(p[0], p[1], p[2], 24, noSpawnColor, width, Math.max(8, rings / 2));
		}
		if (showDespawnChance) {
			RadiusShapes.sphere(p[0], p[1], p[2], 32, despawnChanceColor, width, Math.max(8, rings / 2));
		}
		if (showWater) {
			RadiusShapes.sphere(p[0], p[1], p[2], 64, waterColor, width, rings);
		}
		if (showDespawn) {
			RadiusShapes.sphere(p[0], p[1], p[2], 128, despawnColor, width, rings);
		}
	}

	// ---- Legend (a movable HUD element) ----

	private List<Object[]> legendRows() {
		List<Object[]> rows = new ArrayList<>();
		if (showNoSpawn) {
			rows.add(new Object[] {noSpawnColor, "24  No spawns inside"});
		}
		if (showDespawnChance) {
			rows.add(new Object[] {despawnChanceColor, "32  Random despawn beyond"});
		}
		if (showWater) {
			rows.add(new Object[] {waterColor, "64  Water mobs despawn beyond"});
		}
		if (showDespawn) {
			rows.add(new Object[] {despawnColor, "128  Instant despawn beyond"});
		}
		int sim = WorldInfo.inWorld() ? WorldInfo.simulationDistance() : 0;
		if (sim > 0) {
			int blocks = sim * 16;
			rows.add(new Object[] {0, "Simulation " + sim + " chunks" + (blocks < 128 ? " - spawns stop ~" + blocks + " blocks out" : "")});
		}
		return rows;
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public int width() {
		if (!legend) {
			return 0;
		}
		int w = 0;
		for (Object[] row : legendRows()) {
			w = Math.max(w, MinecraftClient.getInstance().textRenderer.getWidth((String) row[1]));
		}
		return w + 20;
	}

	@Override
	public int height() {
		return legend ? legendRows().size() * 10 + 16 : 0;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		if (!legend) {
			return;
		}
		var renderer = MinecraftClient.getInstance().textRenderer;
		VeloDraw.fillRounded(context, x, y, width(), height(), 5, 0x900C0C10);
		context.drawTextWithShadow(renderer, "Spawn radius", x + 6, y + 4, 0xFFFFFFFF);
		int rowY = y + 15;
		for (Object[] row : legendRows()) {
			int color = (int) row[0];
			if (color != 0) {
				context.fill(x + 6, rowY + 2, x + 11, rowY + 7, color | 0xFF000000);
			}
			context.drawTextWithShadow(renderer, (String) row[1], x + 15, rowY, color == 0 ? 0xFF9A9AA6 : 0xFFE6E6EA);
			rowY += 10;
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show No-Spawn Zone (24)", () -> showNoSpawn, v -> showNoSpawn = v),
				new ConfigField.ColorField("No-Spawn Color", () -> noSpawnColor, v -> noSpawnColor = v, true),
				new ConfigField.ToggleField("Show Random Despawn (32)", () -> showDespawnChance, v -> showDespawnChance = v),
				new ConfigField.ColorField("Random Despawn Color", () -> despawnChanceColor, v -> despawnChanceColor = v, true),
				new ConfigField.ToggleField("Show Instant Despawn (128)", () -> showDespawn, v -> showDespawn = v),
				new ConfigField.ColorField("Instant Despawn Color", () -> despawnColor, v -> despawnColor = v, true),
				new ConfigField.ToggleField("Show Water Mobs (64)", () -> showWater, v -> showWater = v),
				new ConfigField.ColorField("Water Mobs Color", () -> waterColor, v -> waterColor = v, true),
				new ConfigField.ToggleField("Show Legend", () -> legend, v -> legend = v),
				new ConfigField.SliderField("Line Width", 0.5, 4.0, () -> lineWidth, v -> lineWidth = v,
						v -> String.format(java.util.Locale.ROOT, "%.1f", v)),
				new ConfigField.SliderField("Sphere Detail", 8, 32, () -> detail, v -> detail = Math.round(v), v -> String.valueOf(Math.round(v))));
	}
}
