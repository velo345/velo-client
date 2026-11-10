package net.veloclient.launcher.sandbox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;

/**
 * One of the three dungeons, generated from the world seed: a grid of rooms joined by a random maze (doorways and
 * platform shafts), traps in the theme's style, chests, a puzzle that unseals the boss arena, and the arena itself.
 * <ul>
 * <li>Crimson Crypt - spikes and arrow traps; pull all three levers.</li>
 * <li>Sunken Foundry - lava pits and fire vents; strike the four runes in the order on the plaque by the entrance.</li>
 * <li>Void Spire - crumbling ledges, spike pits and orb traps; collect the three key fragments.</li>
 * </ul>
 */
final class Dungeon extends Level {

	enum Theme {
		CRYPT("Crimson Crypt", "Crypt Warden", Items.BRICK_CRYPT, 0xFF1A0C0F, 0xFF3A1418),
		FOUNDRY("Sunken Foundry", "Furnace Golem", Items.BRICK_FOUNDRY, 0xFF1A0E08, 0xFF4A2410),
		SPIRE("Void Spire", "Void Herald", Items.BRICK_SPIRE, 0xFF0C0816, 0xFF2A1450);

		final String title;
		final String boss;
		final int brick;
		final int dark;
		final int glow;

		Theme(String title, String boss, int brick, int dark, int glow) {
			this.title = title;
			this.boss = boss;
			this.brick = brick;
			this.dark = dark;
			this.glow = glow;
		}
	}

	static final int RW = 22;
	static final int RH = 12;
	static final int COLS = 5;
	static final int ROWS = 3;
	static final int AW = 36;
	static final int AH = 16;

	final int index;
	final Theme theme;
	final int width;
	final int heightTiles;
	private final byte[] tiles;
	final int startX;
	final int startY;
	/** Boss arena bounds (inclusive interior) and where the boss appears. */
	final int arenaX0;
	final int arenaX1;
	final int arenaY0;
	final int arenaY1;
	final double bossX;
	final double bossY;
	/** Door tiles: {x, y, id when open}. */
	final List<int[]> doors = new ArrayList<>();
	/** The riddle that seals the arena (picked at random per dungeon). */
	enum Riddle {
		LEVERS, RUNES, KEYS, PLATES, BRAZIERS, GLYPHS
	}

	final Riddle riddle;
	/** Levers / runes / key fragments / plates / braziers / tablets, in order. */
	final List<int[]> puzzle = new ArrayList<>();
	/** Tablets: the symbol each one has to show (scratched beside the arena door). */
	final int[] glyphTarget = new int[4];
	int targetX;
	int targetY;
	/** Columns kept clear for the vertical shafts and doorways. */
	private final java.util.Set<Integer> keepClear = new java.util.HashSet<>();
	/** Foundry: the symbol (0-3) on each rune, and where the plaque hangs. */
	final int[] runeSymbol = new int[4];
	int plaqueX;
	int plaqueY;
	/** Traps: {x, y, dirX, dirY}. */
	final List<int[]> traps = new ArrayList<>();
	/** Monsters placed at generation: {x, y, kind}. */
	final List<int[]> spawns = new ArrayList<>();
	boolean bossDefeated;

	Dungeon(long worldSeed, int index) {
		this.index = index;
		this.theme = Theme.values()[index];
		this.width = COLS * RW + AW;
		this.heightTiles = ROWS * RH;
		this.tiles = new byte[width * heightTiles];
		SplittableRandom r = new SplittableRandom(worldSeed * 31 + index * 7919L + 5);
		this.riddle = Riddle.values()[r.nextInt(Riddle.values().length)];
		java.util.Arrays.fill(tiles, (byte) theme.brick);

		// ---- maze over the room grid (random depth-first) ----
		int startRow = r.nextInt(ROWS);
		boolean[][] seen = new boolean[COLS][ROWS];
		int[][] degree = new int[COLS][ROWS];
		List<int[]> links = new ArrayList<>();
		Deque<int[]> stack = new ArrayDeque<>();
		stack.push(new int[] {0, startRow});
		seen[0][startRow] = true;
		while (!stack.isEmpty()) {
			int[] at = stack.peek();
			List<int[]> next = new ArrayList<>();
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				int nx = at[0] + d[0];
				int ny = at[1] + d[1];
				if (nx >= 0 && nx < COLS && ny >= 0 && ny < ROWS && !seen[nx][ny]) {
					next.add(new int[] {nx, ny});
				}
			}
			if (next.isEmpty()) {
				stack.pop();
				continue;
			}
			int[] go = next.get(r.nextInt(next.size()));
			seen[go[0]][go[1]] = true;
			links.add(new int[] {at[0], at[1], go[0], go[1]});
			degree[at[0]][at[1]]++;
			degree[go[0]][go[1]]++;
			stack.push(go);
		}
		// a couple of loops so it isn't a pure tree
		for (int extra = 0; extra < 2; extra++) {
			int cx = r.nextInt(COLS - 1);
			int cy = r.nextInt(ROWS);
			links.add(new int[] {cx, cy, cx + 1, cy});
		}

		for (int cx = 0; cx < COLS; cx++) {
			for (int cy = 0; cy < ROWS; cy++) {
				carve(cx * RW + 1, cy * RH + 1, cx * RW + RW - 2, cy * RH + RH - 2, Items.AIR);
			}
		}
		for (int[] link : links) {
			connect(link, r);
		}

		// ---- the arena (bottom right), sealed by a door from the last room ----
		arenaX0 = COLS * RW + 1;
		arenaX1 = width - 2;
		arenaY1 = heightTiles - 2;
		arenaY0 = heightTiles - AH + 1;
		carve(arenaX0, arenaY0, arenaX1, arenaY1, Items.AIR);
		int lastFloor = (ROWS - 1) * RH + RH - 2;
		for (int y = lastFloor - 2; y <= lastFloor; y++) {
			for (int x = COLS * RW - 1; x <= COLS * RW; x++) {
				set0(x, y, Items.DOOR);
				doors.add(new int[] {x, y, Items.AIR});
			}
		}
		// arena ledges to dodge onto
		for (int x = arenaX0 + 4; x < arenaX0 + 10; x++) {
			set0(x, arenaY1 - 4, Items.PLATFORM);
		}
		for (int x = arenaX1 - 9; x < arenaX1 - 3; x++) {
			set0(x, arenaY1 - 4, Items.PLATFORM);
		}
		for (int x = (arenaX0 + arenaX1) / 2 - 3; x <= (arenaX0 + arenaX1) / 2 + 3; x++) {
			set0(x, arenaY1 - 8, Items.PLATFORM);
		}
		set0(arenaX0 + 1, arenaY0 + 2, Items.TORCH);
		set0(arenaX1 - 1, arenaY0 + 2, Items.TORCH);
		bossX = (arenaX0 + arenaX1) / 2.0 + 0.5;
		bossY = arenaY1 + 1 - 1e-3;

		// ---- start room ----
		int sx0 = 1;
		int sy0 = startRow * RH + 1;
		int floor = startRow * RH + RH - 2;
		set0(sx0 + 2, floor, Items.GATE);
		startX = sx0 + 6;
		startY = floor + 1;

		// ---- rooms: roles ----
		List<int[]> rooms = new ArrayList<>();
		for (int cx = 0; cx < COLS; cx++) {
			for (int cy = 0; cy < ROWS; cy++) {
				if (!(cx == 0 && cy == startRow)) {
					rooms.add(new int[] {cx, cy, degree[cx][cy]});
				}
			}
		}
		// dead ends first for the riddle pieces
		java.util.Collections.shuffle(rooms, new java.util.Random(r.nextLong()));
		rooms.sort((a, b) -> Integer.compare(a[2], b[2]));
		int puzzleRooms = switch (riddle) {
			case RUNES, PLATES, GLYPHS -> 1;
			case BRAZIERS -> 4;
			default -> 3;
		};
		for (int i = 0; i < rooms.size(); i++) {
			int[] room = rooms.get(i);
			int rx = room[0] * RW + 1;
			int ry = room[1] * RH + 1;
			int rfloor = room[1] * RH + RH - 2;
			boolean puzzleRoom = i < puzzleRooms;
			// riddle rooms stay plain halls so the pieces can be reached and pushed around
			int template = puzzleRoom && (riddle == Riddle.PLATES || riddle == Riddle.GLYPHS || riddle == Riddle.RUNES) ? 0 : r.nextInt(6);
			shapeRoom(rx, ry, rfloor, template, r);
			if (puzzleRoom) {
				placePuzzle(rx, rfloor, r);
			} else if (i < puzzleRooms + 4) {
				int cx = rx + 4 + r.nextInt(RW - 10);
				int fy = floorAt(cx, rfloor);
				if (fy > 0) {
					set0(cx, fy, Items.CHEST);
				}
			}
			if (!puzzleRoom || riddle == Riddle.LEVERS || riddle == Riddle.KEYS || riddle == Riddle.BRAZIERS) {
				placeTraps(rx, ry, rfloor, r);
			}
			for (int m = 0; m < 2; m++) {
				int mx = rx + 3 + r.nextInt(RW - 8);
				spawns.add(new int[] {mx, floorAt(mx, rfloor) + 1, r.nextInt(2)});
			}
		}
		if (riddle == Riddle.RUNES) {
			// the order is scratched into a wall somewhere else in the dungeon
			int[] room = rooms.get(Math.min(rooms.size() - 1, puzzleRooms + r.nextInt(Math.max(1, rooms.size() - puzzleRooms))));
			plaqueX = room[0] * RW + 4 + r.nextInt(RW - 12);
			plaqueY = room[1] * RH + 3;
		} else {
			plaqueX = -100;
		}
		if (riddle == Riddle.GLYPHS) {
			for (int i = 0; i < 4; i++) {
				glyphTarget[i] = r.nextInt(4);
			}
			targetX = COLS * RW - 7;
			targetY = (ROWS - 1) * RH + 3;
		}
		for (int t = 0; t < 2; t++) {
			set0(sx0 + 4 + t * 10, sy0 + 2, Items.TORCH);
		}
	}

	private void connect(int[] link, SplittableRandom r) {
		int ax = Math.min(link[0], link[2]);
		int ay = Math.min(link[1], link[3]);
		if (link[1] == link[3]) {
			// side by side: a doorway at floor level through the double wall
			int floor = ay * RH + RH - 2;
			int wx = ax * RW + RW - 1;
			for (int y = floor - 2; y <= floor; y++) {
				set0(wx, y, Items.AIR);
				set0(wx + 1, y, Items.AIR);
			}
		} else {
			// one above the other: a shaft with platforms every two rows
			int sx = ax * RW + 5 + r.nextInt(RW - 12);
			for (int k = -1; k < 5; k++) {
				keepClear.add(sx + k);
			}
			int upperFloor = ay * RH + RH - 1;
			int lowerFeet = (ay + 1) * RH + RH - 1;
			for (int x = sx; x < sx + 3; x++) {
				set0(x, upperFloor, Items.PLATFORM);
				set0(x, upperFloor + 1, Items.AIR);
				for (int y = lowerFeet - 2; y > upperFloor + 1; y -= 2) {
					set0(x + ((lowerFeet - y) / 2 % 2 == 0 ? 0 : 1) - 0, y, Items.PLATFORM);
				}
			}
		}
	}

	/** The floor's top row at column x in a room (rubble and pits move it), or -1. */
	private int floorAt(int x, int floor) {
		for (int y = floor - 6; y <= floor + 1; y++) {
			if (get(x, y) == Items.AIR && Items.solid(get(x, y + 1))) {
				return y;
			}
		}
		return -1;
	}

	private boolean clearColumn(int x, int rx) {
		return keepClear.contains(x) || x <= rx + 2 || x >= rx + RW - 5;
	}

	/**
	 * Room shapes: 0 hall with ledges, 1 pillar hall, 2 pit room (spikes / lava / the void), 3 collapsed room with
	 * rubble, 4 climbing room, 5 alcoves with candles.
	 */
	private void shapeRoom(int rx, int ry, int floor, int template, SplittableRandom r) {
		switch (template) {
			case 1 -> {
				for (int k = 0; k < 3; k++) {
					int x = rx + 5 + k * 5 + r.nextInt(2);
					if (clearColumn(x, rx) || clearColumn(x + 1, rx)) {
						continue;
					}
					boolean hanging = r.nextBoolean();
					for (int y = hanging ? ry : floor - 2; y <= (hanging ? floor - 3 : floor); y++) {
						set0(x, y, theme.brick);
						set0(x + 1, y, theme.brick);
					}
				}
				decorateRoom(rx, ry, floor, r);
			}
			case 2 -> {
				// raise the middle of the floor and sink a pit in it, bridged by a few platforms
				int a = rx + 4;
				int b = rx + RW - 7;
				int p0 = a + 3 + r.nextInt(3);
				int p1 = Math.min(b - 2, p0 + 6 + r.nextInt(4));
				for (int x = a; x <= b; x++) {
					if (keepClear.contains(x)) {
						continue;
					}
					boolean pit = x >= p0 && x <= p1;
					set0(x, floor, pit ? hazard() : theme.brick);
					set0(x, floor - 1, pit ? Items.AIR : theme.brick);
				}
				for (int x = p0 + 1; x <= p1 - 1; x += 3) {
					set0(x, floor - 4, Items.PLATFORM);
					set0(x + 1, floor - 4, theme == Theme.SPIRE ? Items.CRUMBLE : Items.PLATFORM);
				}
				set0(rx + 3 + r.nextInt(5), ry + 2, Items.TORCH);
			}
			case 3 -> {
				for (int k = 0; k < 3; k++) {
					int cx = rx + 5 + r.nextInt(RW - 12);
					int hgt = 1 + r.nextInt(3);
					for (int dx = -hgt; dx <= hgt; dx++) {
						int x = cx + dx;
						if (clearColumn(x, rx)) {
							continue;
						}
						for (int y = floor - (hgt - Math.abs(dx)) + 1; y <= floor; y++) {
							set0(x, y, theme.brick);
						}
					}
				}
				// a broken chunk of ceiling
				int cx = rx + 6 + r.nextInt(RW - 14);
				for (int x = cx; x < cx + 3; x++) {
					if (!clearColumn(x, rx)) {
						set0(x, ry, theme.brick);
						set0(x, ry + 1, r.nextBoolean() ? theme.brick : Items.AIR);
					}
				}
			}
			case 4 -> {
				// zigzag of short platforms up to the ceiling (often with a chest up there)
				for (int y = floor - 2, k = 0; y > ry + 1; y -= 2, k++) {
					int x = k % 2 == 0 ? rx + 4 : rx + RW - 10;
					for (int dx = 0; dx < 4; dx++) {
						if (get(x + dx, y) == Items.AIR) {
							set0(x + dx, y, theme == Theme.SPIRE && k % 3 == 2 ? Items.CRUMBLE : Items.PLATFORM);
						}
					}
				}
				if (get(rx + RW - 8, ry + 1) == Items.AIR && r.nextBoolean()) {
					set0(rx + RW - 8, ry + 1, Items.CHEST);
					set0(rx + RW - 8, ry + 2, Items.PLATFORM);
				}
			}
			case 5 -> {
				// niches in the back wall with candles, the floor between kept clear
				for (int k = 0; k < 4; k++) {
					int x = rx + 3 + k * 4;
					set0(x, ry + 3, Items.TORCH);
					set0(x, ry + 4, Items.PLATFORM);
				}
				decorateRoom(rx, ry, floor, r);
			}
			default -> decorateRoom(rx, ry, floor, r);
		}
	}

	private int hazard() {
		return theme == Theme.FOUNDRY ? Items.LAVA : Items.SPIKES;
	}

	private void decorateRoom(int rx, int ry, int floor, SplittableRandom r) {
		int ledges = 1 + r.nextInt(2);
		for (int l = 0; l < ledges; l++) {
			int y = floor - 3 - r.nextInt(3);
			int x0 = rx + 2 + r.nextInt(RW - 10);
			int len = 4 + r.nextInt(4);
			boolean crumble = theme == Theme.SPIRE && r.nextBoolean();
			for (int x = x0; x < x0 + len; x++) {
				if (get(x, y) == Items.AIR) {
					set0(x, y, crumble ? Items.CRUMBLE : Items.PLATFORM);
				}
			}
		}
		set0(rx + 3 + r.nextInt(5), ry + 2, Items.TORCH);
		set0(rx + RW - 6 - r.nextInt(5), ry + 2, Items.TORCH);
	}

	private void placePuzzle(int rx, int floor, SplittableRandom r) {
		switch (riddle) {
			case LEVERS -> {
				int x = rx + 5 + r.nextInt(RW - 12);
				int fy = floorAt(x, floor);
				set0(x, fy > 0 ? fy : floor, Items.LEVER);
				puzzle.add(new int[] {x, fy > 0 ? fy : floor});
			}
			case RUNES -> {
				int[] order = shuffled(r);
				for (int i = 0; i < 4; i++) {
					int x = rx + 3 + i * 4;
					set0(x, floor, Items.RUNE);
					puzzle.add(new int[] {x, floor});
					runeSymbol[i] = order[i];
				}
			}
			case KEYS -> {
				int x = rx + 5 + r.nextInt(RW - 12);
				int y = floor - 4;
				set0(x, y, Items.KEY_FRAG);
				for (int dx = -1; dx <= 1; dx++) {
					set0(x + dx, y + 1, Items.PLATFORM);
				}
				puzzle.add(new int[] {x, y});
			}
			case PLATES -> {
				// plates sunk into the floor, crates elsewhere in the room (one up on a ledge)
				int n = 2 + r.nextInt(2);
				for (int i = 0; i < n; i++) {
					int x = rx + 4 + i * 3;
					set0(x, floor + 1, Items.PLATE);
					puzzle.add(new int[] {x, floor + 1});
				}
				for (int i = 0; i < n; i++) {
					int x = rx + RW - 7 - i * 2;
					if (i == n - 1) {
						// up on a ledge: has to be pushed off
						for (int dx = -2; dx <= 1; dx++) {
							set0(x - 3 + dx, floor - 1, theme.brick);
							set0(x - 3 + dx, floor, theme.brick);
						}
						set0(x - 4, floor - 2, Items.CRATE);
					} else {
						set0(x, floor, Items.CRATE);
					}
				}
			}
			case BRAZIERS -> {
				int x = rx + 5 + r.nextInt(RW - 12);
				int fy = floorAt(x, floor);
				set0(x, fy > 0 ? fy : floor, Items.BRAZIER);
				puzzle.add(new int[] {x, fy > 0 ? fy : floor});
			}
			case GLYPHS -> {
				for (int i = 0; i < 4; i++) {
					int x = rx + 4 + i * 4;
					set0(x, floor, Items.GLYPH + r.nextInt(4));
					puzzle.add(new int[] {x, floor});
				}
			}
		}
	}

	private static int[] shuffled(SplittableRandom r) {
		int[] order = {0, 1, 2, 3};
		for (int i = 3; i > 0; i--) {
			int j = r.nextInt(i + 1);
			int t = order[i];
			order[i] = order[j];
			order[j] = t;
		}
		return order;
	}

	private void placeTraps(int rx, int ry, int floor, SplittableRandom r) {
		switch (theme) {
			case CRYPT -> {
				int x = rx + 4 + r.nextInt(RW - 10);
				for (int i = 0; i < 3; i++) {
					if (get(x + i, floor) == Items.AIR) {
						set0(x + i, floor, Items.SPIKES);
					}
				}
				boolean left = r.nextBoolean();
				int wx = left ? rx - 1 : rx + RW - 2;
				if (get(wx, floor - 1) == theme.brick) {
					set0(wx, floor - 1, Items.TRAP);
					traps.add(new int[] {wx, floor - 1, left ? 1 : -1, 0});
				}
			}
			case FOUNDRY -> {
				int x = rx + 4 + r.nextInt(RW - 10);
				for (int i = 0; i < 3; i++) {
					if (get(x + i, floor + 1) == theme.brick && get(x + i, floor) == Items.AIR) {
						set0(x + i, floor + 1, Items.LAVA);
					}
				}
				int vx = rx + 3 + r.nextInt(RW - 6);
				if (get(vx, floor + 1) == theme.brick && get(vx, floor) == Items.AIR) {
					set0(vx, floor + 1, Items.TRAP);
					traps.add(new int[] {vx, floor + 1, 0, -1});
				}
			}
			case SPIRE -> {
				int x = rx + 4 + r.nextInt(RW - 10);
				if (get(x, floor + 1) == theme.brick && get(x + 1, floor + 1) == theme.brick) {
					set0(x, floor + 1, Items.SPIKES);
					set0(x + 1, floor + 1, Items.SPIKES);
				}
				boolean left = r.nextBoolean();
				int wx = left ? rx - 1 : rx + RW - 2;
				if (get(wx, ry + 3) == theme.brick) {
					set0(wx, ry + 3, Items.TRAP);
					traps.add(new int[] {wx, ry + 3, left ? 1 : -1, 0});
				}
			}
		}
	}

	private void carve(int x0, int y0, int x1, int y1, int id) {
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				set0(x, y, id);
			}
		}
	}

	/** Generation write (not an edit). */
	private void set0(int x, int y, int id) {
		if (x >= 0 && x < width && y >= 0 && y < heightTiles) {
			tiles[y * width + x] = (byte) id;
		}
	}

	// ------------------------------------------------------------------ Level

	@Override
	String id() {
		return "dungeon" + index;
	}

	@Override
	int height() {
		return heightTiles;
	}

	@Override
	boolean outdoors() {
		return false;
	}

	@Override
	double ambient() {
		return 0.12;
	}

	@Override
	int get(int x, int y) {
		if (x < 0 || x >= width || y < 0 || y >= heightTiles) {
			return theme.brick;
		}
		return tiles[y * width + x];
	}

	@Override
	void set(int x, int y, int id) {
		if (x >= 0 && x < width && y >= 0 && y < heightTiles) {
			tiles[y * width + x] = (byte) id;
			edits.put(key(x, y), id);
		}
	}

	/** Re-applies saved / received edits on top of the generated layout. */
	void applyEdits() {
		for (var e : edits.entrySet()) {
			int x = keyX(e.getKey());
			int y = keyY(e.getKey());
			if (x >= 0 && x < width && y >= 0 && y < heightTiles) {
				tiles[y * width + x] = (byte) (int) e.getValue();
			}
		}
	}

	@Override
	int wallAt(int x, int y) {
		return theme.brick;
	}

	@Override
	int decorAt(int x, int y) {
		if (get(x, y) != Items.AIR) {
			return -1;
		}
		double h = World.hash(index * 131L + 7, x * 7919L + y * 31L);
		boolean floor = Items.solid(get(x, y + 1));
		boolean ceiling = Items.solid(get(x, y - 1));
		switch (theme) {
			case CRYPT -> {
				if (floor && h < 0.06) {
					return h < 0.03 ? 11 : 3;
				}
				if (ceiling && h < 0.08) {
					return 13;
				}
			}
			case FOUNDRY -> {
				if (floor && h < 0.07) {
					return 12;
				}
				if (ceiling && h < 0.05) {
					return 9;
				}
			}
			case SPIRE -> {
				if (floor && h < 0.08) {
					return 8;
				}
				if (ceiling && h < 0.05) {
					return 9;
				}
			}
		}
		return -1;
	}

	/** True when the riddle that seals the arena is solved (judged from the blocks, so it syncs by itself). */
	boolean solved() {
		return !puzzle.isEmpty() && progress() == puzzle.size();
	}

	int progress() {
		int done = 0;
		for (int i = 0; i < puzzle.size(); i++) {
			int[] p = puzzle.get(i);
			int id = get(p[0], p[1]);
			boolean ok = switch (riddle) {
				case LEVERS -> id == Items.LEVER_ON;
				case RUNES -> id == Items.RUNE_LIT;
				case KEYS -> id != Items.KEY_FRAG;
				case PLATES -> get(p[0], p[1] - 1) == Items.CRATE;
				case BRAZIERS -> id == Items.BRAZIER_LIT;
				case GLYPHS -> id == Items.GLYPH + glyphTarget[i];
			};
			if (ok) {
				done++;
			}
		}
		return done;
	}

	boolean doorOpen() {
		return doors.isEmpty() || get(doors.get(0)[0], doors.get(0)[1]) != Items.DOOR;
	}

	boolean inArena(double x, double y) {
		return x >= arenaX0 && x <= arenaX1 + 1 && y >= arenaY0 && y <= arenaY1 + 1.5;
	}
}
