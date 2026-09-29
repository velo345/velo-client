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
import net.veloclient.velo.client.util.ModuleProfiler;

import java.util.ArrayList;
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
	// The (2*radius+1)^2 column scan below (heightmap + light lookups) is the expensive part,
	// not drawing the cached labels - at radius 16 that's over a thousand world queries. Redone
	// every frame this scaled badly with render distance/radius; recomputing a few times a
	// second instead of 60+ is imperceptible for a debug heatmap that isn't tracking anything
	// moving.
	private static final long RECOMPUTE_INTERVAL_NANOS = 200_000_000L;

	private int radius = 8;
	private int unsafeThreshold = 7;

	private List<Cell> cache = List.of();
	private long lastComputeNanos;

	private record Cell(BlockPos pos, String label, int color) {
	}

	public LightLevelOverlayModule() {
		super("light-level-overlay", "Light Level Overlay",
				"Shows block light levels on nearby surface blocks, highlighting where hostile mobs can spawn.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.ALWAYS_SAFE, false);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.WORLD_RENDER, () -> onRender(context)));
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

		long now = System.nanoTime();
		if (now - lastComputeNanos >= RECOMPUTE_INTERVAL_NANOS) {
			lastComputeNanos = now;
			cache = recompute(world, camera.getBlockX(), camera.getBlockZ());
		}
		for (Cell cell : cache) {
			GizmoDrawing.blockLabel(cell.label(), cell.pos(), 0, cell.color(), 1.0f);
		}
	}

	private List<Cell> recompute(ClientWorld world, int originX, int originZ) {
		List<Cell> result = new ArrayList<>();
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
				result.add(new Cell(spawnPos, String.valueOf(light), color));
			}
		}
		return result;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Radius", 2, 16, () -> radius, v -> radius = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Unsafe Threshold", 0, 15, () -> unsafeThreshold, v -> unsafeThreshold = (int) v, v -> String.valueOf((int) v)));
	}
}
