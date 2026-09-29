package net.veloclient.velo.client.modules.servertools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Excavates a whole rectangular volume you mark out with two corners, in your own singleplayer
 * world, using the same real interaction-manager calls as {@link AutoTunnelMinerModule} and
 * {@link EnderChestFarmerModule} (an iron axe here, mining a real 3x3 face per swing via your own
 * separate 3x3-mining tool setup - this module never estimates or hardcodes that, it just aims at
 * the center of each face and lets your tool's own behavior do the rest).
 *
 * <p>Mines top-down in 3-tall bands: within a band it sweeps a row along the volume's long axis,
 * mines 3 steps sideways to line up the next row, sweeps back the other way, and repeats until the
 * whole band's width is covered - then descends straight down into the next band and does it
 * again, until it reaches the bottom of the selection. Every step checks for solid footing first
 * and bridges (places a block against the block it's already standing on) rather than walking into
 * a hole; every newly-cleared cell on the outer boundary of the selection gets checked for a breach
 * into an open cave and sealed if found; cobwebs get a sword swapped in for the one swing they need;
 * lava/water uncovered mid-dig gets a nearby source capped where reachable (a bounded local search,
 * not a full pathfinder - see {@link #tryPlugFluid}) or the run pauses itself rather than push
 * through it blind.
 *
 * <p>A "missing center" cell (the block you'd need to look at to trigger the next 3x3 doesn't
 * exist - a cave pocket) is handled by placing back against whichever neighbor of it is still
 * solid and breaking that one placed block instead. If literally nothing around it is solid (a
 * pocket bigger than the tool's own reach in every direction), this walks the cell as open space
 * instead of attempting to scaffold one back into existence blind - flagged in the class docs
 * rather than silently claimed to match a fancier scheme that was never actually verified in-game.
 */
public final class AutoExcavatorModule extends AbstractModule implements Configurable {

	public static final KeyBinding CORNER_A_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.auto-excavator-corner-a", InputUtil.Type.KEYSYM,
			org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));
	public static final KeyBinding CORNER_B_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.auto-excavator-corner-b", InputUtil.Type.KEYSYM,
			org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));
	public static final KeyBinding CLEAR_SELECTION_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.auto-excavator-clear-selection", InputUtil.Type.KEYSYM,
			org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));
	public static final KeyBinding START_PAUSE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.auto-excavator-start-pause", InputUtil.Type.KEYSYM,
			org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));

	private static final Random RANDOM = new Random();
	private static final int MINING_STUCK_TICKS = 100;
	private static final int STEP_STUCK_TICKS = 100;
	private static final int BRIDGE_COOLDOWN_MIN_TICKS = 5;
	private static final int BRIDGE_COOLDOWN_MAX_TICKS = 10;
	private static final float BRIDGE_AIM_TOLERANCE_DEGREES = 3f;

	private enum Phase { MOVING, DESCENDING, PLUGGING_FLUID, SELLING, WAITING_FOR_SHOP, DONE }

	private enum StepPurpose { SWEEP_ROW, SIDESTEP }

	private enum PlaceState { IDLE, TURNING, COOLDOWN }

	/**
	 * The 3x3 tool mines a 3-wide horizontal slab when you look straight down, not a clean 1-block
	 * vertical step, so a single "mine below you" doesn't land you cleanly on the next band - this
	 * is the exact "dig down twice, then jump and place a block underneath to land on" sequence
	 * described for reaching the next 3-tall layer, run as its own little state machine.
	 */
	private enum DescendStage { DIG_FIRST, DIG_SECOND, JUMPING, PLACING, LANDING }

	private enum MissingCenterStage { IDLE, PLACING_INTERMEDIATE, PLACING_CENTER }

	private boolean requireIronAxe = true;
	private boolean mineCobwebsWithSword = true;
	private boolean fillOuterGaps = true;
	private boolean autoPlugFluids = true;
	private int fluidScanRadius = 4;
	private String sellCommand = "sell";
	private int sellEmptySlotThreshold = 2;
	private double aimJitterRadius = NaturalMining.DEFAULT_AIM_JITTER_RADIUS;
	private float maxTurnDegreesPerTick = NaturalMining.DEFAULT_MAX_TURN_DEGREES_PER_TICK;
	private int maxStuckTicks = 100;

	private BlockPos cornerA;
	private BlockPos cornerB;
	private boolean active;
	private boolean running;
	private Phase phase = Phase.MOVING;

	private int minX, maxX, minY, maxY, minZ, maxZ;
	private Direction travelDir;
	private Direction rowDir;
	private int layerTopY;
	private int lateralMoved;
	private int rowAxisSpan;
	private int longAxisSpan;

	private StepPurpose purpose = StepPurpose.SWEEP_ROW;
	private Direction stepDir;
	private int stepsRemaining;
	private boolean advancingIntoCell;
	private BlockPos stepTarget;
	private BlockPos centerTarget;
	private int stepStuckTicks;
	private int miningStuckTicks;

	private final NaturalMining.Session miner = new NaturalMining.Session();
	private boolean usingSword;

	private DescendStage descendStage = DescendStage.DIG_FIRST;
	private BlockPos descendPillarBase;
	private int descendStuckTicks;

	private MissingCenterStage missingCenterStage = MissingCenterStage.IDLE;
	private BlockPos missingCenterCorner;
	private BlockPos missingCenterIntermediate;
	private int missingCenterStuckTicks;

	private BlockPos fluidHazard;
	private BlockPos fluidSource;
	private int fluidStuckTicks;

	private PlaceState placeState = PlaceState.IDLE;
	private Vec3d placeAimPoint;
	private BlockHitResult placeHit;
	private Direction placeSide;
	private BlockPos placeResultPos;
	private int placeCooldownTicks;

	private int waitTicks;
	private int shopWaitTicks;

	public AutoExcavatorModule() {
		super("auto-excavator", "Auto Excavator",
				"Mark a box with two corners and it digs the whole thing out in 3-tall sweeping bands "
						+ "on its own in your own singleplayer world - bridges gaps, seals boundary breaches, "
						+ "caps nearby lava/water, swaps to a sword for cobwebs, and sells off blocks once your "
						+ "inventory fills up. Requires an iron axe. Singleplayer only.",
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
		active = false;
	}

	/** Never silently resumes digging/walking/selling just because a saved profile says it was on. */
	@Override
	public boolean restoreEnabledStateOnLoad() {
		return false;
	}

	private void onTick(MinecraftClient client) {
		handleSelectionKeys(client);
		if (START_PAUSE_KEY.wasPressed() && isEnabled()) {
			active = !active;
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
			notify(client, "singleplayer only - disabling.");
			return;
		}
		if (!active) {
			if (running) {
				releaseAll(client);
				running = false;
			}
			return;
		}
		if (cornerA == null || cornerB == null) {
			active = false;
			notify(client, "select both corners before starting.");
			return;
		}
		if (!running) {
			beginExcavation(client);
			running = true;
		}
		step(client);
	}

	private void handleSelectionKeys(MinecraftClient client) {
		if (client.player == null || client.world == null) {
			return;
		}
		if (CORNER_A_KEY.wasPressed()) {
			BlockPos pos = lookedAtBlock(client);
			if (pos != null) {
				cornerA = pos;
			}
		}
		if (CORNER_B_KEY.wasPressed()) {
			BlockPos pos = lookedAtBlock(client);
			if (pos != null) {
				cornerB = pos;
			}
		}
		if (CLEAR_SELECTION_KEY.wasPressed()) {
			cornerA = null;
			cornerB = null;
			active = false;
		}
	}

	private static BlockPos lookedAtBlock(MinecraftClient client) {
		HitResult hit = client.crosshairTarget;
		if (hit instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK) {
			return immutable(blockHit.getBlockPos());
		}
		return null;
	}

	private void beginExcavation(MinecraftClient client) {
		minX = Math.min(cornerA.getX(), cornerB.getX());
		maxX = Math.max(cornerA.getX(), cornerB.getX());
		minY = Math.min(cornerA.getY(), cornerB.getY());
		maxY = Math.max(cornerA.getY(), cornerB.getY());
		minZ = Math.min(cornerA.getZ(), cornerB.getZ());
		maxZ = Math.max(cornerA.getZ(), cornerB.getZ());

		int spanX = maxX - minX + 1;
		int spanZ = maxZ - minZ + 1;
		boolean longIsX = spanX >= spanZ;
		longAxisSpan = longIsX ? spanX : spanZ;
		rowAxisSpan = longIsX ? spanZ : spanX;
		travelDir = longIsX ? Direction.EAST : Direction.SOUTH;
		rowDir = longIsX ? Direction.SOUTH : Direction.EAST;

		PlayerEntity player = client.player;
		layerTopY = Math.min(maxY, player.getBlockY() + 2);
		lateralMoved = 0;

		purpose = StepPurpose.SWEEP_ROW;
		stepDir = travelDir;
		stepsRemaining = longAxisSpan;
		advancingIntoCell = false;
		stepStuckTicks = 0;
		miningStuckTicks = 0;
		miner.stop(client);
		phase = Phase.MOVING;
	}

	private void step(MinecraftClient client) {
		if (phase == Phase.MOVING && checkInventoryFull(client)) {
			setForward(client, false);
			miner.stop(client);
			waitTicks = 5;
			phase = Phase.SELLING;
			return;
		}
		switch (phase) {
			case MOVING -> advanceOneCell(client);
			case DESCENDING -> handleDescend(client);
			case PLUGGING_FLUID -> pluginFluidStep(client);
			case SELLING -> {
				if (--waitTicks <= 0) {
					sendCommand(client, sellCommand);
					shopWaitTicks = 100;
					phase = Phase.WAITING_FOR_SHOP;
				}
			}
			case WAITING_FOR_SHOP -> waitForShop(client);
			case DONE -> { }
		}
	}

	// ---- core movement/mining primitive ----------------------------------------------------

	private void advanceOneCell(MinecraftClient client) {
		if (stepsRemaining <= 0) {
			onStepsExhausted(client);
			return;
		}
		if (!ensureTool(client)) {
			return;
		}
		PlayerEntity player = client.player;
		ClientWorld world = client.world;
		BlockPos feet = feet(player);

		if (!advancingIntoCell) {
			stepTarget = stepDir == Direction.DOWN ? down(feet) : offset(feet, stepDir);
			centerTarget = stepDir == Direction.DOWN ? stepTarget
					: new BlockPos(stepTarget.getX(), layerTopY - 1, stepTarget.getZ());
			miningStuckTicks = 0;
			advancingIntoCell = true;
		}

		BlockState centerState = world.getBlockState(centerTarget);
		boolean centerAlreadyClear = centerState.isAir();
		if (!centerAlreadyClear && isHazardBlock(centerState) && autoPlugFluids) {
			fluidHazard = centerTarget;
			phase = Phase.PLUGGING_FLUID;
			fluidStuckTicks = 0;
			return;
		}

		if (centerAlreadyClear) {
			if (!faceOpenAlready(world, centerTarget, stepDir)) {
				if (!handleMissingCenter(client, centerTarget, stepDir)) {
					return;
				}
			}
		} else {
			boolean isCobweb = centerState.getBlock() == cobwebBlock();
			if (isCobweb && mineCobwebsWithSword) {
				if (!ensureSword(client)) {
					return;
				}
			} else if (usingSword) {
				if (!ensureAxe(client)) {
					return;
				}
			}
			miner.configure(0.0, NaturalMining.DEFAULT_MAX_REACH, aimJitterRadius, maxTurnDegreesPerTick);
			NaturalMining.Result result = miner.tick(client, player, centerTarget, stepDir.getOpposite());
			if (result == NaturalMining.Result.PROGRESSED) {
				miningStuckTicks = 0;
			} else if (result != NaturalMining.Result.NO_TARGET) {
				if (++miningStuckTicks > MINING_STUCK_TICKS) {
					giveUpStuck(client, "couldn't clear a block - pausing.");
				}
				return;
			}
		}

		sealBoundaryBreaches(client, centerTarget);

		if (!ensureFooting(client, stepTarget)) {
			return;
		}

		moveInto(client, stepTarget, stepDir);
	}

	private void onStepsExhausted(MinecraftClient client) {
		switch (purpose) {
			case SWEEP_ROW -> {
				int rowAxisRemaining = rowAxisSpan - lateralMoved;
				if (rowAxisRemaining <= 0) {
					beginDescendSequence(client);
				} else {
					purpose = StepPurpose.SIDESTEP;
					stepDir = rowDir;
					stepsRemaining = Math.min(3, rowAxisRemaining);
					advancingIntoCell = false;
				}
			}
			case SIDESTEP -> {
				lateralMoved += lastSidestepSteps;
				travelDir = travelDir.getOpposite();
				purpose = StepPurpose.SWEEP_ROW;
				stepDir = travelDir;
				stepsRemaining = longAxisSpan;
				advancingIntoCell = false;
			}
		}
	}

	private int lastSidestepSteps;

	/** The current band's floor (layerTopY-2, clamped at minY) is the bottom of the selection - nothing left to dig into below it. */
	private void beginDescendSequence(MinecraftClient client) {
		if (Math.max(minY, layerTopY - 2) <= minY) {
			finishExcavation(client);
			return;
		}
		setForward(client, false);
		descendStage = DescendStage.DIG_FIRST;
		descendPillarBase = null;
		descendStuckTicks = 0;
		miner.stop(client);
		phase = Phase.DESCENDING;
	}

	/**
	 * "Dig down twice directly below you, then jump and place a block underneath you to land on" -
	 * literally, rather than assuming a clean 1-block-per-swing vertical tunnel, since the 3x3 tool
	 * clears a horizontal slab per swing and doesn't step down cleanly on its own. The new layer's
	 * band is re-derived from wherever the player actually ends up standing afterward (same as the
	 * very first layer at {@link #beginExcavation}), rather than computed in advance, since exactly
	 * how far those two digs drop you isn't something this module tries to predict.
	 */
	private void handleDescend(MinecraftClient client) {
		PlayerEntity player = client.player;
		switch (descendStage) {
			case DIG_FIRST -> {
				if (digStraightDown(client)) {
					descendStage = DescendStage.DIG_SECOND;
					descendStuckTicks = 0;
				}
			}
			case DIG_SECOND -> {
				if (digStraightDown(client)) {
					descendStage = DescendStage.JUMPING;
					descendStuckTicks = 0;
				}
			}
			case JUMPING -> {
				if (isOnGround(player)) {
					descendPillarBase = down(feet(player));
					setJump(client, true);
				} else {
					setJump(client, false);
					descendStage = DescendStage.PLACING;
				}
			}
			case PLACING -> {
				if (descendPillarBase == null) {
					descendPillarBase = down(feet(player));
				}
				PlaceResult result = placeBlockStep(client, descendPillarBase, Direction.UP);
				switch (result) {
					case PLACED -> descendStage = DescendStage.LANDING;
					case FAILED -> giveUpStuck(client, "no blocks left to complete the layer descent - pausing.");
					case IN_PROGRESS -> { }
				}
			}
			case LANDING -> {
				if (!isOnGround(player)) {
					return;
				}
				int newLayerTop = Math.min(layerTopY - 1, feet(player).getY() + 2);
				if (newLayerTop < minY) {
					finishExcavation(client);
					return;
				}
				layerTopY = newLayerTop;
				lateralMoved = 0;
				purpose = StepPurpose.SWEEP_ROW;
				stepDir = travelDir;
				stepsRemaining = longAxisSpan;
				advancingIntoCell = false;
				phase = Phase.MOVING;
			}
		}
		if (descendStage != DescendStage.LANDING && ++descendStuckTicks > MINING_STUCK_TICKS * 2) {
			giveUpStuck(client, "got stuck during layer descent - pausing.");
		}
	}

	/** Mines the block directly below the player's feet - checks two blocks down for a hazard first, same safety margin as {@code EnderChestFarmerModule#digDown}. Returns true once that block is confirmed gone. */
	private boolean digStraightDown(MinecraftClient client) {
		PlayerEntity player = client.player;
		BlockPos feet = feet(player);
		BlockPos below = down(feet);
		BlockState belowState = client.world.getBlockState(below);
		if (belowState.isAir()) {
			return true;
		}
		if (isHazardBlock(belowState) || isHazardBlock(client.world.getBlockState(down(below)))) {
			giveUpStuck(client, "lava/water directly below during layer descent - pausing.");
			return false;
		}
		miner.configure(0.0, NaturalMining.DEFAULT_MAX_REACH, aimJitterRadius, maxTurnDegreesPerTick);
		faceDown(player);
		NaturalMining.Result result = miner.tick(client, player, below, Direction.UP);
		if (result == NaturalMining.Result.PROGRESSED) {
			miningStuckTicks = 0;
		}
		return false;
	}

	private void finishExcavation(MinecraftClient client) {
		miner.stop(client);
		phase = Phase.DONE;
		active = false;
		notify(client, "selection fully excavated.");
	}

	// Tracks how many sidestep cells were actually taken this pass, since stepsRemaining is reset
	// to 0 by the time onStepsExhausted reads it.
	private void moveInto(MinecraftClient client, BlockPos target, Direction dir) {
		if (dir == Direction.DOWN) {
			faceDown(client.player);
		} else {
			faceHeadingLevel(client.player, dir);
			setForward(client, true);
		}
		BlockPos feet = feet(client.player);
		if (feet.equals(target) || ++stepStuckTicks > STEP_STUCK_TICKS) {
			setForward(client, false);
			if (purpose == StepPurpose.SIDESTEP) {
				lastSidestepSteps++;
			}
			stepStuckTicks = 0;
			advancingIntoCell = false;
			stepsRemaining--;
			if (stepsRemaining <= 0) {
				if (purpose != StepPurpose.SIDESTEP) {
					lastSidestepSteps = 0;
				}
				onStepsExhausted(client);
			}
		}
	}

	// ---- footing / bridging ------------------------------------------------------------------

	private boolean ensureFooting(MinecraftClient client, BlockPos target) {
		BlockPos below = down(target);
		if (target.getY() <= minY - 1 || !client.world.getBlockState(below).isAir()) {
			return true;
		}
		if (isHazardBlock(client.world.getBlockState(below))) {
			giveUpStuck(client, "hazard directly below the next step - pausing.");
			return false;
		}
		Direction travelFromCurrent = stepDir == Direction.DOWN ? null : stepDir;
		if (travelFromCurrent == null) {
			return true;
		}
		BlockPos currentFloor = down(feet(client.player));
		PlaceResult result = placeBlockStep(client, currentFloor, travelFromCurrent);
		return switch (result) {
			case PLACED -> true;
			case IN_PROGRESS -> false;
			case FAILED -> {
				giveUpStuck(client, "no blocks to bridge a gap with - pausing.");
				yield false;
			}
		};
	}

	// ---- missing center handling --------------------------------------------------------------

	private static boolean faceOpenAlready(ClientWorld world, BlockPos center, Direction dir) {
		Direction a;
		Direction b;
		if (dir.getAxis() == Direction.Axis.Y) {
			a = Direction.EAST;
			b = Direction.SOUTH;
		} else if (dir.getAxis() == Direction.Axis.X) {
			a = Direction.UP;
			b = Direction.SOUTH;
		} else {
			a = Direction.UP;
			b = Direction.EAST;
		}
		for (int da = -1; da <= 1; da++) {
			for (int db = -1; db <= 1; db++) {
				BlockPos p = offsetBy(offsetBy(center, a, da), b, db);
				if (!world.getBlockState(p).isAir()) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Center block is missing (a cave pocket): first tries a direct face-adjacent neighbor (behind
	 * it deeper into the wall, above, below, or to either side) and places straight against that.
	 * If none of those are solid either, falls back to the 3x3 face's own diagonal corners - places
	 * one block against a solid corner (nudging the player a step closer first if the corner is out
	 * of reach, but only ever onto footing that's already confirmed solid), then a second block
	 * against that new block to finally recreate the center itself, which the normal mining branch
	 * then breaks like any other block.
	 */
	private boolean handleMissingCenter(MinecraftClient client, BlockPos center, Direction dir) {
		if (missingCenterStage == MissingCenterStage.IDLE) {
			Direction faceNeighbor = findSolidFaceNeighbor(client.world, center, dir);
			if (faceNeighbor != null) {
				PlaceResult result = placeBlockStep(client, offset(center, faceNeighbor), faceNeighbor.getOpposite());
				if (result == PlaceResult.IN_PROGRESS) {
					return false;
				}
				// Placed or failed - either way, re-check the center fresh next tick (placed: it's now
				// a real block to mine; failed: nothing more to try here this pass).
				return true;
			}
			BlockPos corner = findSolidCorner(client.world, center, dir);
			if (corner == null) {
				// Nothing solid anywhere immediately around it either - a pocket bigger than the tool's
				// own reach in every direction. Walk it as open space rather than getting stuck.
				return true;
			}
			missingCenterCorner = corner;
			missingCenterIntermediate = intermediateTowardCenter(corner, center);
			missingCenterStuckTicks = 0;
			missingCenterStage = MissingCenterStage.PLACING_INTERMEDIATE;
		}

		if (missingCenterStage == MissingCenterStage.PLACING_INTERMEDIATE) {
			if (!ensureReach(client, missingCenterIntermediate)) {
				if (++missingCenterStuckTicks > MINING_STUCK_TICKS) {
					missingCenterStage = MissingCenterStage.IDLE;
					return true;
				}
				return false;
			}
			Direction side = directionBetween(missingCenterCorner, missingCenterIntermediate);
			PlaceResult result = placeBlockStep(client, missingCenterCorner, side);
			if (result == PlaceResult.IN_PROGRESS) {
				return false;
			}
			if (result == PlaceResult.FAILED) {
				missingCenterStage = MissingCenterStage.IDLE;
				return true;
			}
			missingCenterStage = MissingCenterStage.PLACING_CENTER;
			missingCenterStuckTicks = 0;
		}

		// PLACING_CENTER
		if (!ensureReach(client, center)) {
			if (++missingCenterStuckTicks > MINING_STUCK_TICKS) {
				missingCenterStage = MissingCenterStage.IDLE;
				return true;
			}
			return false;
		}
		Direction side = directionBetween(missingCenterIntermediate, center);
		PlaceResult result = placeBlockStep(client, missingCenterIntermediate, side);
		if (result == PlaceResult.IN_PROGRESS) {
			return false;
		}
		missingCenterStage = MissingCenterStage.IDLE;
		return true;
	}

	private static Direction findSolidFaceNeighbor(ClientWorld world, BlockPos center, Direction dir) {
		List<Direction> candidates = new ArrayList<>();
		// "Behind" the missing center from the player's own point of view means one step deeper into
		// the wall (continuing the direction being mined) - the block between the player and center
		// is guaranteed already open (that's where the player is standing), so it's not worth checking.
		candidates.add(dir);
		candidates.add(Direction.UP);
		candidates.add(Direction.DOWN);
		if (dir.getAxis() != Direction.Axis.Y) {
			candidates.add(rotateYClockwise(dir));
			candidates.add(rotateYCounterclockwise(dir));
		}
		for (Direction candidate : candidates) {
			if (!world.getBlockState(offset(center, candidate)).isAir()) {
				return candidate;
			}
		}
		return null;
	}

	/** The 4 diagonal corners of the 3x3 face (both in-plane axes offset by 1) - checked only once none of the direct face neighbors turned up solid. */
	private static BlockPos findSolidCorner(ClientWorld world, BlockPos center, Direction dir) {
		Direction a;
		Direction b;
		if (dir.getAxis() == Direction.Axis.Y) {
			a = Direction.EAST;
			b = Direction.SOUTH;
		} else if (dir.getAxis() == Direction.Axis.X) {
			a = Direction.UP;
			b = Direction.SOUTH;
		} else {
			a = Direction.UP;
			b = Direction.EAST;
		}
		int[][] signs = { { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } };
		for (int[] sign : signs) {
			BlockPos p = offsetBy(offsetBy(center, a, sign[0]), b, sign[1]);
			if (!world.getBlockState(p).isAir()) {
				return p;
			}
		}
		return null;
	}

	/** One of the two face-adjacent cells between a diagonal corner and the center - either choice works, this just always drops the X difference first if there is one. */
	private static BlockPos intermediateTowardCenter(BlockPos corner, BlockPos center) {
		int dx = corner.getX() - center.getX();
		int dy = corner.getY() - center.getY();
		if (dx != 0) {
			return new BlockPos(center.getX() + dx, center.getY(), center.getZ());
		}
		return new BlockPos(center.getX(), center.getY() + dy, center.getZ());
	}

	/** {@code from} and {@code to} must be exactly one axis-aligned step apart. */
	private static Direction directionBetween(BlockPos from, BlockPos to) {
		int dx = to.getX() - from.getX();
		int dy = to.getY() - from.getY();
		int dz = to.getZ() - from.getZ();
		if (dx > 0) {
			return Direction.EAST;
		}
		if (dx < 0) {
			return Direction.WEST;
		}
		if (dy > 0) {
			return Direction.UP;
		}
		if (dy < 0) {
			return Direction.DOWN;
		}
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/**
	 * True once {@code point} is within safe interaction range. Otherwise nudges the player one
	 * step closer - but only ever onto a cell whose floor is already confirmed solid and whose own
	 * space is open, so this can never be the thing that walks the player off an edge.
	 */
	private boolean ensureReach(MinecraftClient client, BlockPos point) {
		PlayerEntity player = client.player;
		Vec3d target = new Vec3d(point.getX() + 0.5, point.getY() + 0.5, point.getZ() + 0.5);
		if (eyeDistanceTo(player, target) <= NaturalMining.DEFAULT_MAX_REACH - 0.5) {
			setForward(client, false);
			return true;
		}
		BlockPos feet = feet(player);
		Direction nudge = lateralDirectionToward(feet, point);
		if (nudge == null || !canStepOnto(client.world, offset(feet, nudge))) {
			// Can't get any closer safely - attempt the placement from here anyway; if it's genuinely
			// out of range the interaction just silently does nothing and this times out via the
			// caller's own stuck counter instead of ever forcing an unsafe step.
			setForward(client, false);
			return true;
		}
		faceHeadingLevel(player, nudge);
		setForward(client, true);
		return false;
	}

	private static Direction lateralDirectionToward(BlockPos from, BlockPos to) {
		int dx = to.getX() - from.getX();
		int dz = to.getZ() - from.getZ();
		if (dx == 0 && dz == 0) {
			return null;
		}
		if (Math.abs(dx) >= Math.abs(dz)) {
			return dx > 0 ? Direction.EAST : Direction.WEST;
		}
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	private static boolean canStepOnto(ClientWorld world, BlockPos target) {
		return !world.getBlockState(down(target)).isAir() && world.getBlockState(target).isAir();
	}

	// ---- boundary sealing ----------------------------------------------------------------------

	private void sealBoundaryBreaches(MinecraftClient client, BlockPos cell) {
		if (!fillOuterGaps) {
			return;
		}
		for (Direction dir : Direction.values()) {
			BlockPos outside = offset(cell, dir);
			if (isInsideSelection(outside)) {
				continue;
			}
			if (client.world.getBlockState(outside).isAir()) {
				placeBlockStep(client, cell, dir);
			}
		}
	}

	private boolean isInsideSelection(BlockPos pos) {
		return pos.getX() >= minX && pos.getX() <= maxX
				&& pos.getY() >= minY && pos.getY() <= maxY
				&& pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}

	// ---- fluid plugging (bounded local search, not a real pathfinder) ------------------------

	private void pluginFluidStep(MinecraftClient client) {
		if (fluidSource == null) {
			fluidSource = findNearestFluidSource(client.world, fluidHazard, fluidScanRadius);
			if (fluidSource == null) {
				giveUpStuck(client, "found lava/water it couldn't safely cap nearby - pausing.");
				fluidHazard = null;
				return;
			}
		}
		Direction sealSide = findReachableSolidFace(client, fluidSource);
		if (sealSide == null) {
			if (++fluidStuckTicks > MINING_STUCK_TICKS) {
				giveUpStuck(client, "lava/water source out of safe reach - pausing.");
				fluidHazard = null;
				fluidSource = null;
			}
			return;
		}
		PlaceResult result = placeBlockStep(client, offset(fluidSource, sealSide), sealSide.getOpposite());
		if (result == PlaceResult.PLACED) {
			fluidHazard = null;
			fluidSource = null;
			phase = Phase.MOVING;
		} else if (result == PlaceResult.FAILED) {
			giveUpStuck(client, "no blocks left to cap lava/water - pausing.");
			fluidHazard = null;
			fluidSource = null;
		}
	}

	/** Nearest still/source fluid block of the same type as {@code from}, via a small bounded scan - not a real search, just checks the local neighborhood outward ring by ring up to {@code radius}. */
	private static BlockPos findNearestFluidSource(ClientWorld world, BlockPos from, int radius) {
		for (int r = 0; r <= radius; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dy = -r; dy <= r; dy++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) {
							continue;
						}
						BlockPos p = new BlockPos(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
						BlockState state = world.getBlockState(p);
						if (isHazardBlock(state) && isFluidSource(state)) {
							return immutable(p);
						}
					}
				}
			}
		}
		return null;
	}

	private static Direction findReachableSolidFace(MinecraftClient client, BlockPos source) {
		PlayerEntity player = client.player;
		for (Direction dir : Direction.values()) {
			BlockPos face = offset(source, dir);
			if (!client.world.getBlockState(face).isAir()) {
				continue;
			}
			double dist = eyeDistanceTo(player, new Vec3d(face.getX() + 0.5, face.getY() + 0.5, face.getZ() + 0.5));
			if (dist <= NaturalMining.DEFAULT_MAX_REACH) {
				return dir;
			}
		}
		return null;
	}

	private static double eyeDistanceTo(PlayerEntity player, Vec3d point) {
		double dx = point.x - player.getX();
		double dy = point.y - player.getEyeY();
		double dz = point.z - player.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// ---- selling ---------------------------------------------------------------------------

	private boolean checkInventoryFull(MinecraftClient client) {
		int empty = countEmptyMainInventorySlots(client.player);
		return empty <= sellEmptySlotThreshold;
	}

	private static int countEmptyMainInventorySlots(PlayerEntity player) {
		var inventory = player.getInventory();
		int empty = 0;
		//? if <26.1 {
		for (int i = 0; i < 36; i++) {
			if (inventory.getStack(i).isEmpty()) {
				empty++;
			}
		}
		//?} else {
		/*for (int i = 0; i < 36; i++) {
			if (inventory.getItem(i).isEmpty()) {
				empty++;
			}
		}
		*///?}
		return empty;
	}

	private void waitForShop(MinecraftClient client) {
		if (hasOpenScreenHandler(client.player)) {
			for (int slotIndex : sellableSlotIndexes(client.player)) {
				quickMoveSlot(client, client.player, slotIndex);
			}
			client.setScreen(null);
			phase = Phase.MOVING;
			return;
		}
		if (--shopWaitTicks <= 0) {
			phase = Phase.MOVING;
		}
	}

	/**
	 * Slots in the currently-open shop screen backed by the player's main inventory (never the
	 * hotbar - the axe/sword/one reserve stack of bridging blocks always live there and are never
	 * offered up) that hold a block item, skipping the very first block stack found so one full
	 * stack is always kept back as a reserve, matching "sell all but one stack."
	 */
	private List<Integer> sellableSlotIndexes(PlayerEntity player) {
		List<Integer> indexes = new ArrayList<>();
		var inventory = player.getInventory();
		boolean keptReserve = false;
		//? if <26.1 {
		for (var slot : player.currentScreenHandler.slots) {
			if (slot.inventory != inventory || slot.id < 9) {
				continue;
			}
			ItemStack stack = slot.getStack();
			if (!stack.isEmpty() && isBlockItem(stack)) {
				if (!keptReserve) {
					keptReserve = true;
					continue;
				}
				indexes.add(slot.id);
			}
		}
		//?} else {
		/*for (var slot : player.containerMenu.slots) {
			if (slot.container != inventory || slot.index < 9) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (!stack.isEmpty() && isBlockItem(stack)) {
				if (!keptReserve) {
					keptReserve = true;
					continue;
				}
				indexes.add(slot.index);
			}
		}
		*///?}
		return indexes;
	}

	private static void sendCommand(MinecraftClient client, String command) {
		var networkHandler = client.getNetworkHandler();
		if (networkHandler != null) {
			networkHandler.sendChatCommand(command.startsWith("/") ? command.substring(1) : command);
		}
	}

	// ---- tool management ---------------------------------------------------------------------

	private boolean ensureTool(MinecraftClient client) {
		if (!requireIronAxe) {
			return true;
		}
		return ensureAxe(client);
	}

	private boolean ensureAxe(MinecraftClient client) {
		int slot = findHotbarSlot(client.player, Items.IRON_AXE);
		if (slot < 0) {
			giveUpStuck(client, "no iron axe in the hotbar - pausing.");
			return false;
		}
		return selectToolSlot(client, slot, false);
	}

	private boolean ensureSword(MinecraftClient client) {
		int slot = findHotbarSword(client.player);
		if (slot < 0) {
			giveUpStuck(client, "no sword in the hotbar for a cobweb - pausing.");
			return false;
		}
		return selectToolSlot(client, slot, true);
	}

	/**
	 * Switching the selected hotbar slot cancels whatever item use is currently active - the exact
	 * same thing {@code EnderChestFarmerModule#placeBlockStep} already avoids doing to {@code
	 * AutoEatModule}'s bite mid-chew. This module re-asserts its own tool slot every single tick
	 * it's mining, so without this same guard it would cancel an eat the instant it started, every
	 * time - waits it out instead of forcing the switch.
	 */
	private boolean selectToolSlot(MinecraftClient client, int slot, boolean sword) {
		PlayerEntity player = client.player;
		if (player.isUsingItem()) {
			return false;
		}
		var inventory = player.getInventory();
		if (selectedSlot(inventory) != slot) {
			setSelectedSlot(inventory, slot);
		}
		usingSword = sword;
		return true;
	}

	private static int findHotbarSlot(PlayerEntity player, net.minecraft.item.Item item) {
		var inventory = player.getInventory();
		//? if <26.1 {
		for (int i = 0; i < 9; i++) {
			if (inventory.getStack(i).getItem() == item) {
				return i;
			}
		}
		//?} else {
		/*for (int i = 0; i < 9; i++) {
			if (inventory.getItem(i).getItem() == item) {
				return i;
			}
		}
		*///?}
		return -1;
	}

	private static int findHotbarSword(PlayerEntity player) {
		var inventory = player.getInventory();
		//? if <26.1 {
		for (int i = 0; i < 9; i++) {
			if (isSword(inventory.getStack(i))) {
				return i;
			}
		}
		//?} else {
		/*for (int i = 0; i < 9; i++) {
			if (isSword(inventory.getItem(i))) {
				return i;
			}
		}
		*///?}
		return -1;
	}

	private static boolean isSword(ItemStack stack) {
		var item = stack.getItem();
		return item == Items.WOODEN_SWORD || item == Items.STONE_SWORD || item == Items.IRON_SWORD
				|| item == Items.GOLDEN_SWORD || item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD;
	}

	// ---- block placement (bridging / sealing / fluid capping) --------------------------------

	private enum PlaceResult { PLACED, IN_PROGRESS, FAILED }

	/** Right-clicks the given face of {@code against} to place a block there - same turn-then-click-then-cooldown shape as {@code EnderChestFarmerModule#placeBlockStep}. */
	private PlaceResult placeBlockStep(MinecraftClient client, BlockPos against, Direction side) {
		PlayerEntity player = client.player;
		if (player.isUsingItem()) {
			return PlaceResult.IN_PROGRESS;
		}
		if (placeState == PlaceState.COOLDOWN) {
			if (--placeCooldownTicks > 0) {
				return PlaceResult.IN_PROGRESS;
			}
			placeState = PlaceState.IDLE;
		}
		if (placeState == PlaceState.IDLE || placeHit == null || !against.equals(placeHit.getBlockPos())
				|| placeSide != side) {
			if (findPlaceableHotbarSlot(player) < 0) {
				return PlaceResult.FAILED;
			}
			placeSide = side;
			Vec3d hitPos = new Vec3d(against.getX() + 0.5 + stepX(side) * 0.5, against.getY() + 0.5 + stepY(side) * 0.5,
					against.getZ() + 0.5 + stepZ(side) * 0.5);
			placeHit = new BlockHitResult(hitPos, side, against, false);
			placeAimPoint = hitPos;
			placeResultPos = offset(against, side);
			placeState = PlaceState.TURNING;
		}

		if (!client.world.getBlockState(placeResultPos).isAir()) {
			placeState = PlaceState.IDLE;
			return PlaceResult.PLACED;
		}

		NaturalMining.aimAt(player, placeAimPoint, maxTurnDegreesPerTick);
		if (!NaturalMining.isFacing(player, placeAimPoint, BRIDGE_AIM_TOLERANCE_DEGREES)) {
			return PlaceResult.IN_PROGRESS;
		}

		int slot = findPlaceableHotbarSlot(player);
		if (slot < 0) {
			placeState = PlaceState.IDLE;
			return PlaceResult.FAILED;
		}
		var inventory = player.getInventory();
		int previousSlot = selectedSlot(inventory);
		setSelectedSlot(inventory, slot);
		interactBlock(client, placeHit);
		setSelectedSlot(inventory, previousSlot);
		placeState = PlaceState.COOLDOWN;
		placeCooldownTicks = BRIDGE_COOLDOWN_MIN_TICKS + RANDOM.nextInt(BRIDGE_COOLDOWN_MAX_TICKS - BRIDGE_COOLDOWN_MIN_TICKS + 1);
		return client.world.getBlockState(placeResultPos).isAir() ? PlaceResult.IN_PROGRESS : PlaceResult.PLACED;
	}

	/** Any block item outside the hotbar's first slot - the iron axe/sword always sit in dedicated slots found by item identity, never picked here since neither is a {@code BlockItem}. */
	private static int findPlaceableHotbarSlot(PlayerEntity player) {
		var inventory = player.getInventory();
		//? if <26.1 {
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inventory.getStack(i);
			//?} else {
			/*for (int i = 0; i < 9; i++) {
			ItemStack stack = inventory.getItem(i);
			*///?}
			if (!stack.isEmpty() && isBlockItem(stack)) {
				return i;
			}
		}
		return -1;
	}

	private static boolean isBlockItem(ItemStack stack) {
		//? if <26.1 {
		return stack.getItem() instanceof net.minecraft.item.BlockItem;
		//?} else {
		/*return stack.getItem() instanceof net.minecraft.world.item.BlockItem;
		*///?}
	}

	// ---- misc helpers ------------------------------------------------------------------------

	private void giveUpStuck(MinecraftClient client, String message) {
		miner.stop(client);
		setForward(client, false);
		active = false;
		notify(client, message);
	}

	private void releaseAll(MinecraftClient client) {
		setForward(client, false);
		setJump(client, false);
		miner.stop(client);
		placeState = PlaceState.IDLE;
		fluidHazard = null;
		fluidSource = null;
		missingCenterStage = MissingCenterStage.IDLE;
	}

	private static BlockPos feet(PlayerEntity player) {
		return new BlockPos(player.getBlockX(), player.getBlockY(), player.getBlockZ());
	}

	private void faceHeadingLevel(PlayerEntity player, Direction heading) {
		Vec3d level = new Vec3d(player.getX() + stepX(heading), player.getEyeY(), player.getZ() + stepZ(heading));
		NaturalMining.aimAt(player, level, maxTurnDegreesPerTick);
	}

	private void faceDown(PlayerEntity player) {
		Vec3d down = new Vec3d(player.getX(), player.getEyeY() - 2, player.getZ());
		NaturalMining.aimAt(player, down, maxTurnDegreesPerTick);
	}

	private void notify(MinecraftClient client, String message) {
		//? if <26.1 {
		client.player.sendMessage(net.minecraft.text.Text.literal("Auto Excavator: " + message), true);
		//?} else {
		/*client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Auto Excavator: " + message));
		*///?}
	}

	private void onRender(WorldRenderContext context) {
		if (cornerA != null && cornerB != null) {
			GizmoDrawing.box(selectionBox(), DrawStyle.stroked(0xFF00AAFF, 2f)).ignoreOcclusion();
		}
		if (isEnabled() && running && centerTarget != null && phase == Phase.MOVING) {
			GizmoDrawing.box(boxOf(centerTarget), DrawStyle.stroked(0xFFFF8800, 2.5f)).ignoreOcclusion();
		}
	}

	private net.minecraft.util.math.Box selectionBox() {
		int lx = Math.min(cornerA.getX(), cornerB.getX());
		int ly = Math.min(cornerA.getY(), cornerB.getY());
		int lz = Math.min(cornerA.getZ(), cornerB.getZ());
		int hx = Math.max(cornerA.getX(), cornerB.getX()) + 1;
		int hy = Math.max(cornerA.getY(), cornerB.getY()) + 1;
		int hz = Math.max(cornerA.getZ(), cornerB.getZ()) + 1;
		return new net.minecraft.util.math.Box(lx, ly, lz, hx, hy, hz);
	}

	private static net.minecraft.util.math.Box boxOf(BlockPos pos) {
		return new net.minecraft.util.math.Box(pos.getX(), pos.getY(), pos.getZ(),
				pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
	}

	// ---- version-compat shims (same rename pattern as the sibling modules in this package) ----

	private static boolean isHazardBlock(BlockState state) {
		var block = state.getBlock();
		//? if <26.1 {
		return block == net.minecraft.block.Blocks.LAVA || block == net.minecraft.block.Blocks.WATER;
		//?} else {
		/*return block == net.minecraft.world.level.block.Blocks.LAVA || block == net.minecraft.world.level.block.Blocks.WATER;
		*///?}
	}

	private static Object cobwebBlock() {
		//? if <26.1 {
		return net.minecraft.block.Blocks.COBWEB;
		//?} else {
		/*return net.minecraft.world.level.block.Blocks.COBWEB;
		*///?}
	}

	private static boolean isFluidSource(BlockState state) {
		//? if <26.1 {
		return state.getFluidState().isStill();
		//?} else {
		/*return state.getFluidState().isSource();
		*///?}
	}

	private static void interactBlock(MinecraftClient client, BlockHitResult hit) {
		//? if <26.1 {
		client.interactionManager.interactBlock(client.player, net.minecraft.util.Hand.MAIN_HAND, hit);
		//?} else {
		/*client.gameMode.useItemOn(client.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
		*///?}
	}

	private static boolean hasOpenScreenHandler(PlayerEntity player) {
		//? if <26.1 {
		return player.currentScreenHandler != player.playerScreenHandler;
		//?} else {
		/*return player.containerMenu != player.inventoryMenu;
		*///?}
	}

	private static void quickMoveSlot(MinecraftClient client, PlayerEntity player, int slotIndex) {
		//? if <26.1 {
		client.interactionManager.clickSlot(player.currentScreenHandler.syncId, slotIndex, 0,
				net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
		//?} else {
		/*client.gameMode.handleContainerInput(player.containerMenu.containerId, slotIndex, 0,
				net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player);
		*///?}
	}

	private static int selectedSlot(Object inventory) {
		//? if <26.1 {
		return ((net.minecraft.entity.player.PlayerInventory) inventory).getSelectedSlot();
		//?} else {
		/*return ((net.minecraft.world.entity.player.Inventory) inventory).getSelectedSlot();
		*///?}
	}

	private static void setSelectedSlot(Object inventory, int slot) {
		//? if <26.1 {
		((net.minecraft.entity.player.PlayerInventory) inventory).setSelectedSlot(slot);
		//?} else {
		/*((net.minecraft.world.entity.player.Inventory) inventory).setSelectedSlot(slot);
		*///?}
	}

	private static BlockPos offset(BlockPos pos, Direction direction) {
		//? if <26.1 {
		return pos.offset(direction);
		//?} else {
		/*return pos.relative(direction);
		*///?}
	}

	private static BlockPos offsetBy(BlockPos pos, Direction direction, int distance) {
		return new BlockPos(pos.getX() + stepX(direction) * distance, pos.getY() + stepY(direction) * distance,
				pos.getZ() + stepZ(direction) * distance);
	}

	private static BlockPos down(BlockPos pos) {
		//? if <26.1 {
		return pos.down();
		//?} else {
		/*return pos.below();
		*///?}
	}

	private static BlockPos immutable(BlockPos pos) {
		//? if <26.1 {
		return pos.toImmutable();
		//?} else {
		/*return pos.immutable();
		*///?}
	}

	private static int stepX(Direction direction) {
		//? if <26.1 {
		return direction.getOffsetX();
		//?} else {
		/*return direction.getStepX();
		*///?}
	}

	private static int stepY(Direction direction) {
		//? if <26.1 {
		return direction.getOffsetY();
		//?} else {
		/*return direction.getStepY();
		*///?}
	}

	private static int stepZ(Direction direction) {
		//? if <26.1 {
		return direction.getOffsetZ();
		//?} else {
		/*return direction.getStepZ();
		*///?}
	}

	private static Direction rotateYClockwise(Direction direction) {
		//? if <26.1 {
		return direction.rotateYClockwise();
		//?} else {
		/*return direction.getClockWise();
		*///?}
	}

	private static Direction rotateYCounterclockwise(Direction direction) {
		//? if <26.1 {
		return direction.rotateYCounterclockwise();
		//?} else {
		/*return direction.getCounterClockWise();
		*///?}
	}

	private static void setForward(MinecraftClient client, boolean pressed) {
		//? if <26.1 {
		client.options.forwardKey.setPressed(pressed);
		//?} else {
		/*client.options.keyUp.setDown(pressed);
		*///?}
	}

	private static void setJump(MinecraftClient client, boolean pressed) {
		//? if <26.1 {
		client.options.jumpKey.setPressed(pressed);
		//?} else {
		/*client.options.keyJump.setDown(pressed);
		*///?}
	}

	private static boolean isOnGround(PlayerEntity player) {
		//? if <26.1 {
		return player.isOnGround();
		//?} else {
		/*return player.onGround();
		*///?}
	}

	private static boolean isSingleplayer(MinecraftClient client) {
		return true;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Select Corner A (looked-at block)", CORNER_A_KEY),
				KeybindConfig.field("Select Corner B (looked-at block)", CORNER_B_KEY),
				KeybindConfig.field("Clear Selection", CLEAR_SELECTION_KEY),
				KeybindConfig.field("Start/Pause Mining", START_PAUSE_KEY),
				new ConfigField.ToggleField("Require Iron Axe", () -> requireIronAxe, v -> requireIronAxe = v),
				new ConfigField.ToggleField("Mine Cobwebs With Sword", () -> mineCobwebsWithSword, v -> mineCobwebsWithSword = v),
				new ConfigField.ToggleField("Seal Outer Boundary Gaps", () -> fillOuterGaps, v -> fillOuterGaps = v),
				new ConfigField.ToggleField("Auto-Plug Nearby Lava/Water", () -> autoPlugFluids, v -> autoPlugFluids = v),
				new ConfigField.SliderField("Fluid Source Search Radius (blocks)", 2, 8,
						() -> fluidScanRadius, v -> fluidScanRadius = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.TextField("Sell Command (no leading /)", "sell", () -> sellCommand, v -> sellCommand = v),
				new ConfigField.SliderField("Sell When Empty Slots At Or Below", 0, 6,
						() -> sellEmptySlotThreshold, v -> sellEmptySlotThreshold = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Aim Jitter Radius (blocks)", 0.0, 0.4,
						() -> aimJitterRadius, v -> aimJitterRadius = v, v -> String.format("%.2f", v)),
				new ConfigField.SliderField("Max Turn Speed (deg/tick)", 5, 90,
						() -> maxTurnDegreesPerTick, v -> maxTurnDegreesPerTick = (float) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Max Stuck Ticks Before Pausing", 20, 400,
						() -> maxStuckTicks, v -> maxStuckTicks = (int) v, v -> String.valueOf((int) v)));
	}
}
