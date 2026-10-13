package net.veloclient.velo.client.redstone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Item water streams for Redstone Mode's Water tool (see {@link Wiring}): routes along the start's
 * level, changes height once with a soul-sand bubble elevator (going up, capped on top so items
 * don't fly out) or a walled drop shaft (going down), then continues along the end's level.
 * Every channel block gets an ice floor and walls; water sources sit every 8 blocks with a sign
 * between sections so they don't push against each other.
 */
final class WaterRouter {

	private static final int[][] HORIZONTAL = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

	private WaterRouter() {
	}

	private record Node(int x, int y, int z, int dir, int vertical, int g, int f, Node parent) implements Comparable<Node> {
		@Override
		public int compareTo(Node o) {
			return Integer.compare(f, o.f);
		}
	}

	private static boolean protectedBlock(String id) {
		return id.contains("redstone") || id.contains("repeater") || id.contains("comparator") || id.contains("observer")
				|| id.contains("piston") || id.contains("hopper") || id.contains("chest") || id.contains("barrel") || id.contains("dispenser")
				|| id.contains("dropper") || id.contains("furnace") || id.contains("shulker") || id.equals("minecraft:bedrock") || id.contains("rail");
	}

	private static boolean open(Object level, int x, int y, int z, boolean through) {
		Object state = RmWorld.state(level, x, y, z);
		if (RmWorld.replaceable(state)) {
			return !RmWorld.id(state).contains("lava");
		}
		return through && !protectedBlock(RmWorld.id(state));
	}

	private static boolean floorOk(Object level, int x, int y, int z) {
		return !protectedBlock(RmWorld.id(RmWorld.state(level, x, y, z)));
	}

	private static int h(int x, int y, int z, int[] t) {
		return Math.abs(x - t[0]) + Math.abs(y - t[1]) + Math.abs(z - t[2]);
	}

	static Wiring.Result plan(Object level, int[] start, int[] end, boolean through) {
		int[] so = RmWorld.faceOffset(start[3]);
		int[] eo = RmWorld.faceOffset(end[3]);
		int[] s = {start[0] + so[0], start[1] + so[1], start[2] + so[2]};
		int[] e = {end[0] + eo[0], end[1] + eo[1], end[2] + eo[2]};
		String endId = RmWorld.id(RmWorld.state(level, end[0], end[1], end[2]));
		boolean joinStream = endId.contains("water");
		if (!open(level, s[0], s[1], s[2], through)) {
			return Wiring.Result.fail("The start spot is blocked");
		}
		if (!joinStream && !open(level, e[0], e[1], e[2], through)) {
			return Wiring.Result.fail("The end spot is blocked");
		}
		PriorityQueue<Node> queue = new PriorityQueue<>();
		Map<Long, Integer> best = new HashMap<>();
		queue.add(new Node(s[0], s[1], s[2], -1, 0, 0, h(s[0], s[1], s[2], e), null));
		Node goal = null;
		int expansions = 0;
		while (!queue.isEmpty() && expansions++ < Wiring.MAX_EXPANSIONS) {
			Node n = queue.poll();
			if (n.x() == e[0] && n.y() == e[1] && n.z() == e[2]) {
				goal = n;
				break;
			}
			long k = Wiring.key(n.x(), n.y(), n.z()) * 2 + (n.y() == e[1] && n.y() != s[1] ? 1 : 0);
			Integer seen = best.get(k);
			if (seen != null && seen <= n.g()) {
				continue;
			}
			best.put(k, n.g());
			for (int d = 0; d < 4; d++) {
				int nx = n.x() + HORIZONTAL[d][0];
				int nz = n.z() + HORIZONTAL[d][2];
				if (Math.abs(nx - s[0]) + Math.abs(nz - s[2]) > Wiring.MAX_DISTANCE + 16) {
					continue;
				}
				int turn = n.dir() >= 0 && n.dir() != d ? 2 : 0;
				boolean isEnd = nx == e[0] && n.y() == e[1] && nz == e[2];
				// Same level.
				if ((isEnd || open(level, nx, n.y(), nz, through)) && floorOk(level, nx, n.y() - 1, nz)) {
					queue.add(new Node(nx, n.y(), nz, d, 0, n.g() + 1 + turn, n.g() + 1 + turn + h(nx, n.y(), nz, e), n));
				}
				// One height change, straight to the end's level.
				if (n.y() != e[1] && n.y() == s[1] && n.parent() != null) {
					int top = Math.max(n.y(), e[1]);
					int bottom = Math.min(n.y(), e[1]);
					boolean ok = floorOk(level, nx, bottom - 1, nz);
					for (int y = bottom; y <= top && ok; y++) {
						ok = open(level, nx, y, nz, through);
					}
					if (e[1] > n.y()) {
						ok &= open(level, nx, top + 1, nz, true) || RmWorld.conductor(level, nx, top + 1, nz);
					}
					if (ok) {
						int g = n.g() + 3 + 2 * (top - bottom) + turn;
						queue.add(new Node(nx, e[1], nz, d, e[1] > n.y() ? 1 : -1, g, g + h(nx, e[1], nz, e), n));
					}
				}
			}
		}
		if (goal == null) {
			return Wiring.Result.fail(expansions >= Wiring.MAX_EXPANSIONS ? "No water route within the search limit"
					: "No water route found - try Go Through or a different end");
		}
		return build(level, start, end, s, goal, joinStream, endId);
	}

	private static Wiring.Result build(Object level, int[] start, int[] end, int[] s, Node goal, boolean joinStream, String endId) {
		List<Node> path = new ArrayList<>();
		for (Node n = goal; n != null; n = n.parent()) {
			path.add(0, n);
		}
		String floorId = floorBlock(level, s);
		Object floor = RmWorld.parse(floorId, Map.of());
		Object wall = RmWorld.parse(WireBlocks.wallId(), Map.of());
		Object source = RmWorld.parse("minecraft:water", Map.of());
		Object air = RmWorld.parse("minecraft:air", Map.of());
		Object soulSand = RmWorld.parse("minecraft:soul_sand", Map.of());
		Object sign = RmWorld.parse("minecraft:oak_sign", Map.of());
		Map<Long, Wiring.Placement> out = new HashMap<>();
		Set<Long> channel = new HashSet<>();
		Set<Long> keep = new HashSet<>();
		keep.add(Wiring.key(start[0], start[1], start[2]));
		keep.add(Wiring.key(end[0], end[1], end[2]));
		for (Node n : path) {
			channel.add(Wiring.key(n.x(), n.y(), n.z()));
		}
		int sinceSource = 0;
		int signs = 0;
		int elevators = 0;
		int drops = 0;
		for (int i = 0; i < path.size(); i++) {
			Node n = path.get(i);
			boolean last = i == path.size() - 1;
			if (n.vertical() != 0) {
				Node p = n.parent();
				int top = Math.max(p.y(), n.y());
				int bottom = Math.min(p.y(), n.y());
				for (int y = bottom; y <= top; y++) {
					channel.add(Wiring.key(n.x(), y, n.z()));
				}
				if (n.vertical() > 0) {
					// Bubble elevator: soul sand, water sources, a cap so items can't jump out.
					put(out, n.x(), bottom - 1, n.z(), soulSand, 0);
					for (int y = bottom; y <= top; y++) {
						put(out, n.x(), y, n.z(), source, 3);
					}
					put(out, n.x(), top + 1, n.z(), wall, 0);
					elevators++;
				} else {
					// Drop shaft: open air down to a fresh source at the bottom.
					for (int y = bottom + 1; y <= top; y++) {
						put(out, n.x(), y, n.z(), air, 0);
					}
					put(out, n.x(), bottom - 1, n.z(), floor, 0);
					put(out, n.x(), bottom, n.z(), source, 3);
					drops++;
				}
				sinceSource = 0;
				continue;
			}
			boolean onTarget = last && !joinStream && n.x() == end[0] && n.y() - 1 == end[1] && n.z() == end[2];
			if (!onTarget && !keep.contains(Wiring.key(n.x(), n.y() - 1, n.z()))) {
				put(out, n.x(), n.y() - 1, n.z(), floor, 0);
			}
			if (last && joinStream) {
				put(out, n.x(), n.y(), n.z(), source, 3);
			} else if (i == 0 || sinceSource == 0) {
				put(out, n.x(), n.y(), n.z(), source, 3);
				sinceSource = 1;
				continue;
			} else if (sinceSource >= 8 && !last) {
				// Separator: water can't pass a sign, items can - the next block starts a new section.
				put(out, n.x(), n.y(), n.z(), sign, 1);
				signs++;
				sinceSource = 0;
				continue;
			}
			sinceSource++;
		}
		// Walls around every channel / column block that isn't part of the stream.
		for (long k : new ArrayList<>(channel)) {
			int x = (int) ((k >>> 38) - 30_000_000);
			int y = (int) (((k >>> 26) & 0xFFF) - 2048);
			int z = (int) ((k & 0x3FFFFFF) - 30_000_000);
			for (int[] d : HORIZONTAL) {
				long nk = Wiring.key(x + d[0], y, z + d[2]);
				if (channel.contains(nk) || keep.contains(nk) || out.containsKey(nk)) {
					continue;
				}
				if (RmWorld.replaceable(RmWorld.state(level, x + d[0], y, z + d[2]))
						&& !RmWorld.id(RmWorld.state(level, x + d[0], y, z + d[2])).contains("water")) {
					put(out, x + d[0], y, z + d[2], wall, 0);
				}
			}
		}
		String ending;
		if (joinStream) {
			ending = "joins the stream";
		} else if (end[3] == 1 && (endId.contains("hopper") || endId.contains("chest") || endId.contains("barrel") || endId.contains("shulker"))) {
			ending = "drops into the " + endId.replace("minecraft:", "").replace('_', ' ');
		} else {
			ending = "ends at the target (items stop there - aim at the top of a hopper or at a stream to deliver)";
		}
		String summary = "Water: " + path.size() + " blocks on " + floorId.replace("minecraft:", "").replace('_', ' ') + ", " + signs + " separators"
				+ (elevators > 0 ? ", bubble elevator" : "") + (drops > 0 ? ", drop shaft" : "") + " - " + ending;
		return new Wiring.Result(new ArrayList<>(out.values()), summary, null);
	}

	private static void put(Map<Long, Wiring.Placement> out, int x, int y, int z, Object state, int order) {
		long k = Wiring.key(x, y, z);
		Wiring.Placement existing = out.get(k);
		if (existing == null || existing.order() <= order) {
			out.put(k, new Wiring.Placement(x, y, z, state, order));
		}
	}

	/** Configured floor, or whatever ice nearby streams already use. */
	private static String floorBlock(Object level, int[] near) {
		switch (RedstoneModeModule.waterBase) {
			case "Blue Ice" -> {
				return "minecraft:blue_ice";
			}
			case "Packed Ice" -> {
				return "minecraft:packed_ice";
			}
			default -> {
				for (int dx = -6; dx <= 6; dx++) {
					for (int dz = -6; dz <= 6; dz++) {
						for (int dy = -3; dy <= 1; dy++) {
							String id = RmWorld.id(RmWorld.state(level, near[0] + dx, near[1] + dy, near[2] + dz));
							if (id.equals("minecraft:blue_ice") || id.equals("minecraft:packed_ice")) {
								return id;
							}
						}
					}
				}
				return "minecraft:blue_ice";
			}
		}
	}
}
