package net.veloclient.velo.client.modules.rendering;

import java.util.ArrayList;
import java.util.List;

/**
 * Velo Shaders' coloured point lights: the light-emitting blocks closest to the camera (rescanned
 * every half second, 14 blocks around the player) plus lights carried in a hand - yours and other
 * players' - every frame. The post lighting pass lights the scene with them in their real colour
 * (soul fire cyan, redstone red, sea lantern cool white...) on top of vanilla's colourless block light.
 */
public final class VeloShaderLights {

	public static final int MAX = 32;
	private static final int RADIUS = 14;
	private static final int SCAN_TICKS = 10;

	/** x, y, z (world), radius, r, g, b, intensity - per light. Swapped whole on the client thread. */
	private static volatile float[] blockLights = new float[0];
	private static int tick;
	private static int heldCount;

	private VeloShaderLights() {
	}

	public static void tick() {
		//? if >=26.2 {
		/*if (++tick % SCAN_TICKS != 0 || !VeloShadersModule.active()) {
			return;
		}
		var mc = net.minecraft.client.Minecraft.getInstance();
		var level = mc.level;
		var player = mc.player;
		if (level == null || player == null) {
			blockLights = new float[0];
			return;
		}
		net.minecraft.core.BlockPos center = player.blockPosition();
		net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		List<float[]> found = new ArrayList<>();
		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				for (int dy = -RADIUS / 2; dy <= RADIUS / 2; dy++) {
					pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					var state = level.getBlockState(pos);
					int emission = state.getLightEmission();
					if (emission < 5) {
						continue;
					}
					float[] color = colorOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
					found.add(new float[] {pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f, emission * 0.6f,
							color[0], color[1], color[2], emission / 15f, dx * dx + dy * dy * 2 + dz * dz});
				}
			}
		}
		found.sort((a, b) -> Float.compare(a[8], b[8]));
		int n = Math.min(found.size(), MAX - 4);
		float[] out = new float[n * 8];
		for (int i = 0; i < n; i++) {
			System.arraycopy(found.get(i), 0, out, i * 8, 8);
		}
		blockLights = out;
		*///?}
	}

	/**
	 * Fills {@code out} with up to MAX lights (8 floats each, positions made camera-relative) and
	 * returns how many: held lights first, then the nearest blocks.
	 */
	public static int collect(float[] out, double camX, double camY, double camZ, boolean handheld) {
		int count = 0;
		//? if >=26.2 {
		/*var mc = net.minecraft.client.Minecraft.getInstance();
		if (handheld && mc.level != null) {
			for (var p : mc.level.players()) {
				if (count >= 4) {
					break;
				}
				int emission = 0;
				float[] color = null;
				for (var stack : new net.minecraft.world.item.ItemStack[] {p.getMainHandItem(), p.getOffhandItem()}) {
					int e = 0;
					float[] c = null;
					if (stack.getItem() instanceof net.minecraft.world.item.BlockItem item) {
						var state = item.getBlock().defaultBlockState();
						e = state.getLightEmission();
						c = colorOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(item.getBlock()).getPath());
					} else if (stack.is(net.minecraft.world.item.Items.LAVA_BUCKET)) {
						e = 15;
						c = colorOf("lava");
					}
					if (e > emission) {
						emission = e;
						color = c;
					}
				}
				if (emission <= 0) {
					continue;
				}
				var eye = p.getEyePosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
				if (eye.distanceToSqr(camX, camY, camZ) > 48 * 48) {
					continue;
				}
				int o = count * 8;
				out[o] = (float) (eye.x - camX);
				out[o + 1] = (float) (eye.y - 0.45 - camY);
				out[o + 2] = (float) (eye.z - camZ);
				out[o + 3] = emission * 0.6f;
				out[o + 4] = color[0];
				out[o + 5] = color[1];
				out[o + 6] = color[2];
				out[o + 7] = emission / 15f;
				count++;
			}
		}
		*///?}
		//? if >=26.2 {
		/*// Auras glow: a soft light in the aura's colour around everyone wearing one.
		if (mc.level != null) {
			float time = (System.nanoTime() % 1_000_000_000_000L) / 1e9f;
			for (var p : mc.level.players()) {
				if (count >= 8) {
					break;
				}
				var worn = p == mc.player ? net.veloclient.velo.client.cosmetics.aura.AuraManager.wornByLocalPlayer()
						: net.veloclient.velo.client.cosmetics.aura.AuraManager.parseNetworkValue(
								net.veloclient.velo.client.network.VeloUserRegistry.auraIdFor(p.getUUID(), p.getGameProfile().name()));
				if (worn == null) {
					continue;
				}
				int rgb = net.veloclient.velo.client.cosmetics.aura.AuraEffects.glowColor(worn.id(), worn.settings(), time);
				if (rgb < 0) {
					continue;
				}
				var pos = p.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
				if (pos.distanceToSqr(camX, camY, camZ) > 48 * 48) {
					continue;
				}
				int o = count * 8;
				out[o] = (float) (pos.x - camX);
				out[o + 1] = (float) (pos.y + 1.0 - camY);
				out[o + 2] = (float) (pos.z - camZ);
				out[o + 3] = 8.5f;
				out[o + 4] = ((rgb >> 16) & 0xFF) / 255f;
				out[o + 5] = ((rgb >> 8) & 0xFF) / 255f;
				out[o + 6] = (rgb & 0xFF) / 255f;
				out[o + 7] = 1.95f;
				count++;
			}
		}
		*///?}
		heldCount = count;
		float[] blocks = blockLights;
		for (int i = 0; i + 8 <= blocks.length && count < MAX; i += 8) {
			int o = count * 8;
			out[o] = (float) (blocks[i] - camX);
			out[o + 1] = (float) (blocks[i + 1] - camY);
			out[o + 2] = (float) (blocks[i + 2] - camZ);
			System.arraycopy(blocks, i + 3, out, o + 3, 5);
			count++;
		}
		return count;
	}

	public static final int MAX_ENTITIES = 16;

	/**
	 * Shadow capsules for the light pass: the nearest living entities (you included, so your own
	 * shadow falls on clouds even in first person; the ground pass skips the camera's own). 8 floats each: camera-relative
	 * feet position, radius, height. Returns how many.
	 */
	public static int collectEntities(float[] out, double camX, double camY, double camZ) {
		int count = 0;
		//? if >=26.2 {
		/*var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level == null) {
			return 0;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		var box = new net.minecraft.world.phys.AABB(camX - 48, camY - 48, camZ - 48, camX + 48, camY + 48, camZ + 48);
		var found = mc.level.getEntities((net.minecraft.world.entity.Entity) null, box,
				e -> e instanceof net.minecraft.world.entity.LivingEntity && !e.isInvisible());
		found.sort((a, b) -> Double.compare(a.distanceToSqr(camX, camY, camZ), b.distanceToSqr(camX, camY, camZ)));
		for (var e : found) {
			if (count >= MAX_ENTITIES) {
				break;
			}
			var pos = e.getPosition(partial);
			int o = count * 8;
			out[o] = (float) (pos.x - camX);
			out[o + 1] = (float) (pos.y - camY);
			out[o + 2] = (float) (pos.z - camZ);
			out[o + 3] = Math.max(0.15f, e.getBbWidth() * 0.5f);
			out[o + 4] = e.getBbHeight();
			count++;
		}
		*///?}
		return count;
	}

	/** How many of the lights the last collect() returned were held in a hand (they come first). */
	public static int lastHandheld() {
		return heldCount;
	}

	/** Light colour by block id (linear-ish RGB, brightest channel 1). */
	static float[] colorOf(String id) {
		if (id.contains("soul")) {
			return new float[] {0.35f, 0.85f, 1.0f};
		}
		if (id.contains("redstone") || id.equals("redstone_lamp")) {
			return new float[] {1.0f, 0.22f, 0.12f};
		}
		if (id.contains("copper") && (id.contains("torch") || id.contains("lantern"))) {
			return new float[] {0.45f, 1.0f, 0.55f};
		}
		if (id.contains("sea_lantern") || id.contains("end_rod") || id.contains("beacon") || id.contains("conduit")) {
			return new float[] {0.72f, 0.88f, 1.0f};
		}
		if (id.contains("amethyst") || id.contains("crying_obsidian") || id.contains("respawn_anchor") || id.contains("portal")
				|| id.contains("ender_chest") || id.contains("dragon")) {
			return new float[] {0.72f, 0.38f, 1.0f};
		}
		if (id.contains("sculk")) {
			return new float[] {0.25f, 0.85f, 1.0f};
		}
		if (id.equals("ochre_froglight")) {
			return new float[] {1.0f, 0.85f, 0.45f};
		}
		if (id.equals("verdant_froglight")) {
			return new float[] {0.6f, 1.0f, 0.55f};
		}
		if (id.equals("pearlescent_froglight")) {
			return new float[] {1.0f, 0.68f, 0.95f};
		}
		if (id.contains("glow_lichen")) {
			return new float[] {0.55f, 1.0f, 0.85f};
		}
		if (id.contains("lava") || id.contains("magma")) {
			return new float[] {1.0f, 0.42f, 0.12f};
		}
		if (id.contains("fire") || id.contains("campfire")) {
			return new float[] {1.0f, 0.5f, 0.18f};
		}
		if (id.contains("torch") || id.contains("lantern") || id.contains("candle") || id.contains("jack_o_lantern")) {
			return new float[] {1.0f, 0.6f, 0.28f};
		}
		if (id.contains("glowstone") || id.contains("shroomlight") || id.contains("cave_vines") || id.contains("glow_berries")) {
			return new float[] {1.0f, 0.78f, 0.42f};
		}
		return new float[] {1.0f, 0.86f, 0.68f};
	}
}
