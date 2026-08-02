package net.veloclient.velo.client.modules.servertools;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.world.border.WorldBorder;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import net.minecraft.world.debug.gizmo.GizmoDrawing;

/**
 * Draws a full-height box along the world border, using the exact bounds the
 * server already enforces ({@code WorldBorder}) - purely a visualization of
 * data the client already has (design spec section 6.3).
 */
public final class WorldBorderVisualizerModule extends AbstractModule {

	private static final int COLOR = ColorHelper.fromFloats(0.6f, 0.2f, 0.9f, 1.0f);

	public WorldBorderVisualizerModule() {
		super("world-border-visualizer", "World Border Visualizer",
				"Highlights the world border boundary in 3D, using the server-enforced bounds.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.ALWAYS_SAFE, false);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::onRender);
	}

	private void onRender(WorldRenderContext context) {
		if (!isEnabled()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		ClientWorld world = client.world;
		if (world == null) {
			return;
		}
		WorldBorder border = world.getWorldBorder();
		double minY = world.getBottomY();
		double maxY = world.getTopYInclusive() + 1;
		Box box = new Box(border.getBoundWest(), minY, border.getBoundNorth(),
				border.getBoundEast(), maxY, border.getBoundSouth());
		GizmoDrawing.box(box, DrawStyle.stroked(COLOR, 2.0f)).ignoreOcclusion();
	}
}
