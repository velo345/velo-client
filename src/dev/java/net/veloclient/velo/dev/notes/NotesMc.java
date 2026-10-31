package net.veloclient.velo.dev.notes;

import net.minecraft.client.MinecraftClient;

/**
 * The Minecraft calls World Notes needs, one {@code //? if} branch per mapping (positions travel as doubles).
 */
public final class NotesMc {

	/** Where the crosshair hits a block: the hit point and the face's normal. */
	public record Aim(double x, double y, double z, int nx, int ny, int nz) {
	}

	private NotesMc() {
	}

	//? if <26.1 {
	/** Eye position and look direction {x, y, z, dx, dy, dz}, or null. */
	public static double[] eye() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return null;
		}
		float tick = client.getRenderTickCounter().getTickProgress(true);
		var pos = client.player.getCameraPosVec(tick);
		var look = client.player.getRotationVec(tick);
		return new double[] {pos.x, pos.y, pos.z, look.x, look.y, look.z};
	}

	/** Nothing solid between your eyes and the point (blocks right at the point itself don't count). */
	public static boolean canSee(double x, double y, double z) {
		MinecraftClient client = MinecraftClient.getInstance();
		double[] eye = eye();
		if (eye == null || client.world == null) {
			return false;
		}
		var from = new net.minecraft.util.math.Vec3d(eye[0], eye[1], eye[2]);
		var to = new net.minecraft.util.math.Vec3d(x, y, z);
		var hit = client.world.raycast(new net.minecraft.world.RaycastContext(from, to, net.minecraft.world.RaycastContext.ShapeType.VISUAL,
				net.minecraft.world.RaycastContext.FluidHandling.NONE, client.player));
		if (hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) {
			return true;
		}
		return hit.getPos().squaredDistanceTo(to) < 0.35 * 0.35;
	}

	/** The block face under the crosshair within {@code reach}, or null. */
	public static Aim aim(double reach) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return null;
		}
		var hit = client.player.raycast(reach, client.getRenderTickCounter().getTickProgress(true), false);
		if (!(hit instanceof net.minecraft.util.hit.BlockHitResult block) || hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) {
			return null;
		}
		var side = block.getSide();
		var pos = block.getPos();
		return new Aim(pos.x, pos.y, pos.z, side.getOffsetX(), side.getOffsetY(), side.getOffsetZ());
	}

	/** Right click this tick (taken away from vanilla - call at the start of the tick). */
	public static boolean consumeUse() {
		boolean any = false;
		while (MinecraftClient.getInstance().options.useKey.wasPressed()) {
			any = true;
		}
		return any;
	}

	public static boolean consumeAttack() {
		boolean any = false;
		while (MinecraftClient.getInstance().options.attackKey.wasPressed()) {
			any = true;
		}
		return any;
	}

	/** Item id in your main hand, or null when it's empty. */
	public static String heldItem() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.player.getMainHandStack().isEmpty()) {
			return null;
		}
		return net.minecraft.registry.Registries.ITEM.getId(client.player.getMainHandStack().getItem()).toString();
	}

	public static String myName() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.player == null ? client.getSession().getUsername() : client.player.getName().getString();
	}
	//?} else {
	/*public static double[] eye() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return null;
		}
		float tick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		var pos = client.player.getEyePosition(tick);
		var look = client.player.getViewVector(tick);
		return new double[] {pos.x, pos.y, pos.z, look.x, look.y, look.z};
	}

	public static boolean canSee(double x, double y, double z) {
		MinecraftClient client = MinecraftClient.getInstance();
		double[] eye = eye();
		if (eye == null || client.level == null) {
			return false;
		}
		var from = new net.minecraft.world.phys.Vec3(eye[0], eye[1], eye[2]);
		var to = new net.minecraft.world.phys.Vec3(x, y, z);
		var hit = client.level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.VISUAL,
				net.minecraft.world.level.ClipContext.Fluid.NONE, client.player));
		if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
			return true;
		}
		return hit.getLocation().distanceToSqr(to) < 0.35 * 0.35;
	}

	public static Aim aim(double reach) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return null;
		}
		var hit = client.player.pick(reach, client.getDeltaTracker().getGameTimeDeltaPartialTick(true), false);
		if (!(hit instanceof net.minecraft.world.phys.BlockHitResult block) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
			return null;
		}
		var side = block.getDirection();
		var pos = block.getLocation();
		return new Aim(pos.x, pos.y, pos.z, side.getStepX(), side.getStepY(), side.getStepZ());
	}

	public static boolean consumeUse() {
		boolean any = false;
		while (MinecraftClient.getInstance().options.keyUse.consumeClick()) {
			any = true;
		}
		return any;
	}

	public static boolean consumeAttack() {
		boolean any = false;
		while (MinecraftClient.getInstance().options.keyAttack.consumeClick()) {
			any = true;
		}
		return any;
	}

	public static String heldItem() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.player.getMainHandItem().isEmpty()) {
			return null;
		}
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(client.player.getMainHandItem().getItem()).toString();
	}

	public static String myName() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.player == null ? client.getUser().getName() : client.player.getName().getString();
	}
	*///?}
}
