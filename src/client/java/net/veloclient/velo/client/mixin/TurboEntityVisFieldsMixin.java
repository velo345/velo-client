package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import net.veloclient.velo.client.modules.performance.EntityVisCache;
import org.spongepowered.asm.mixin.Unique;
*///?}

/** The per-entity fields of {@link net.veloclient.velo.client.modules.performance.EntityVisCache}. */
//? if >=26.2 {
/*@Mixin(net.minecraft.world.entity.Entity.class)
public abstract class TurboEntityVisFieldsMixin implements EntityVisCache {

	@Unique
	private long velo$time;
	@Unique
	private double velo$camX;
	@Unique
	private double velo$camY;
	@Unique
	private double velo$camZ;
	@Unique
	private float velo$xRot;
	@Unique
	private float velo$yRot;
	@Unique
	private float velo$fov;
	@Unique
	private double velo$x;
	@Unique
	private double velo$y;
	@Unique
	private double velo$z;
	@Unique
	private boolean velo$result;

	@Override
	public long velo$visTime() {
		return velo$time;
	}

	@Override
	public boolean velo$visMatches(double camX, double camY, double camZ, float xRot, float yRot, float fov, double x, double y, double z) {
		return camX == velo$camX && camY == velo$camY && camZ == velo$camZ && xRot == velo$xRot && yRot == velo$yRot && fov == velo$fov
				&& x == velo$x && y == velo$y && z == velo$z;
	}

	@Override
	public boolean velo$visResult() {
		return velo$result;
	}

	@Override
	public void velo$visStore(long time, double camX, double camY, double camZ, float xRot, float yRot, float fov, double x, double y,
			double z, boolean result) {
		velo$time = time;
		velo$camX = camX;
		velo$camY = camY;
		velo$camZ = camZ;
		velo$xRot = xRot;
		velo$yRot = yRot;
		velo$fov = fov;
		velo$x = x;
		velo$y = y;
		velo$z = z;
		velo$result = result;
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboEntityVisFieldsMixin {
	// Vulkan-era versions only (VeloMixinPlugin skips TurboEntity* elsewhere).
}
//?}
