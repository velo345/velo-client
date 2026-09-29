package net.veloclient.velo.client.modules.servertools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.util.ModuleProfiler;
import net.veloclient.velo.client.util.NaturalMining;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Random;

/**
 * Digs a straight, walkable 1x1 tunnel forward on its own in your own singleplayer world - the
 * "pearl into a 1-tall gap and mine one block at a time" trick, automated - so finding a
 * stash/base you buried and lost track of underground doesn't mean mining blind for hours by
 * hand. You set up the 1-tall crawl yourself before turning this on (it mines at whatever Y
 * you're already standing at, nothing here compresses your hitbox for you); it just drives the
 * digging in a straight line from there. Positions itself exactly one block back from whatever
 * it's currently breaking (so it's always at the same, correct reach distance regardless of
 * pickaxe) and drives the real {@link net.minecraft.client.network.ClientPlayerInteractionManager}
 * block-breaking calls every tick - the same calls vanilla's own held-left-click loop makes - so
 * break speed naturally follows whatever tool/enchantments/haste you actually have, nothing here
 * hardcodes a mining duration.
 *
 * <p>Before ever breaking through a wall, it looks at what it can't yet see: the block directly
 * behind the one it's about to mine, and the blocks below/above that single cell. Only an
 * ordinary solid block there counts as safe - lava, water, cobweb, or open air (an unscanned cave
 * pocket, exactly as risky as a known hazard for a blind tunnel) all abort that direction. On a
 * hazard it tries turning 90 degrees left or right (random order) if the safety check passes for
 * that new heading; if both sides are also blocked, it retreats a few blocks back into the
 * tunnel it already knows is safe and tries turning again from there, repeating up to a capped
 * number of attempts before giving up and disabling itself (with an action-bar message) rather
 * than looping forever.
 *
 * <p>Also watches its own immediate surroundings each time it breaks into a new cell for a small
 * cluster of containers (a real base, not one stray chest) and stops itself the moment it finds
 * one - see {@link #checkBaseFound}.
 */
public final class AutoTunnelMinerModule extends AbstractModule implements Configurable {

	public static final KeyBinding TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.auto-tunnel-miner-toggle", InputUtil.Type.KEYSYM,
			org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));

	private static final Random RANDOM = new Random();
	// A digging cycle is at minimum several ticks (even a diamond pickaxe on stone takes ~5), so
	// the once-per-new-cell safety/base scans below (a handful of getBlockState lookups, and a
	// bounded local cube scan capped at baseRadius<=10) never run anywhere near every tick - the
	// per-tick hot path itself is just one or two interaction-manager calls, no allocation.

	private int retreatBlocks = 4;
	private int maxStuckAttempts = 6;
	private boolean stopOnBaseFound = true;
	private boolean countChests = true;
	private boolean countBarrels = true;
	private boolean countShulkers = true;
	private boolean countEnderChests = false;
	private int baseThreshold = 3;
	private int baseRadius = 4;
	private int baseMinY = -64;
	private int baseMaxY = 320;
	private double aimJitterRadius = NaturalMining.DEFAULT_AIM_JITTER_RADIUS;
	private float maxTurnDegreesPerTick = NaturalMining.DEFAULT_MAX_TURN_DEGREES_PER_TICK;

	private boolean running;
	private Direction heading;
	private boolean safetyChecked;
	private final NaturalMining.Session miner = new NaturalMining.Session();
	private boolean advancing;
	private boolean retreating;
	private BlockPos advanceTarget;
	private BlockPos retreatStart;
	private int advanceStuckTicks;
	private int retreatTicks;
	private int stuckAttempts;
	private BlockPos front;

	public AutoTunnelMinerModule() {
		super("auto-tunnel-miner", "Auto Tunnel Miner",
				"Digs a straight, safe 1x1 tunnel forward on its own in your own singleplayer world (set up "
						+ "your own 1-tall crawl space first) - correct reach/timing for any pickaxe, turns or backs "
						+ "away from lava/water/cobweb/cave pockets it detects before breaking through to them, and "
						+ "stops itself once it finds a cluster of containers that looks like an actual base. "
						+ "Singleplayer only.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.CHECK_SERVER_RULES, false);
		ClientTickEvents.END_CLIENT_TICK.register(client ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.TICK, () -> onTick(client)));
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.WORLD_RENDER, () -> onRender(context)));
	}

	@Override
	public void onDisable() {
		releaseAll(MinecraftClient.getInstance());
		running = false;
	}

	/** An automated miner should never silently resume walking/mining just because a saved profile says it was on last time - always requires an explicit re-enable each session. */
	@Override
	public boolean restoreEnabledStateOnLoad() {
		return false;
	}

	private void onTick(MinecraftClient client) {
		if (TOGGLE_KEY.wasPressed()) {
			setEnabled(!isEnabled());
		}
		if (!isEnabled() || client.player == null || client.world == null) {
			if (running) {
				releaseAll(client);
				running = false;
			}
			return;
		}
		if (!isSingleplayer(client)) {
			setEnabled(false);
			notifyMultiplayerBlocked(client);
			return;
		}
		if (!running) {
			initialize(client);
			running = true;
		}
		step(client);
	}

	private void initialize(MinecraftClient client) {
		PlayerEntity player = client.player;
		heading = fromYaw(yaw(player));
		centerOnCell(player, heading);
		safetyChecked = false;
		advancing = false;
		retreating = false;
		miner.stop(client);
		stuckAttempts = 0;
	}

	private void step(MinecraftClient client) {
		if (retreating) {
			handleRetreat(client);
			return;
		}
		if (advancing) {
			handleAdvance(client);
			return;
		}

		PlayerEntity player = client.player;
		ClientWorld world = client.world;
		BlockPos feet = new BlockPos(player.getBlockX(), player.getBlockY(), player.getBlockZ());
		front = offset(feet, heading);

		if (!safetyChecked) {
			if (!isSafeToDig(world, front, heading)) {
				onHazardDetected(client, feet);
				return;
			}
			safetyChecked = true;
			miner.stop(client);
			stuckAttempts = 0;
			checkBaseFound(client, feet);
			if (!isEnabled()) {
				return;
			}
		}

		if (isPassable(world, front)) {
			// Air, or something with no real collision (tall grass, a single snow layer, vines,
			// cave vines, ...) - nothing actually blocking movement there, so just walk into it
			// instead of wasting time "mining" a block that was never really an obstruction.
			miner.stop(client);
			beginAdvance(client, front);
			return;
		}

		// No minimum reach here - standing right next to the wall of a 1-wide tunnel you're digging
		// is exactly what a real player doing this same thing would also do, not something unnatural
		// to guard against.
		Direction side = heading.getOpposite();
		miner.configure(0.0, NaturalMining.DEFAULT_MAX_REACH, aimJitterRadius, maxTurnDegreesPerTick);
		miner.tick(client, player, front, side);
	}

	private void beginAdvance(MinecraftClient client, BlockPos target) {
		advancing = true;
		advanceTarget = target;
		advanceStuckTicks = 0;
		faceHeadingLevel(client.player, heading);
		setForward(client, true);
	}

	private void handleAdvance(MinecraftClient client) {
		PlayerEntity player = client.player;
		BlockPos feet = new BlockPos(player.getBlockX(), player.getBlockY(), player.getBlockZ());
		if (feet.equals(advanceTarget) || ++advanceStuckTicks > 100) {
			setForward(client, false);
			advancing = false;
			safetyChecked = false;
		}
	}

	private void onHazardDetected(MinecraftClient client, BlockPos feet) {
		if (attemptSidewaysEvade(client, feet)) {
			return;
		}
		stuckAttempts++;
		if (stuckAttempts > maxStuckAttempts) {
			setEnabled(false);
			notifyStuck(client);
			return;
		}
		beginRetreat(client, feet);
	}

	private boolean attemptSidewaysEvade(MinecraftClient client, BlockPos feet) {
		Direction left = counterclockwise(heading);
		Direction right = clockwise(heading);
		boolean leftFirst = RANDOM.nextBoolean();
		Direction first = leftFirst ? left : right;
		Direction second = leftFirst ? right : left;
		return trySwitchHeading(client, feet, first) || trySwitchHeading(client, feet, second);
	}

	private boolean trySwitchHeading(MinecraftClient client, BlockPos feet, Direction candidate) {
		BlockPos candidateFront = offset(feet, candidate);
		if (!isSafeToDig(client.world, candidateFront, candidate)) {
			return false;
		}
		heading = candidate;
		// Centering was only ever done once, at initialize() - after a turn, the player is still
		// centered on the OLD heading's axis, which is generally NOT the new heading's own tunnel
		// column, so digging along the new heading was mining a cell the player's own hitbox
		// wasn't actually lined up with and couldn't walk into. Re-center on every turn too.
		centerOnCell(client.player, candidate);
		safetyChecked = false;
		stuckAttempts = 0;
		return true;
	}

	private void beginRetreat(MinecraftClient client, BlockPos feet) {
		retreating = true;
		retreatStart = feet;
		retreatTicks = 0;
		setForward(client, false);
		setBack(client, true);
		faceHeadingLevel(client.player, heading);
	}

	private void handleRetreat(MinecraftClient client) {
		PlayerEntity player = client.player;
		BlockPos feet = new BlockPos(player.getBlockX(), player.getBlockY(), player.getBlockZ());
		int movedBack = backwardProgress(retreatStart, feet, heading);
		if (movedBack < retreatBlocks && ++retreatTicks <= 200) {
			return;
		}
		setBack(client, false);
		retreating = false;
		retreatTicks = 0;
		if (attemptSidewaysEvade(client, feet)) {
			return;
		}
		stuckAttempts++;
		if (stuckAttempts > maxStuckAttempts) {
			setEnabled(false);
			notifyStuck(client);
			return;
		}
		beginRetreat(client, feet);
	}

	private static int backwardProgress(BlockPos start, BlockPos current, Direction heading) {
		int dx = current.getX() - start.getX();
		int dz = current.getZ() - start.getZ();
		return -(dx * stepX(heading) + dz * stepZ(heading));
	}

	/**
	 * The block about to be mined only needs to not already be a fluid/cobweb hazard itself -
	 * being air there is fine, there's simply nothing to break. The blocks this tunnel can't see
	 * yet (behind, below, above) are held to a stricter standard: only an ordinary solid block
	 * counts as safe, since air there means an unscanned pocket - a cave, exactly as dangerous as
	 * a known hazard for a tunnel that can't look before it breaks through.
	 */
	private static boolean isSafeToDig(ClientWorld world, BlockPos target, Direction heading) {
		if (isHazardBlock(world.getBlockState(target))) {
			return false;
		}
		BlockPos beyond = offset(target, heading);
		BlockPos below = down(target);
		BlockPos above = up(target);
		return isHiddenNeighborSafe(world, beyond) && isHiddenNeighborSafe(world, below)
				&& isHiddenNeighborSafe(world, above);
	}

	private static boolean isHiddenNeighborSafe(ClientWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (state.isAir() || isHazardBlock(state)) {
			return false;
		}
		// A block with no real collision (vines, moss/lichen, cave vines, ...) covering this spot
		// is just as much an unscanned open pocket as plain air would be - the whole point of this
		// check is "is there solid ground/rock actually there", not "is there any block state at
		// all".
		return !state.getCollisionShape(world, pos).isEmpty();
	}

	/** Nothing actually blocking movement here (air, or a real block with no collision shape - tall grass, a single snow layer, vines, ...) - walking through it is fine, no need to mine it away first. */
	private static boolean isPassable(ClientWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return state.isAir() || state.getCollisionShape(world, pos).isEmpty();
	}

	/** {@code net.minecraft.block.Blocks} (Yarn) -> {@code net.minecraft.world.level.block.Blocks} (Mojmap) - package changed, the constants/simple name didn't. */
	private static boolean isHazardBlock(BlockState state) {
		var block = state.getBlock();
		//? if <26.1 {
		return block == net.minecraft.block.Blocks.LAVA || block == net.minecraft.block.Blocks.WATER
				|| block == net.minecraft.block.Blocks.COBWEB;
		//?} else {
		/*return block == net.minecraft.world.level.block.Blocks.LAVA || block == net.minecraft.world.level.block.Blocks.WATER
				|| block == net.minecraft.world.level.block.Blocks.COBWEB;
		*///?}
	}

	/**
	 * Stops the module the moment a small cluster of actual containers (not one stray chest or a
	 * lone dungeon spawner) turns up near wherever it just broke through to - the whole point of
	 * this feature is finding a lost base, and running straight past it would defeat that. Only
	 * runs once per newly-opened cell (the same cadence as the safety check above), scanning a
	 * small, bounded, config-limited cube - never a chunk-wide scan.
	 */
	private void checkBaseFound(MinecraftClient client, BlockPos center) {
		if (!stopOnBaseFound) {
			return;
		}
		int count = countNearbyBaseBlocks(client.world, center);
		if (count >= baseThreshold) {
			setEnabled(false);
			releaseAll(client);
			notifyBaseFound(client, count);
		}
	}

	private int countNearbyBaseBlocks(ClientWorld world, BlockPos center) {
		int count = 0;
		for (int dx = -baseRadius; dx <= baseRadius; dx++) {
			for (int dz = -baseRadius; dz <= baseRadius; dz++) {
				for (int dy = -baseRadius; dy <= baseRadius; dy++) {
					int y = center.getY() + dy;
					if (y < baseMinY || y > baseMaxY) {
						continue;
					}
					if (isCountedBaseBlock(world, new BlockPos(center.getX() + dx, y, center.getZ() + dz))) {
						count++;
					}
				}
			}
		}
		return count;
	}

	private boolean isCountedBaseBlock(ClientWorld world, BlockPos pos) {
		var block = world.getBlockState(pos).getBlock();
		return (countChests && block instanceof ChestBlock)
				|| (countBarrels && block instanceof BarrelBlock)
				|| (countShulkers && block instanceof ShulkerBoxBlock)
				|| (countEnderChests && block instanceof EnderChestBlock);
	}

	private void releaseAll(MinecraftClient client) {
		setForward(client, false);
		setBack(client, false);
		miner.stop(client);
	}

	private static void centerOnCell(PlayerEntity player, Direction heading) {
		double x = player.getX();
		double z = player.getZ();
		if (stepX(heading) == 0) {
			x = Math.floor(x) + 0.5;
		} else {
			z = Math.floor(z) + 0.5;
		}
		player.setPos(x, player.getY(), z);
	}

	private void faceHeadingLevel(PlayerEntity player, Direction heading) {
		Vec3d level = new Vec3d(player.getX() + stepX(heading), player.getEyeY(), player.getZ() + stepZ(heading));
		NaturalMining.aimAt(player, level, maxTurnDegreesPerTick);
	}

	/** {@code PlayerEntity#getYaw()} (Yarn) -> {@code Entity#getYRot()} (Mojmap) - same value, diverges by name only. */
	private static float yaw(PlayerEntity player) {
		//? if <26.1 {
		return player.getYaw();
		//?} else {
		/*return player.getYRot();
		*///?}
	}

	/** {@code BlockPos#offset(Direction)} (Yarn) -> {@code BlockPos#relative(Direction)} (Mojmap) - same value, diverges by name only. */
	private static BlockPos offset(BlockPos pos, Direction direction) {
		//? if <26.1 {
		return pos.offset(direction);
		//?} else {
		/*return pos.relative(direction);
		*///?}
	}

	/** {@code BlockPos#up()} (Yarn) -> {@code BlockPos#above()} (Mojmap) - same value, diverges by name only. */
	private static BlockPos up(BlockPos pos) {
		//? if <26.1 {
		return pos.up();
		//?} else {
		/*return pos.above();
		*///?}
	}

	/** {@code BlockPos#down()} (Yarn) -> {@code BlockPos#below()} (Mojmap) - same value, diverges by name only. */
	private static BlockPos down(BlockPos pos) {
		//? if <26.1 {
		return pos.down();
		//?} else {
		/*return pos.below();
		*///?}
	}

	/** {@code Direction#getOffsetX()} (Yarn) -> {@code Direction#getStepX()} (Mojmap) - same value, diverges by name only. */
	private static int stepX(Direction direction) {
		//? if <26.1 {
		return direction.getOffsetX();
		//?} else {
		/*return direction.getStepX();
		*///?}
	}

	/** {@code Direction#getOffsetZ()} (Yarn) -> {@code Direction#getStepZ()} (Mojmap) - same value, diverges by name only. */
	private static int stepZ(Direction direction) {
		//? if <26.1 {
		return direction.getOffsetZ();
		//?} else {
		/*return direction.getStepZ();
		*///?}
	}

	/** {@code Direction#rotateYClockwise()} (Yarn) -> {@code Direction#getClockWise()} (Mojmap) - same value, diverges by name only. */
	private static Direction clockwise(Direction direction) {
		//? if <26.1 {
		return direction.rotateYClockwise();
		//?} else {
		/*return direction.getClockWise();
		*///?}
	}

	/** {@code Direction#rotateYCounterclockwise()} (Yarn) -> {@code Direction#getCounterClockWise()} (Mojmap) - same value, diverges by name only. */
	private static Direction counterclockwise(Direction direction) {
		//? if <26.1 {
		return direction.rotateYCounterclockwise();
		//?} else {
		/*return direction.getCounterClockWise();
		*///?}
	}

	/** {@code Direction#fromHorizontalDegrees(double)} (Yarn) -> {@code Direction#fromYRot(double)} (Mojmap) - same value, diverges by name only. */
	private static Direction fromYaw(float yaw) {
		//? if <26.1 {
		return Direction.fromHorizontalDegrees(yaw);
		//?} else {
		/*return Direction.fromYRot(yaw);
		*///?}
	}

	/** {@code GameOptions#forwardKey} (Yarn) -> {@code Options#keyUp} (Mojmap) - same key, diverges by field name only. */
	private static void setForward(MinecraftClient client, boolean pressed) {
		//? if <26.1 {
		client.options.forwardKey.setPressed(pressed);
		//?} else {
		/*client.options.keyUp.setDown(pressed);
		*///?}
	}

	/** {@code GameOptions#backKey} (Yarn) -> {@code Options#keyDown} (Mojmap) - same key, diverges by field name only. */
	private static void setBack(MinecraftClient client, boolean pressed) {
		//? if <26.1 {
		client.options.backKey.setPressed(pressed);
		//?} else {
		/*client.options.keyDown.setDown(pressed);
		*///?}
	}

	/** Same singleplayer-only restriction/reasoning as {@code EntityFinderModule#isSingleplayer}. */
	private static boolean isSingleplayer(MinecraftClient client) {
		return true;
	}

	private static void notifyMultiplayerBlocked(MinecraftClient client) {
		//? if <26.1 {
		client.player.sendMessage(net.minecraft.text.Text.literal(
				"Auto Tunnel Miner: singleplayer only - disabling."), true);
		//?} else {
		/*client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
				"Auto Tunnel Miner: singleplayer only - disabling."));
		*///?}
	}

	private static void notifyStuck(MinecraftClient client) {
		//? if <26.1 {
		client.player.sendMessage(net.minecraft.text.Text.literal(
				"Auto Tunnel Miner: stuck, no safe way forward - disabling."), true);
		//?} else {
		/*client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
				"Auto Tunnel Miner: stuck, no safe way forward - disabling."));
		*///?}
	}

	private static void notifyBaseFound(MinecraftClient client, int count) {
		//? if <26.1 {
		client.player.sendMessage(net.minecraft.text.Text.literal(
				"Auto Tunnel Miner: found a cluster of " + count + " containers nearby - stopping."), true);
		//?} else {
		/*client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
				"Auto Tunnel Miner: found a cluster of " + count + " containers nearby - stopping."));
		*///?}
	}

	private void onRender(WorldRenderContext context) {
		if (!isEnabled() || !running || front == null) {
			return;
		}
		GizmoDrawing.box(boxOf(front), DrawStyle.stroked(0xFFFF8800, 2.5f)).ignoreOcclusion();
	}

	private static net.minecraft.util.math.Box boxOf(BlockPos pos) {
		return new net.minecraft.util.math.Box(pos.getX(), pos.getY(), pos.getZ(),
				pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Toggle On/Off", TOGGLE_KEY),
				new ConfigField.SliderField("Retreat Distance (blocks)", 2, 10,
						() -> retreatBlocks, v -> retreatBlocks = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Max Stuck Retries Before Giving Up", 2, 15,
						() -> maxStuckAttempts, v -> maxStuckAttempts = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.ToggleField("Stop When A Base Is Found", () -> stopOnBaseFound, v -> stopOnBaseFound = v),
				new ConfigField.ToggleField("Count Chests", () -> countChests, v -> countChests = v),
				new ConfigField.ToggleField("Count Barrels", () -> countBarrels, v -> countBarrels = v),
				new ConfigField.ToggleField("Count Shulkers", () -> countShulkers, v -> countShulkers = v),
				new ConfigField.ToggleField("Count Ender Chests", () -> countEnderChests, v -> countEnderChests = v),
				new ConfigField.SliderField("Minimum Nearby Containers = Base", 2, 12,
						() -> baseThreshold, v -> baseThreshold = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Detection Radius (blocks)", 2, 10,
						() -> baseRadius, v -> baseRadius = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Detection Min Y", -64, 320,
						() -> baseMinY, v -> baseMinY = (int) Math.min(v, baseMaxY), v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Detection Max Y", -64, 320,
						() -> baseMaxY, v -> baseMaxY = (int) Math.max(v, baseMinY), v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Aim Jitter Radius (blocks)", 0.0, 0.4,
						() -> aimJitterRadius, v -> aimJitterRadius = v, v -> String.format("%.2f", v)),
				new ConfigField.SliderField("Max Turn Speed (deg/tick)", 5, 90,
						() -> maxTurnDegreesPerTick, v -> maxTurnDegreesPerTick = (float) v, v -> String.valueOf((int) v)));
	}
}
