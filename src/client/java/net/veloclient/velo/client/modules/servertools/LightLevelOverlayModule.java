package net.veloclient.velo.client.modules.servertools;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Classic mob-spawn light-level heatmap: prints the block light level on top
 * of each loaded surface column near you, color-coded by whether hostile mobs
 * could spawn there (light &lt;= threshold). This also functions as a
 * lightweight mob spawn checker (design spec section 6.3) - it only reads
 * light/heightmap data for chunks already loaded and rendered by this
 * client, never scans beyond render distance or through unloaded chunks.
 */
public final class LightLevelOverlayModule extends AbstractModule implements Configurable {

	private static final int UNSAFE_COLOR = 0xFFFF5555;
	private static final int SAFE_COLOR = 0xFF55FF55;

	private int radius = 8;
	private int unsafeThreshold = 7;

	public LightLevelOverlayModule() {
		super("light-level-overlay", "Light Level Overlay",
				"Shows block light levels on nearby surface blocks, highlighting where hostile mobs can spawn.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.ALWAYS_SAFE, false);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::onRender);
	}

	private void onRender(WorldRenderContext context) {
		if (!isEnabled()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		ClientWorld world = client.world;
		Entity camera = client.getCameraEntity();
		if (world == null || camera == null) {
			return;
		}

		int originX = camera.getBlockX();
		int originZ = camera.getBlockZ();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int x = originX + dx;
				int z = originZ + dz;
				if (!world.isChunkLoaded(x >> 4, z >> 4)) {
					continue;
				}
				int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
				BlockPos spawnPos = new BlockPos(x, surfaceY, z);
				int light = world.getLightLevel(LightType.BLOCK, spawnPos);
				int color = light <= unsafeThreshold ? UNSAFE_COLOR : SAFE_COLOR;
				GizmoDrawing.blockLabel(String.valueOf(light), spawnPos, 0, color, 1.0f);
			}
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Radius", 2, 16, () -> radius, v -> radius = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Unsafe Threshold", 0, 15, () -> unsafeThreshold, v -> unsafeThreshold = (int) v, v -> String.valueOf((int) v)));
	}
}
