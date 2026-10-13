package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redstone Mode's wiring tools: left click a start, right click an end, and a route is found in the
 * background (progress shows above the hotbar) and previewed; left click / Enter builds it as one
 * undoable step.
 *
 * <p>The search heads straight for the target and only spreads out where something is in the way
 * (weighted A*), so routes of hundreds of blocks take a moment rather than forever.
 *
 * <p><b>Dust</b>: a support block under every dust (the chosen one, or the start's own block -
 * always a full block dust works on), flat runs, staircases up and down that never get cut by a
 * block above, polarity-keeping torch towers for straight climbs, a repeater before every 16th dust
 * (Delay Mode: with the chosen delay), no dust running alongside itself, and the last dust pointing
 * into the target. After building, every piece recomputes its connections so no dust is left as a
 * dot. <b>Observer</b>: a chain that turns and climbs freely, each observer watching the previous.
 * Water streams: see {@link WaterRouter}.
 */
public final class Wiring {

	public static final int MAX_EXPANSIONS = 1_500_000;
	public static final int MAX_DISTANCE = 2000;
	private static final double WEIGHT = 1.6;

	public record Placement(int x, int y, int z, Object state, int order) {
	}

	public record Result(List<Placement> placements, String summary, String error) {
		static Result fail(String error) {
			return new Result(List.of(), null, error);
		}
	}

	private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "velo-wire-planner");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	private static final AtomicInteger GENERATION = new AtomicInteger();
	private static volatile List<Placement> preview = List.of();
	private static volatile String summary;
	private static volatile String progress;
	private static volatile Result pending;
	private static volatile Set<Long> pendingOverrides = Set.of();
	/** Cells the current preview may build over although Go Around is on (around its connection points). */
	private static volatile Set<Long> previewOverrides = Set.of();

	private Wiring() {
	}

	public static List<Placement> preview() {
		return preview;
	}

	public static String summary() {
		return summary;
	}

	/** "Finding route... 120k steps" while planning, else null. */
	public static String progressText() {
		return progress;
	}

	public static void clearPreview() {
		GENERATION.incrementAndGet();
		preview = List.of();
		summary = null;
		progress = null;
	}

	/** Forget start, end and route. */
	public static void reset() {
		clearPreview();
		RmState.wireStart = null;
		RmState.wireEnd = null;
	}

	static long key(int x, int y, int z) {
		return ((long) (x + 30_000_000) << 38) | ((long) (y + 2048) << 26) | (z + 30_000_000);
	}

	/** Client tick: hands a finished plan to the preview. */
	static void tick() {
		Result r = pending;
		if (r == null) {
			return;
		}
		pending = null;
		progress = null;
		if (r.error() != null) {
			preview = List.of();
			summary = null;
			RmState.error(r.error());
		} else {
			preview = r.placements();
			previewOverrides = pendingOverrides;
			summary = r.summary();
			RmState.info(r.summary() + " - left click / Enter builds");
		}
	}

	// ---- world access for the planner (client world, cached) ----

	static final class Grid {
		final Object level;
		final boolean through;
		final int generation;
		private final Map<Long, Object> cache = new HashMap<>();
		/** Cells around the two connection points that may be built over even when going around (glass, stone...). */
		final Set<Long> overrides = new HashSet<>();
		int expansions;

		Grid(Object level, boolean through, int generation) {
			this.level = level;
			this.through = through;
			this.generation = generation;
		}

		Object state(int x, int y, int z) {
			return cache.computeIfAbsent(key(x, y, z), k -> RmWorld.state(level, x, y, z));
		}

		String id(int x, int y, int z) {
			return RmWorld.id(state(x, y, z));
		}

		boolean air(int x, int y, int z) {
			return RmWorld.replaceable(state(x, y, z)) && !id(x, y, z).contains("water") && !id(x, y, z).contains("lava");
		}

		boolean conductor(int x, int y, int z) {
			return RmWorld.conductor(level, x, y, z);
		}

		boolean water(int x, int y, int z) {
			return id(x, y, z).equals("minecraft:water");
		}

		/** May a new block go here? Air always; plain blocks only when going through. */
		boolean placeable(int x, int y, int z) {
			if (air(x, y, z)) {
				return true;
			}
			String id = id(x, y, z);
			return (through || overrides.contains(key(x, y, z))) && !protectedBlock(id) && !id.contains("water") && !id.contains("lava");
		}

		/**
		 * The connection points may replace ordinary blocks: the cell in front of each clicked face and
		 * its neighbours (never the clicked blocks themselves, nor redstone / containers).
		 */
		void allowEndpoint(int[] clicked) {
			int[] o = RmWorld.faceOffset(clicked[3]);
			int cx = clicked[0] + o[0];
			int cy = clicked[1] + o[1];
			int cz = clicked[2] + o[2];
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						overrides.add(key(cx + dx, cy + dy, cz + dz));
					}
				}
			}
		}

		boolean cancelled() {
			return generation != GENERATION.get();
		}

		void step(String what) {
			if (++expansions % 20_000 == 0) {
				progress = what + "... " + expansions / 1000 + "k steps";
			}
		}
	}

	static boolean protectedBlock(String id) {
		return id.contains("redstone") || id.contains("repeater") || id.contains("comparator") || id.contains("observer")
				|| id.contains("piston") || id.contains("hopper") || id.contains("chest") || id.contains("barrel") || id.contains("dispenser")
				|| id.contains("dropper") || id.contains("furnace") || id.contains("shulker") || id.equals("minecraft:bedrock")
				|| id.contains("lever") || id.contains("button") || id.contains("rail") || id.contains("crafter") || id.contains("command_block")
				|| id.contains("sign");
	}

	// ---- entry points ----

	/** Starts planning a route between the chosen start and end in the background. */
	public static void plan(boolean water) {
		int[] start = RmState.wireStart;
		int[] end = RmState.wireEnd;
		Object level = RmWorld.clientLevel();
		if (start == null || end == null || level == null) {
			return;
		}
		int distance = Math.abs(start[0] - end[0]) + Math.abs(start[1] - end[1]) + Math.abs(start[2] - end[2]);
		if (distance > MAX_DISTANCE) {
			RmState.error("Too far - at most " + MAX_DISTANCE + " blocks per route");
			return;
		}
		int generation = GENERATION.incrementAndGet();
		preview = List.of();
		summary = null;
		progress = "Finding route...";
		boolean through = "Go Through".equals(RmSettings.get().pathMode);
		String style = RmSettings.get().wireStyle;
		PLANNER.execute(() -> {
			Grid grid = new Grid(level, through, generation);
			grid.allowEndpoint(start);
			grid.allowEndpoint(end);
			grid.overrides.remove(key(start[0], start[1], start[2]));
			grid.overrides.remove(key(end[0], end[1], end[2]));
			Result result;
			long t0 = System.nanoTime();
			try {
				result = water ? WaterRouter.plan(grid, start, end) : "Observer".equals(style) ? planObservers(grid, start, end) : planDust(grid, start, end);
			} catch (RuntimeException e) {
				net.veloclient.velo.VeloClient.LOGGER.warn("Route planning failed", e);
				result = Result.fail("Couldn't plan this route");
			}
			if (grid.cancelled()) {
				return;
			}
			pendingOverrides = Set.copyOf(grid.overrides);
			if (result.error() == null) {
				result = new Result(result.placements(), result.summary() + " (" + (System.nanoTime() - t0) / 1_000_000 + " ms)", null);
			}
			pending = result;
		});
	}

	/** Builds the previewed route on the server as one undoable step; pieces recompute their connections afterwards. */
	public static void build() {
		List<Placement> placements = preview;
		if (placements.isEmpty()) {
			return;
		}
		if (!RmWorld.singleplayer()) {
			RmState.error("Building needs singleplayer");
			return;
		}
		boolean through = "Go Through".equals(RmSettings.get().pathMode);
		Set<Long> overrides = previewOverrides;
		List<Placement> sorted = new ArrayList<>(placements);
		sorted.sort(java.util.Comparator.comparingInt(Placement::order));
		int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (Placement p : sorted) {
			b[0] = Math.min(b[0], p.x());
			b[1] = Math.min(b[1], p.y());
			b[2] = Math.min(b[2], p.z());
			b[3] = Math.max(b[3], p.x());
			b[4] = Math.max(b[4], p.y());
			b[5] = Math.max(b[5], p.z());
		}
		int sx = b[3] - b[0] + 1;
		int sy = b[4] - b[1] + 1;
		int sz = b[5] - b[2] + 1;
		reset();
		RmWorld.onServer(level -> {
			// The client may not have known every block (far / unloaded) - check against the real world first.
			for (Placement p : sorted) {
				Object current = RmWorld.state(level, p.x(), p.y(), p.z());
				if (current != p.state() && !RmWorld.replaceable(current) && !(p.order() == 0 && RmWorld.conductor(level, p.x(), p.y(), p.z()))
						&& !((through || overrides.contains(key(p.x(), p.y(), p.z()))) && !protectedBlock(RmWorld.id(current)))) {
					MinecraftClient.getInstance().execute(() -> RmState.error("Blocked at " + p.x() + " " + p.y() + " " + p.z() + " by "
							+ RmWorld.id(current).replace("minecraft:", "") + " - plan again"));
					return;
				}
			}
			RmJobs.Changes changes = new RmJobs.Changes();
			for (Placement p : sorted) {
				Object old = RmWorld.state(level, p.x(), p.y(), p.z());
				changes.add(((p.x() - b[0]) * sy + (p.y() - b[1])) * sz + (p.z() - b[2]), old, RmWorld.isAir(old) ? null : RmWorld.saveBe(level, p.x(), p.y(), p.z()));
				RmWorld.set(level, p.x(), p.y(), p.z(), p.state(), RmWorld.NORMAL);
			}
			// Dust placed next to pieces that came later still has its "dot" shape - refresh every piece.
			for (Placement p : sorted) {
				RmWorld.refreshShape(level, p.x(), p.y(), p.z());
			}
			for (Placement p : sorted) {
				if (p.order() >= 1) {
					RmWorld.neighborUpdate(level, p.x(), p.y(), p.z());
				}
			}
			RmOps.rememberSparse(b[0], b[1], b[2], sx, sy, sz, changes, sorted);
			int count = sorted.size();
			MinecraftClient.getInstance().execute(() -> RmState.info("Built " + count + " blocks - Ctrl+Z to undo"));
		});
	}

	// ---- dust ----

	private static final int[][] HORIZONTAL = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

	private enum Move { START, FLAT, UP, DOWN, TOWER }

	private static final class Node implements Comparable<Node> {
		final int x;
		final int y;
		final int z;
		final int dir;
		final Move move;
		final int g;
		final double f;
		final Node parent;

		Node(int x, int y, int z, int dir, Move move, int g, double h, Node parent) {
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
			return Double.compare(f, o.f);
		}
	}

	private static double h(int x, int y, int z, int[] t) {
		return WEIGHT * (Math.abs(x - t[0]) + Math.abs(z - t[2]) + 1.5 * Math.abs(y - t[1]));
	}

	static Result planDust(Grid grid, int[] start, int[] end) {
		Set<Long> banned = new HashSet<>();
		Result result = null;
		for (int attempt = 0; attempt < 5; attempt++) {
			result = planDustOnce(grid, start, end, banned);
			if (result.error() != null || grid.cancelled()) {
				return result;
			}
			// Dust that ends up next to a non-neighbouring part of its own line would short-circuit it.
			Set<Long> conflicts = selfAdjacent(result.placements());
			if (conflicts.isEmpty()) {
				return result;
			}
			banned.addAll(conflicts);
		}
		return result;
	}

	private static Set<Long> selfAdjacent(List<Placement> placements) {
		Map<Long, Integer> order = new HashMap<>();
		int i = 0;
		for (Placement p : placements) {
			if (RmWorld.id(p.state()).equals("minecraft:redstone_wire")) {
				order.put(key(p.x(), p.y(), p.z()), i);
			}
			i++;
		}
		Set<Long> conflicts = new HashSet<>();
		for (Placement p : placements) {
			Integer self = order.get(key(p.x(), p.y(), p.z()));
			if (self == null) {
				continue;
			}
			for (int[] d : HORIZONTAL) {
				for (int dy = -1; dy <= 1; dy++) {
					Integer other = order.get(key(p.x() + d[0], p.y() + dy, p.z() + d[2]));
					if (other != null && Math.abs(other - self) > 2) {
						conflicts.add(key(p.x(), p.y(), p.z()));
					}
				}
			}
		}
		return conflicts;
	}

	private static Result planDustOnce(Grid grid, int[] start, int[] end, Set<Long> banned) {
		int[] so = RmWorld.faceOffset(start[3]);
		int[] eo = RmWorld.faceOffset(end[3]);
		int[] s = {start[0] + so[0], start[1] + so[1], start[2] + so[2]};
		int[] e = {end[0] + eo[0], end[1] + eo[1], end[2] + eo[2]};
		if (end[3] == 0) {
			return Result.fail("Dust can't power a block from below - click the top or a side of the target");
		}
		if (!grid.placeable(s[0], s[1], s[2]) || !grid.placeable(e[0], e[1], e[2])) {
			return Result.fail("The start or end spot is blocked");
		}
		int requiredDir = -1;
		if (end[3] >= 2) {
			for (int i = 0; i < 4; i++) {
				if (HORIZONTAL[i][0] == -eo[0] && HORIZONTAL[i][2] == -eo[2]) {
					requiredDir = i;
				}
			}
		}
		Set<Long> endpoints = Set.of(key(start[0], start[1], start[2]), key(end[0], end[1], end[2]));
		PriorityQueue<Node> open = new PriorityQueue<>();
		Map<Long, Integer> best = new HashMap<>();
		open.add(new Node(s[0], s[1], s[2], -1, Move.START, 0, h(s[0], s[1], s[2], e), null));
		Node goal = null;
		while (!open.isEmpty() && grid.expansions < MAX_EXPANSIONS) {
			if (grid.cancelled()) {
				return Result.fail("cancelled");
			}
			Node n = open.poll();
			if (n.x == e[0] && n.y == e[1] && n.z == e[2] && (requiredDir < 0 || n.dir == requiredDir)) {
				goal = n;
				break;
			}
			long stateKey = key(n.x, n.y, n.z) * 5 + (n.dir + 1);
			Integer seen = best.get(stateKey);
			if (seen != null && seen <= n.g) {
				continue;
			}
			best.put(stateKey, n.g);
			grid.step("Finding redstone route");
			for (int d = 0; d < 4; d++) {
				int nx = n.x + HORIZONTAL[d][0];
				int nz = n.z + HORIZONTAL[d][2];
				if (Math.abs(nx - s[0]) + Math.abs(nz - s[2]) > MAX_DISTANCE + 32) {
					continue;
				}
				int turn = n.dir >= 0 && n.dir != d ? 1 : 0;
				if (dustOk(grid, nx, n.y, nz, endpoints, banned)) {
					push(open, grid, nx, n.y, nz, d, Move.FLAT, n, 2 + turn, e, endpoints);
				}
				if (!grid.conductor(n.x, n.y + 1, n.z) && supportOk(grid, nx, n.y, nz) && dustOk(grid, nx, n.y + 1, nz, endpoints, banned)) {
					push(open, grid, nx, n.y + 1, nz, d, Move.UP, n, 3 + turn, e, endpoints);
				}
				if (grid.air(nx, n.y, nz) && dustOk(grid, nx, n.y - 1, nz, endpoints, banned)) {
					push(open, grid, nx, n.y - 1, nz, d, Move.DOWN, n, 3 + turn, e, endpoints);
				}
				if (e[1] - n.y >= 4 && towerOk(grid, nx, n.y, nz)) {
					push(open, grid, nx, n.y + 5, nz, d, Move.TOWER, n, 13 + turn, e, endpoints);
				}
			}
		}
		if (goal == null) {
			return Result.fail(grid.expansions >= MAX_EXPANSIONS ? "No route within the search limit - try Go Through or a closer end"
					: "No route found - try Go Through, or another start / end");
		}
		return buildDust(grid, start, goal);
	}

	private static void push(PriorityQueue<Node> open, Grid grid, int x, int y, int z, int dir, Move move, Node parent, int cost, int[] goal,
			Set<Long> endpoints) {
		int penalty = 0;
		for (int[] h : HORIZONTAL) {
			if (protectedBlock(grid.id(x + h[0], y, z + h[2])) && !endpoints.contains(key(x + h[0], y, z + h[2]))) {
				penalty += 25;
			}
		}
		open.add(new Node(x, y, z, dir, move, parent.g + cost + penalty, h(x, y, z, goal), parent));
	}

	private static boolean supportOk(Grid grid, int x, int y, int z) {
		return grid.conductor(x, y, z) || grid.placeable(x, y, z);
	}

	private static boolean dustOk(Grid grid, int x, int y, int z, Set<Long> endpoints, Set<Long> banned) {
		long k = key(x, y, z);
		return !banned.contains(k) && grid.placeable(x, y, z) && !endpoints.contains(k)
				&& (supportOk(grid, x, y - 1, z) || endpoints.contains(key(x, y - 1, z)));
	}

	private static boolean towerOk(Grid grid, int x, int y, int z) {
		for (int dy = 0; dy <= 5; dy++) {
			if (!grid.placeable(x, y + dy, z)) {
				return false;
			}
		}
		return true;
	}

	private static Result buildDust(Grid grid, int[] start, Node goal) {
		List<Node> path = new ArrayList<>();
		for (Node n = goal; n != null; n = n.parent) {
			path.add(0, n);
		}
		String supportId = supportBlock(grid, start);
		Object support = RmWorld.parse(supportId, Map.of());
		Object dust = RmWorld.parse("minecraft:redstone_wire", Map.of());
		Object torch = RmWorld.parse("minecraft:redstone_torch", Map.of());
		Map<Long, Placement> out = new java.util.LinkedHashMap<>();
		Set<Long> repeaters = new HashSet<>();
		RmSettings.Data s = RmSettings.get();
		int run = 0;
		boolean placedDelay = false;
		for (int i = 0; i < path.size(); i++) {
			Node n = path.get(i);
			if (n.move == Move.TOWER) {
				run = 0;
			}
			run++;
			boolean straight = i > 0 && i + 1 < path.size() && n.move == Move.FLAT && path.get(i + 1).move == Move.FLAT && path.get(i + 1).dir == n.dir;
			boolean want = run >= 14 || s.delayMode && !placedDelay && i >= 1;
			if (straight && want) {
				repeaters.add(key(n.x, n.y, n.z));
				run = 0;
				placedDelay = true;
			}
			if (run > 15) {
				return Result.fail("This line needs a repeater but has no straight 3-block stretch - try another route");
			}
		}
		int repeaterCount = 0;
		int torchCount = 0;
		for (Node n : path) {
			if (n.move == Move.TOWER) {
				Node p = n.parent;
				int y0 = p.y;
				put(out, n.x, y0, n.z, support, 0);
				put(out, n.x, y0 + 1, n.z, torch, 1);
				put(out, n.x, y0 + 2, n.z, support, 0);
				put(out, n.x, y0 + 3, n.z, torch, 1);
				put(out, n.x, y0 + 4, n.z, support, 0);
				torchCount += 2;
			}
			if (grid.air(n.x, n.y - 1, n.z) && !out.containsKey(key(n.x, n.y - 1, n.z))) {
				put(out, n.x, n.y - 1, n.z, support, 0);
			}
			if (repeaters.contains(key(n.x, n.y, n.z))) {
				int[] back = {-HORIZONTAL[n.dir][0], -HORIZONTAL[n.dir][2]};
				String facing = back[0] == 1 ? "east" : back[0] == -1 ? "west" : back[1] == 1 ? "south" : "north";
				int delay = s.delayMode ? Math.max(1, Math.min(4, s.delayTicks)) : 1;
				put(out, n.x, n.y, n.z, RmWorld.parse("minecraft:repeater", Map.of("facing", facing, "delay", String.valueOf(delay))), 1);
				repeaterCount++;
			} else {
				put(out, n.x, n.y, n.z, dust, 2);
			}
		}
		return new Result(new ArrayList<>(out.values()), "Redstone: " + path.size() + " blocks, " + repeaterCount + " repeater"
				+ (repeaterCount == 1 ? "" : "s") + (torchCount > 0 ? ", " + torchCount + " tower torches" : "") + " on "
				+ supportId.replace("minecraft:", "").replace('_', ' '), null);
	}

	/** The block under dust: the chosen one, or what the start is built from (a full conducting block), else smooth stone. */
	private static String supportBlock(Grid grid, int[] start) {
		String chosen = RmSettings.get().wireBlock;
		if (chosen != null && !chosen.equals("auto")) {
			return chosen;
		}
		for (int dy : new int[] {0, -1}) {
			String id = grid.id(start[0], start[1] + dy, start[2]);
			if (grid.conductor(start[0], start[1] + dy, start[2]) && !protectedBlock(id) && !id.contains("lamp") && !id.contains("target")
					&& !id.contains("note_block")) {
				return id;
			}
		}
		return "minecraft:smooth_stone";
	}

	static void put(Map<Long, Placement> out, int x, int y, int z, Object state, int order) {
		long k = key(x, y, z);
		Placement existing = out.get(k);
		if (existing == null || existing.order() <= order) {
			out.put(k, new Placement(x, y, z, state, order));
		}
	}

	// ---- observers ----

	private static final int[][] ALL_DIRS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

	static Result planObservers(Grid grid, int[] start, int[] end) {
		int[] so = RmWorld.faceOffset(start[3]);
		int[] eo = RmWorld.faceOffset(end[3]);
		int[] s = {start[0] + so[0], start[1] + so[1], start[2] + so[2]};
		int[] last = {end[0] + eo[0], end[1] + eo[1], end[2] + eo[2]};
		int[] beforeLast = {end[0] + 2 * eo[0], end[1] + 2 * eo[1], end[2] + 2 * eo[2]};
		if (!grid.placeable(s[0], s[1], s[2]) || !grid.placeable(last[0], last[1], last[2]) || !grid.placeable(beforeLast[0], beforeLast[1], beforeLast[2])) {
			return Result.fail("The start or end spot is blocked (observers need two free blocks in front of the target)");
		}
		Set<Long> reserved = Set.of(key(last[0], last[1], last[2]), key(start[0], start[1], start[2]), key(end[0], end[1], end[2]));
		PriorityQueue<Node> open = new PriorityQueue<>();
		Map<Long, Integer> best = new HashMap<>();
		open.add(new Node(s[0], s[1], s[2], -1, Move.START, 0, h(s[0], s[1], s[2], beforeLast), null));
		Node goal = null;
		while (!open.isEmpty() && grid.expansions < MAX_EXPANSIONS) {
			if (grid.cancelled()) {
				return Result.fail("cancelled");
			}
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
			grid.step("Finding observer route");
			for (int d = 0; d < 6; d++) {
				int nx = n.x + ALL_DIRS[d][0];
				int ny = n.y + ALL_DIRS[d][1];
				int nz = n.z + ALL_DIRS[d][2];
				if (reserved.contains(key(nx, ny, nz)) || !grid.placeable(nx, ny, nz)) {
					continue;
				}
				int g = n.g + 1 + (n.dir >= 0 && n.dir != d ? 1 : 0);
				open.add(new Node(nx, ny, nz, d, Move.FLAT, g, h(nx, ny, nz, beforeLast), n));
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
		Map<Long, Placement> out = new java.util.LinkedHashMap<>();
		int[] previous = {start[0], start[1], start[2]};
		for (int[] c : cells) {
			put(out, c[0], c[1], c[2], RmWorld.parse("minecraft:observer", Map.of("facing", faceTowards(c, previous))), 1);
			previous = c;
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
