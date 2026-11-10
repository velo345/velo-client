package net.veloclient.launcher.sandbox;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The endless overworld: three biomes (Crimson Forest, Ashlands, Frostpeaks), caves that get bigger and hotter with
 * depth, ores in layers - and the deepest ores only once their dungeon's boss is beaten. Three dungeon gates sit in
 * carved chambers deep underground. Generated column by column from the seed; only edits are saved.
 */
final class World extends Level {

	static final int HEIGHT = 128;
	static final int CHUNK = 16;

	static final int FOREST = 0, ASHLANDS = 1, FROST = 2;

	final long seed;
	/** Which dungeons are beaten (crypt, foundry, spire): unlocks their ore and the harder monsters. */
	final boolean[] cleared = new boolean[3];
	private final Map<Integer, byte[]> chunks = new HashMap<>();

	World(long seed) {
		this.seed = seed;
	}

	@Override
	String id() {
		return "world";
	}

	@Override
	int height() {
		return HEIGHT;
	}

	@Override
	boolean outdoors() {
		return true;
	}

	@Override
	int get(int x, int y) {
		if (y < 0) {
			return Items.AIR;
		}
		if (y >= HEIGHT) {
			return Items.BEDROCK;
		}
		return chunk(Math.floorDiv(x, CHUNK))[Math.floorMod(x, CHUNK) * HEIGHT + y];
	}

	@Override
	void set(int x, int y, int id) {
		if (y < 0 || y >= HEIGHT) {
			return;
		}
		chunk(Math.floorDiv(x, CHUNK))[Math.floorMod(x, CHUNK) * HEIGHT + y] = (byte) id;
		edits.put(key(x, y), id);
	}

	void setCleared(int dungeon, boolean value) {
		if (cleared[dungeon] != value) {
			cleared[dungeon] = value;
			chunks.clear(); // regenerate with the newly unlocked ore (edits stay on top)
		}
	}

	void trim(double centerX) {
		if (chunks.size() < 40) {
			return;
		}
		int center = Math.floorDiv((int) Math.floor(centerX), CHUNK);
		Iterator<Integer> it = chunks.keySet().iterator();
		while (it.hasNext()) {
			if (Math.abs(it.next() - center) > 8) {
				it.remove();
			}
		}
	}

	void clearCache() {
		chunks.clear();
	}

	private byte[] chunk(int cx) {
		byte[] chunk = chunks.get(cx);
		if (chunk == null) {
			chunk = generate(cx);
			chunks.put(cx, chunk);
		}
		return chunk;
	}

	// ------------------------------------------------------------------ shape

	private double biomeValue(double x) {
		double b = fractal(seed + 77, x / 300.0, 2);
		double near = Math.max(0, 1 - Math.abs(x) / 60.0);
		return b * (1 - near) + 0.2 * near; // forest around the start
	}

	/** The biome at x - dithered across the border so the two blend into each other over a dozen blocks. */
	int biome(int x) {
		double b = biomeValue(x) + (hash(seed + 79, x) - 0.5) * 0.035;
		return b < 0.42 ? FOREST : b < 0.6 ? ASHLANDS : FROST;
	}

	/** Smooth biome weights {forest, ash, frost} (for the sky tint and the hills). */
	double[] biomeWeights(double x) {
		double b = biomeValue(x);
		double ash = smooth(0.39, 0.45, b) * (1 - smooth(0.57, 0.63, b));
		double frost = smooth(0.57, 0.63, b);
		return new double[] {Math.max(0, 1 - ash - frost), ash, frost};
	}

	private static double smooth(double a, double b, double v) {
		double t = Math.max(0, Math.min(1, (v - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}

	// ------------------------------------------------------------------ villages and outposts

	static final int VILLAGE = 1, OUTPOST = 2;
	private static final int REGION = 320;

	/** A village or bandit outpost: where it is, its flat ground row, and (villages) its houses as {x, width}. */
	record Structure(int type, int x0, int width, int ground, long seed, java.util.List<int[]> houses, int smithy) {
		int x1() {
			return x0 + width - 1;
		}

		double centre() {
			return x0 + width / 2.0;
		}
	}

	private final Map<Integer, Structure> structures = new HashMap<>();
	private static final Structure NONE = new Structure(0, 0, 0, 0, 0, java.util.List.of(), -1);

	Structure structureFor(int region) {
		return structures.computeIfAbsent(region, r -> {
			if (r == 0 || r == -1) {
				return NONE; // the start stays open
			}
			double h = hash(seed + 211, r);
			int x0 = r * REGION + 40 + (int) (hash(seed + 213, r) * 200);
			int biome = biome(x0 + 20);
			int type = biome == ASHLANDS ? (h < 0.7 ? OUTPOST : 0) : h < 0.42 ? VILLAGE : h < 0.62 ? OUTPOST : 0;
			if (type == 0) {
				return NONE;
			}
			for (int i = 0; i < 3; i++) {
				if (Math.abs(gateX(i) - x0) < 70) {
					return NONE;
				}
			}
			long sseed = (long) (hash(seed + 217, r) * Long.MAX_VALUE);
			java.util.Random rnd = new java.util.Random(sseed);
			int width = type == VILLAGE ? 46 + rnd.nextInt(18) : 28 + rnd.nextInt(8);
			int ground = rawSurface(x0 + width / 2);
			java.util.List<int[]> houses = new java.util.ArrayList<>();
			int smithy = -1;
			if (type == VILLAGE) {
				int x = x0 + 3;
				while (true) {
					int hw = 7 + rnd.nextInt(3);
					if (x + hw > x0 + width - 3) {
						break;
					}
					houses.add(new int[] {x, hw});
					x += hw + 4 + rnd.nextInt(4);
				}
				smithy = houses.isEmpty() ? -1 : rnd.nextInt(houses.size());
			}
			return new Structure(type, x0, width, ground, sseed, java.util.List.copyOf(houses), smithy);
		});
	}

	/** The structure whose grounds (plus a short ramp each side) include column x, or null. */
	Structure structureAt(int x) {
		int r = Math.floorDiv(x, REGION);
		for (int k = r - 1; k <= r; k++) {
			Structure s = structureFor(k);
			if (s.type() != 0 && x >= s.x0() - 4 && x <= s.x1() + 4) {
				return s;
			}
		}
		return null;
	}

	/** Structures within {@code range} of x (for spawning their people). */
	java.util.List<Structure> structuresNear(double x, double range) {
		java.util.List<Structure> out = new java.util.ArrayList<>();
		int r = (int) Math.floor(x / REGION);
		for (int k = r - 1; k <= r + 1; k++) {
			Structure s = structureFor(k);
			if (s.type() != 0 && Math.abs(s.centre() - x) < range + s.width() / 2.0) {
				out.add(s);
			}
		}
		return out;
	}

	int surface(int x) {
		Structure st = structureAt(x);
		if (st == null) {
			return rawSurface(x);
		}
		if (x >= st.x0() && x <= st.x1()) {
			return st.ground();
		}
		// a short ramp from the natural ground to the flattened grounds
		int edge = x < st.x0() ? st.x0() : st.x1();
		double t = Math.abs(x - edge) / 5.0;
		return (int) Math.round(st.ground() * (1 - t) + rawSurface(x) * t);
	}

	private int rawSurface(int x) {
		double[] wgt = biomeWeights(x);
		double amp = wgt[0] * 13 + wgt[1] * 7 + wgt[2] * 19;
		double h = 44 + (fractal(seed, x / 52.0, 3) - 0.5) * 2 * amp + (noise1(seed + 3, x / 13.0) - 0.5) * 4;
		double calm = Math.min(1, Math.abs(x) / 12.0);
		return (int) Math.round(44 + (h - 44) * calm);
	}

	int dirtDepth(int x) {
		return 3 + (int) (noise1(seed + 5, x / 9.0) * 3);
	}

	private int topBlock(int biome) {
		return biome == ASHLANDS ? Items.SAND : biome == FROST ? Items.SNOW : Items.GRASS;
	}

	private int subBlock(int biome) {
		return biome == ASHLANDS ? Items.SANDSTONE : Items.DIRT;
	}

	@Override
	int wallAt(int x, int y) {
		int s = surface(x);
		if (y <= s) {
			Structure st = structureAt(x);
			if (st != null && st.type() == VILLAGE && y < s && y >= s - 6) {
				for (int[] h : st.houses()) {
					if (x > h[0] && x < h[0] + h[1] - 1) {
						return Items.PLANKS;
					}
				}
			}
			return 0;
		}
		int depth = y - s;
		if (depth > 38) {
			return Items.SHALE;
		}
		if (depth > dirtDepth(x)) {
			return Items.STONE;
		}
		return subBlock(biome(x));
	}

	// ------------------------------------------------------------------ dungeon gates

	/** Column of dungeon {@code i}'s gate: far from the start, one each way and one further out. */
	int gateX(int i) {
		int base = i == 0 ? -360 : i == 1 ? 560 : -980;
		return base + (int) ((hash(seed + 91, i) - 0.5) * 120);
	}

	int gateY(int i) {
		int x = gateX(i);
		return Math.min(HEIGHT - 14, surface(x) + 30 + i * 12);
	}

	private void carveGates(byte[] out, int x0) {
		for (int i = 0; i < 3; i++) {
			int gx = gateX(i);
			if (gx + 9 < x0 || gx - 9 >= x0 + CHUNK) {
				continue;
			}
			int gy = gateY(i);
			int brick = Items.BRICK_CRYPT + i;
			for (int x = gx - 8; x <= gx + 8; x++) {
				for (int y = gy - 6; y <= gy + 1; y++) {
					boolean frame = x == gx - 8 || x == gx + 8 || y == gy - 6 || y == gy + 1;
					put(out, x0, x, y, frame ? brick : Items.AIR, true);
				}
			}
			put(out, x0, gx, gy, Items.GATE, true);
			put(out, x0, gx - 6, gy - 3, Items.TORCH, true);
			put(out, x0, gx + 6, gy - 3, Items.TORCH, true);
			for (int y = gy - 2; y <= gy; y++) {
				put(out, x0, gx - 8, y, Items.AIR, true);
				put(out, x0, gx + 8, y, Items.AIR, true);
			}
		}
	}

	// ------------------------------------------------------------------ generation

	private byte[] generate(int cx) {
		byte[] out = new byte[CHUNK * HEIGHT];
		int x0 = cx * CHUNK;
		for (int lx = 0; lx < CHUNK; lx++) {
			int x = x0 + lx;
			int s = surface(x);
			int biome = biome(x);
			int dirt = dirtDepth(x);
			for (int y = 0; y < HEIGHT; y++) {
				int id;
				int depth = y - s;
				if (y >= HEIGHT - 1 || (y == HEIGHT - 2 && hash(seed + 21, x * 31L + y) < 0.5)) {
					id = Items.BEDROCK;
				} else if (y < s) {
					id = Items.AIR;
				} else if (y == s) {
					id = topBlock(biome);
				} else if (depth <= dirt) {
					id = subBlock(biome);
					if (biome == FROST && depth <= 2 && noise2(seed + 31, x / 5.0, y / 3.0) > 0.7) {
						id = Items.ICE;
					}
				} else {
					id = depth > 38 ? Items.SHALE : Items.STONE;
					id = ore(x, y, depth, id);
				}
				if (id != Items.BEDROCK && y > s + 1 && y < HEIGHT - 3) {
					double threshold = 0.64 - Math.min(0.1, depth / 300.0);
					double cave = noise2(seed + 9, x / 15.0, y / 9.0) * 0.7 + noise2(seed + 13, x / 6.0, y / 6.0) * 0.3;
					if ((depth > 5 && cave > threshold) || (depth <= 5 && cave > 0.74)) {
						id = Items.AIR;
						if (depth > 58 && cave > threshold + 0.07 && noise2(seed + 57, x / 7.0, y / 3.0) > 0.62) {
							id = Items.LAVA;
						} else if (depth > 12 && depth < 50 && cave > threshold + 0.05 && noise2(seed + 71, x / 9.0, y / 4.0) > 0.66) {
							// water pockets in the caves (they pour down once opened, see Liquids)
							id = Items.WATER;
						}
					}
				}
				out[lx * HEIGHT + y] = (byte) id;
			}
			if (biome == ASHLANDS && hash(seed + 61, x) < 0.06 && Math.abs(x) > 6) {
				int h = 2 + (int) (hash(seed + 63, x) * 2);
				for (int i = 1; i <= h; i++) {
					out[lx * HEIGHT + s - i] = (byte) Items.THORN;
				}
			}
		}
		for (int tx = x0 - 3; tx < x0 + CHUNK + 3; tx++) {
			if (!isTree(tx)) {
				continue;
			}
			int s = surface(tx);
			int trunk = s - treeTop(tx);
			for (int i = 1; i <= trunk; i++) {
				put(out, x0, tx, s - i, Items.TRUNK, false);
			}
		}
		carveGates(out, x0);
		for (int r = Math.floorDiv(x0, REGION) - 1; r <= Math.floorDiv(x0 + CHUNK, REGION); r++) {
			Structure st = structureFor(r);
			if (st.type() != 0 && st.x1() + 6 >= x0 && st.x0() - 6 < x0 + CHUNK) {
				build(out, x0, st);
			}
		}
		// Thornstalks (desert cacti) stand on solid ground only: gates and structures dig or flatten after they were
		// placed and left some hanging in the air.
		for (int lx = 0; lx < CHUNK; lx++) {
			for (int y = HEIGHT - 2; y >= 1; y--) {
				if (out[lx * HEIGHT + y] == (byte) Items.THORN) {
					int below = out[lx * HEIGHT + y + 1];
					if (below != Items.THORN && !Items.solid(below)) {
						for (int up = y; up >= 0 && out[lx * HEIGHT + up] == (byte) Items.THORN; up--) {
							out[lx * HEIGHT + up] = (byte) Items.AIR;
						}
					}
				}
			}
		}
		for (Map.Entry<Long, Integer> edit : edits.entrySet()) {
			int x = keyX(edit.getKey());
			int y = keyY(edit.getKey());
			if (x >= x0 && x < x0 + CHUNK && y >= 0 && y < HEIGHT) {
				out[(x - x0) * HEIGHT + y] = (byte) (int) edit.getValue();
			}
		}
		return out;
	}

	private void build(byte[] out, int x0, Structure st) {
		int g = st.ground();
		java.util.Random rnd = new java.util.Random(st.seed());
		// clear the air above the grounds (no stray thorns or trunks)
		for (int x = st.x0() - 4; x <= st.x1() + 4; x++) {
			for (int y = Math.max(0, g - 16); y < surface(x); y++) {
				put(out, x0, x, y, Items.AIR, true);
			}
		}
		if (st.type() == VILLAGE) {
			for (int i = 0; i < st.houses().size(); i++) {
				int[] h = st.houses().get(i);
				int hx = h[0];
				int hw = h[1];
				boolean doorLeft = rnd.nextBoolean();
				for (int x = hx; x < hx + hw; x++) {
					put(out, x0, x, g, Items.PLANKS, true); // floor
					boolean wall = x == hx || x == hx + hw - 1;
					for (int y = g - 5; y < g; y++) {
						boolean door = wall && (doorLeft ? x == hx : x == hx + hw - 1) && y >= g - 2;
						boolean window = wall && !(doorLeft ? x == hx : x == hx + hw - 1) && y == g - 3;
						put(out, x0, x, y, wall && !door && !window ? Items.LOG : Items.AIR, true);
					}
					put(out, x0, x, g - 6, Items.PLANKS, true); // ceiling
				}
				// a pitched roof of clay tiles, overhanging by one
				for (int row = 0; row < (hw + 2) / 2 + 1; row++) {
					for (int x = hx - 1 + row; x <= hx + hw - row; x++) {
						put(out, x0, x, g - 7 - row, Items.ROOF, true);
					}
				}
				put(out, x0, hx + hw / 2, g - 4, Items.TORCH, true);
				if (i == st.smithy()) {
					put(out, x0, hx + 1 + (doorLeft ? 1 : 0), g - 1, Items.ANVIL, true);
					put(out, x0, hx + hw - 3 + (doorLeft ? 0 : -1), g - 1, Items.BENCH, true);
				} else if (rnd.nextDouble() < 0.5) {
					put(out, x0, doorLeft ? hx + hw - 2 : hx + 1, g - 1, Items.CHEST, true);
				}
			}
			// lamp posts between the houses
			for (int i = 0; i + 1 < st.houses().size(); i++) {
				int[] a = st.houses().get(i);
				int lx = a[0] + a[1] + 1;
				for (int y = g - 3; y < g; y++) {
					put(out, x0, lx, y, Items.LOG, true);
				}
				put(out, x0, lx, g - 4, Items.TORCH, true);
			}
		} else {
			int cx = st.x0() + st.width() / 2;
			// palisade at both ends
			for (int x : new int[] {st.x0(), st.x0() + 1, st.x1() - 1, st.x1()}) {
				for (int y = g - 4; y < g; y++) {
					put(out, x0, x, y, Items.LOG, true);
				}
				put(out, x0, x, g - 5, Items.SPIKES, true);
			}
			for (int y = g - 2; y < g; y++) {
				put(out, x0, st.x0(), y, Items.AIR, true); // a gate to walk in through
				put(out, x0, st.x0() + 1, y, Items.AIR, true);
				put(out, x0, st.x1() - 1, y, Items.AIR, true);
				put(out, x0, st.x1(), y, Items.AIR, true);
			}
			// watchtower
			for (int y = g - 8; y < g; y++) {
				put(out, x0, cx - 2, y, Items.LOG, true);
				put(out, x0, cx + 2, y, Items.LOG, true);
			}
			for (int x = cx - 3; x <= cx + 3; x++) {
				put(out, x0, x, g - 8, Items.PLATFORM, true);
				put(out, x0, x, g - 4, Items.PLATFORM, true);
				put(out, x0, x, g - 12, Items.ROOF, true);
			}
			for (int x = cx - 2; x <= cx + 2; x++) {
				put(out, x0, x, g - 13, Items.ROOF, true);
			}
			put(out, x0, cx - 2, g - 11, Items.LOG, true);
			put(out, x0, cx + 2, g - 11, Items.LOG, true);
			put(out, x0, cx - 2, g - 10, Items.LOG, true);
			put(out, x0, cx + 2, g - 10, Items.LOG, true);
			put(out, x0, cx - 2, g - 9, Items.LOG, true);
			put(out, x0, cx + 2, g - 9, Items.LOG, true);
			// tents of red cloth
			for (int t : new int[] {st.x0() + 5, st.x1() - 9}) {
				for (int row = 0; row < 4; row++) {
					for (int x = t + row; x < t + 7 - row; x++) {
						put(out, x0, x, g - 1 - row, Items.CLOTH, true);
					}
				}
			}
			put(out, x0, cx - 6, g - 1, Items.TORCH, true); // campfire
			put(out, x0, cx + 5, g - 1, Items.CHEST, true);
		}
	}

	private int ore(int x, int y, int depth, int stone) {
		if (depth > 66 && cleared[2] && noise2(seed + 53, x / 3.0, y / 3.0) > 0.86) {
			return Items.AETHER_ORE;
		}
		if (depth > 54 && cleared[1] && noise2(seed + 51, x / 3.2, y / 3.2) > 0.855) {
			return Items.EMBER_ORE;
		}
		if (depth > 42 && cleared[0] && noise2(seed + 49, x / 3.4, y / 3.4) > 0.85) {
			return Items.BLOOD_ORE;
		}
		if (depth > 30 && noise2(seed + 47, x / 3.0, y / 3.0) > 0.87) {
			return Items.RUBY_ORE;
		}
		if (depth > 14 && noise2(seed + 43, x / 3.5, y / 3.5) > 0.83) {
			return Items.IRON_ORE;
		}
		if (depth > 4 && noise2(seed + 41, x / 4.0, y / 4.0) > 0.80) {
			return Items.COAL_ORE;
		}
		return stone;
	}

	private static void put(byte[] out, int x0, int x, int y, int id, boolean force) {
		if (x < x0 || x >= x0 + CHUNK || y < 0 || y >= HEIGHT) {
			return;
		}
		int i = (x - x0) * HEIGHT + y;
		if (force || out[i] == Items.AIR) {
			out[i] = (byte) id;
		}
	}

	private boolean rawTree(int x) {
		int biome = biome(x);
		double chance = biome == ASHLANDS ? 0.035 : biome == FROST ? 0.12 : 0.17;
		return hash(seed + 15, x) < chance;
	}

	boolean isTree(int x) {
		if (Math.abs(x) < 3 || !rawTree(x) || rawTree(x - 1) || rawTree(x - 2) || structureAt(x) != null) {
			return false;
		}
		int s = surface(x);
		return Math.abs(surface(x - 1) - s) <= 1 && Math.abs(surface(x + 1) - s) <= 1;
	}

	int treeTop(int x) {
		return surface(x) - (4 + (int) (hash(seed + 17, x) * 3));
	}

	// ------------------------------------------------------------------ decorations

	@Override
	int decorAt(int x, int y) {
		if (get(x, y) != Items.AIR) {
			return -1;
		}
		int s = surface(x);
		double h = hash(seed + 101, x * 7919L + y * 31L);
		int below = get(x, y + 1);
		int above = get(x, y - 1);
		if (y == s - 1 && below == topBlock(biome(x)) && !isTree(x)) {
			return switch (biome(x)) {
				case ASHLANDS -> h < 0.10 ? 2 : h < 0.13 ? 3 : -1;
				case FROST -> h < 0.15 ? 4 : h < 0.19 ? 5 : -1;
				default -> h < 0.30 ? 0 : h < 0.38 ? 1 : -1;
			};
		}
		int depth = y - s;
		if (depth < 3) {
			return -1;
		}
		if (Items.solid(below)) {
			if (depth > 55 && h < 0.04) {
				return 12;
			}
			if (depth > 40 && h < 0.035) {
				return 8;
			}
			if (depth > 30 && h < 0.012) {
				return 11;
			}
			if (depth > 22 && h < 0.03) {
				return 7;
			}
			if (h < 0.045) {
				return 6;
			}
		}
		if (Items.solid(above)) {
			if (depth < 14 && h < 0.10) {
				return 10;
			}
			if (h < 0.06) {
				return 9;
			}
			if (Items.solid(get(x - 1, y)) && h < 0.075) {
				return 13;
			}
		}
		return -1;
	}

	// ------------------------------------------------------------------ noise

	static double hash(long seed, long x) {
		long z = seed * 0x9E3779B97F4A7C15L + x * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		z = z ^ (z >>> 31);
		return (z >>> 11) * 0x1.0p-53;
	}

	static double noise1(long seed, double x) {
		long i = (long) Math.floor(x);
		double f = x - i;
		f = f * f * (3 - 2 * f);
		return hash(seed, i) * (1 - f) + hash(seed, i + 1) * f;
	}

	static double fractal(long seed, double x, int octaves) {
		double sum = 0;
		double amp = 1;
		double total = 0;
		for (int o = 0; o < octaves; o++) {
			sum += noise1(seed + o * 101L, x) * amp;
			total += amp;
			amp *= 0.5;
			x *= 2;
		}
		return sum / total;
	}

	static double noise2(long seed, double x, double y) {
		long ix = (long) Math.floor(x);
		long iy = (long) Math.floor(y);
		double fx = x - ix;
		double fy = y - iy;
		fx = fx * fx * (3 - 2 * fx);
		fy = fy * fy * (3 - 2 * fy);
		double a = hash(seed, ix * 73856093L ^ iy * 19349663L);
		double b = hash(seed, (ix + 1) * 73856093L ^ iy * 19349663L);
		double c = hash(seed, ix * 73856093L ^ (iy + 1) * 19349663L);
		double d = hash(seed, (ix + 1) * 73856093L ^ (iy + 1) * 19349663L);
		return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
	}
}
