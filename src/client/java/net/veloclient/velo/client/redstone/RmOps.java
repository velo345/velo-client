package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;

/**
 * Redstone Mode's edits - copy, cut, paste, move, stack, erase, replace, undo, redo - on the
 * singleplayer server. Copying reads the area in one go; everything that writes blocks runs as a
 * {@link RmJobs} job (a slice per tick, cancellable with End). There's no size limit beyond what
 * the PC can hold; the undo history drops its oldest steps past {@link #MAX_UNDO_BLOCKS} changed
 * blocks.
 */
public final class RmOps {

	private static final long MAX_UNDO_BLOCKS = 20_000_000;
	private static final int MAX_UNDO = 40;

	private static final Set<String> REDSTONE = Set.of("minecraft:observer", "minecraft:repeater", "minecraft:comparator",
			"minecraft:piston", "minecraft:sticky_piston", "minecraft:redstone_torch", "minecraft:redstone_wall_torch",
			"minecraft:redstone_wire", "minecraft:redstone_lamp", "minecraft:dropper", "minecraft:dispenser", "minecraft:hopper",
			"minecraft:powered_rail", "minecraft:activator_rail", "minecraft:detector_rail", "minecraft:note_block", "minecraft:target",
			"minecraft:daylight_detector", "minecraft:sculk_sensor", "minecraft:calibrated_sculk_sensor", "minecraft:crafter",
			"minecraft:lever", "minecraft:tnt", "minecraft:redstone_block", "minecraft:trapped_chest", "minecraft:lectern");

	private record Undo(int x, int y, int z, RmJobs.Source before, RmJobs.Source after, boolean skipAir, long blocks) {
	}

	private static final Deque<Undo> UNDO = new ArrayDeque<>();
	private static final Deque<Undo> REDO = new ArrayDeque<>();
	private static volatile Clip clipboard;

	private RmOps() {
	}

	static boolean isRedstone(String id) {
		return REDSTONE.contains(id) || id.endsWith("_button") || id.endsWith("pressure_plate");
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

	private static boolean canEdit() {
		if (!RmWorld.singleplayer()) {
			RmState.error("Editing the world needs singleplayer");
			return false;
		}
		return true;
	}

	// ---- capture (server thread) ----

	static Clip capture(Object level, int x0, int y0, int z0, int sx, int sy, int sz) {
		return capture(level, x0, y0, z0, sx, sy, sz, false);
	}

	static Clip capture(Object level, int x0, int y0, int z0, int sx, int sy, int sz, boolean withEntities) {
		Clip clip = new Clip(sx, sy, sz);
		for (int x = 0; x < sx; x++) {
			for (int y = 0; y < sy; y++) {
				for (int z = 0; z < sz; z++) {
					int i = clip.index(x, y, z);
					Object state = RmWorld.state(level, x0 + x, y0 + y, z0 + z);
					clip.states[i] = state;
					if (!RmWorld.isAir(state)) {
						clip.blockEntities[i] = RmWorld.saveBe(level, x0 + x, y0 + y, z0 + z);
						if (isRedstone(RmWorld.id(state))) {
							clip.pendingTicks[i] = RmWorld.hasPendingTick(level, x0 + x, y0 + y, z0 + z);
						}
					}
				}
			}
		}
		if (withEntities) {
			clip.entities.addAll(RmWorld.captureEntities(level, x0, y0, z0, x0 + sx - 1, y0 + sy - 1, z0 + sz - 1));
		}
		return clip;
	}

	private static void runJob(Object level, RmJobs.Source source, int x, int y, int z, boolean skipAir, String label, Runnable onDone) {
		RmJobs.submit(new RmJobs.Job(level, source, x, y, z, skipAir, RmSettings.get().keepRunning, true, label, onDone));
	}

	static void rememberJob(RmJobs.Job job, int sx, int sy, int sz) {
		RmJobs.Source before = job.changes.asSource(sx, sy, sz);
		synchronized (RmOps.class) {
			UNDO.addLast(new Undo(job.x0, job.y0, job.z0, before, job.source, job.skipAir, before.count()));
			REDO.clear();
			trim();
		}
	}

	/** Records an edit made outside a job (wiring) so it can be undone too. Server thread. */
	static void rememberEdit(int x, int y, int z, Clip before, Clip after) {
		synchronized (RmOps.class) {
			UNDO.addLast(new Undo(x, y, z, RmJobs.Source.of(before), RmJobs.Source.of(after), false, before.volume()));
			REDO.clear();
			trim();
		}
	}

	/** Undo entry for a build that touched only some blocks of a large box (wiring). Server thread. */
	static void rememberSparse(int x0, int y0, int z0, int sx, int sy, int sz, RmJobs.Changes changes, java.util.List<Wiring.Placement> placed) {
		int[] indices = new int[placed.size()];
		Object[] states = new Object[placed.size()];
		for (int i = 0; i < placed.size(); i++) {
			Wiring.Placement p = placed.get(i);
			indices[i] = ((p.x() - x0) * sy + (p.y() - y0)) * sz + (p.z() - z0);
			states[i] = p.state();
		}
		RmJobs.Source after = new RmJobs.Source(sx, sy, sz, indices, states, null, null, java.util.List.of());
		RmJobs.Source before = changes.asSource(sx, sy, sz);
		synchronized (RmOps.class) {
			UNDO.addLast(new Undo(x0, y0, z0, before, after, false, before.count()));
			REDO.clear();
			trim();
		}
	}

	private static void trim() {
		long total = 0;
		for (Undo u : UNDO) {
			total += u.blocks();
		}
		while (UNDO.size() > MAX_UNDO || total > MAX_UNDO_BLOCKS && UNDO.size() > 1) {
			total -= UNDO.removeFirst().blocks();
		}
	}

	private static Clip air(int sx, int sy, int sz) {
		Clip clip = new Clip(sx, sy, sz);
		java.util.Arrays.fill(clip.states, RmWorld.parse("minecraft:air", Map.of()));
		return clip;
	}

	/** After removing blocks: let the blocks around the box notice (dust reconnects, rails settle). */
	private static void updateBorder(Object level, int[] b) {
		long faces = 2L * ((long) (b[3] - b[0] + 3) * (b[4] - b[1] + 3) + (long) (b[3] - b[0] + 3) * (b[5] - b[2] + 3)
				+ (long) (b[4] - b[1] + 3) * (b[5] - b[2] + 3));
		if (faces > 2_000_000) {
			return;
		}
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

	// ---- operations (client thread) ----

	public static void copy(boolean cut) {
		int[] b = RmState.box();
		if (b == null) {
			RmState.error("Select an area first");
			return;
		}
		if (!canEdit()) {
			return;
		}
		int[] s = RmState.size();
		RmState.info(cut ? "Cutting..." : "Copying...");
		RmWorld.onServer(level -> {
			Clip clip = capture(level, b[0], b[1], b[2], s[0], s[1], s[2], true);
			clipboard = clip;
			if (cut) {
				RmWorld.removeEntities(level, b[0], b[1], b[2], b[3], b[4], b[5]);
				runJob(level, RmJobs.Source.of(air(s[0], s[1], s[2])), b[0], b[1], b[2], false, "Cutting", () -> RmState.info("Cut done"));
			}
			client(() -> RmState.info((cut ? "Cut " : "Copied ") + clip.solidCount() + " blocks"
					+ (clip.entities.isEmpty() ? "" : " + " + clip.entities.size() + " entities") + " - Ctrl+V to paste"));
		});
	}

	public static void erase() {
		int[] b = RmState.box();
		if (b == null) {
			RmState.error("Select an area first");
			return;
		}
		if (!canEdit()) {
			return;
		}
		int[] s = RmState.size();
		RmWorld.onServer(level -> runJob(level, RmJobs.Source.of(air(s[0], s[1], s[2])), b[0], b[1], b[2], false, "Erasing", () -> {
			RmWorld.onServer(l -> updateBorder(l, b));
			RmState.info("Erased - Ctrl+Z to undo");
		}));
	}

	public static void paste(Clip clip, int x, int y, int z) {
		if (clip == null || !canEdit()) {
			return;
		}
		RmWorld.onServer(level -> runJob(level, RmJobs.Source.of(clip), x, y, z, true, "Pasting",
				() -> RmState.info("Pasted " + clip.solidCount() + " blocks - Ctrl+Z to undo")));
	}

	/** Applies the Move tool's offset / rotation to the selection. */
	public static void applyMove() {
		int[] b = RmState.box();
		if (b == null || !RmState.moveActive() || !canEdit()) {
			return;
		}
		int[] s = RmState.size();
		int[] off = RmState.moveOffset.clone();
		int rotation = RmState.moveRotation;
		boolean mirror = RmState.moveMirror;
		RmWorld.onServer(level -> {
			Clip source = capture(level, b[0], b[1], b[2], s[0], s[1], s[2], true);
			Clip moved = source.transformed(rotation, mirror);
			// Rotation keeps the box's centre column in place.
			int nx = b[0] + off[0] + (s[0] - moved.sx) / 2;
			int nz = b[2] + off[2] + (s[2] - moved.sz) / 2;
			int ny = b[1] + off[1];
			RmWorld.removeEntities(level, b[0], b[1], b[2], b[3], b[4], b[5]);
			runJob(level, RmJobs.Source.of(air(s[0], s[1], s[2])), b[0], b[1], b[2], false, "Moving", null);
			runJob(level, RmJobs.Source.of(moved), nx, ny, nz, true, "Moving", () -> {
				RmState.cornerA = new int[] {nx, ny, nz};
				RmState.cornerB = new int[] {nx + moved.sx - 1, ny + moved.sy - 1, nz + moved.sz - 1};
				RmState.resetTransforms();
				RmState.info("Moved - Ctrl+Z twice to undo");
			});
		});
	}

	/** Applies the Stack tool's copies. */
	public static void applyStack() {
		int[] b = RmState.box();
		int count = RmState.stackCount;
		if (b == null || count == 0 || !canEdit()) {
			return;
		}
		int[] s = RmState.size();
		int[] d = RmWorld.faceOffset(RmState.stackFace);
		RmWorld.onServer(level -> {
			Clip source = capture(level, b[0], b[1], b[2], s[0], s[1], s[2], true);
			for (int i = 1; i <= Math.abs(count); i++) {
				boolean last = i == Math.abs(count);
				runJob(level, RmJobs.Source.of(source), b[0] + d[0] * s[0] * i, b[1] + d[1] * s[1] * i, b[2] + d[2] * s[2] * i, false,
						"Stacking " + i + "/" + Math.abs(count), last ? () -> {
							RmState.resetTransforms();
							RmState.info("Stacked " + Math.abs(count) + "x - Ctrl+Z per copy to undo");
						} : null);
			}
		});
	}

	/**
	 * Replaces every block in the selection whose id is {@code fromId} ("*" = every non-air block)
	 * with {@code toText} ("id" or "id[prop=value]"), carrying over shared properties unless set.
	 */
	public static void replace(String fromId, String toText, boolean keepProperties) {
		int[] b = RmState.box();
		if (b == null || !canEdit()) {
			return;
		}
		Object target = RmWorld.fromText(toText);
		if (target == null) {
			RmState.error("Unknown block: " + toText);
			return;
		}
		String explicit = toText.contains("[") ? toText.substring(toText.indexOf('[')) : "";
		int[] s = RmState.size();
		RmWorld.onServer(level -> {
			Clip current = capture(level, b[0], b[1], b[2], s[0], s[1], s[2]);
			Clip after = new Clip(s[0], s[1], s[2]);
			int changed = 0;
			for (int i = 0; i < current.states.length; i++) {
				Object state = current.states[i];
				boolean match = "*".equals(fromId) ? !RmWorld.isAir(state) : RmWorld.id(state).equals(fromId);
				if (!match) {
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
			int count = changed;
			runJob(level, RmJobs.Source.of(after), b[0], b[1], b[2], false, "Replacing", () -> RmState.info("Replaced " + count + " blocks"));
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
		RmWorld.onServer(level -> RmJobs.submit(new RmJobs.Job(level, u.before(), u.x(), u.y(), u.z(), false, RmSettings.get().keepRunning,
				false, "Undoing", () -> RmState.info("Undone"))));
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
		RmWorld.onServer(level -> RmJobs.submit(new RmJobs.Job(level, u.after(), u.x(), u.y(), u.z(), u.skipAir(), RmSettings.get().keepRunning,
				false, "Redoing", () -> RmState.info("Redone"))));
	}

	public static void reset() {
		synchronized (RmOps.class) {
			UNDO.clear();
			REDO.clear();
		}
	}
}
