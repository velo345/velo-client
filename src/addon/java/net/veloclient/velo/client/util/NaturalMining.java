package net.veloclient.velo.client.util;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Shared "how a real held-click break actually looks" logic for the autonomous block-breaking
 * modules ({@code EnderChestFarmerModule}, {@code AutoTunnelMinerModule}) - previously each one
 * aimed dead-on at the exact block center every tick and drove {@code attackBlock}/{@code
 * updateBlockBreakingProgress} on a perfectly regular schedule, which is a much more mechanical
 * pattern than a real player ever produces. A {@link Session} instead: aims at a randomly
 * jittered point on the actual face being broken (never dead-center, and clamped to the block's
 * real clickable surface so a slab/stair/fence never gets jittered into empty space past it), and
 * re-verifies every tick via {@code client.crosshairTarget} that the block being broken is still
 * the one actually being looked at (mirrors vanilla itself aborting/restarting progress the
 * moment your crosshair moves off the block, and stops the instant something steps in front -
 * continuing to report progress on a block you can no longer actually see is not something a real
 * client does). {@code updateBlockBreakingProgress} is still called every single tick, same as a
 * real held-click - an earlier version of this deliberately spaced those calls out to look less
 * mechanical, but that call is what actually drives break progress (the real hardness/tool-speed
 * formula runs inside it), so skipping ticks just made blocks take longer to break than the
 * equipped tool should allow - a real inconsistency, not a cosmetic one.
 */
public final class NaturalMining {

	private static final Random RANDOM = new Random();

	public static final double DEFAULT_AIM_JITTER_RADIUS = 0.25;
	public static final float DEFAULT_MAX_TURN_DEGREES_PER_TICK = 30f;
	public static final double DEFAULT_MIN_REACH = 1.5;
	public static final double DEFAULT_MAX_REACH = 4.5;

	private NaturalMining() {
	}

	public enum Result {
		/** The target is null or already air - nothing to do, caller should move on. */
		NO_TARGET,
		/** Held off breaking/paused an in-progress break because the block is currently too close. */
		TOO_CLOSE,
		/** Held off breaking/paused an in-progress break because the block is out of reach. */
		TOO_FAR,
		/**
		 * The crosshair isn't actually on this block right now - either still turning toward it
		 * (never started yet, so nothing to abort) or, if a break was already in progress, something
		 * genuinely got in the way and that progress was just aborted.
		 */
		OBSTRUCTED,
		/** Turning, waiting out the randomized progress gap, or waiting for the reach/line-of-sight checks above - no break progress happened this tick. */
		WAITING,
		/** {@code updateBlockBreakingProgress}/{@code continueDestroyBlock} actually advanced this tick - caller doesn't need to swing/spawn particles itself, this already did. */
		PROGRESSED
	}

	/**
	 * Holds the state for one in-progress break (current target/side/jittered aim point, whether
	 * {@code attackBlock}/{@code startDestroyBlock} has fired yet, and the randomized tick countdown
	 * to the next progress call). A module only needs one instance even if it breaks obstructions
	 * and a "real" target at different times - {@link #tick} treats a changed target/side as a
	 * proper switch (aborts whatever was in progress on the old one first) rather than requiring the
	 * caller to juggle separate sessions.
	 */
	public static final class Session {

		private double minReach = DEFAULT_MIN_REACH;
		private double maxReach = DEFAULT_MAX_REACH;
		private double jitterRadius = DEFAULT_AIM_JITTER_RADIUS;
		private float maxTurnDegreesPerTick = DEFAULT_MAX_TURN_DEGREES_PER_TICK;

		private BlockPos target;
		private Direction side;
		private Vec3d aimPoint;
		private boolean started;

		/** {@code minReach <= 0} skips the too-close check entirely - not every module's geometry has room for one (a 1-wide tunnel miner always stands right next to the wall it's breaking, which is exactly what a real player digging that same tunnel would also do). */
		public void configure(double minReach, double maxReach, double jitterRadius, float maxTurnDegreesPerTick) {
			this.minReach = minReach;
			this.maxReach = maxReach;
			this.jitterRadius = jitterRadius;
			this.maxTurnDegreesPerTick = maxTurnDegreesPerTick;
		}

		public BlockPos currentTarget() {
			return target;
		}

		public boolean isBreaking() {
			return target != null;
		}

		public Result tick(MinecraftClient client, PlayerEntity player, BlockPos desiredTarget, Direction desiredSide) {
			ClientWorld world = client.world;
			if (desiredTarget == null || world.getBlockState(desiredTarget).isAir()) {
				stop(client);
				return Result.NO_TARGET;
			}
			if (target == null || !target.equals(desiredTarget) || side != desiredSide) {
				begin(client, desiredTarget, desiredSide);
			}

			Vec3d center = new Vec3d(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
			double distance = eyeDistanceTo(player, center);
			if (minReach > 0 && distance < minReach) {
				pause(client);
				return Result.TOO_CLOSE;
			}
			if (distance > maxReach) {
				pause(client);
				return Result.TOO_FAR;
			}

			aimAt(player, aimPoint, maxTurnDegreesPerTick);

			// client.crosshairTarget is NOT kept up to date every tick in lockstep with a yaw/pitch
			// change made here - it's only refreshed once per rendered frame, from the camera's
			// interpolated rotation (lerped between last tick's and this tick's values by however far
			// into the current frame render is). A yaw/pitch set moments ago in aimAt() can lag a full
			// tick or more behind before crosshairTarget reflects it, which showed up as: mining not
			// starting until something else (like manual movement) forced an extra interpolation
			// update, and - worse - alignment flickering true/false tick to tick, which re-triggers
			// begin() (a fresh cancelBreaking() + a brand new jittered aim point) over and over,
			// producing the restart-every-tick particle/crack-stage churn. Casting our own raycast from
			// the player's actual current rotation (tickDelta=1.0 - no interpolation) sidesteps the
			// render-timing gap entirely instead of waiting on it.
			HitResult hit = raycast(player, maxReach);
			boolean aligned = hit instanceof BlockHitResult blockHit
					&& blockHit.getType() == HitResult.Type.BLOCK
					&& blockHit.getBlockPos().equals(target);
			if (!aligned) {
				if (started) {
					// Was genuinely mid-break and the block is no longer what's actually in front of
					// the crosshair (something stepped into view, or line of sight otherwise broke) -
					// a real client can't keep reporting progress through that, so abort immediately
					// rather than pretend it didn't notice.
					stop(client);
					return Result.OBSTRUCTED;
				}
				// Still turning toward it (capped turn rate takes more than one tick) - nothing
				// started yet, so there's nothing to abort, just keep waiting.
				return Result.WAITING;
			}

			if (!started) {
				// Matches real held-click mining exactly: attackBlock()/startDestroyBlock() (start)
				// AND updateBlockBreakingProgress()/continueDestroyBlock() (continue) both happen in
				// the same tick the target is first picked, not attackBlock alone with progress
				// waiting a full tick to begin.
				startBreaking(client, target, side);
				started = true;
			}
			BlockState stateBeforeProgress = world.getBlockState(target);
			boolean progressed = continueBreaking(client, target, side);
			if (progressed) {
				swingArm(player);
				spawnBreakingParticles(client, target, side, stateBeforeProgress);
				if (world.getBlockState(target).isAir()) {
					// continueBreaking() just finished the block itself (vanilla's own
					// updateBlockBreakingProgress/continueDestroyBlock already sent the real "done
					// digging" packet for it internally) - clear our started/target state directly
					// instead of leaving started=true for the next tick to find. Otherwise the next
					// tick sees this same position as air, treats it as NO_TARGET, and stop() sends a
					// cancelBlockBreaking() (an abort packet) for a block that's already gone - a
					// stray abort racing the just-confirmed break, which is what was making the block
					// flicker back instead of staying broken.
					target = null;
					side = null;
					aimPoint = null;
					started = false;
				}
				return Result.PROGRESSED;
			}
			return Result.WAITING;
		}

		private void begin(MinecraftClient client, BlockPos newTarget, Direction newSide) {
			if (target != null) {
				// Switching target (or side) mid-break - vanilla itself treats moving your crosshair
				// to a different block as aborting whatever progress was on the old one, not carrying
				// it over.
				cancelBreaking(client);
			}
			target = newTarget;
			side = newSide;
			aimPoint = jitteredFacePoint(client.world, newTarget, newSide, jitterRadius);
			started = false;
		}

		private void pause(MinecraftClient client) {
			if (started) {
				cancelBreaking(client);
				started = false;
			}
		}

		/** Cleanly abandons whatever's in progress - always call this before giving up on a target for good (module disabled, target abandoned, phase changed away from mining). */
		public void stop(MinecraftClient client) {
			if (started) {
				cancelBreaking(client);
			}
			target = null;
			side = null;
			aimPoint = null;
			started = false;
		}
	}

	/** {@code Entity#raycast(double, float, boolean)} (Yarn) -> {@code Entity#pick(double, float, boolean)} (Mojmap) - same block/fluid raycast vanilla's own camera-target update uses internally, diverges by name only. {@code tickDelta=1.0F} means "the current tick's real rotation, no render interpolation" - see the comment at the {@code crosshairTarget} call site this replaced. */
	private static HitResult raycast(PlayerEntity player, double maxDistance) {
		//? if <26.1 {
		return player.raycast(maxDistance, 1.0F, false);
		//?} else {
		/*return player.pick(maxDistance, 1.0F, false);
		*///?}
	}

	private static double eyeDistanceTo(PlayerEntity player, Vec3d point) {
		double dx = point.x - player.getX();
		double dy = point.y - player.getEyeY();
		double dz = point.z - player.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * A random point on the actual face being broken, offset up to {@code jitterRadius} blocks
	 * from its center along the two axes that lie in that face - never dead-center. Clamped to the
	 * block's own real collision bounds on those two axes rather than blindly assuming a full 1x1
	 * face: for a slab, stair, fence, or anything else that isn't a full cube, an unclamped jitter
	 * could land the aim point past the block's actual surface (e.g. over the empty top half of a
	 * bottom slab) - a point the real crosshair could never actually be looking at, which is
	 * exactly the "does it check the new look position is still on the correct block" gap this
	 * closes.
	 */
	private static Vec3d jitteredFacePoint(ClientWorld world, BlockPos pos, Direction side, double jitterRadius) {
		double cx = pos.getX() + 0.5 + stepX(side) * 0.5;
		double cy = pos.getY() + 0.5 + stepY(side) * 0.5;
		double cz = pos.getZ() + 0.5 + stepZ(side) * 0.5;
		double radius = Math.max(0, Math.min(jitterRadius, 0.5));
		if (radius <= 0) {
			return new Vec3d(cx, cy, cz);
		}
		var shape = world.getBlockState(pos).getCollisionShape(world, pos);
		net.minecraft.util.math.Box bounds;
		if (shape.isEmpty()) {
			bounds = new net.minecraft.util.math.Box(pos);
		} else {
			//? if <26.1 {
			bounds = shape.getBoundingBox();
			//?} else {
			/*bounds = shape.bounds();
			*///?}
		}
		double a;
		double b;
		switch (side.getAxis()) {
			case X -> {
				a = clampedJitter(radius, cy, bounds.minY, bounds.maxY);
				b = clampedJitter(radius, cz, bounds.minZ, bounds.maxZ);
			}
			case Y -> {
				a = clampedJitter(radius, cx, bounds.minX, bounds.maxX);
				b = clampedJitter(radius, cz, bounds.minZ, bounds.maxZ);
			}
			default -> {
				a = clampedJitter(radius, cx, bounds.minX, bounds.maxX);
				b = clampedJitter(radius, cy, bounds.minY, bounds.maxY);
			}
		}
		return switch (side.getAxis()) {
			case X -> new Vec3d(cx, cy + a, cz + b);
			case Y -> new Vec3d(cx + a, cy, cz + b);
			case Z -> new Vec3d(cx + a, cy + b, cz);
		};
	}

	/** A random offset in {@code [-radius, radius]}, additionally clamped to stay within the block's own real extent on this axis (shrunk slightly inward so the point never lands exactly on the boundary edge). {@code 0} for a degenerately thin extent on this axis (the two bounds clamp to nothing usable) - just aim at center for that axis rather than jitter into it. */
	private static double clampedJitter(double radius, double center, double min, double max) {
		double lo = Math.max(-radius, min - center) + 0.02;
		double hi = Math.min(radius, max - center) - 0.02;
		if (lo >= hi) {
			return 0;
		}
		return lo + RANDOM.nextDouble() * (hi - lo);
	}

	// Snapping yaw/pitch straight to the target every tick was an instant camera teleport, not a
	// look - that's what was actually destabilizing things (client-side interpolation/movement
	// validation both assume a head turns continuously, not that it jumps). Capped, incremental
	// turning every tick instead - still reaches the target within a handful of ticks, never an
	// instant jump.
	public static void aimAt(PlayerEntity player, Vec3d target, float maxTurnDegreesPerTick) {
		double dx = target.x - player.getX();
		double dy = target.y - player.getEyeY();
		double dz = target.z - player.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
		turnToward(player, targetYaw, targetPitch, maxTurnDegreesPerTick);
	}

	/** Whether the player's current look direction is already within {@code toleranceDegrees} of {@code target} on both axes - use this to know when a capped, multi-tick turn (started via {@link #aimAt}) has actually arrived, rather than assuming one call is enough. */
	public static boolean isFacing(PlayerEntity player, Vec3d target, float toleranceDegrees) {
		double dx = target.x - player.getX();
		double dy = target.y - player.getEyeY();
		double dz = target.z - player.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
		float yawDiff = Math.abs(wrapDegrees(targetYaw - yaw(player)));
		float pitchDiff = Math.abs(targetPitch - pitch(player));
		return yawDiff <= toleranceDegrees && pitchDiff <= toleranceDegrees;
	}

	public static void turnToward(PlayerEntity player, float targetYaw, float targetPitch, float maxTurnDegreesPerTick) {
		float yawDelta = clampAngle(wrapDegrees(targetYaw - yaw(player)), maxTurnDegreesPerTick);
		float pitchDelta = clampAngle(targetPitch - pitch(player), maxTurnDegreesPerTick);
		setLook(player, yaw(player) + yawDelta, pitch(player) + pitchDelta);
	}

	private static float wrapDegrees(float degrees) {
		float wrapped = degrees % 360f;
		if (wrapped >= 180f) {
			wrapped -= 360f;
		} else if (wrapped < -180f) {
			wrapped += 360f;
		}
		return wrapped;
	}

	private static float clampAngle(float delta, float max) {
		return Math.max(-max, Math.min(max, delta));
	}

	/** {@code PlayerEntity#getYaw()} (Yarn) -> {@code Entity#getYRot()} (Mojmap) - same value, diverges by name only. */
	private static float yaw(PlayerEntity player) {
		//? if <26.1 {
		return player.getYaw();
		//?} else {
		/*return player.getYRot();
		*///?}
	}

	/** {@code PlayerEntity#getPitch()} (Yarn) -> {@code Entity#getXRot()} (Mojmap) - same value, diverges by name only. */
	private static float pitch(PlayerEntity player) {
		//? if <26.1 {
		return player.getPitch();
		//?} else {
		/*return player.getXRot();
		*///?}
	}

	/** {@code Entity#setYaw/setPitch} (Yarn) -> {@code Entity#setYRot/setXRot} (Mojmap) - same values, diverges by name only. */
	private static void setLook(PlayerEntity player, float newYaw, float newPitch) {
		//? if <26.1 {
		player.setYaw(newYaw);
		player.setPitch(newPitch);
		//?} else {
		/*player.setYRot(newYaw);
		player.setXRot(newPitch);
		*///?}
	}

	/** {@code Direction#getOffsetX()} (Yarn) -> {@code Direction#getStepX()} (Mojmap) - same value, diverges by name only. */
	public static int stepX(Direction direction) {
		//? if <26.1 {
		return direction.getOffsetX();
		//?} else {
		/*return direction.getStepX();
		*///?}
	}

	/** {@code Direction#getOffsetY()} (Yarn) -> {@code Direction#getStepY()} (Mojmap) - same value, diverges by name only. */
	public static int stepY(Direction direction) {
		//? if <26.1 {
		return direction.getOffsetY();
		//?} else {
		/*return direction.getStepY();
		*///?}
	}

	/** {@code Direction#getOffsetZ()} (Yarn) -> {@code Direction#getStepZ()} (Mojmap) - same value, diverges by name only. */
	public static int stepZ(Direction direction) {
		//? if <26.1 {
		return direction.getOffsetZ();
		//?} else {
		/*return direction.getStepZ();
		*///?}
	}

	/** {@code ClientPlayerInteractionManager#attackBlock} (Yarn) -> {@code MultiPlayerGameMode#startDestroyBlock} (Mojmap) - same value, diverges by name only. */
	private static void startBreaking(MinecraftClient client, BlockPos pos, Direction side) {
		//? if <26.1 {
		client.interactionManager.attackBlock(pos, side);
		//?} else {
		/*client.gameMode.startDestroyBlock(pos, side);
		*///?}
	}

	/** {@code ClientPlayerInteractionManager#updateBlockBreakingProgress} (Yarn) -> {@code MultiPlayerGameMode#continueDestroyBlock} (Mojmap) - same value, diverges by name only. The real vanilla hardness/tool-speed formula runs inside this call, so break speed naturally follows whatever pickaxe/enchantments/haste are actually equipped - nothing here estimates or hardcodes a duration. */
	private static boolean continueBreaking(MinecraftClient client, BlockPos pos, Direction side) {
		//? if <26.1 {
		return client.interactionManager.updateBlockBreakingProgress(pos, side);
		//?} else {
		/*return client.gameMode.continueDestroyBlock(pos, side);
		*///?}
	}

	/** {@code ClientPlayerInteractionManager#cancelBlockBreaking} (Yarn) -> {@code MultiPlayerGameMode#stopDestroyBlock} (Mojmap) - same value, diverges by name only. */
	private static void cancelBreaking(MinecraftClient client) {
		//? if <26.1 {
		client.interactionManager.cancelBlockBreaking();
		//?} else {
		/*client.gameMode.stopDestroyBlock();
		*///?}
	}

	/** {@code PlayerEntity#swingHand(Hand)} (Yarn) -> {@code LivingEntity#swing(InteractionHand)} (Mojmap) - same value, diverges by name only. */
	private static void swingArm(PlayerEntity player) {
		//? if <26.1 {
		player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
		//?} else {
		/*player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		*///?}
	}

	/**
	 * {@code ClientWorld#spawnBlockBreakingParticle(BlockPos, Direction)} (Yarn) -> {@code
	 * ClientLevel#addDestroyBlockEffect(BlockPos, BlockState)} (Mojmap) - vanilla's own held-click
	 * mining spawns this every tick it makes progress, alongside the swing; without it, mining
	 * looked/felt visibly different from a real player's (no chip particles flying off the block)
	 * even though the actual break timing was already identical.
	 */
	private static void spawnBreakingParticles(MinecraftClient client, BlockPos pos, Direction side, BlockState state) {
		//? if <26.1 {
		client.world.spawnBlockBreakingParticle(pos, side);
		//?} else {
		/*client.world.addDestroyBlockEffect(pos, state);
		*///?}
	}
}
