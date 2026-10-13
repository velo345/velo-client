package net.veloclient.velo.client.redstone;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every Minecraft call Redstone Mode makes, in one place, per mapping. The rest of Redstone Mode
 * (operations, routing, schematics, timeline) only sees {@code Object} block states / levels / tags
 * and plain ints, so it reads the same on every supported version.
 *
 * <p>World edits only ever touch the integrated (singleplayer) server - {@link #onServer} runs on
 * its thread, and nothing here can reach a real server.
 */
public final class RmWorld {

	/**
	 * Update clients only: no neighbour / shape updates, no on-place reactions (512), no block-entity
	 * side effects (256) and no drops (32). Without 512 every placed block ran its own on-place logic
	 * while the rest of the build wasn't there yet - dust recalculated its power, powered observers
	 * switched off, pistons checked for power - which is what made pasted clocks stop at random.
	 */
	public static final int SILENT = 2 | 16 | 32 | 256 | 512;
	/** Normal placement: neighbours updated, clients told. */
	public static final int NORMAL = 3;

	private RmWorld() {
	}

	private static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

	/** Face index (0 down, 1 up, 2 north, 3 south, 4 west, 5 east) to its name. */
	public static String faceName(int face) {
		return FACES[Math.max(0, Math.min(5, face))];
	}

	public static int[] faceOffset(int face) {
		return switch (face) {
			case 0 -> new int[] {0, -1, 0};
			case 1 -> new int[] {0, 1, 0};
			case 2 -> new int[] {0, 0, -1};
			case 3 -> new int[] {0, 0, 1};
			case 4 -> new int[] {-1, 0, 0};
			default -> new int[] {1, 0, 0};
		};
	}

	public static Object parse(String id, Map<String, String> props) {
		Object state = defaultState(id);
		if (state == null) {
			return null;
		}
		if (props != null) {
			for (Map.Entry<String, String> e : props.entrySet()) {
				state = with(state, e.getKey(), e.getValue());
			}
		}
		return state;
	}

	/** "minecraft:repeater[delay=2,facing=north]" form. */
	public static Object fromText(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		int bracket = text.indexOf('[');
		String id = bracket < 0 ? text : text.substring(0, bracket);
		Map<String, String> props = new java.util.LinkedHashMap<>();
		if (bracket >= 0 && text.endsWith("]")) {
			for (String pair : text.substring(bracket + 1, text.length() - 1).split(",")) {
				int eq = pair.indexOf('=');
				if (eq > 0) {
					props.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
				}
			}
		}
		return parse(id, props);
	}

	public static Object readGzipNbt(Path file) throws java.io.IOException {
		try (InputStream in = Files.newInputStream(file)) {
			return readCompressed(in);
		}
	}

	public static JsonElement json(Object tag) {
		return nbtOps().convertTo(JsonOps.INSTANCE, castTag(tag));
	}

	/** Elements of a list tag (empty if it isn't one). */
	public static List<Object> list(Object listTag) {
		List<Object> out = new ArrayList<>();
		if (listTag instanceof Iterable<?> iterable) {
			for (Object o : iterable) {
				out.add(o);
			}
		}
		return out;
	}

	//? if <26.1 {
	private static com.mojang.serialization.DynamicOps<net.minecraft.nbt.NbtElement> nbtOps() {
		return net.minecraft.nbt.NbtOps.INSTANCE;
	}

	private static net.minecraft.nbt.NbtElement castTag(Object tag) {
		return (net.minecraft.nbt.NbtElement) tag;
	}

	private static Object readCompressed(InputStream in) throws java.io.IOException {
		return net.minecraft.nbt.NbtIo.readCompressed(in, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
	}

	public static Object child(Object compound, String key) {
		return compound instanceof net.minecraft.nbt.NbtCompound c ? c.get(key) : null;
	}

	public static boolean singleplayer() {
		return net.minecraft.client.MinecraftClient.getInstance().getServer() != null;
	}

	/** Runs {@code task} on the integrated server thread with the level you're in (no-op outside singleplayer). */
	public static void onServer(java.util.function.Consumer<Object> task) {
		var client = net.minecraft.client.MinecraftClient.getInstance();
		var server = client.getServer();
		if (server == null || client.world == null) {
			return;
		}
		var key = client.world.getRegistryKey();
		server.execute(() -> {
			var level = server.getWorld(key);
			if (level != null) {
				task.accept(level);
			}
		});
	}

	public static Object clientLevel() {
		return net.minecraft.client.MinecraftClient.getInstance().world;
	}

	private static net.minecraft.world.World w(Object level) {
		return (net.minecraft.world.World) level;
	}

	private static net.minecraft.block.BlockState s(Object state) {
		return (net.minecraft.block.BlockState) state;
	}

	private static net.minecraft.util.math.BlockPos pos(int x, int y, int z) {
		return new net.minecraft.util.math.BlockPos(x, y, z);
	}

	public static Object state(Object level, int x, int y, int z) {
		return w(level).getBlockState(pos(x, y, z));
	}

	public static void set(Object level, int x, int y, int z, Object state, int flags) {
		w(level).setBlockState(pos(x, y, z), s(state), flags);
	}

	public static Object saveBe(Object level, int x, int y, int z) {
		var be = w(level).getBlockEntity(pos(x, y, z));
		return be == null ? null : be.createNbtWithIdentifyingData(w(level).getRegistryManager());
	}

	public static void loadBe(Object level, int x, int y, int z, Object tag) {
		if (tag == null) {
			return;
		}
		var p = pos(x, y, z);
		var state = w(level).getBlockState(p);
		var be = net.minecraft.block.entity.BlockEntity.createFromNbt(p, state, ((net.minecraft.nbt.NbtCompound) tag).copy(),
				w(level).getRegistryManager());
		if (be != null) {
			w(level).addBlockEntity(be);
			w(level).updateListeners(p, state, state, 3);
		}
	}

	public static Object rotate(Object state, int quarterTurnsClockwise) {
		return switch (((quarterTurnsClockwise % 4) + 4) % 4) {
			case 1 -> s(state).rotate(net.minecraft.util.BlockRotation.CLOCKWISE_90);
			case 2 -> s(state).rotate(net.minecraft.util.BlockRotation.CLOCKWISE_180);
			case 3 -> s(state).rotate(net.minecraft.util.BlockRotation.COUNTERCLOCKWISE_90);
			default -> state;
		};
	}

	/** Mirrors east-west (x flipped). */
	public static Object mirrorX(Object state) {
		return s(state).mirror(net.minecraft.util.BlockMirror.FRONT_BACK);
	}

	public static boolean isAir(Object state) {
		return state == null || s(state).isAir();
	}

	public static String id(Object state) {
		return net.minecraft.registry.Registries.BLOCK.getId(s(state).getBlock()).toString();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static String prop(Object state, String name) {
		var property = s(state).getBlock().getStateManager().getProperty(name);
		if (property == null || !s(state).contains(property)) {
			return null;
		}
		return ((net.minecraft.state.property.Property) property).name((Comparable) s(state).get(property));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static Object with(Object state, String name, String value) {
		var property = s(state).getBlock().getStateManager().getProperty(name);
		if (property == null) {
			return state;
		}
		java.util.Optional parsed = ((net.minecraft.state.property.Property) property).parse(value);
		return parsed.isPresent() ? s(state).with((net.minecraft.state.property.Property) property, (Comparable) parsed.get()) : state;
	}

	private static Object defaultState(String id) {
		var identifier = net.minecraft.util.Identifier.tryParse(id);
		if (identifier == null || !net.minecraft.registry.Registries.BLOCK.containsId(identifier)) {
			return null;
		}
		return net.minecraft.registry.Registries.BLOCK.get(identifier).getDefaultState();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static String toText(Object state) {
		StringBuilder out = new StringBuilder(id(state));
		var entries = s(state).getEntries();
		if (!entries.isEmpty()) {
			out.append('[');
			boolean first = true;
			for (var e : entries.entrySet()) {
				if (!first) {
					out.append(',');
				}
				first = false;
				out.append(e.getKey().getName()).append('=').append(((net.minecraft.state.property.Property) e.getKey()).name((Comparable) e.getValue()));
			}
			out.append(']');
		}
		return out.toString();
	}

	public static int rawId(Object state) {
		return net.minecraft.block.Block.getRawIdFromState(s(state));
	}

	public static Object byRawId(int id) {
		return net.minecraft.block.Block.getStateFromRawId(id);
	}

	public static String tagToText(Object tag) {
		return tag == null ? null : tag.toString();
	}

	public static Object tagFromText(String text) {
		try {
			return text == null ? null : net.minecraft.nbt.StringNbtReader.readCompound(text);
		} catch (Exception e) {
			return null;
		}
	}

	public static void neighborUpdate(Object level, int x, int y, int z) {
		var p = pos(x, y, z);
		w(level).updateNeighbors(p, w(level).getBlockState(p).getBlock());
	}

	public static void scheduleTick(Object level, int x, int y, int z, int delay) {
		var p = pos(x, y, z);
		w(level).scheduleBlockTick(p, w(level).getBlockState(p).getBlock(), delay);
	}

	/** Server level only: whether the block there has a scheduled tick waiting. */
	public static boolean hasPendingTick(Object level, int x, int y, int z) {
		if (!(level instanceof net.minecraft.server.world.ServerWorld server)) {
			return false;
		}
		var p = pos(x, y, z);
		return server.getBlockTickScheduler().isQueued(p, server.getBlockState(p).getBlock());
	}

	/**
	 * Every scheduled block and fluid tick inside the box, in the order the game would run them:
	 * {dx, dy, dz, delay, priority ordinal, fluid ? 1 : 0}. Server level. Only ticks meant for the
	 * block / fluid that's actually there count (a stale tick for a replaced block is dropped).
	 */
	public static List<int[]> scheduledTicks(Object level, int x0, int y0, int z0, int sx, int sy, int sz) {
		List<int[]> out = new ArrayList<>();
		if (!(level instanceof net.minecraft.server.world.ServerWorld server)) {
			return out;
		}
		long now = server.getTime();
		List<Object[]> all = new ArrayList<>();
		for (int cx = x0 >> 4; cx <= (x0 + sx - 1) >> 4; cx++) {
			for (int cz = z0 >> 4; cz <= (z0 + sz - 1) >> 4; cz++) {
				if (!server.isChunkLoaded(cx, cz)) {
					continue;
				}
				var chunk = server.getChunk(cx, cz);
				for (int kind = 0; kind < 2; kind++) {
					if (!((kind == 0 ? chunk.getBlockTickScheduler() : chunk.getFluidTickScheduler())
							instanceof net.minecraft.world.tick.ChunkTickScheduler<?> ticks)) {
						continue;
					}
					int fluid = kind;
					ticks.getQueueAsStream().forEach(t -> {
						var p = t.pos();
						if (p.getX() < x0 || p.getY() < y0 || p.getZ() < z0 || p.getX() >= x0 + sx || p.getY() >= y0 + sy || p.getZ() >= z0 + sz) {
							return;
						}
						var here = server.getBlockState(p);
						if (fluid == 0 ? t.type() != here.getBlock() : t.type() != here.getFluidState().getFluid()) {
							return;
						}
						all.add(new Object[] {t, fluid});
					});
				}
			}
		}
		all.sort((a, b) -> net.minecraft.world.tick.OrderedTick.TRIGGER_TICK_COMPARATOR.compare(
				(net.minecraft.world.tick.OrderedTick<?>) a[0], (net.minecraft.world.tick.OrderedTick<?>) b[0]));
		for (Object[] e : all) {
			var t = (net.minecraft.world.tick.OrderedTick<?>) e[0];
			out.add(new int[] {t.pos().getX() - x0, t.pos().getY() - y0, t.pos().getZ() - z0,
					(int) Math.max(1, Math.min(Integer.MAX_VALUE, t.triggerTick() - now)), t.priority().ordinal(), (int) e[1]});
		}
		return out;
	}

	/** Schedules a tick for whatever block (or fluid) is at x/y/z, with its original delay and priority. Server level. */
	public static void scheduleExact(Object level, int x, int y, int z, int delay, int priority, boolean fluid) {
		var p = pos(x, y, z);
		var here = w(level).getBlockState(p);
		if (fluid) {
			if (!here.getFluidState().isEmpty()) {
				w(level).scheduleFluidTick(p, here.getFluidState().getFluid(), delay);
			}
		} else if (!here.isAir()) {
			var priorities = net.minecraft.world.tick.TickPriority.values();
			w(level).scheduleBlockTick(p, here.getBlock(), delay, priorities[Math.max(0, Math.min(priorities.length - 1, priority))]);
		}
	}

	/** A full block redstone treats as a conductor (dust on top works, signal passes into it). */
	public static boolean conductor(Object level, int x, int y, int z) {
		var p = pos(x, y, z);
		return w(level).getBlockState(p).isSolidBlock(w(level), p);
	}

	public static boolean replaceable(Object state) {
		return state == null || s(state).isReplaceable();
	}

	public static long gameTime(Object level) {
		return w(level).getTime();
	}

	/** Non-player entities inside the box: {dx, dy, dz (relative to x0/y0/z0), yaw, tag}. Server level. */
	public static List<Object[]> captureEntities(Object level, int x0, int y0, int z0, int x1, int y1, int z1) {
		List<Object[]> out = new ArrayList<>();
		var box = new net.minecraft.util.math.Box(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
		for (var entity : w(level).getOtherEntities(null, box, e -> !(e instanceof net.minecraft.entity.player.PlayerEntity) && !e.hasVehicle())) {
			var view = net.minecraft.storage.NbtWriteView.create(net.minecraft.util.ErrorReporter.EMPTY, w(level).getRegistryManager());
			if (entity.saveSelfData(view)) {
				out.add(new Object[] {entity.getX() - x0, entity.getY() - y0, entity.getZ() - z0, entity.getYaw(), view.getNbt()});
			}
		}
		return out;
	}

	public static void removeEntities(Object level, int x0, int y0, int z0, int x1, int y1, int z1) {
		var box = new net.minecraft.util.math.Box(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
		for (var entity : w(level).getOtherEntities(null, box, e -> !(e instanceof net.minecraft.entity.player.PlayerEntity) && !e.hasVehicle())) {
			entity.discard();
		}
	}

	/** Spawns a saved entity at x/y/z with its own fresh id. Server level. */
	public static void spawnEntity(Object level, Object tag, double x, double y, double z, float yaw) {
		var entity = net.minecraft.entity.EntityType.loadEntityWithPassengers(((net.minecraft.nbt.NbtCompound) tag).copy(), w(level),
				net.minecraft.entity.SpawnReason.LOAD, net.minecraft.entity.LoadedEntityProcessor.NOOP);
		if (entity != null) {
			entity.setUuid(java.util.UUID.randomUUID());
			entity.refreshPositionAndAngles(x, y, z, yaw, entity.getPitch());
			w(level).spawnEntity(entity);
		}
	}

	/** {eyeX, eyeY, eyeZ, lookX, lookY, lookZ} of the player, or null. */
	public static double[] eyeAndLook() {
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		if (player == null) {
			return null;
		}
		var eye = player.getEyePos();
		var look = player.getRotationVec(1f);
		return new double[] {eye.x, eye.y, eye.z, look.x, look.y, look.z};
	}

	public static int selectedSlot() {
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		return player == null ? -1 : player.getInventory().getSelectedSlot();
	}

	/** Spectators scroll to change fly speed - Redstone Mode leaves that alone. */
	public static boolean spectator() {
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		return player != null && player.isSpectator();
	}

	/** Bit i set while hotbar key i+1 is held. */
	public static int hotbarKeysDown() {
		var keys = net.minecraft.client.MinecraftClient.getInstance().options.hotbarKeys;
		int mask = 0;
		for (int i = 0; i < keys.length && i < 9; i++) {
			if (keys[i].isPressed()) {
				mask |= 1 << i;
			}
		}
		return mask;
	}

	public static void setSelectedSlot(int slot) {
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		if (player != null && slot >= 0 && slot < 9) {
			player.getInventory().setSelectedSlot(slot);
		}
	}

	/** Map colour (RGB) of a state, for block previews. */
	public static int mapColor(Object level, Object state, int x, int y, int z) {
		try {
			return s(state).getMapColor(w(level), pos(x, y, z)).color;
		} catch (RuntimeException e) {
			return 0x808080;
		}
	}

	/** The block's item (an ItemStack), or an empty stack for blocks without one. */
	public static Object blockItem(Object state) {
		return s(state).getBlock().asItem().getDefaultStack();
	}

	/** A full, non-see-through cube (stone, planks) - hides whatever is behind it. */
	public static boolean opaque(Object state) {
		return s(state).isOpaqueFullCube();
	}

	/** The block's name in the game's language ("Redstone Repeater"). */
	public static String blockName(Object state) {
		return s(state).getBlock().getName().getString();
	}

	public static List<String> allBlockIds() {
		List<String> ids = new ArrayList<>();
		for (var id : net.minecraft.registry.Registries.BLOCK.getIds()) {
			ids.add(id.toString());
		}
		java.util.Collections.sort(ids);
		return ids;
	}

	public static boolean hasNeighborSignal(Object level, int x, int y, int z) {
		return w(level).isReceivingRedstonePower(pos(x, y, z));
	}

	/** A comparator's output strength (server level), or -1. */
	public static int comparatorOutput(Object level, int x, int y, int z) {
		return w(level).getBlockEntity(pos(x, y, z)) instanceof net.minecraft.block.entity.ComparatorBlockEntity c ? c.getOutputSignal() : -1;
	}

	/** Recomputes a block's connections from its neighbours (dust, fences, walls...) after silent placement. */
	public static void refreshShape(Object level, int x, int y, int z) {
		var p = pos(x, y, z);
		var state = w(level).getBlockState(p);
		var updated = net.minecraft.block.Block.postProcessState(state, (net.minecraft.world.WorldAccess) w(level), p);
		if (updated != state) {
			w(level).setBlockState(p, updated, 2);
		}
	}

	/** Item entities in the box: {entity id, item count}. */
	public static List<int[]> itemEntities(Object level, double x0, double y0, double z0, double x1, double y1, double z1) {
		List<int[]> out = new ArrayList<>();
		var box = new net.minecraft.util.math.Box(x0, y0, z0, x1, y1, z1);
		for (var e : w(level).getEntitiesByClass(net.minecraft.entity.ItemEntity.class, box, e -> true)) {
			out.add(new int[] {e.getId(), e.getStack().getCount()});
		}
		return out;
	}

	/** Middle click on a schematic block: select / fetch its item from your inventory (creative: create it). */
	public static boolean pickItem(Object state) {
		var client = net.minecraft.client.MinecraftClient.getInstance();
		var player = client.player;
		if (player == null || client.interactionManager == null) {
			return false;
		}
		var item = s(state).getBlock().asItem();
		if (item == net.minecraft.item.Items.AIR) {
			return false;
		}
		var inventory = player.getInventory();
		for (int i = 0; i < 9; i++) {
			if (inventory.getStack(i).isOf(item)) {
				inventory.setSelectedSlot(i);
				return true;
			}
		}
		int selected = inventory.getSelectedSlot();
		for (int i = 9; i < 36; i++) {
			if (inventory.getStack(i).isOf(item)) {
				client.interactionManager.clickSlot(player.playerScreenHandler.syncId, i, selected,
						net.minecraft.screen.slot.SlotActionType.SWAP, player);
				return true;
			}
		}
		if (player.isCreative()) {
			var stack = item.getDefaultStack();
			inventory.setStack(selected, stack);
			client.interactionManager.clickCreativeStack(stack, 36 + selected);
			return true;
		}
		return false;
	}

	/** Whether the level actually advances this tick (not frozen by Tick Mode). */
	public static boolean runsNormally(Object level) {
		return w(level).getTickManager().shouldTick();
	}

	public static int[] playerBlock() {
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		return player == null ? null : new int[] {player.getBlockX(), player.getBlockY(), player.getBlockZ()};
	}

	/** Horizontal facing of the player as a face index (2 north, 3 south, 4 west, 5 east). */
	public static int playerFacing() {
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		return player == null ? 2 : player.getHorizontalFacing().ordinal();
	}

	/** {x, y, z, face} of the block you look at within {@code range} blocks, or null. */
	public static int[] lookedBlock(double range) {
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		if (player == null) {
			return null;
		}
		var hit = player.raycast(range, 1f, false);
		if (hit instanceof net.minecraft.util.hit.BlockHitResult block && hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
			var p = block.getBlockPos();
			return new int[] {p.getX(), p.getY(), p.getZ(), block.getSide().ordinal()};
		}
		return null;
	}
	//?} else {
	/*private static com.mojang.serialization.DynamicOps<net.minecraft.nbt.Tag> nbtOps() {
		return net.minecraft.nbt.NbtOps.INSTANCE;
	}

	private static net.minecraft.nbt.Tag castTag(Object tag) {
		return (net.minecraft.nbt.Tag) tag;
	}

	private static Object readCompressed(InputStream in) throws java.io.IOException {
		return net.minecraft.nbt.NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
	}

	public static Object child(Object compound, String key) {
		return compound instanceof net.minecraft.nbt.CompoundTag c ? c.get(key) : null;
	}

	public static boolean singleplayer() {
		return net.minecraft.client.Minecraft.getInstance().getSingleplayerServer() != null;
	}

	public static void onServer(java.util.function.Consumer<Object> task) {
		var client = net.minecraft.client.Minecraft.getInstance();
		var server = client.getSingleplayerServer();
		if (server == null || client.level == null) {
			return;
		}
		var key = client.level.dimension();
		server.execute(() -> {
			var level = server.getLevel(key);
			if (level != null) {
				task.accept(level);
			}
		});
	}

	public static Object clientLevel() {
		return net.minecraft.client.Minecraft.getInstance().level;
	}

	private static net.minecraft.world.level.Level w(Object level) {
		return (net.minecraft.world.level.Level) level;
	}

	private static net.minecraft.world.level.block.state.BlockState s(Object state) {
		return (net.minecraft.world.level.block.state.BlockState) state;
	}

	private static net.minecraft.core.BlockPos pos(int x, int y, int z) {
		return new net.minecraft.core.BlockPos(x, y, z);
	}

	public static Object state(Object level, int x, int y, int z) {
		return w(level).getBlockState(pos(x, y, z));
	}

	public static void set(Object level, int x, int y, int z, Object state, int flags) {
		w(level).setBlock(pos(x, y, z), s(state), flags);
	}

	public static Object saveBe(Object level, int x, int y, int z) {
		var be = w(level).getBlockEntity(pos(x, y, z));
		return be == null ? null : be.saveWithFullMetadata(w(level).registryAccess());
	}

	public static void loadBe(Object level, int x, int y, int z, Object tag) {
		if (tag == null) {
			return;
		}
		var p = pos(x, y, z);
		var state = w(level).getBlockState(p);
		var be = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(p, state, ((net.minecraft.nbt.CompoundTag) tag).copy(),
				w(level).registryAccess());
		if (be != null) {
			w(level).setBlockEntity(be);
			w(level).sendBlockUpdated(p, state, state, 3);
		}
	}

	public static Object rotate(Object state, int quarterTurnsClockwise) {
		return switch (((quarterTurnsClockwise % 4) + 4) % 4) {
			case 1 -> s(state).rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
			case 2 -> s(state).rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_180);
			case 3 -> s(state).rotate(net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90);
			default -> state;
		};
	}

	public static Object mirrorX(Object state) {
		return s(state).mirror(net.minecraft.world.level.block.Mirror.FRONT_BACK);
	}

	public static boolean isAir(Object state) {
		return state == null || s(state).isAir();
	}

	public static String id(Object state) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s(state).getBlock()).toString();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static String prop(Object state, String name) {
		var property = s(state).getBlock().getStateDefinition().getProperty(name);
		if (property == null || !s(state).hasProperty(property)) {
			return null;
		}
		return ((net.minecraft.world.level.block.state.properties.Property) property).getName((Comparable) s(state).getValue(property));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static Object with(Object state, String name, String value) {
		var property = s(state).getBlock().getStateDefinition().getProperty(name);
		if (property == null) {
			return state;
		}
		java.util.Optional parsed = ((net.minecraft.world.level.block.state.properties.Property) property).getValue(value);
		return parsed.isPresent()
				? s(state).setValue((net.minecraft.world.level.block.state.properties.Property) property, (Comparable) parsed.get()) : state;
	}

	private static Object defaultState(String id) {
		var identifier = net.minecraft.resources.Identifier.tryParse(id);
		if (identifier == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(identifier)) {
			return null;
		}
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(identifier).defaultBlockState();
	}

	public static String toText(Object state) {
		StringBuilder out = new StringBuilder(id(state));
		List<String> parts = new ArrayList<>();
		s(state).getValues().forEach(v -> parts.add(v.property().getName() + "=" + v.valueName()));
		if (!parts.isEmpty()) {
			out.append('[').append(String.join(",", parts)).append(']');
		}
		return out.toString();
	}

	public static int rawId(Object state) {
		return net.minecraft.world.level.block.Block.getId(s(state));
	}

	public static Object byRawId(int id) {
		return net.minecraft.world.level.block.Block.stateById(id);
	}

	public static String tagToText(Object tag) {
		return tag == null ? null : tag.toString();
	}

	public static Object tagFromText(String text) {
		try {
			return text == null ? null : net.minecraft.nbt.TagParser.parseCompoundFully(text);
		} catch (Exception e) {
			return null;
		}
	}

	public static void neighborUpdate(Object level, int x, int y, int z) {
		var p = pos(x, y, z);
		w(level).updateNeighborsAt(p, w(level).getBlockState(p).getBlock());
	}

	public static void scheduleTick(Object level, int x, int y, int z, int delay) {
		var p = pos(x, y, z);
		w(level).scheduleTick(p, w(level).getBlockState(p).getBlock(), delay);
	}

	public static boolean hasPendingTick(Object level, int x, int y, int z) {
		if (!(level instanceof net.minecraft.server.level.ServerLevel server)) {
			return false;
		}
		var p = pos(x, y, z);
		return server.getBlockTicks().hasScheduledTick(p, server.getBlockState(p).getBlock());
	}

	public static List<int[]> scheduledTicks(Object level, int x0, int y0, int z0, int sx, int sy, int sz) {
		List<int[]> out = new ArrayList<>();
		if (!(level instanceof net.minecraft.server.level.ServerLevel server)) {
			return out;
		}
		long now = server.getGameTime();
		List<Object[]> all = new ArrayList<>();
		for (int cx = x0 >> 4; cx <= (x0 + sx - 1) >> 4; cx++) {
			for (int cz = z0 >> 4; cz <= (z0 + sz - 1) >> 4; cz++) {
				if (!server.hasChunk(cx, cz)) {
					continue;
				}
				var chunk = server.getChunk(cx, cz);
				for (int kind = 0; kind < 2; kind++) {
					if (!((kind == 0 ? chunk.getBlockTicks() : chunk.getFluidTicks())
							instanceof net.minecraft.world.ticks.LevelChunkTicks<?> ticks)) {
						continue;
					}
					int fluid = kind;
					ticks.getAll().forEach(t -> {
						var p = t.pos();
						if (p.getX() < x0 || p.getY() < y0 || p.getZ() < z0 || p.getX() >= x0 + sx || p.getY() >= y0 + sy || p.getZ() >= z0 + sz) {
							return;
						}
						var here = server.getBlockState(p);
						if (fluid == 0 ? t.type() != here.getBlock() : t.type() != here.getFluidState().getType()) {
							return;
						}
						all.add(new Object[] {t, fluid});
					});
				}
			}
		}
		all.sort((a, b) -> net.minecraft.world.ticks.ScheduledTick.DRAIN_ORDER.compare(
				(net.minecraft.world.ticks.ScheduledTick<?>) a[0], (net.minecraft.world.ticks.ScheduledTick<?>) b[0]));
		for (Object[] e : all) {
			var t = (net.minecraft.world.ticks.ScheduledTick<?>) e[0];
			out.add(new int[] {t.pos().getX() - x0, t.pos().getY() - y0, t.pos().getZ() - z0,
					(int) Math.max(1, Math.min(Integer.MAX_VALUE, t.triggerTick() - now)), t.priority().ordinal(), (int) e[1]});
		}
		return out;
	}

	public static void scheduleExact(Object level, int x, int y, int z, int delay, int priority, boolean fluid) {
		var p = pos(x, y, z);
		var here = w(level).getBlockState(p);
		if (fluid) {
			if (!here.getFluidState().isEmpty()) {
				w(level).scheduleTick(p, here.getFluidState().getType(), delay);
			}
		} else if (!here.isAir()) {
			var priorities = net.minecraft.world.ticks.TickPriority.values();
			w(level).scheduleTick(p, here.getBlock(), delay, priorities[Math.max(0, Math.min(priorities.length - 1, priority))]);
		}
	}

	public static boolean conductor(Object level, int x, int y, int z) {
		var p = pos(x, y, z);
		return w(level).getBlockState(p).isRedstoneConductor(w(level), p);
	}

	public static boolean replaceable(Object state) {
		return state == null || s(state).canBeReplaced();
	}

	public static long gameTime(Object level) {
		return w(level).getGameTime();
	}

	public static List<Object[]> captureEntities(Object level, int x0, int y0, int z0, int x1, int y1, int z1) {
		List<Object[]> out = new ArrayList<>();
		var box = new net.minecraft.world.phys.AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
		for (var entity : w(level).getEntities((net.minecraft.world.entity.Entity) null, box,
				e -> !(e instanceof net.minecraft.world.entity.player.Player) && !e.isPassenger())) {
			var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,
					w(level).registryAccess());
			if (entity.saveAsPassenger(output)) {
				out.add(new Object[] {entity.getX() - x0, entity.getY() - y0, entity.getZ() - z0, entity.getYRot(), output.buildResult()});
			}
		}
		return out;
	}

	public static void removeEntities(Object level, int x0, int y0, int z0, int x1, int y1, int z1) {
		var box = new net.minecraft.world.phys.AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
		for (var entity : w(level).getEntities((net.minecraft.world.entity.Entity) null, box,
				e -> !(e instanceof net.minecraft.world.entity.player.Player) && !e.isPassenger())) {
			entity.discard();
		}
	}

	public static void spawnEntity(Object level, Object tag, double x, double y, double z, float yaw) {
		var input = net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, w(level).registryAccess(),
				((net.minecraft.nbt.CompoundTag) tag).copy());
		var entity = net.minecraft.world.entity.EntityType.loadEntityRecursive(input, w(level), net.minecraft.world.entity.EntitySpawnReason.LOAD,
				net.minecraft.world.entity.EntityProcessor.NOP);
		if (entity != null && level instanceof net.minecraft.server.level.ServerLevel server) {
			entity.setUUID(java.util.UUID.randomUUID());
			entity.snapTo(x, y, z, yaw, entity.getXRot());
			server.addFreshEntity(entity);
		}
	}

	public static double[] eyeAndLook() {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player == null) {
			return null;
		}
		var eye = player.getEyePosition();
		var look = player.getViewVector(1f);
		return new double[] {eye.x, eye.y, eye.z, look.x, look.y, look.z};
	}

	public static int selectedSlot() {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		return player == null ? -1 : player.getInventory().getSelectedSlot();
	}

	public static boolean spectator() {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		return player != null && player.isSpectator();
	}

	public static int hotbarKeysDown() {
		var keys = net.minecraft.client.Minecraft.getInstance().options.keyHotbarSlots;
		int mask = 0;
		for (int i = 0; i < keys.length && i < 9; i++) {
			if (keys[i].isDown()) {
				mask |= 1 << i;
			}
		}
		return mask;
	}

	public static void setSelectedSlot(int slot) {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player != null && slot >= 0 && slot < 9) {
			player.getInventory().setSelectedSlot(slot);
		}
	}

	public static int mapColor(Object level, Object state, int x, int y, int z) {
		try {
			return s(state).getMapColor(w(level), pos(x, y, z)).col;
		} catch (RuntimeException e) {
			return 0x808080;
		}
	}

	public static Object blockItem(Object state) {
		return s(state).getBlock().asItem().getDefaultInstance();
	}

	public static boolean opaque(Object state) {
		return s(state).isSolidRender();
	}

	public static String blockName(Object state) {
		return s(state).getBlock().getName().getString();
	}

	public static List<String> allBlockIds() {
		List<String> ids = new ArrayList<>();
		for (var id : net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet()) {
			ids.add(id.toString());
		}
		java.util.Collections.sort(ids);
		return ids;
	}

	public static boolean hasNeighborSignal(Object level, int x, int y, int z) {
		return w(level).hasNeighborSignal(pos(x, y, z));
	}

	public static int comparatorOutput(Object level, int x, int y, int z) {
		return w(level).getBlockEntity(pos(x, y, z)) instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity c
				? c.getOutputSignal() : -1;
	}

	public static void refreshShape(Object level, int x, int y, int z) {
		var p = pos(x, y, z);
		var state = w(level).getBlockState(p);
		var updated = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(state, (net.minecraft.world.level.LevelAccessor) w(level), p);
		if (updated != state) {
			w(level).setBlock(p, updated, 2);
		}
	}

	public static List<int[]> itemEntities(Object level, double x0, double y0, double z0, double x1, double y1, double z1) {
		List<int[]> out = new ArrayList<>();
		var box = new net.minecraft.world.phys.AABB(x0, y0, z0, x1, y1, z1);
		for (var e : w(level).getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box, e -> true)) {
			out.add(new int[] {e.getId(), e.getItem().getCount()});
		}
		return out;
	}

	public static boolean pickItem(Object state) {
		var client = net.minecraft.client.Minecraft.getInstance();
		var player = client.player;
		if (player == null || client.gameMode == null) {
			return false;
		}
		var item = s(state).getBlock().asItem();
		if (item == net.minecraft.world.item.Items.AIR) {
			return false;
		}
		var inventory = player.getInventory();
		for (int i = 0; i < 9; i++) {
			if (inventory.getItem(i).is(item)) {
				inventory.setSelectedSlot(i);
				return true;
			}
		}
		int selected = inventory.getSelectedSlot();
		for (int i = 9; i < 36; i++) {
			if (inventory.getItem(i).is(item)) {
				client.gameMode.handleContainerInput(player.inventoryMenu.containerId, i, selected,
						net.minecraft.world.inventory.ContainerInput.SWAP, player);
				return true;
			}
		}
		if (player.isCreative()) {
			var stack = item.getDefaultInstance();
			inventory.setItem(selected, stack);
			client.gameMode.handleCreativeModeItemAdd(stack, 36 + selected);
			return true;
		}
		return false;
	}

	public static boolean runsNormally(Object level) {
		return w(level).tickRateManager().runsNormally();
	}

	public static int[] playerBlock() {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		return player == null ? null : new int[] {player.getBlockX(), player.getBlockY(), player.getBlockZ()};
	}

	public static int playerFacing() {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		return player == null ? 2 : player.getDirection().ordinal();
	}

	public static int[] lookedBlock(double range) {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player == null) {
			return null;
		}
		var hit = player.pick(range, 1f, false);
		if (hit instanceof net.minecraft.world.phys.BlockHitResult block && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			var p = block.getBlockPos();
			return new int[] {p.getX(), p.getY(), p.getZ(), block.getDirection().ordinal()};
		}
		return null;
	}
	*///?}
}
