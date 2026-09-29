package net.veloclient.velo.client.modules.servertools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
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
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Player-vs-AI ender chest farming for your own singleplayer project: {@code /rtp}, scan the
 * freshly-loaded area for ender chests the same way {@link StorageFinderModule} would, walk to
 * each one (mining through solid terrain in the way, hopping 1-block ledges, backing off from
 * lava instead of walking into it), break it, then once every found chest is gone {@code /rtp}
 * again and repeat. Once the ender chests piling up in your inventory hit the configured stack
 * size, it runs {@code /sell}, shift-clicks every one of them from your inventory into whatever
 * screen that opens, closes it, and keeps going.
 *
 * <p>Point-to-point walking here, not a real pathfinder - it steers straight at the target,
 * breaking/hopping whatever's directly in its own path, and simply gives up on a chest (skips it,
 * moves to the next one) if it gets stuck trying to reach it rather than looping forever. Fall
 * hazards (walking off a cliff mid-approach) aren't specifically detected - this is built for the
 * kind of open, walkable terrain a fresh {@code /rtp} usually drops you into, not deep ravines.
 * Singleplayer only, same reasoning as {@link AutoTunnelMinerModule}.
 */
public final class EnderChestFarmerModule extends AbstractModule implements Configurable {

	public static final KeyBinding TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.ender-chest-farmer-toggle", InputUtil.Type.KEYSYM,
			org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));

	private enum Phase { WAITING_AFTER_RTP, SCANNING, NAVIGATING, MINING_OBSTRUCTION, MINING_TARGET, COLLECTING, SELLING, WAITING_FOR_SHOP }

	private static final Random RANDOM = new Random();

	// A real sprint-jump clears roughly a 3-block gap in vanilla - but only while actually
	// sprinting. A standing/walking jump only clears about 1, so a gap wider than that needs
	// sprint engaged (with a short run-up to actually build the speed) before it's safe to jump,
	// or an actual bridge block if it's wider than even that.
	private static final int MAX_JUMPABLE_GAP_SPRINTING = 3;
	private static final int MAX_JUMPABLE_GAP_WALKING = 1;
	private static final int SPRINT_RUNUP_TICKS = 6;
	private static final int MAX_GAP_SCAN = 6;
	private static final int JUMP_ACROSS_TICKS = 20;
	private static final float BRIDGE_AIM_TOLERANCE_DEGREES = 3f;
	private static final int BRIDGE_COOLDOWN_MIN_TICKS = 5;
	private static final int BRIDGE_COOLDOWN_MAX_TICKS = 10;

	private String rtpCommand = "rtp";
	private String sellCommand = "sell";
	private int scanRadius = 48;
	private int waitAfterRtpTicks = 60;
	private int sellThreshold = 64;
	private boolean mineObstructions = true;
	private double aimJitterRadius = NaturalMining.DEFAULT_AIM_JITTER_RADIUS;
	private float maxTurnDegreesPerTick = NaturalMining.DEFAULT_MAX_TURN_DEGREES_PER_TICK;

	private boolean running;
	private Phase phase = Phase.WAITING_AFTER_RTP;
	private int waitTicks;
	private List<BlockPos> targets = List.of();
	private BlockPos currentTarget;
	private BlockPos obstructionTarget;
	private final NaturalMining.Session miner = new NaturalMining.Session();
	private int miningStuckTicks;
	private int stuckTicks;
	private int shopWaitTicks;
	private int collectTicks;
	private int jumpAcrossTicksRemaining;
	private int sprintRunupTicks;
	private BlockPos pillarBase;
	private enum PlaceState { IDLE, TURNING, COOLDOWN }
	private PlaceState placeState = PlaceState.IDLE;
	private Vec3d placeAimPoint;
	private BlockHitResult placeHit;
	private BlockPos placeResultPos;
	private int placeCooldownTicks;

	public EnderChestFarmerModule() {
		super("ender-chest-farmer", "Ender Chest Farmer",
				"RTPs, scans the area for ender chests, walks/mines/hops to each one and breaks it, then "
						+ "RTPs again and repeats - once you're carrying a full stack of ender chests it runs your "
						+ "sell command and shift-clicks them all in for you first. Singleplayer only.",
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

	/** An automated farmer should never silently resume RTPing/walking/mining/selling just because a saved profile says it was on last time - always requires an explicit re-enable each session. */
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
			startCycle(client);
			running = true;
		}
		step(client);
	}

	private void startCycle(MinecraftClient client) {
		miner.stop(client);
		sendCommand(client, rtpCommand);
		phase = Phase.WAITING_AFTER_RTP;
		waitTicks = waitAfterRtpTicks;
		targets = List.of();
		currentTarget = null;
		stuckTicks = 0;
	}

	private void step(MinecraftClient client) {
		switch (phase) {
			case WAITING_AFTER_RTP -> {
				if (--waitTicks <= 0) {
					targets = scanEnderChests(client);
					phase = Phase.SCANNING;
				}
			}
			case SCANNING -> {
				if (targets.isEmpty()) {
					startCycle(client);
				} else {
					currentTarget = nearest(client.player, targets);
					stuckTicks = 0;
					phase = Phase.NAVIGATING;
				}
			}
			case NAVIGATING -> navigate(client);
			case MINING_OBSTRUCTION -> mineObstruction(client);
			case MINING_TARGET -> mineTarget(client);
			case COLLECTING -> collect(client);
			case SELLING -> {
				if (--waitTicks <= 0) {
					sendCommand(client, sellCommand);
					shopWaitTicks = 100;
					phase = Phase.WAITING_FOR_SHOP;
				}
			}
			case WAITING_FOR_SHOP -> waitForShop(client);
		}
	}

	/** {@code MinecraftClient#getNetworkHandler()#sendChatCommand} - takes the command without a leading slash, identical across every supported version. */
	private static void sendCommand(MinecraftClient client, String command) {
		var networkHandler = client.getNetworkHandler();
		if (networkHandler != null) {
			networkHandler.sendChatCommand(command.startsWith("/") ? command.substring(1) : command);
		}
	}

	private List<BlockPos> scanEnderChests(MinecraftClient client) {
		ClientWorld world = client.world;
		PlayerEntity player = client.player;
		int centerX = player.getBlockX() >> 4;
		int centerZ = player.getBlockZ() >> 4;
		int chunkRadius = Math.max(1, (int) Math.ceil(scanRadius / 16.0));
		List<BlockPos> found = new ArrayList<>();
		for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
			for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
				int chunkX = centerX + dx;
				int chunkZ = centerZ + dz;
				if (!world.isChunkLoaded(chunkX, chunkZ)) {
					continue;
				}
				for (BlockPos pos : world.getChunk(chunkX, chunkZ).getBlockEntities().keySet()) {
					if (world.getBlockState(pos).getBlock() instanceof EnderChestBlock
							&& player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= (double) scanRadius * scanRadius) {
						found.add(immutable(pos));
					}
				}
			}
		}
		return found;
	}

	private static BlockPos nearest(PlayerEntity player, List<BlockPos> positions) {
		return positions.stream()
				.min(Comparator.comparingDouble(p -> player.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
				.orElse(null);
	}

	private void navigate(MinecraftClient client) {
		if (currentTarget == null || client.world.getBlockState(currentTarget).isAir()) {
			// Already gone (broken, or never really there) - drop it and move on.
			advanceTargetList(client);
			return;
		}
		PlayerEntity player = client.player;
		Vec3d targetCenter = centerOf(currentTarget);
		double distance = entityPos(player).distanceTo(targetCenter);
		if (distance <= 3.5) {
			setForward(client, false);
			NaturalMining.aimAt(player, targetCenter, maxTurnDegreesPerTick);
			miningStuckTicks = 0;
			phase = Phase.MINING_TARGET;
			return;
		}
		if (++stuckTicks > 400) {
			// Been trying to reach this one chest for 20s straight - give up on it, not the whole run.
			setForward(client, false);
			setJump(client, false);
			advanceTargetList(client);
			return;
		}

		double dx = targetCenter.x - player.getX();
		double dz = targetCenter.z - player.getZ();
		double horizontalLength = Math.sqrt(dx * dx + dz * dz);
		double dy = currentTarget.getY() - player.getY();

		// Nearly directly above or below the chest with a real vertical gap left - steering by
		// horizontal direction alone is numerically degenerate here (atan2 on a near-zero vector
		// spins the facing direction essentially at random tick to tick, which is exactly what was
		// causing it to spin in place instead of closing the actual gap). Handle the vertical
		// approach on its own instead of folding it into the horizontal-walk logic below.
		if (horizontalLength < 1.0 && Math.abs(dy) > 1.5) {
			if (dy < 0) {
				digDown(client, player);
			} else {
				buildUp(client, player);
			}
			return;
		}

		if (horizontalLength < 0.001) {
			horizontalLength = 1;
		}
		double dirX = dx / horizontalLength;
		double dirZ = dz / horizontalLength;
		NaturalMining.aimAt(player, new Vec3d(player.getX() + dirX, player.getEyeY(), player.getZ() + dirZ), maxTurnDegreesPerTick);

		BlockPos feet = new BlockPos(player.getBlockX(), player.getBlockY(), player.getBlockZ());
		BlockPos aheadFoot = new BlockPos((int) Math.floor(player.getX() + dirX * 0.8), feet.getY(),
				(int) Math.floor(player.getZ() + dirZ * 0.8));
		BlockPos aheadHead = up(aheadFoot);

		if (isGap(client.world, aheadFoot)) {
			handleGap(client, feet, dirX, dirZ);
			return;
		}

		BlockState aheadFootState = client.world.getBlockState(aheadFoot);
		if (isHazardBlock(aheadFootState)) {
			// Lava (or, cautiously, water) directly ahead - don't walk into it, give up on this
			// chest and try the next one rather than risking it.
			setForward(client, false);
			advanceTargetList(client);
			return;
		}

		boolean footBlocked = !isPassable(aheadFootState, client.world, aheadFoot) && aheadFoot.getY() < feet.getY() + 2;
		boolean headBlocked = !isPassable(client.world.getBlockState(aheadHead), client.world, aheadHead);
		if (footBlocked && !headBlocked) {
			// A single-block step - hop it rather than mining it away.
			setJump(client, true);
			setForward(client, true);
			return;
		}
		setJump(client, false);
		if ((footBlocked || headBlocked) && mineObstructions) {
			setForward(client, false);
			obstructionTarget = headBlocked && !aheadFootState.isAir() ? aheadHead
					: headBlocked ? aheadHead : aheadFoot;
			miningStuckTicks = 0;
			phase = Phase.MINING_OBSTRUCTION;
			return;
		}
		if (footBlocked || headBlocked) {
			// Blocked and not allowed to mine through it - same "give up on this one" fallback.
			setForward(client, false);
			advanceTargetList(client);
			return;
		}
		setForward(client, true);
	}

	/** Once an obstruction/target break has made no progress at all (obstructed, or out of the natural reach band) for this long, give up on it rather than waiting forever - roughly the same 5-10s budget the rest of this module already gives up stuck situations after. */
	private static final int MINING_STUCK_TICKS = 100;

	private void mineObstruction(MinecraftClient client) {
		if (obstructionTarget == null) {
			miner.stop(client);
			phase = Phase.NAVIGATING;
			return;
		}
		PlayerEntity player = client.player;
		Direction side = sideFacingPlayer(obstructionTarget, player);
		miner.configure(NaturalMining.DEFAULT_MIN_REACH, NaturalMining.DEFAULT_MAX_REACH, aimJitterRadius, maxTurnDegreesPerTick);
		NaturalMining.Result result = miner.tick(client, player, obstructionTarget, side);
		if (result == NaturalMining.Result.NO_TARGET) {
			phase = Phase.NAVIGATING;
			return;
		}
		if (result == NaturalMining.Result.PROGRESSED) {
			miningStuckTicks = 0;
		} else if (++miningStuckTicks > MINING_STUCK_TICKS) {
			// Can't reach/see this obstruction anymore - give up on it and let navigate() re-evaluate
			// what's actually in the way now, rather than sitting here forever.
			miner.stop(client);
			phase = Phase.NAVIGATING;
		}
	}

	private void mineTarget(MinecraftClient client) {
		if (currentTarget == null) {
			miner.stop(client);
			beginCollect(client);
			return;
		}
		PlayerEntity player = client.player;
		Direction side = sideFacingPlayer(currentTarget, player);
		miner.configure(NaturalMining.DEFAULT_MIN_REACH, NaturalMining.DEFAULT_MAX_REACH, aimJitterRadius, maxTurnDegreesPerTick);
		NaturalMining.Result result = miner.tick(client, player, currentTarget, side);
		if (result == NaturalMining.Result.NO_TARGET) {
			beginCollect(client);
			return;
		}
		if (result == NaturalMining.Result.PROGRESSED) {
			miningStuckTicks = 0;
		} else if (++miningStuckTicks > MINING_STUCK_TICKS) {
			// Can't reach/see this chest anymore - give up on it, not the whole run, matching the
			// same "skip it and move on" fallback navigate() itself uses.
			miner.stop(client);
			advanceTargetList(client);
		}
	}

	/**
	 * The dropped ender chest item spawns right where the block was, which can easily be outside
	 * normal item-pickup range from wherever the player was standing to mine it (reach is up to
	 * 4.5 blocks) - without this, the bot would just walk off toward the next target and leave the
	 * drop sitting on the ground. Walks straight into the old block's spot (nothing solid there
	 * anymore) and lets vanilla's own pickup-on-collision handle the rest.
	 */
	private void beginCollect(MinecraftClient client) {
		collectTicks = 0;
		phase = Phase.COLLECTING;
		if (currentTarget != null) {
			NaturalMining.aimAt(client.player, centerOf(currentTarget), maxTurnDegreesPerTick);
		}
		setForward(client, true);
	}

	private void collect(MinecraftClient client) {
		PlayerEntity player = client.player;
		if (currentTarget != null && ++collectTicks <= 30) {
			double distance = entityPos(player).distanceTo(centerOf(currentTarget));
			if (distance > 1.0) {
				NaturalMining.aimAt(player, centerOf(currentTarget), maxTurnDegreesPerTick);
				return;
			}
		}
		setForward(client, false);
		advanceTargetList(client);
	}

	private void advanceTargetList(MinecraftClient client) {
		if (currentTarget != null) {
			List<BlockPos> remaining = new ArrayList<>(targets);
			remaining.remove(currentTarget);
			targets = remaining;
		}
		currentTarget = null;
		stuckTicks = 0;
		if (targets.isEmpty()) {
			if (countEnderChests(client.player) >= sellThreshold) {
				waitTicks = 5;
				phase = Phase.SELLING;
			} else {
				startCycle(client);
			}
			return;
		}
		currentTarget = nearest(client.player, targets);
		phase = Phase.NAVIGATING;
	}

	private void waitForShop(MinecraftClient client) {
		if (hasOpenScreenHandler(client.player)) {
			for (int slotIndex : enderChestSlotIndexesInInventory(client.player)) {
				quickMoveSlot(client, client.player, slotIndex);
			}
			client.setScreen(null);
			startCycle(client);
			return;
		}
		if (--shopWaitTicks <= 0) {
			// The sell command never opened anything (wrong command, or nothing left to sell) -
			// don't wait forever, just continue the farming loop.
			startCycle(client);
		}
	}

	/** {@code Inventory#size()/getStack(int)} (Yarn) -> {@code Container#getContainerSize()/getItem(int)} (Mojmap) - same indexed access, diverges by name only. */
	private static int countEnderChests(PlayerEntity player) {
		var inventory = player.getInventory();
		int count = 0;
		//? if <26.1 {
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack stack = inventory.getStack(i);
			//?} else {
			/*for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			*///?}
			if (stack.getItem() == Items.ENDER_CHEST) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static Direction sideFacingPlayer(BlockPos target, PlayerEntity player) {
		double dx = player.getX() - (target.getX() + 0.5);
		double dz = player.getZ() - (target.getZ() + 0.5);
		if (Math.abs(dx) > Math.abs(dz)) {
			return dx > 0 ? Direction.EAST : Direction.WEST;
		}
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/** {@code Entity#getEntityPos()} (Yarn) -> {@code Entity#position()} (Mojmap) - same real, continuous position, diverges by name only. */
	private static Vec3d entityPos(PlayerEntity player) {
		//? if <26.1 {
		return player.getEntityPos();
		//?} else {
		/*return player.position();
		*///?}
	}

	private static Vec3d centerOf(BlockPos pos) {
		return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	private void releaseAll(MinecraftClient client) {
		setForward(client, false);
		setJump(client, false);
		setSprinting(client, false);
		miner.stop(client);
		placeState = PlaceState.IDLE;
		sprintRunupTicks = 0;
		pillarBase = null;
	}

	/** Nothing actually blocking movement here (air, or a real block with no collision shape - tall grass, a single snow layer, vines, ...) - walking through it is fine, no need to mine it away first. */
	private static boolean isPassable(BlockState state, ClientWorld world, BlockPos pos) {
		return state.isAir() || state.getCollisionShape(world, pos).isEmpty();
	}

	private static boolean isPassable(ClientWorld world, BlockPos pos) {
		return isPassable(world.getBlockState(pos), world, pos);
	}

	/**
	 * A real fall hazard - not just a normal 1-block step down (vanilla walks off a single-block
	 * ledge with no fall damage risk worth worrying about), but nothing solid within 2 blocks
	 * below the very next step. Without this, the bot would happily walk straight off a cliff
	 * chasing a chest it can see on the other side.
	 */
	private static boolean isGap(ClientWorld world, BlockPos aheadFoot) {
		if (!isPassable(world, aheadFoot)) {
			return false;
		}
		for (int dy = 1; dy <= 2; dy++) {
			BlockPos below = new BlockPos(aheadFoot.getX(), aheadFoot.getY() - dy, aheadFoot.getZ());
			if (!isPassable(world, below)) {
				return false;
			}
		}
		return true;
	}

	/** How many blocks ahead (along the direction of travel, at the current floor height) solid ground resumes - {@code -1} if it doesn't within {@link #MAX_GAP_SCAN}. */
	private static int measureGapWidth(ClientWorld world, BlockPos feet, double dirX, double dirZ) {
		for (int i = 1; i <= MAX_GAP_SCAN; i++) {
			int x = (int) Math.floor(feet.getX() + 0.5 + dirX * i);
			int z = (int) Math.floor(feet.getZ() + 0.5 + dirZ * i);
			BlockPos floorPos = new BlockPos(x, feet.getY() - 1, z);
			if (!isPassable(world, floorPos)) {
				return i;
			}
		}
		return -1;
	}

	/** Jumps a gap narrow enough to safely clear, otherwise bridges it with a real block; gives up on this chest (not the whole run) only if neither is possible - never just walks forward and falls in. */
	private void handleGap(MinecraftClient client, BlockPos feet, double dirX, double dirZ) {
		int gapWidth = measureGapWidth(client.world, feet, dirX, dirZ);
		if (gapWidth > 0 && gapWidth <= MAX_JUMPABLE_GAP_WALKING) {
			// Small enough to clear from a standstill - no need to sprint first.
			sprintRunupTicks = 0;
			setSprinting(client, false);
			jumpAcrossTicksRemaining = JUMP_ACROSS_TICKS;
			setJump(client, true);
			setForward(client, true);
			return;
		}
		if (gapWidth > MAX_JUMPABLE_GAP_WALKING && gapWidth <= MAX_JUMPABLE_GAP_SPRINTING) {
			// A gap this wide only clears with a real sprint jump - a player can't do that from a
			// standing start, they need a few steps of actual sprinting first to build up speed.
			setSprinting(client, true);
			if (!client.player.isSprinting() || sprintRunupTicks < SPRINT_RUNUP_TICKS) {
				sprintRunupTicks++;
				setForward(client, true);
				setJump(client, false);
				return;
			}
			sprintRunupTicks = 0;
			jumpAcrossTicksRemaining = JUMP_ACROSS_TICKS;
			setJump(client, true);
			setForward(client, true);
			return;
		}
		sprintRunupTicks = 0;
		if (jumpAcrossTicksRemaining > 0) {
			// Mid-jump already committed to clearing this gap - keep holding through it rather
			// than switching to bridging halfway across.
			jumpAcrossTicksRemaining--;
			setJump(client, true);
			setForward(client, true);
			return;
		}
		setJump(client, false);
		setSprinting(client, false);
		Direction side = nearestCardinal(dirX, dirZ);
		PlaceResult result = placeBlockStep(client, down(feet), side);
		switch (result) {
			case PLACED -> setForward(client, false);
			case IN_PROGRESS ->
					// Still turning to face the block being clicked, on cooldown after an attempt, or
					// waiting out an active item use (eating, etc.) - hold still rather than blindly
					// walking toward the gap in the meantime.
					setForward(client, false);
			case FAILED -> {
				// Too wide to jump and nothing placeable to bridge with - can't safely reach this chest.
				setForward(client, false);
				advanceTargetList(client);
			}
		}
	}

	/**
	 * Chest is well below the player and roughly straight down - mine down toward it one block at
	 * a time (checking for lava/water immediately below, and one further below that, before ever
	 * breaking into it) instead of trying and failing to approach it horizontally with nothing to
	 * actually walk on.
	 */
	private void digDown(MinecraftClient client, PlayerEntity player) {
		setForward(client, false);
		BlockPos feet = new BlockPos(player.getBlockX(), player.getBlockY(), player.getBlockZ());
		BlockPos below = down(feet);
		BlockState belowState = client.world.getBlockState(below);
		if (belowState.isAir()) {
			// Already open below - just fall, nothing to mine yet this tick.
			return;
		}
		if (isHazardBlock(belowState) || isHazardBlock(client.world.getBlockState(down(below)))) {
			// Straight down leads into (or immediately past) lava/water - not safe to keep digging
			// blind, give up on this chest rather than risk it.
			advanceTargetList(client);
			return;
		}
		miner.configure(0.0, NaturalMining.DEFAULT_MAX_REACH, aimJitterRadius, maxTurnDegreesPerTick);
		NaturalMining.Result result = miner.tick(client, player, below, Direction.UP);
		if (result == NaturalMining.Result.PROGRESSED) {
			miningStuckTicks = 0;
		} else if (++miningStuckTicks > MINING_STUCK_TICKS) {
			miner.stop(client);
			advanceTargetList(client);
		}
	}

	/**
	 * Chest is well above the player - pillar straight up toward it instead of trying to walk at
	 * it horizontally with nothing to stand on: jump, then (the same trick a real player uses to
	 * build a pillar) place a block directly beneath your own feet while airborne, land on it, and
	 * repeat.
	 */
	private void buildUp(MinecraftClient client, PlayerEntity player) {
		setForward(client, false);
		BlockPos feet = new BlockPos(player.getBlockX(), player.getBlockY(), player.getBlockZ());
		if (isOnGround(player)) {
			// Can't place a block directly beneath feet that are still resting on solid ground -
			// nowhere for it to go. Jump first to actually vacate the cell.
			pillarBase = down(feet);
			setJump(client, true);
			return;
		}
		setJump(client, false);
		if (pillarBase == null) {
			pillarBase = down(feet);
		}
		PlaceResult result = placeBlockStep(client, pillarBase, Direction.UP);
		switch (result) {
			case PLACED -> pillarBase = null; // will land on it; next on-ground check starts the next block
			case FAILED -> {
				pillarBase = null;
				advanceTargetList(client);
			}
			case IN_PROGRESS -> { }
		}
	}

	private enum PlaceResult { PLACED, IN_PROGRESS, FAILED }

	/**
	 * Right-clicks the given face of {@code against} to place a block there - shared by
	 * horizontal bridging ({@code against} = the floor block underfoot, {@code side} = direction
	 * of travel, extending the floor one step forward) and vertical pillaring ({@code against} =
	 * the block underfoot, {@code side} = UP, extending it one step upward). Turns to actually
	 * face the clicked point first (same capped-turn machinery as breaking) rather than placing
	 * instantly with the camera pointed wherever it already was, defers entirely while the player
	 * is mid-item-use (so it never fights {@code AutoEatModule} - or a real manual eat/block/bow
	 * draw - for the hotbar slot), waits out a cooldown after every attempt instead of retrying
	 * every single tick, and only reports {@link PlaceResult#PLACED} once the target cell has
	 * actually turned solid rather than assuming the right-click worked (it silently does nothing
	 * if, say, the player's own hitbox is still overlapping the spot).
	 */
	private PlaceResult placeBlockStep(MinecraftClient client, BlockPos against, Direction side) {
		PlayerEntity player = client.player;
		if (player.isUsingItem()) {
			// Eating, blocking, drawing a bow, ... - a real player can't also be right-clicking a
			// block face to place with the same hand right now, so just wait it out.
			return PlaceResult.IN_PROGRESS;
		}
		if (placeState == PlaceState.COOLDOWN) {
			if (--placeCooldownTicks > 0) {
				return PlaceResult.IN_PROGRESS;
			}
			placeState = PlaceState.IDLE;
		}
		if (placeState == PlaceState.IDLE || placeHit == null || !against.equals(placeHit.getBlockPos())) {
			if (findPlaceableHotbarSlot(player) < 0) {
				return PlaceResult.FAILED;
			}
			Vec3d hitPos = new Vec3d(against.getX() + 0.5 + stepX(side) * 0.5, against.getY() + 0.5 + stepY(side) * 0.5,
					against.getZ() + 0.5 + stepZ(side) * 0.5);
			placeHit = new BlockHitResult(hitPos, side, against, false);
			placeAimPoint = hitPos;
			placeResultPos = offset(against, side);
			placeState = PlaceState.TURNING;
		}

		if (!client.world.getBlockState(placeResultPos).isAir()) {
			// Already solid - a previous attempt actually landed (or something else filled it) -
			// nothing left to do.
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

	private static Direction nearestCardinal(double dirX, double dirZ) {
		if (Math.abs(dirX) > Math.abs(dirZ)) {
			return dirX > 0 ? Direction.EAST : Direction.WEST;
		}
		return dirZ > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/** {@code BlockPos#offset(Direction)} (Yarn) -> {@code BlockPos#relative(Direction)} (Mojmap) - same value, diverges by name only. */
	private static BlockPos offset(BlockPos pos, Direction direction) {
		//? if <26.1 {
		return pos.offset(direction);
		//?} else {
		/*return pos.relative(direction);
		*///?}
	}

	/** {@code Entity#isOnGround()} (Yarn) -> {@code Entity#onGround()} (Mojmap) - same value, diverges by name only. */
	private static boolean isOnGround(PlayerEntity player) {
		//? if <26.1 {
		return player.isOnGround();
		//?} else {
		/*return player.onGround();
		*///?}
	}

	/** {@code Inventory#size()/getStack(int)} (Yarn) -> {@code Container#getContainerSize()/getItem(int)} (Mojmap) - same indexed access, diverges by name only. Never picks an ender chest to bridge with - that would just undo the whole point of farming them. */
	private static int findPlaceableHotbarSlot(PlayerEntity player) {
		var inventory = player.getInventory();
		//? if <26.1 {
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inventory.getStack(i);
			//?} else {
			/*for (int i = 0; i < 9; i++) {
			ItemStack stack = inventory.getItem(i);
			*///?}
			if (!stack.isEmpty() && stack.getItem() != Items.ENDER_CHEST && isBlockItem(stack)) {
				return i;
			}
		}
		return -1;
	}

	/** {@code net.minecraft.item.BlockItem} (Yarn) -> {@code net.minecraft.world.item.BlockItem} (Mojmap) - same "this item places a block" check, diverges by package only. */
	private static boolean isBlockItem(ItemStack stack) {
		//? if <26.1 {
		return stack.getItem() instanceof net.minecraft.item.BlockItem;
		//?} else {
		/*return stack.getItem() instanceof net.minecraft.world.item.BlockItem;
		*///?}
	}

	/** {@code PlayerInventory#getSelectedSlot()/setSelectedSlot(int)} (Yarn) -> {@code Inventory#getSelectedSlot()/setSelectedSlot(int)} (Mojmap) - identical names, only the enclosing inventory type's own name differs (already handled generically via {@code var}). */
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

	/** {@code ClientPlayerInteractionManager#interactBlock} (Yarn) -> {@code MultiPlayerGameMode#useItemOn} (Mojmap) - same "right-click this block face" action, diverges by name only. */
	private static void interactBlock(MinecraftClient client, BlockHitResult hit) {
		//? if <26.1 {
		client.interactionManager.interactBlock(client.player, net.minecraft.util.Hand.MAIN_HAND, hit);
		//?} else {
		/*client.gameMode.useItemOn(client.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
		*///?}
	}

	/** {@code net.minecraft.block.Blocks} (Yarn) -> {@code net.minecraft.world.level.block.Blocks} (Mojmap) - package changed, the constants/simple name didn't. */
	private static boolean isHazardBlock(BlockState state) {
		var block = state.getBlock();
		//? if <26.1 {
		return block == net.minecraft.block.Blocks.LAVA || block == net.minecraft.block.Blocks.WATER;
		//?} else {
		/*return block == net.minecraft.world.level.block.Blocks.LAVA || block == net.minecraft.world.level.block.Blocks.WATER;
		*///?}
	}

	/** {@code BlockPos#toImmutable()} (Yarn) -> {@code BlockPos#immutable()} (Mojmap) - same value, diverges by name only. */
	private static BlockPos immutable(BlockPos pos) {
		//? if <26.1 {
		return pos.toImmutable();
		//?} else {
		/*return pos.immutable();
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

	/** {@code Direction#getOffsetY()} (Yarn) -> {@code Direction#getStepY()} (Mojmap) - same value, diverges by name only. */
	private static int stepY(Direction direction) {
		//? if <26.1 {
		return direction.getOffsetY();
		//?} else {
		/*return direction.getStepY();
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

	/** {@code GameOptions#forwardKey} (Yarn) -> {@code Options#keyUp} (Mojmap) - same key, diverges by field name only. */
	private static void setForward(MinecraftClient client, boolean pressed) {
		//? if <26.1 {
		client.options.forwardKey.setPressed(pressed);
		//?} else {
		/*client.options.keyUp.setDown(pressed);
		*///?}
	}

	/** {@code GameOptions#jumpKey} (Yarn) -> {@code Options#keyJump} (Mojmap) - same key, diverges by field name only. */
	private static void setJump(MinecraftClient client, boolean pressed) {
		//? if <26.1 {
		client.options.jumpKey.setPressed(pressed);
		//?} else {
		/*client.options.keyJump.setDown(pressed);
		*///?}
	}

	/** {@code GameOptions#sprintKey} (Yarn) -> {@code Options#keySprint} (Mojmap) - same key, diverges by field name only. Holding this (while moving forward) is how a real player actually engages sprint - it isn't instant, {@code PlayerEntity#isSprinting()} only flips true a moment after this is pressed and movement is already underway. */
	private static void setSprinting(MinecraftClient client, boolean pressed) {
		//? if <26.1 {
		client.options.sprintKey.setPressed(pressed);
		//?} else {
		/*client.options.keySprint.setDown(pressed);
		*///?}
	}

	/** Whether a non-inventory screen (the shop the sell command opens) is currently up - {@code currentScreenHandler != playerScreenHandler} (Yarn) vs {@code containerMenu != inventoryMenu} (Mojmap), same field-pair rename. */
	private static boolean hasOpenScreenHandler(PlayerEntity player) {
		//? if <26.1 {
		return player.currentScreenHandler != player.playerScreenHandler;
		//?} else {
		/*return player.containerMenu != player.inventoryMenu;
		*///?}
	}

	/**
	 * The slot indexes, within whatever screen is currently open, that are backed by the
	 * player's own inventory and currently hold an ender chest - exactly the slots shift-clicking
	 * would move into the other side of the screen (the shop). {@code Slot#inventory}/{@code
	 * Slot#id} (Yarn) -> {@code Slot#container}/{@code Slot#index} (Mojmap), same rename pattern
	 * as {@code StorageFinderModule#isNonEmptyInventory}'s own {@code Inventory}/{@code
	 * Container} divergence.
	 */
	private static List<Integer> enderChestSlotIndexesInInventory(PlayerEntity player) {
		List<Integer> indexes = new ArrayList<>();
		var inventory = player.getInventory();
		//? if <26.1 {
		for (var slot : player.currentScreenHandler.slots) {
			if (slot.inventory == inventory && slot.getStack().getItem() == Items.ENDER_CHEST) {
				indexes.add(slot.id);
			}
		}
		//?} else {
		/*for (var slot : player.containerMenu.slots) {
			if (slot.container == inventory && slot.getItem().getItem() == Items.ENDER_CHEST) {
				indexes.add(slot.index);
			}
		}
		*///?}
		return indexes;
	}

	/** {@code ClientPlayerInteractionManager#clickSlot(syncId, slotIndex, button, SlotActionType, player)} (Yarn) -> {@code MultiPlayerGameMode#handleContainerInput(containerId, slotIndex, button, ContainerInput, player)} (Mojmap) - same shift-click ("quick move") action, diverges by call shape/name only. */
	private static void quickMoveSlot(MinecraftClient client, PlayerEntity player, int slotIndex) {
		//? if <26.1 {
		client.interactionManager.clickSlot(player.currentScreenHandler.syncId, slotIndex, 0,
				net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
		//?} else {
		/*client.gameMode.handleContainerInput(player.containerMenu.containerId, slotIndex, 0,
				net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player);
		*///?}
	}

	private static void notifyMultiplayerBlocked(MinecraftClient client) {
		//? if <26.1 {
		client.player.sendMessage(net.minecraft.text.Text.literal(
				"Ender Chest Farmer: singleplayer only - disabling."), true);
		//?} else {
		/*client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
				"Ender Chest Farmer: singleplayer only - disabling."));
		*///?}
	}

	/** Same singleplayer-only restriction/reasoning as {@code EntityFinderModule#isSingleplayer}. */
	private static boolean isSingleplayer(MinecraftClient client) {
		return true;
	}

	private void onRender(WorldRenderContext context) {
		if (!isEnabled() || !running) {
			return;
		}
		BlockPos highlight = phase == Phase.MINING_OBSTRUCTION ? obstructionTarget : currentTarget;
		if (highlight == null) {
			return;
		}
		int color = phase == Phase.MINING_OBSTRUCTION ? 0xFFFF8800 : 0xFF00FFAA;
		net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(highlight.getX(), highlight.getY(), highlight.getZ(),
				highlight.getX() + 1, highlight.getY() + 1, highlight.getZ() + 1);
		GizmoDrawing.box(box, DrawStyle.stroked(color, 2.5f)).ignoreOcclusion();
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Toggle On/Off", TOGGLE_KEY),
				new ConfigField.TextField("RTP Command (no leading /)", "rtp", () -> rtpCommand, v -> rtpCommand = v),
				new ConfigField.TextField("Sell Command (no leading /)", "sell", () -> sellCommand, v -> sellCommand = v),
				new ConfigField.SliderField("Scan Radius (blocks)", 16, 128,
						() -> scanRadius, v -> scanRadius = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Wait After RTP (ticks)", 20, 200,
						() -> waitAfterRtpTicks, v -> waitAfterRtpTicks = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Sell When Carrying At Least", 8, 320,
						() -> sellThreshold, v -> sellThreshold = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.ToggleField("Mine Obstructions In The Way", () -> mineObstructions, v -> mineObstructions = v),
				new ConfigField.SliderField("Aim Jitter Radius (blocks)", 0.0, 0.4,
						() -> aimJitterRadius, v -> aimJitterRadius = v, v -> String.format("%.2f", v)),
				new ConfigField.SliderField("Max Turn Speed (deg/tick)", 5, 90,
						() -> maxTurnDegreesPerTick, v -> maxTurnDegreesPerTick = (float) v, v -> String.valueOf((int) v)));
	}
}
