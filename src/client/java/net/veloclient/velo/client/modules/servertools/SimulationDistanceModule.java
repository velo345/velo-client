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

import java.util.List;

/**
 * Shows the area the server actually simulates around you (crops grow, redstone and mobs tick,
 * mobs spawn): the square of chunks within the server's simulation distance, drawn as a fence at
 * your height. Optional sphere at the same radius for a quick sense of scale. The number comes
 * from the server (what it really uses), not your own video settings.
 */
public final class SimulationDistanceModule extends AbstractModule implements HudModule, Configurable {

	private int color = 0xFF5AC8FF;
	private boolean sphere;
	private int sphereColor = 0xFFB07CFF;
	private boolean legend = true;
	private double height = 48;
	private double lineWidth = 1.5;

	private final HudPosition position = new HudPosition(0.0f, 0.68f);

	public SimulationDistanceModule() {
		super("simulation-distance", "Simulation Distance", "Shows the chunks the server simulates around you (crops, redstone, mob spawning) "
				+ "as a fence, plus the server's distance in a small legend.", ModuleCategory.SERVER_TOOLS, SafetyTag.ALWAYS_SAFE, false);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> ModuleProfiler.time(id(), ModuleProfiler.Phase.WORLD_RENDER, this::renderWorld));
	}

	private void renderWorld() {
		if (!isEnabled() || !WorldInfo.inWorld()) {
			return;
		}
		int sim = WorldInfo.simulationDistance();
		if (sim <= 0) {
			return;
		}
		double[] p = WorldInfo.position();
		int[] b = WorldInfo.blockPos();
		int cx = b[0] >> 4;
		int cz = b[2] >> 4;
		double minX = (cx - sim) * 16.0;
		double maxX = (cx + sim + 1) * 16.0;
		double minZ = (cz - sim) * 16.0;
		double maxZ = (cz + sim + 1) * 16.0;
		double half = height / 2;
		double bottom = Math.floor(p[1] - half);
		double top = Math.ceil(p[1] + half);
		float width = (float) lineWidth;
		// Posts at every chunk corner along the edge.
		for (double x = minX; x <= maxX; x += 16) {
			RadiusShapes.line(x, bottom, minZ, x, top, minZ, color, width);
			RadiusShapes.line(x, bottom, maxZ, x, top, maxZ, color, width);
		}
		for (double z = minZ + 16; z < maxZ; z += 16) {
			RadiusShapes.line(minX, bottom, z, minX, top, z, color, width);
			RadiusShapes.line(maxX, bottom, z, maxX, top, z, color, width);
		}
		// Rails every 8 blocks up the fence, thicker at your own height.
		for (double y = bottom; y <= top; y += 8) {
			float w = Math.abs(y - Math.floor(p[1] / 8) * 8) < 1 ? width * 2.2f : width;
			RadiusShapes.line(minX, y, minZ, maxX, y, minZ, color, w);
			RadiusShapes.line(minX, y, maxZ, maxX, y, maxZ, color, w);
			RadiusShapes.line(minX, y, minZ, minX, y, maxZ, color, w);
			RadiusShapes.line(maxX, y, minZ, maxX, y, maxZ, color, w);
		}
		if (sphere) {
			RadiusShapes.sphere(p[0], p[1], p[2], sim * 16.0, sphereColor, width, 16);
		}
	}

	@Override
	public HudPosition position() {
		return position;
	}

	private String legendText() {
		int sim = WorldInfo.inWorld() ? WorldInfo.simulationDistance() : 0;
		return sim <= 0 ? "Simulation distance: -" : "Simulation " + sim + " chunks (" + (sim * 2 + 1) + "x" + (sim * 2 + 1) + ")";
	}

	@Override
	public int width() {
		return legend ? MinecraftClient.getInstance().textRenderer.getWidth(legendText()) + 20 : 0;
	}

	@Override
	public int height() {
		return legend ? 16 : 0;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		if (!legend) {
			return;
		}
		VeloDraw.fillRounded(context, x, y, width(), height(), 5, 0x900C0C10);
		context.fill(x + 6, y + 5, x + 11, y + 10, color | 0xFF000000);
		context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, legendText(), x + 15, y + 4, 0xFFE6E6EA);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ColorField("Fence Color", () -> color, v -> color = v, true),
				new ConfigField.SliderField("Fence Height", 16, 128, () -> height, v -> height = Math.round(v), v -> Math.round(v) + " blocks"),
				new ConfigField.ToggleField("Also Show As Sphere", () -> sphere, v -> sphere = v),
				new ConfigField.ColorField("Sphere Color", () -> sphereColor, v -> sphereColor = v, true),
				new ConfigField.ToggleField("Show Legend", () -> legend, v -> legend = v),
				new ConfigField.SliderField("Line Width", 0.5, 4.0, () -> lineWidth, v -> lineWidth = v,
						v -> String.format(java.util.Locale.ROOT, "%.1f", v)));
	}
}
