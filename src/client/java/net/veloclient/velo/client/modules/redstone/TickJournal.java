package net.veloclient.velo.client.modules.redstone;

//? if <26.1 {
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
//?} else {
/*import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
*///?}

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tick Mode's rewind, on the integrated server thread. For every game tick that really runs
 * (stepping / playing - not while frozen) it records the previous state of every block changed
 * during that tick, plus the contents of the block entities around you at the start of the tick
 * (hoppers, droppers, furnaces, crafters, comparators change contents without changing blocks).
 * Rewinding one tick puts those back, newest first.
 *
 * <p>Not rewound: entities (item drops, minecarts, mobs) and the server's pending scheduled ticks -
 * a repeater that had a tick queued may fire once more after rewinding.
 */
public final class TickJournal {

	//? if <26.1 {
	private record Change(BlockPos pos, BlockState old) {
	}

	private static final class Entry {
		final List<Change> changes = new ArrayList<>();
		Map<BlockPos, NbtCompound> blockEntities = Map.of();
	}
	//?} else {
	/*private record Change(BlockPos pos, BlockState old) {
	}

	private static final class Entry {
		final List<Change> changes = new ArrayList<>();
		Map<BlockPos, CompoundTag> blockEntities = Map.of();
	}
	*///?}

	private static final int MAX_CHANGES = 400_000;
	private static final Deque<Entry> HISTORY = new ArrayDeque<>();
	private static Entry current;
	private static Object recordingLevel;
	private static boolean restoring;
	private static int totalChanges;

	/** Set from the client thread. */
	public static volatile boolean enabled;
	public static volatile int radius = 32;
	public static volatile int maxTicks = 200;

	private TickJournal() {
	}

	public static synchronized int available() {
		return HISTORY.size();
	}

	public static synchronized void clear() {
		HISTORY.clear();
		current = null;
		totalChanges = 0;
	}

	//? if <26.1 {
	/** ServerWorld#tick HEAD. */
	public static synchronized void onLevelTick(ServerWorld world) {
		if (!enabled || world.getPlayers().isEmpty()) {
			if (recordingLevel == world) {
				current = null;
			}
			return;
		}
		recordingLevel = world;
		if (!world.getTickManager().shouldTick()) {
			current = null;
			return;
		}
		Entry entry = new Entry();
		entry.blockEntities = snapshot(world, HISTORY.isEmpty() ? Map.of() : HISTORY.peekLast().blockEntities);
		push(entry);
	}

	/** World#setBlockState HEAD. */
	public static void onSetBlock(World world, BlockPos pos) {
		if (current == null || restoring || world != recordingLevel) {
			return;
		}
		synchronized (TickJournal.class) {
			if (current != null && totalChanges < MAX_CHANGES) {
				current.changes.add(new Change(pos.toImmutable(), world.getBlockState(pos)));
				totalChanges++;
			}
		}
	}

	private static Map<BlockPos, NbtCompound> snapshot(ServerWorld world, Map<BlockPos, NbtCompound> previous) {
		Map<BlockPos, NbtCompound> out = new HashMap<>();
		var player = world.getPlayers().get(0);
		int r = radius;
		int cx0 = ((int) Math.floor(player.getX()) - r) >> 4;
		int cx1 = ((int) Math.floor(player.getX()) + r) >> 4;
		int cz0 = ((int) Math.floor(player.getZ()) - r) >> 4;
		int cz1 = ((int) Math.floor(player.getZ()) + r) >> 4;
		var registries = world.getRegistryManager();
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				if (!world.isChunkLoaded(cx, cz)) {
					continue;
				}
				for (BlockEntity be : world.getChunk(cx, cz).getBlockEntities().values()) {
					try {
						NbtCompound tag = be.createNbtWithIdentifyingData(registries);
						NbtCompound old = previous.get(be.getPos());
						out.put(be.getPos().toImmutable(), old != null && old.equals(tag) ? old : tag);
					} catch (RuntimeException ignored) {
						// A block entity that can't save right now just isn't rewound.
					}
				}
			}
		}
		return out;
	}

	/** Undoes the newest recorded tick. Server thread. */
	public static synchronized boolean rewind(ServerWorld world) {
		Entry entry = HISTORY.pollLast();
		if (entry == null) {
			return false;
		}
		restoring = true;
		try {
			for (int i = entry.changes.size() - 1; i >= 0; i--) {
				Change change = entry.changes.get(i);
				world.setBlockState(change.pos(), change.old(), 2 | 16);
			}
			var registries = world.getRegistryManager();
			for (Map.Entry<BlockPos, NbtCompound> e : entry.blockEntities.entrySet()) {
				BlockEntity restored = BlockEntity.createFromNbt(e.getKey(), world.getBlockState(e.getKey()), e.getValue().copy(), registries);
				if (restored != null) {
					world.addBlockEntity(restored);
					BlockState state = world.getBlockState(e.getKey());
					world.updateListeners(e.getKey(), state, state, 3);
				}
			}
		} finally {
			restoring = false;
		}
		totalChanges -= entry.changes.size();
		current = null;
		return true;
	}
	//?} else {
	/*public static synchronized void onLevelTick(ServerLevel level) {
		if (!enabled || level.players().isEmpty()) {
			if (recordingLevel == level) {
				current = null;
			}
			return;
		}
		recordingLevel = level;
		if (!level.tickRateManager().runsNormally()) {
			current = null;
			return;
		}
		Entry entry = new Entry();
		entry.blockEntities = snapshot(level, HISTORY.isEmpty() ? Map.of() : HISTORY.peekLast().blockEntities);
		push(entry);
	}

	public static void onSetBlock(Level level, BlockPos pos) {
		if (current == null || restoring || level != recordingLevel) {
			return;
		}
		synchronized (TickJournal.class) {
			if (current != null && totalChanges < MAX_CHANGES) {
				current.changes.add(new Change(pos.immutable(), level.getBlockState(pos)));
				totalChanges++;
			}
		}
	}

	private static Map<BlockPos, CompoundTag> snapshot(ServerLevel level, Map<BlockPos, CompoundTag> previous) {
		Map<BlockPos, CompoundTag> out = new HashMap<>();
		var player = level.players().get(0);
		int r = radius;
		int cx0 = ((int) Math.floor(player.getX()) - r) >> 4;
		int cx1 = ((int) Math.floor(player.getX()) + r) >> 4;
		int cz0 = ((int) Math.floor(player.getZ()) - r) >> 4;
		int cz1 = ((int) Math.floor(player.getZ()) + r) >> 4;
		var registries = level.registryAccess();
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				if (!level.hasChunk(cx, cz)) {
					continue;
				}
				for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
					try {
						CompoundTag tag = be.saveWithFullMetadata(registries);
						CompoundTag old = previous.get(be.getBlockPos());
						out.put(be.getBlockPos().immutable(), old != null && old.equals(tag) ? old : tag);
					} catch (RuntimeException ignored) {
						// A block entity that can't save right now just isn't rewound.
					}
				}
			}
		}
		return out;
	}

	public static synchronized boolean rewind(ServerLevel level) {
		Entry entry = HISTORY.pollLast();
		if (entry == null) {
			return false;
		}
		restoring = true;
		try {
			for (int i = entry.changes.size() - 1; i >= 0; i--) {
				Change change = entry.changes.get(i);
				level.setBlock(change.pos(), change.old(), 2 | 16);
			}
			var registries = level.registryAccess();
			for (Map.Entry<BlockPos, CompoundTag> e : entry.blockEntities.entrySet()) {
				BlockEntity restored = BlockEntity.loadStatic(e.getKey(), level.getBlockState(e.getKey()), e.getValue().copy(), registries);
				if (restored != null) {
					level.setBlockEntity(restored);
					BlockState state = level.getBlockState(e.getKey());
					level.sendBlockUpdated(e.getKey(), state, state, 3);
				}
			}
		} finally {
			restoring = false;
		}
		totalChanges -= entry.changes.size();
		current = null;
		return true;
	}
	*///?}

	/** Rewinds the level that's being recorded (yours). Server thread. */
	public static boolean rewindRecorded() {
		Object level = recordingLevel;
		//? if <26.1 {
		return level instanceof ServerWorld world && rewind(world);
		//?} else {
		/*return level instanceof ServerLevel serverLevel && rewind(serverLevel);
		*///?}
	}

	private static void push(Entry entry) {
		current = entry;
		HISTORY.addLast(entry);
		while (HISTORY.size() > Math.max(1, maxTicks)) {
			totalChanges -= HISTORY.removeFirst().changes.size();
		}
	}
}
