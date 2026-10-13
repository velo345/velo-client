package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Redstone Mode's wiring tools: pick a start (left click) and an end (right click) and a route is
 * planned and previewed; Enter / left click builds it (one undoable step).
 *
 * <p><b>Redstone wire (Dust)</b> walks flat, steps up and down staircases (never under a block
 * that would cut the line) and climbs straight up with torch towers that keep the input's polarity
 * (an even number of torches), places a support block under every dust (a full conducting block -
 * the configured one, or the one the start is built from), and puts repeaters on straight runs so
 * no line is longer than 15 dust. Delay Mode adds a repeater with the configured delay. The last
 * dust always points into the target. <b>Observer</b> wiring builds an observer chain that can turn
 * and climb freely, each observer watching the previous one; the last one fires into the target.
 *
 * <p><b>Water</b> wiring builds an item stream: an ice floor (blue / packed, or whatever nearby
 * streams use), walls, a water source every 8 blocks with a sign separating the sections, a soul
 * sand bubble elevator (capped so items can't jump out) to go up, and a walled drop shaft to go
 * down. Streams can end on top of a hopper / container or join an existing water stream.
 *
 * <p>"Go Around" avoids existing blocks; "Go Through" may replace plain blocks on the way. Planning
 * is capped ({@link #MAX_EXPANSIONS} search steps, {@link #MAX_DISTANCE} blocks) so it can't hang
 * the game.
 */
public final class Wiring {

	public static final int MAX_EXPANSIONS = 150_000;
	public static final int MAX_DISTANCE = 160;

	/** One block to place; {@code order}: 0 support/structure, 1 components, 2 dust, 3 water. */
	public record Placement(int x, int y, int z, Object state, int order) {
	}

	private static volatile List<Placement> preview = List.of();
	private static volatile String summary;

	private Wiring() {
	}

	public static List<Placement> preview() {
		return preview;
	}

	public static String summary() {
		return summary;
	}

	public static void clearPreview() {
		preview = List.of();
		summary = null;
		if (RmState.pending == RmState.Pending.WIRE || RmState.pending == RmState.Pending.WATER) {
			RmState.pending = RmState.Pending.NONE;
		}
	}

	static long key(int x, int y, int z) {
		return ((long) (x + 30_000_000) << 38) | ((long) (y + 2048) << 26) | (z + 30_000_000);
	}

	// ---- shared world checks (client level) ----

	private static boolean protectedBlock(String id) {
		return id.contains("redstone") || id.contains("repeater") || id.contains("comparator") || id.contains("observer")
				|| id.contains("piston") || id.contains("hopper") || id.contains("chest") || id.contains("barrel") || id.contains("dispenser")
				|| id.contains("dropper") || id.contains("furnace") || id.contains("shulker") || id.equals("minecraft:bedrock")
				|| id.contains("lever") || id.contains("button") || id.contains("rail") || id.contains("crafter") || id.contains("command_block");
	}

	/** May a new block go here? Air / replaceable always; plain blocks only in "Go Through" mode. */
	private static boolean placeable(Object level, int x, int y, int z, boolean through) {
		Object state = RmWorld.state(level, x, y, z);
		String id = RmWorld.id(state);
		if (id.contains("water") || id.contains("lava")) {
			return false;
		}
		if (RmWorld.replaceable(state)) {
			return true;
		}
		return through && !protectedBlock(id);
	}

	private static boolean isAirLike(Object level, int x, int y, int z) {
		return RmWorld.replaceable(RmWorld.state(level, x, y, z));
	}

	private static boolean isRedstoneThing(Object level, int x, int y, int z) {
		return protectedBlock(RmWorld.id(RmWorld.state(level, x, y, z)));
	}

	// ---- planning entry ----

	/** Plans a route between the chosen start and end and shows it as a preview. Client thread. */
	public static void plan(boolean water) {
		int[] start = RmState.wireStart;
		int[] end = RmState.wireEnd;
		Object level = RmWorld.clientLevel();
		if (start == null || end == null || level == null) {
			return;
		}
		if (Math.abs(start[0] - end[0]) + Math.abs(start[1] - end[1]) + Math.abs(start[2] - end[2]) > MAX_DISTANCE) {
			RmState.error("Too far - wire at most " + MAX_DISTANCE + " blocks at once");
			return;
		}
		boolean through = "Go Through".equals(RedstoneModeModule.pathMode);
		long t0 = System.nanoTime();
		Result result;
		try {
			if (water) {
				result = WaterRouter.plan(level, start, end, through);
			} else if ("Observer".equals(RedstoneModeModule.wireStyle)) {
				result = planObservers(level, start, end, through);
			} else {
				result = planDust(level, start, end, through);
			}
		} catch (RuntimeException e) {
			net.veloclient.velo.VeloClient.LOGGER.warn("Wiring planner failed", e);
			result = Result.fail("Couldn't plan this route");
		}
		if (result.error() != null) {
			clearPreview();
			RmState.error(result.error());
			return;
		}
		preview = result.placements();
		summary = result.summary();
		RmState.pending = water ? RmState.Pending.WATER : RmState.Pending.WIRE;
		RmState.info(result.summary() + "  (" + (System.nanoTime() - t0) / 1_000_000 + " ms) - Enter / left click to build, Backspace to cancel");
	}

	/** Builds the previewed route on the server, as one undoable step. */
	public static void build() {
		List<Placement> placements = preview;
		if (placements.isEmpty()) {
			return;
		}
		if (!RmWorld.singleplayer()) {
			RmState.error("Building needs singleplayer");
			return;
		}
		int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (Placement p : placements) {
			b[0] = Math.min(b[0], p.x());
			b[1] = Math.min(b[1], p.y());
			b[2] = Math.min(b[2], p.z());
			b[3] = Math.max(b[3], p.x());
			b[4] = Math.max(b[4], p.y());
			b[5] = Math.max(b[5], p.z());
		}
		long volume = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
		List<Placement> sorted = new ArrayList<>(placements);
		sorted.sort(java.util.Comparator.comparingInt(Placement::order));
		RmWorld.onServer(level -> {
			Clip before = volume <= RmOps.MAX_VOLUME
					? RmOps.capture(level, b[0], b[1], b[2], b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1) : null;
			for (Placement p : sorted) {
				RmWorld.set(level, p.x(), p.y(), p.z(), p.state(), RmWorld.NORMAL);
			}
			if (before != null) {
				Clip after = RmOps.capture(level, b[0], b[1], b[2], b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1);
				RmOps.rememberEdit(b[0], b[1], b[2], before, after);
			}
			MinecraftClient.getInstance().execute(() -> {
				RmState.info("Built " + sorted.size() + " blocks - Ctrl+Z to undo");
				clearPreview();
				RmState.wireStart = null;
				RmState.wireEnd = null;
			});
		});
	}

	public record Result(List<Placement> placements, String summary, String error) {
		static Result fail(String error) {
			return new Result(List.of(), null, error);
		}
	}

	// ---- dust router ----

	private static final int[][] HORIZONTAL = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
	/** Face index of each HORIZONTAL entry (north, south, west, east). */
	private static final int[] HORIZONTAL_FACE = {2, 3, 4, 5};

	private enum Move { START, FLAT, UP, DOWN, TOWER }

	private static final class Node implements Comparable<Node> {
		final int x;
		final int y;
		final int z;
		final int dir;
		final Move move;
		final int g;
		final int f;
		final Node parent;

		Node(int x, int y, int z, int dir, Move move, int g, int h, Node parent) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.dir = dir;
			this.move = move;
			this.g = g;
			this.f = g + h;
			this.parent = parent;
		}

		@Override
		public int compareTo(Node o) {
			return Integer.compare(f, o.f);
		}
	}

	private static int manhattan(int x, int y, int z, int[] t) {
		return Math.abs(x - t[0]) + Math.abs(y - t[1]) + Math.abs(z - t[2]);
	}

	static Result planDust(Object level, int[] start, int[] end, boolean through) {
		int[] so = RmWorld.faceOffset(start[3]);
		int[] eo = RmWorld.faceOffset(end[3]);
		int[] s = {start[0] + so[0], start[1] + so[1], start[2] + so[2]};
		int[] e = {end[0] + eo[0], end[1] + eo[1], end[2] + eo[2]};
		if (end[3] == 0) {
			return Result.fail("Dust can't power a block from below - pick the top or a side of the target");
		}
		if (!placeable(level, s[0], s[1], s[2], through) || !placeable(level, e[0], e[1], e[2], through)) {
			return Result.fail("Start or end spot is blocked");
		}
		// Arriving at the end must point into the target (from the opposite side) unless the dust sits on top of it.
		int requiredDir = -1;
		if (end[3] >= 2) {
			int[] towardTarget = {-eo[0], 0, -eo[2]};
			for (int i = 0; i < 4; i++) {
				if (HORIZONTAL[i][0] == towardTarget[0] && HORIZONTAL[i][2] == towardTarget[2]) {
					requiredDir = i;
				}
			}
		}
		Set<Long> endpoints = Set.of(key(start[0], start[1], start[2]), key(end[0], end[1], end[2]));
		PriorityQueue<Node> open = new PriorityQueue<>();
		Map<Long, Integer> best = new HashMap<>();
		open.add(new Node(s[0], s[1], s[2], -1, Move.START, 0, manhattan(s[0], s[1], s[2], e), null));
		int expansions = 0;
		Node goal = null;
		while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
			Node n = open.poll();
			if (n.x == e[0] && n.y == e[1] && n.z == e[2] && (requiredDir < 0 || n.dir == requiredDir || n.move == Move.START && requiredDir < 0)) {
				goal = n;
				break;
			}
			long stateKey = key(n.x, n.y, n.z) * 5 + (n.dir + 1);
			Integer seen = best.get(stateKey);
			if (seen != null && seen <= n.g) {
				continue;
			}
			best.put(stateKey, n.g);
			for (int d = 0; d < 4; d++) {
				int dx = HORIZONTAL[d][0];
				int dz = HORIZONTAL[d][2];
				int nx = n.x + dx;
				int nz = n.z + dz;
				if (Math.abs(nx - s[0]) + Math.abs(nz - s[2]) > MAX_DISTANCE + 16) {
					continue;
				}
				int turnCost = n.dir >= 0 && n.dir != d ? 1 : 0;
				// Flat.
				if (dustCellOk(level, nx, n.y, nz, through, endpoints)) {
					push(open, level, nx, n.y, nz, d, Move.FLAT, n, 2 + turnCost, e, endpoints);
				}
				// Up a stair: the block in front becomes the support, nothing conducting above us.
				if (!RmWorld.conductor(level, n.x, n.y + 1, n.z) && supportOk(level, nx, n.y, nz, through)
						&& dustCellOk(level, nx, n.y + 1, nz, through, endpoints)) {
					push(open, level, nx, n.y + 1, nz, d, Move.UP, n, 3 + turnCost, e, endpoints);
				}
				// Down a stair: the cell in front must be open so the line isn't cut.
				if (isAirLike(level, nx, n.y, nz) && dustCellOk(level, nx, n.y - 1, nz, through, endpoints)) {
					push(open, level, nx, n.y - 1, nz, d, Move.DOWN, n, 3 + turnCost, e, endpoints);
				}
				// Torch tower: rises 5 with two torches (same polarity as the input).
				if (e[1] - n.y >= 4 && towerOk(level, nx, n.y, nz, through)) {
					push(open, level, nx, n.y + 5, nz, d, Move.TOWER, n, 14 + turnCost, e, endpoints);
				}
			}
		}
		if (goal == null) {
			return Result.fail(expansions >= MAX_EXPANSIONS ? "No route found within the search limit - try a closer end or Go Through"
					: "No route found - try Go Through, or a different start/end");
		}
		return buildDust(level, start, goal, through);
	}

	private static void push(PriorityQueue<Node> open, Object level, int x, int y, int z, int dir, Move move, Node parent, int cost, int[] goal,
			Set<Long> endpoints) {
		int penalty = 0;
		// Don't brush past other redstone - a new line next to it would connect and leak.
		for (int[] h : HORIZONTAL) {
			if (isRedstoneThing(level, x + h[0], y, z + h[2]) && !endpoints.contains(key(x + h[0], y, z + h[2]))) {
				penalty += 25;
			}
		}
		if (isRedstoneThing(level, x, y - 1, z)) {
			penalty += 25;
		}
		int g = parent.g + cost + penalty;
		open.add(new Node(x, y, z, dir, move, g, manhattan(x, y, z, goal), parent));
	}

	private static boolean supportOk(Object level, int x, int y, int z, boolean through) {
		return RmWorld.conductor(level, x, y, z) || placeable(level, x, y, z, through);
	}

	private static boolean dustCellOk(Object level, int x, int y, int z, boolean through, Set<Long> endpoints) {
		return placeable(level, x, y, z, through) && !endpoints.contains(key(x, y, z))
				&& (supportOk(level, x, y - 1, z, through) || endpoints.contains(key(x, y - 1, z)));
	}

	private static boolean towerOk(Object level, int x, int y, int z, boolean through) {
		for (int dy = 0; dy <= 5; dy++) {
			if (!placeable(level, x, y + dy, z, through)) {
				return false;
			}
		}
		return true;
	}

	private static Result buildDust(Object level, int[] start, Node goal, boolean through) {
		List<Node> path = new ArrayList<>();
		for (Node n = goal; n != null; n = n.parent) {
			path.add(0, n);
		}
		String supportId = WireBlocks.supportId(level, start);
		Object support = RmWorld.parse(supportId, Map.of());
		Object dust = RmWorld.parse("minecraft:redstone_wire", Map.of());
		Object torch = RmWorld.parse("minecraft:redstone_torch", Map.of());
		Map<Long, Placement> out = new HashMap<>();
		Set<Long> repeaters = new HashSet<>();
		// Decide repeater spots: never more than 15 dust after a source; Delay Mode always gets one.
		boolean delayMode = RedstoneModeModule.delayMode;
		int run = 0;
		boolean placedDelay = false;
		for (int i = 0; i < path.size(); i++) {
			Node n = path.get(i);
			if (n.move == Move.TOWER) {
				run = 0;
			}
			run++;
			boolean straight = i > 0 && i + 1 < path.size() && n.move == Move.FLAT && path.get(i + 1).move == Move.FLAT
					&& path.get(i + 1).dir == n.dir;
			boolean want = run >= 14 || delayMode && !placedDelay && i >= 1;
			if (straight && want) {
				repeaters.add(key(n.x, n.y, n.z));
				run = 0;
				placedDelay = true;
			}
			if (run > 15) {
				return Result.fail("Line needs a repeater but there's no straight 3-block stretch - try a different route");
			}
		}
		int repeaterCount = 0;
		int torchCount = 0;
		for (int i = 0; i < path.size(); i++) {
			Node n = path.get(i);
			if (n.move == Move.TOWER) {
				Node p = n.parent;
				int tx = n.x;
				int tz = n.z;
				int y0 = p.y;
				put(out, tx, y0, tz, support, 0);
				put(out, tx, y0 + 1, tz, torch, 1);
				put(out, tx, y0 + 2, tz, support, 0);
				put(out, tx, y0 + 3, tz, torch, 1);
				put(out, tx, y0 + 4, tz, support, 0);
				torchCount += 2;
			}
			// A support only goes where there is nothing yet (existing blocks - or the target itself - carry the dust).
			if (RmWorld.replaceable(RmWorld.state(level, n.x, n.y - 1, n.z)) && !out.containsKey(key(n.x, n.y - 1, n.z))) {
				put(out, n.x, n.y - 1, n.z, support, 0);
			}
			long k = key(n.x, n.y, n.z);
			if (repeaters.contains(k)) {
				int[] opposite = {-HORIZONTAL[n.dir][0], 0, -HORIZONTAL[n.dir][2]};
				String facing = opposite[0] == 1 ? "east" : opposite[0] == -1 ? "west" : opposite[2] == 1 ? "south" : "north";
				int delay = delayMode ? (int) Math.round(RedstoneModeModule.delayTicks) : 1;
				put(out, n.x, n.y, n.z, RmWorld.parse("minecraft:repeater", Map.of("facing", facing, "delay", String.valueOf(delay))), 1);
				repeaterCount++;
			} else {
				put(out, n.x, n.y, n.z, dust, 2);
			}
		}
		String conflict = verify(level, out, through);
		if (conflict != null) {
			return Result.fail(conflict);
		}
		String summary = "Wire: " + path.size() + " dust/steps, " + repeaterCount + " repeater" + (repeaterCount == 1 ? "" : "s")
				+ (torchCount > 0 ? ", " + torchCount + " tower torches" : "") + " on " + supportId.replace("minecraft:", "");
		return new Result(new ArrayList<>(out.values()), summary, null);
	}

	private static void put(Map<Long, Placement> out, int x, int y, int z, Object state, int order) {
		long k = key(x, y, z);
		Placement existing = out.get(k);
		// Structure must never overwrite a component / dust already planned at the same spot.
		if (existing == null || existing.order() < order) {
			out.put(k, new Placement(x, y, z, state, order));
		}
	}

	/** Final check: every planned cell is buildable, and dust/components didn't collide with each other. */
	private static String verify(Object level, Map<Long, Placement> out, boolean through) {
		for (Placement p : out.values()) {
			Object current = RmWorld.state(level, p.x(), p.y(), p.z());
			if (p.order() == 0 && RmWorld.conductor(level, p.x(), p.y(), p.z())) {
				continue;
			}
			if (!RmWorld.replaceable(current) && !(through && !protectedBlock(RmWorld.id(current)))) {
				return "Route crosses " + RmWorld.id(current).replace("minecraft:", "") + " at " + p.x() + " " + p.y() + " " + p.z();
			}
		}
		return null;
	}

	// ---- observer router ----

	private static final int[][] ALL_DIRS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

	static Result planObservers(Object level, int[] start, int[] end, boolean through) {
		int[] so = RmWorld.faceOffset(start[3]);
		int[] eo = RmWorld.faceOffset(end[3]);
		int[] s = {start[0] + so[0], start[1] + so[1], start[2] + so[2]};
		// The last observer sits next to the target and fires into it, so the one before must be straight behind it.
		int[] last = {end[0] + eo[0], end[1] + eo[1], end[2] + eo[2]};
		int[] beforeLast = {end[0] + 2 * eo[0], end[1] + 2 * eo[1], end[2] + 2 * eo[2]};
		if (!placeable(level, s[0], s[1], s[2], through) || !placeable(level, last[0], last[1], last[2], through)
				|| !placeable(level, beforeLast[0], beforeLast[1], beforeLast[2], through)) {
			return Result.fail("Start or end spot is blocked (observers need 2 free blocks in front of the target)");
		}
		PriorityQueue<Node> open = new PriorityQueue<>();
		Map<Long, Integer> best = new HashMap<>();
		Set<Long> reserved = Set.of(key(last[0], last[1], last[2]), key(start[0], start[1], start[2]), key(end[0], end[1], end[2]));
		open.add(new Node(s[0], s[1], s[2], -1, Move.START, 0, manhattan(s[0], s[1], s[2], beforeLast), null));
		Node goal = null;
		int expansions = 0;
		while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
			Node n = open.poll();
			if (n.x == beforeLast[0] && n.y == beforeLast[1] && n.z == beforeLast[2]) {
				goal = n;
				break;
			}
			long k = key(n.x, n.y, n.z);
			Integer seen = best.get(k);
			if (seen != null && seen <= n.g) {
				continue;
			}
			best.put(k, n.g);
			for (int d = 0; d < 6; d++) {
				int nx = n.x + ALL_DIRS[d][0];
				int ny = n.y + ALL_DIRS[d][1];
				int nz = n.z + ALL_DIRS[d][2];
				if (reserved.contains(key(nx, ny, nz)) || !placeable(level, nx, ny, nz, through)) {
					continue;
				}
				int g = n.g + 1 + (n.dir >= 0 && n.dir != d ? 1 : 0);
				open.add(new Node(nx, ny, nz, d, Move.FLAT, g, manhattan(nx, ny, nz, beforeLast), n));
			}
		}
		if (goal == null) {
			return Result.fail("No observer route found - try Go Through or a closer end");
		}
		List<int[]> cells = new ArrayList<>();
		for (Node n = goal; n != null; n = n.parent) {
			cells.add(0, new int[] {n.x, n.y, n.z});
		}
		cells.add(last);
		Map<Long, Placement> out = new HashMap<>();
		int[] previous = {start[0], start[1], start[2]};
		for (int[] c : cells) {
			String facing = faceTowards(c, previous);
			put(out, c[0], c[1], c[2], RmWorld.parse("minecraft:observer", Map.of("facing", facing)), 1);
			previous = c;
		}
		String conflict = verify(level, out, through);
		if (conflict != null) {
			return Result.fail(conflict);
		}
		return new Result(new ArrayList<>(out.values()), "Observer line: " + cells.size() + " observers (" + cells.size() * 2 + " ticks)", null);
	}

	/** Facing name pointing from {@code from} to the adjacent {@code to}. */
	static String faceTowards(int[] from, int[] to) {
		int dx = Integer.signum(to[0] - from[0]);
		int dy = Integer.signum(to[1] - from[1]);
		int dz = Integer.signum(to[2] - from[2]);
		if (dy > 0) {
			return "up";
		}
		if (dy < 0) {
			return "down";
		}
		if (dx > 0) {
			return "east";
		}
		if (dx < 0) {
			return "west";
		}
		return dz > 0 ? "south" : "north";
	}
}
