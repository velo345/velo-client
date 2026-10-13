package net.veloclient.velo.client.redstone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Item water streams for Redstone Mode's Water tool.
 *
 * <p>Layout: an ice floor and walls along the whole stream; sections of one water source plus seven
 * flowing blocks, each followed by a separator (a sign on the side wall by default - items pass,
 * water doesn't) and the next section's source, so every source has a wall or separator behind it
 * and pushes items forward - there's never still water on the path. Going up: a separator right
 * before a soul-sand bubble elevator (so the column's water can't push items back), capped on top
 * so items can't jump out, and the column's top source carries them out. Going down: a separator in
 * the shaft opening keeps the shaft dry so items drop straight down, and a source at the bottom
 * carries them on. A route can climb and drop as often as needed to get around things.
 *
 * <p>Floor, walls and separator are the chosen blocks, or "Auto": copied from streams already built
 * nearby. Click an existing stream as the end and the new one joins it from the side (through its
 * wall) or from above.
 */
final class WaterRouter {

	private static final int[][] HORIZONTAL = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
	private static final String[] FACE_NAMES = {"north", "south", "west", "east"};
	private static final int[] CLIMBS = {1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32, 48, 64};
	private static final double WEIGHT = 1.6;

	private WaterRouter() {
	}

	/** {@code vertical}: 0 flat, +h climbed / -h dropped into this node. */
	private record Node(int x, int y, int z, int dir, int vertical, int g, double f, Node parent) implements Comparable<Node> {
		@Override
		public int compareTo(Node o) {
			return Double.compare(f, o.f);
		}
	}

	private record Style(String floor, String wall, String separator) {
	}

	static Wiring.Result plan(Wiring.Grid grid, int[] start, int[] end) {
		int[] so = RmWorld.faceOffset(start[3]);
		int[] s = {start[0] + so[0], start[1] + so[1], start[2] + so[2]};
		if (!grid.placeable(s[0], s[1], s[2])) {
			return Wiring.Result.fail("The start spot is blocked");
		}
		// Ending at an existing stream (clicked its water, wall or floor): join it.
		Set<Long> joins = joinCells(grid, end);
		boolean joining = !joins.isEmpty();
		int[] eo = RmWorld.faceOffset(end[3]);
		int[] e = joining ? new int[] {end[0], end[1], end[2]} : new int[] {end[0] + eo[0], end[1] + eo[1], end[2] + eo[2]};
		if (!joining && !grid.placeable(e[0], e[1], e[2])) {
			return Wiring.Result.fail("The end spot is blocked");
		}
		Style style = style(grid, s, end);
		PriorityQueue<Node> open = new PriorityQueue<>();
		Map<Long, Integer> best = new HashMap<>();
		open.add(new Node(s[0], s[1], s[2], -1, 0, 0, h(s, e), null));
		Node goal = null;
		while (!open.isEmpty() && grid.expansions < Wiring.MAX_EXPANSIONS) {
			if (grid.cancelled()) {
				return Wiring.Result.fail("cancelled");
			}
			Node n = open.poll();
			long nk = Wiring.key(n.x(), n.y(), n.z());
			if (joining ? joins.contains(nk) : n.x() == e[0] && n.y() == e[1] && n.z() == e[2]) {
				goal = n;
				break;
			}
			long stateKey = nk * 6 + (n.dir() + 1);
			Integer seen = best.get(stateKey);
			if (seen != null && seen <= n.g()) {
				continue;
			}
			best.put(stateKey, n.g());
			grid.step("Finding water route");
			for (int d = 0; d < 4; d++) {
				if (n.dir() >= 0 && opposite(d) == n.dir()) {
					continue;
				}
				int nx = n.x() + HORIZONTAL[d][0];
				int nz = n.z() + HORIZONTAL[d][2];
				if (Math.abs(nx - s[0]) + Math.abs(nz - s[2]) > Wiring.MAX_DISTANCE + 32) {
					continue;
				}
				int turn = n.dir() >= 0 && n.dir() != d ? 2 : 0;
				boolean joinHere = joining && joins.contains(Wiring.key(nx, n.y(), nz));
				// Flat: channel block, floor below.
				if ((joinHere || channelOk(grid, nx, n.y(), nz)) && floorOk(grid, nx, n.y() - 1, nz)) {
					add(open, new Node(nx, n.y(), nz, d, 0, n.g() + 1 + turn, 0, n), e);
				}
				// Height changes only start from a flat stretch (there must be room for the separator).
				if (n.parent() == null || n.vertical() != 0) {
					continue;
				}
				for (int climb : CLIMBS) {
					int top = n.y() + climb;
					if (!columnOk(grid, nx, n.y(), top, nz)) {
						break;
					}
					if (top == e[1] || Math.abs(top - e[1]) > 2 || grid.air(nx + HORIZONTAL[d][0], top, nz + HORIZONTAL[d][2])) {
						add(open, new Node(nx, top, nz, d, climb, n.g() + 4 + climb + turn, 0, n), e);
					}
				}
				for (int drop : CLIMBS) {
					int bottom = n.y() - drop;
					if (!shaftOk(grid, nx, bottom, n.y(), nz)) {
						break;
					}
					add(open, new Node(nx, bottom, nz, d, -drop, n.g() + 3 + drop + turn, 0, n), e);
				}
			}
		}
		if (goal == null) {
			return Wiring.Result.fail(grid.expansions >= Wiring.MAX_EXPANSIONS ? "No water route within the search limit - try Go Through"
					: "No water route found - try Go Through or another end");
		}
		return build(grid, start, end, goal, joining, style);
	}

	private static void add(PriorityQueue<Node> open, Node n, int[] goal) {
		open.add(new Node(n.x(), n.y(), n.z(), n.dir(), n.vertical(), n.g(), n.g() + h(new int[] {n.x(), n.y(), n.z()}, goal), n.parent()));
	}

	private static double h(int[] a, int[] goal) {
		return WEIGHT * (Math.abs(a[0] - goal[0]) + Math.abs(a[2] - goal[2]) + Math.abs(a[1] - goal[1]));
	}

	private static int opposite(int d) {
		return d ^ 1;
	}

	private static boolean channelOk(Wiring.Grid grid, int x, int y, int z) {
		return grid.placeable(x, y, z);
	}

	private static boolean floorOk(Wiring.Grid grid, int x, int y, int z) {
		return !Wiring.protectedBlock(grid.id(x, y, z));
	}

	private static boolean columnOk(Wiring.Grid grid, int x, int bottom, int top, int z) {
		if (!floorOk(grid, x, bottom - 1, z)) {
			return false;
		}
		for (int y = bottom; y <= top; y++) {
			if (!grid.placeable(x, y, z)) {
				return false;
			}
		}
		return grid.placeable(x, top + 1, z) || grid.conductor(x, top + 1, z);
	}

	private static boolean shaftOk(Wiring.Grid grid, int x, int bottom, int top, int z) {
		if (!floorOk(grid, x, bottom - 1, z)) {
			return false;
		}
		for (int y = bottom; y <= top; y++) {
			if (!grid.placeable(x, y, z)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Cells a new stream may end in to join the stream at / next to {@code end}: the stream's side
	 * walls (replaced by the new channel) and the air just above its water. Empty if there's no stream.
	 */
	private static Set<Long> joinCells(Wiring.Grid grid, int[] end) {
		int[] water = null;
		for (int dx = -1; dx <= 1 && water == null; dx++) {
			for (int dy = -1; dy <= 1 && water == null; dy++) {
				for (int dz = -1; dz <= 1 && water == null; dz++) {
					if (grid.water(end[0] + dx, end[1] + dy, end[2] + dz)) {
						water = new int[] {end[0] + dx, end[1] + dy, end[2] + dz};
					}
				}
			}
		}
		Set<Long> out = new HashSet<>();
		if (water == null) {
			return out;
		}
		// Flood the stream at that height.
		Set<Long> region = new HashSet<>();
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		queue.add(water);
		region.add(Wiring.key(water[0], water[1], water[2]));
		while (!queue.isEmpty() && region.size() < 3000) {
			int[] c = queue.poll();
			for (int[] d : HORIZONTAL) {
				int x = c[0] + d[0];
				int z = c[2] + d[2];
				long k = Wiring.key(x, c[1], z);
				if (!region.contains(k) && grid.water(x, c[1], z)) {
					region.add(k);
					queue.add(new int[] {x, c[1], z});
				}
			}
		}
		for (long k : region) {
			int[] c = ClockTracker.decode(k);
			String level = RmWorld.prop(grid.state(c[0], c[1], c[2]), "level");
			boolean flowing = level != null && !level.equals("0");
			if (!flowing) {
				continue;
			}
			for (int[] d : HORIZONTAL) {
				int x = c[0] + d[0];
				int z = c[2] + d[2];
				if (!region.contains(Wiring.key(x, c[1], z)) && !Wiring.protectedBlock(grid.id(x, c[1], z)) && !grid.water(x, c[1], z)) {
					out.add(Wiring.key(x, c[1], z));
				}
			}
			if (grid.air(c[0], c[1] + 1, c[2])) {
				out.add(Wiring.key(c[0], c[1] + 1, c[2]));
			}
		}
		return out;
	}

	/** Chosen blocks, or the ones nearby streams already use. */
	private static Style style(Wiring.Grid grid, int[] near, int[] end) {
		RmSettings.Data s = RmSettings.get();
		Map<String, Integer> floors = new HashMap<>();
		Map<String, Integer> walls = new HashMap<>();
		Map<String, Integer> separators = new HashMap<>();
		for (int[] center : new int[][] {near, end}) {
			for (int dx = -6; dx <= 6; dx++) {
				for (int dz = -6; dz <= 6; dz++) {
					for (int dy = -3; dy <= 3; dy++) {
						int x = center[0] + dx;
						int y = center[1] + dy;
						int z = center[2] + dz;
						if (!grid.water(x, y, z)) {
							continue;
						}
						floors.merge(grid.id(x, y - 1, z), 1, Integer::sum);
						for (int[] d : HORIZONTAL) {
							String id = grid.id(x + d[0], y, z + d[2]);
							if (id.contains("sign") || id.contains("rail") || id.contains("slab") || id.contains("trapdoor")) {
								separators.merge(id, 1, Integer::sum);
							} else if (!id.contains("water") && !RmWorld.replaceable(grid.state(x + d[0], y, z + d[2]))) {
								walls.merge(id, 1, Integer::sum);
							}
						}
					}
				}
			}
		}
		String floor = !"auto".equals(s.waterFloor) ? s.waterFloor : top(floors, "minecraft:blue_ice", "ice");
		String wall = !"auto".equals(s.waterWall) ? s.waterWall : top(walls, "minecraft:glass", null);
		String separator = !"auto".equals(s.waterSeparator) ? s.waterSeparator : top(separators, "minecraft:oak_wall_sign", null);
		return new Style(floor, wall, separator);
	}

	private static String top(Map<String, Integer> counts, String fallback, String mustContain) {
		String best = null;
		int bestCount = 0;
		for (var e : counts.entrySet()) {
			if (mustContain != null && !e.getKey().contains(mustContain)) {
				continue;
			}
			if (e.getValue() > bestCount) {
				best = e.getKey();
				bestCount = e.getValue();
			}
		}
		return best != null ? best : fallback;
	}

	private static Wiring.Result build(Wiring.Grid grid, int[] start, int[] end, Node goal, boolean joining, Style style) {
		List<Node> path = new ArrayList<>();
		for (Node n = goal; n != null; n = n.parent()) {
			path.add(0, n);
		}
		Object floor = RmWorld.parse(style.floor(), Map.of());
		Object wall = RmWorld.parse(style.wall(), Map.of());
		Object source = RmWorld.parse("minecraft:water", Map.of());
		Object air = RmWorld.parse("minecraft:air", Map.of());
		Object soulSand = RmWorld.parse("minecraft:soul_sand", Map.of());
		if (floor == null || wall == null) {
			return Wiring.Result.fail("Unknown floor / wall block");
		}
		Map<Long, Wiring.Placement> out = new java.util.LinkedHashMap<>();
		Set<Long> stream = new HashSet<>();
		Set<Long> keep = new HashSet<>();
		keep.add(Wiring.key(start[0], start[1], start[2]));
		keep.add(Wiring.key(end[0], end[1], end[2]));
		// Every cell items travel through (incl. columns / shafts), so walls never block the stream.
		for (Node n : path) {
			stream.add(Wiring.key(n.x(), n.y(), n.z()));
			if (n.vertical() != 0) {
				int lo = Math.min(n.y(), n.parent().y());
				int hi = Math.max(n.y(), n.parent().y());
				for (int y = lo; y <= hi; y++) {
					stream.add(Wiring.key(n.x(), y, n.z()));
				}
			}
		}
		int sinceSource = 0;
		int separators = 0;
		int elevators = 0;
		int drops = 0;
		for (int i = 0; i < path.size(); i++) {
			Node n = path.get(i);
			boolean last = i == path.size() - 1;
			Node next = last ? null : path.get(i + 1);
			int dir = n.dir() >= 0 ? n.dir() : next != null ? next.dir() : 0;
			if (n.vertical() > 0) {
				// Bubble elevator: soul sand, water sources, a cap so items can't jump out; the top source carries them on.
				Node p = n.parent();
				Wiring.put(out, n.x(), p.y() - 1, n.z(), soulSand, 0);
				for (int y = p.y(); y <= n.y(); y++) {
					Wiring.put(out, n.x(), y, n.z(), source, 3);
				}
				Wiring.put(out, n.x(), n.y() + 1, n.z(), wall, 0);
				elevators++;
				sinceSource = 1;
				continue;
			}
			if (n.vertical() < 0) {
				// Drop shaft: dry all the way down, a source at the bottom carries items on.
				Node p = n.parent();
				for (int y = n.y() + 1; y < p.y(); y++) {
					Wiring.put(out, n.x(), y, n.z(), air, 0);
				}
				separator(out, grid, style, n.x(), p.y(), n.z(), n.dir(), stream);
				Wiring.put(out, n.x(), n.y() - 1, n.z(), floor, 0);
				Wiring.put(out, n.x(), n.y(), n.z(), source, 3);
				drops++;
				separators++;
				sinceSource = 1;
				continue;
			}
			boolean onTarget = last && !joining && n.x() == end[0] && n.y() - 1 == end[1] && n.z() == end[2];
			if (!onTarget && !keep.contains(Wiring.key(n.x(), n.y() - 1, n.z())) && !grid.water(n.x(), n.y() - 1, n.z())) {
				Wiring.put(out, n.x(), n.y() - 1, n.z(), floor, 0);
			}
			boolean beforeVertical = next != null && next.vertical() > 0;
			boolean beforeDrop = next != null && next.vertical() < 0;
			if (i == 0) {
				Wiring.put(out, n.x(), n.y(), n.z(), source, 3);
				sinceSource = 1;
			} else if (beforeVertical || sinceSource >= 8 && !last && !beforeDrop) {
				// Separator: before an elevator (its water must not push items back) or after every 7 flowing blocks.
				separator(out, grid, style, n.x(), n.y(), n.z(), dir, stream);
				separators++;
				sinceSource = 0;
			} else if (sinceSource == 0) {
				Wiring.put(out, n.x(), n.y(), n.z(), source, 3);
				sinceSource = 1;
			} else {
				if (!grid.air(n.x(), n.y(), n.z()) && !grid.water(n.x(), n.y(), n.z())) {
					Wiring.put(out, n.x(), n.y(), n.z(), air, 0);
				}
				sinceSource++;
			}
		}
		// Walls around every stream block.
		for (long k : stream) {
			int[] c = ClockTracker.decode(k);
			for (int[] d : HORIZONTAL) {
				long nk = Wiring.key(c[0] + d[0], c[1], c[2] + d[2]);
				if (stream.contains(nk) || keep.contains(nk) || out.containsKey(nk)) {
					continue;
				}
				if (grid.air(c[0] + d[0], c[1], c[2] + d[2])) {
					Wiring.put(out, c[0] + d[0], c[1], c[2] + d[2], wall, 0);
				}
			}
		}
		String endId = grid.id(end[0], end[1], end[2]);
		String ending = joining ? "joins the stream" : end[3] == 1 && (endId.contains("hopper") || endId.contains("chest")
				|| endId.contains("barrel") || endId.contains("shulker")) ? "drops into the " + endId.replace("minecraft:", "").replace('_', ' ')
						: "ends at the target (aim at the top of a hopper / chest or at a stream to deliver)";
		String summary = "Water: " + path.size() + " blocks, " + separators + " separators" + (elevators > 0 ? ", " + elevators + " elevator"
				+ (elevators > 1 ? "s" : "") : "") + (drops > 0 ? ", " + drops + " drop" + (drops > 1 ? "s" : "") : "") + " - " + ending;
		return new Wiring.Result(new ArrayList<>(out.values()), summary, null);
	}

	/** A separator in a stream block: a wall sign on a side wall (or the chosen block as-is). */
	private static void separator(Map<Long, Wiring.Placement> out, Wiring.Grid grid, Style style, int x, int y, int z, int dir,
			Set<Long> stream) {
		String id = style.separator();
		Object state = RmWorld.parse(id, Map.of());
		if (state == null) {
			state = RmWorld.parse("minecraft:oak_wall_sign", Map.of());
			id = "minecraft:oak_wall_sign";
		}
		if (id.contains("wall_sign") || id.contains("wall_hanging")) {
			// Hang it on a side wall: a perpendicular neighbour that isn't part of the stream.
			int side = dir < 2 ? 2 : 0;
			for (int candidate : new int[] {side, side + 1}) {
				int wx = x + HORIZONTAL[candidate][0];
				int wz = z + HORIZONTAL[candidate][2];
				if (!stream.contains(Wiring.key(wx, y, wz))) {
					state = RmWorld.with(state, "facing", FACE_NAMES[candidate ^ 1]);
					break;
				}
			}
		}
		state = RmWorld.with(state, "waterlogged", "false");
		Wiring.put(out, x, y, z, state, 1);
	}
}
