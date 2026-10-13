package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;

/**
 * Redstone Mode's area operations - copy, cut, paste, move, stack, erase, replace, undo, redo -
 * run on the singleplayer server thread. Blocks are placed without neighbour/shape updates (so
 * observers don't fire and nothing pops off), then, with "Keep Redstone Running" on, redstone
 * components get their pending ticks back and a neighbour update, so pasted clocks keep ticking
 * and pistons don't get stuck. Every edit is undoable.
 *
 * <p>To protect the PC an operation is limited to {@link #MAX_VOLUME} blocks, and the undo
 * history keeps at most {@link #MAX_UNDO_VOLUME} blocks in total.
 */
public final class RmOps {

	public static final int MAX_VOLUME = 262_144;
	private static final long MAX_UNDO_VOLUME = 2_000_000;
	private static final int MAX_UNDO = 30;

	/** Blocks that matter for keeping redstone alive after a paste. */
	private static final Set<String> REDSTONE = Set.of("minecraft:observer", "minecraft:repeater", "minecraft:comparator",
			"minecraft:piston", "minecraft:sticky_piston", "minecraft:redstone_torch", "minecraft:redstone_wall_torch",
			"minecraft:redstone_wire", "minecraft:redstone_lamp", "minecraft:dropper", "minecraft:dispenser", "minecraft:hopper",
			"minecraft:powered_rail", "minecraft:activator_rail", "minecraft:detector_rail", "minecraft:note_block", "minecraft:target",
			"minecraft:daylight_detector", "minecraft:sculk_sensor", "minecraft:calibrated_sculk_sensor", "minecraft:crafter",
			"minecraft:lever", "minecraft:stone_button", "minecraft:tnt", "minecraft:redstone_block");

	private record Undo(int x, int y, int z, Clip before, Clip after) {
	}

	private static final Deque<Undo> UNDO = new ArrayDeque<>();
	private static final Deque<Undo> REDO = new ArrayDeque<>();
	private static volatile Clip clipboard;

	private RmOps() {
	}

	public static Clip clipboard() {
		return clipboard;
	}

	public static void setClipboard(Clip clip) {
		clipboard = clip;
	}

	private static void client(Runnable task) {
		MinecraftClient.getInstance().execute(task);
	}

	private static boolean checkVolume(long volume) {
		if (!RmWorld.singleplayer()) {
			RmState.error("World editing only works in singleplayer");
			return false;
		}
		if (volume > MAX_VOLUME) {
			RmState.error("Selection too big (" + volume + " blocks, max " + MAX_VOLUME + ")");
			return false;
		}
		return true;
	}

	// ---- capture / place (server thread) ----

	static Clip capture(Object level, int x0, int y0, int z0, int sx, int sy, int sz) {
		return capture(level, x0, y0, z0, sx, sy, sz, false);
	}

	/** {@code withEntities}: also take the entities in the box (copies / schematics - not undo snapshots). */
	static Clip capture(Object level, int x0, int y0, int z0, int sx, int sy, int sz, boolean withEntities) {
		Clip clip = new Clip(sx, sy, sz);
		if (withEntities) {
			clip.entities.addAll(RmWorld.captureEntities(level, x0, y0, z0, x0 + sx - 1, y0 + sy - 1, z0 + sz - 1));
		}
		for (int x = 0; x < sx; x++) {
			for (int y = 0; y < sy; y++) {
				for (int z = 0; z < sz; z++) {
					int i = clip.index(x, y, z);
					Object state = RmWorld.state(level, x0 + x, y0 + y, z0 + z);
					clip.states[i] = state;
					if (!RmWorld.isAir(state)) {
						clip.blockEntities[i] = RmWorld.saveBe(level, x0 + x, y0 + y, z0 + z);
						if (REDSTONE.contains(RmWorld.id(state))) {
							clip.pendingTicks[i] = RmWorld.hasPendingTick(level, x0 + x, y0 + y, z0 + z);
						}
					}
				}
			}
		}
		return clip;
	}

	/** Writes {@code clip} at x0/y0/z0. {@code skipAir}: air in the clip leaves the world untouched. */
	static void place(Object level, Clip clip, int x0, int y0, int z0, boolean skipAir, boolean keepRunning) {
		for (int x = 0; x < clip.sx; x++) {
			for (int y = 0; y < clip.sy; y++) {
				for (int z = 0; z < clip.sz; z++) {
					Object state = clip.states[clip.index(x, y, z)];
					if (state == null || skipAir && RmWorld.isAir(state)) {
						continue;
					}
					RmWorld.set(level, x0 + x, y0 + y, z0 + z, state, RmWorld.SILENT);
				}
			}
		}
		for (int x = 0; x < clip.sx; x++) {
			for (int y = 0; y < clip.sy; y++) {
				for (int z = 0; z < clip.sz; z++) {
					Object tag = clip.blockEntities[clip.index(x, y, z)];
					if (tag != null) {
						RmWorld.loadBe(level, x0 + x, y0 + y, z0 + z, tag);
					}
				}
			}
		}
		if (keepRunning) {
			wake(level, clip, x0, y0, z0);
		}
		// Entities only once every block is in place (minecarts need their rails, item frames their wall).
		for (Object[] e : clip.entities) {
			RmWorld.spawnEntity(level, e[4], x0 + (double) e[0], y0 + (double) e[1], z0 + (double) e[2], (float) e[3]);
		}
	}

	private static void wake(Object level, Clip clip, int x0, int y0, int z0) {
		for (int x = 0; x < clip.sx; x++) {
			for (int y = 0; y < clip.sy; y++) {
				for (int z = 0; z < clip.sz; z++) {
					int i = clip.index(x, y, z);
					Object state = clip.states[i];
					if (state == null || RmWorld.isAir(state) || !REDSTONE.contains(RmWorld.id(state))) {
						continue;
					}
					if (clip.pendingTicks[i]) {
						RmWorld.scheduleTick(level, x0 + x, y0 + y, z0 + z, 1);
					}
					// Observers react to shape updates, not neighbour updates - so this wakes pistons,
					// repeaters, comparators and dust without making any observer fire.
					RmWorld.neighborUpdate(level, x0 + x, y0 + y, z0 + z);
				}
			}
		}
	}

	private static void remember(int x, int y, int z, Clip before, Clip after) {
		UNDO.addLast(new Undo(x, y, z, before, after));
		REDO.clear();
		long total = 0;
		for (Undo u : UNDO) {
			total += u.before().volume() + u.after().volume();
		}
		while (UNDO.size() > MAX_UNDO || total > MAX_UNDO_VOLUME && UNDO.size() > 1) {
			Undo dropped = UNDO.removeFirst();
			total -= dropped.before().volume() + dropped.after().volume();
		}
	}

	/** Places {@code clip} at the given corner as one undoable step. Server thread. */
	static void applyUndoable(Object level, Clip clip, int x, int y, int z, boolean skipAir) {
		Clip before = capture(level, x, y, z, clip.sx, clip.sy, clip.sz);
		place(level, clip, x, y, z, skipAir, RedstoneModeModule.preserveStates());
		Clip after = capture(level, x, y, z, clip.sx, clip.sy, clip.sz);
		synchronized (RmOps.class) {
			remember(x, y, z, before, after);
		}
	}

	// ---- operations (client thread entry points) ----

	public static void copy(boolean cut) {
		int[] b = RmState.box();
		if (b == null) {
			RmState.error("Select an area first (left + right click)");
			return;
		}
		int sx = b[3] - b[0] + 1;
		int sy = b[4] - b[1] + 1;
		int sz = b[5] - b[2] + 1;
		if (!checkVolume((long) sx * sy * sz)) {
			return;
		}
		RmWorld.onServer(level -> {
			Clip clip = capture(level, b[0], b[1], b[2], sx, sy, sz, true);
			clipboard = clip;
			if (cut) {
				RmWorld.removeEntities(level, b[0], b[1], b[2], b[3], b[4], b[5]);
				applyUndoable(level, air(sx, sy, sz), b[0], b[1], b[2], false);
				updateBorder(level, b);
			}
			client(() -> RmState.info((cut ? "Cut " : "Copied ") + clip.solidCount() + " blocks"
					+ (clip.entities.isEmpty() ? "" : " + " + clip.entities.size() + " entities") + " (" + sx + "x" + sy + "x" + sz + ") - Ctrl+V to paste"));
		});
	}

	private static Clip air(int sx, int sy, int sz) {
		Clip clip = new Clip(sx, sy, sz);
		Object air = RmWorld.parse("minecraft:air", Map.of());
		java.util.Arrays.fill(clip.states, air);
		return clip;
	}

	/** After removing blocks: let the blocks just outside the box notice (dust reconnects, rails settle). */
	private static void updateBorder(Object level, int[] b) {
		for (int x = b[0] - 1; x <= b[3] + 1; x++) {
			for (int y = b[1] - 1; y <= b[4] + 1; y++) {
				for (int z = b[2] - 1; z <= b[5] + 1; z++) {
					boolean inside = x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
					if (!inside) {
						RmWorld.neighborUpdate(level, x, y, z);
					}
				}
			}
		}
	}

	public static void erase() {
		int[] b = RmState.box();
		if (b == null) {
			RmState.error("Select an area first");
			return;
		}
		if (!checkVolume(RmState.volume())) {
			return;
		}
		RmWorld.onServer(level -> {
			applyUndoable(level, air(b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1), b[0], b[1], b[2], false);
			updateBorder(level, b);
			client(() -> RmState.info("Erased " + RmState.volume() + " blocks - Ctrl+Z to undo"));
		});
	}

	/** Pastes the (transformed) clipboard with its minimum corner at x/y/z. */
	public static void paste(Clip clip, int x, int y, int z) {
		if (clip == null || !checkVolume(clip.volume())) {
			return;
		}
		RmWorld.onServer(level -> {
			applyUndoable(level, clip, x, y, z, true);
			client(() -> RmState.info("Pasted " + clip.solidCount() + " blocks"));
		});
	}

	/** Moves the selection: erases the source and pastes the (transformed) copy at x/y/z, as one step each way. */
	public static void move(int x, int y, int z, int rotation, boolean mirror) {
		int[] b = RmState.box();
		if (b == null || !checkVolume(RmState.volume())) {
			return;
		}
		int sx = b[3] - b[0] + 1;
		int sy = b[4] - b[1] + 1;
		int sz = b[5] - b[2] + 1;
		RmWorld.onServer(level -> {
			Clip source = capture(level, b[0], b[1], b[2], sx, sy, sz, true);
			RmWorld.removeEntities(level, b[0], b[1], b[2], b[3], b[4], b[5]);
			applyUndoable(level, air(sx, sy, sz), b[0], b[1], b[2], false);
			Clip moved = source.transformed(rotation, mirror);
			applyUndoable(level, moved, x, y, z, true);
			updateBorder(level, b);
			client(() -> {
				RmState.cornerA = new int[] {x, y, z};
				RmState.cornerB = new int[] {x + moved.sx - 1, y + moved.sy - 1, z + moved.sz - 1};
				RmState.info("Moved - Ctrl+Z twice to undo");
			});
		});
	}

	/** Repeats the selection {@code count} more times towards {@code face}. */
	public static void stack(int count, int face) {
		int[] b = RmState.box();
		if (b == null) {
			return;
		}
		int sx = b[3] - b[0] + 1;
		int sy = b[4] - b[1] + 1;
		int sz = b[5] - b[2] + 1;
		if (!checkVolume((long) sx * sy * sz * (count + 1))) {
			return;
		}
		int[] d = RmWorld.faceOffset(face);
		RmWorld.onServer(level -> {
			Clip source = capture(level, b[0], b[1], b[2], sx, sy, sz, true);
			for (int i = 1; i <= count; i++) {
				applyUndoable(level, source, b[0] + d[0] * sx * i, b[1] + d[1] * sy * i, b[2] + d[2] * sz * i, false);
			}
			client(() -> RmState.info("Stacked " + count + "x - Ctrl+Z per copy to undo"));
		});
	}

	/**
	 * Replaces every block in the selection whose id is {@code fromId} (or every non-air block for
	 * "*") with {@code toText} ("id" or "id[prop=value]"), copying over properties both share
	 * (facing, powered...) unless the new text sets them.
	 */
	public static void replace(String fromId, String toText, boolean keepProperties) {
		int[] b = RmState.box();
		if (b == null || !checkVolume(RmState.volume())) {
			return;
		}
		Object target = RmWorld.fromText(toText);
		if (target == null) {
			RmState.error("Unknown block: " + toText);
			return;
		}
		String explicit = toText.contains("[") ? toText.substring(toText.indexOf('[')) : "";
		RmWorld.onServer(level -> {
			int sx = b[3] - b[0] + 1;
			int sy = b[4] - b[1] + 1;
			int sz = b[5] - b[2] + 1;
			Clip before = capture(level, b[0], b[1], b[2], sx, sy, sz);
			Clip after = new Clip(sx, sy, sz);
			int changed = 0;
			for (int i = 0; i < before.states.length; i++) {
				Object state = before.states[i];
				boolean match = "*".equals(fromId) ? !RmWorld.isAir(state) : RmWorld.id(state).equals(fromId);
				if (!match) {
					after.states[i] = null;
					continue;
				}
				Object next = target;
				if (keepProperties) {
					String text = RmWorld.toText(state);
					int bracket = text.indexOf('[');
					if (bracket > 0) {
						for (String pair : text.substring(bracket + 1, text.length() - 1).split(",")) {
							int eq = pair.indexOf('=');
							if (eq > 0 && !explicit.contains(pair.substring(0, eq) + "=")) {
								next = RmWorld.with(next, pair.substring(0, eq), pair.substring(eq + 1));
							}
						}
					}
				}
				after.states[i] = next;
				changed++;
			}
			place(level, after, b[0], b[1], b[2], true, RedstoneModeModule.preserveStates());
			Clip result = capture(level, b[0], b[1], b[2], sx, sy, sz);
			synchronized (RmOps.class) {
				remember(b[0], b[1], b[2], before, result);
			}
			int count = changed;
			client(() -> RmState.info("Replaced " + count + " blocks - Ctrl+Z to undo"));
		});
	}

	public static void undo() {
		Undo u;
		synchronized (RmOps.class) {
			u = UNDO.pollLast();
			if (u != null) {
				REDO.addLast(u);
			}
		}
		if (u == null) {
			RmState.error("Nothing to undo");
			return;
		}
		RmWorld.onServer(level -> place(level, u.before(), u.x(), u.y(), u.z(), false, RedstoneModeModule.preserveStates()));
		RmState.info("Undone");
	}

	public static void redo() {
		Undo u;
		synchronized (RmOps.class) {
			u = REDO.pollLast();
			if (u != null) {
				UNDO.addLast(u);
			}
		}
		if (u == null) {
			RmState.error("Nothing to redo");
			return;
		}
		RmWorld.onServer(level -> place(level, u.after(), u.x(), u.y(), u.z(), false, RedstoneModeModule.preserveStates()));
		RmState.info("Redone");
	}

	/** Records an edit made elsewhere (wiring) so it can be undone too. Server thread. */
	static void rememberEdit(int x, int y, int z, Clip before, Clip after) {
		synchronized (RmOps.class) {
			remember(x, y, z, before, after);
		}
	}

	/** Forget everything when leaving the world. */
	public static void reset() {
		synchronized (RmOps.class) {
			UNDO.clear();
			REDO.clear();
		}
	}
}
