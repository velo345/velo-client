package net.veloclient.launcher.sandbox;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Blockfall: a falling-blocks puzzle built from the world's own blocks (planks, stone, bricks, ores, grass). Fill a
 * row to clear it; the blocks burst into pixels. Ghost piece, next preview, hard drop, levels that speed up.
 */
final class BlockfallGame implements MiniGame {

	private static final int W = 10;
	private static final int H = 20;
	/** The seven four-block shapes, as cells in a 4x4 box. */
	private static final int[][][] SHAPES = {
			{{0, 1}, {1, 1}, {2, 1}, {3, 1}},
			{{1, 0}, {2, 0}, {1, 1}, {2, 1}},
			{{1, 0}, {0, 1}, {1, 1}, {2, 1}},
			{{1, 0}, {2, 0}, {0, 1}, {1, 1}},
			{{0, 0}, {1, 0}, {1, 1}, {2, 1}},
			{{0, 0}, {0, 1}, {1, 1}, {2, 1}},
			{{2, 0}, {0, 1}, {1, 1}, {2, 1}},
			{{1, 0}, {2, 0}, {1, 1}, {2, 1}}}; // 7: your glowing head (2x2) - clearing it clears the whole board
	private static final int HEAD = 7;
	private static final int[] SKINS = {Items.RUBY_ORE, Items.STONE, Items.PLANKS, Items.GRASS, Items.BRICK, Items.IRON_ORE,
			Items.COAL_ORE};

	private final Arcade arcade;
	private final int[][] board = new int[H][W];
	private final List<Integer> bag = new ArrayList<>();
	private int type;
	private int next;
	private int[][] cells;
	private int px;
	private int py;
	private double fall;
	private double lock;
	private int lockMoves;
	private boolean left;
	private boolean right;
	private boolean down;
	private double repeat;
	private int lines;
	private int score;
	private int best;
	private boolean started;
	private boolean over;
	private double overFor;
	private final List<Integer> clearing = new ArrayList<>();
	private double clearTimer;
	private double t;
	private double cell;
	private double bx;
	private double by;

	BlockfallGame(Arcade arcade) {
		this.arcade = arcade;
		reset();
	}

	@Override
	public String id() {
		return "blocks";
	}

	@Override
	public String music() {
		return "blocks";
	}

	private void reset() {
		for (int[] row : board) {
			java.util.Arrays.fill(row, 0);
		}
		bag.clear();
		lines = 0;
		score = 0;
		over = false;
		overFor = 0;
		started = false;
		clearing.clear();
		next = draw();
		spawn();
	}

	private int level() {
		return lines / 10 + 1;
	}

	private int sinceHead;
	private boolean nuke;
	private final java.util.Random chance = new java.util.Random();

	private int draw() {
		sinceHead++;
		if (sinceHead > 9 && chance.nextDouble() < 0.11) {
			sinceHead = 0;
			return HEAD;
		}
		if (bag.isEmpty()) {
			for (int i = 0; i < 7; i++) {
				bag.add(i);
			}
			Collections.shuffle(bag);
		}
		return bag.remove(0);
	}

	private void spawn() {
		type = next;
		next = draw();
		cells = new int[4][];
		for (int i = 0; i < 4; i++) {
			cells[i] = SHAPES[type][i].clone();
		}
		px = 3;
		py = -1;
		lock = 0;
		lockMoves = 0;
		fall = 0;
		if (!fits(cells, px, py)) {
			over = true;
			best = Math.max(best, score);
			arcade.audio.play("gameover");
			arcade.shake(8);
		}
	}

	private boolean fits(int[][] shape, int x, int y) {
		for (int[] c : shape) {
			int cx = x + c[0];
			int cy = y + c[1];
			if (cx < 0 || cx >= W || cy >= H) {
				return false;
			}
			if (cy >= 0 && board[cy][cx] != 0) {
				return false;
			}
		}
		return true;
	}

	private void rotate() {
		if (type == 1 || type == HEAD) {
			return; // the square
		}
		int size = type == 0 ? 4 : 3;
		int[][] turned = new int[4][];
		for (int i = 0; i < 4; i++) {
			turned[i] = new int[] {size - 1 - cells[i][1], cells[i][0]};
		}
		for (int[] kick : new int[][] {{0, 0}, {-1, 0}, {1, 0}, {-2, 0}, {2, 0}, {0, -1}}) {
			if (fits(turned, px + kick[0], py + kick[1])) {
				cells = turned;
				px += kick[0];
				py += kick[1];
				arcade.audio.play("rotate");
				touchLock();
				return;
			}
		}
	}

	private void touchLock() {
		if (lockMoves < 12) {
			lock = 0;
			lockMoves++;
		}
	}

	private boolean move(int dx) {
		if (fits(cells, px + dx, py)) {
			px += dx;
			arcade.audio.play("move");
			touchLock();
			return true;
		}
		return false;
	}

	private void hardDrop() {
		int dropped = 0;
		while (fits(cells, px, py + 1)) {
			py++;
			dropped++;
		}
		score += dropped * 2;
		arcade.audio.play("drop");
		arcade.shake(4);
		for (int[] c : cells) {
			double[] p = center(px + c[0], py + c[1]);
			arcade.burst(p[0], p[1] + cell / 2, Color.rgb(255, 230, 220, 0.8), 2, 200);
		}
		place();
	}

	private void place() {
		for (int[] c : cells) {
			int cy = py + c[1];
			if (cy >= 0) {
				board[cy][px + c[0]] = type == HEAD ? 8 + quadrant(c) : type + 1;
			}
		}
		for (int y = 0; y < H; y++) {
			boolean full = true;
			for (int x = 0; x < W; x++) {
				if (board[y][x] == 0) {
					full = false;
					break;
				}
			}
			if (full) {
				clearing.add(y);
				for (int x = 0; x < W; x++) {
					if (board[y][x] >= 8) {
						nuke = true;
					}
				}
			}
		}
		if (!clearing.isEmpty()) {
			clearTimer = 0.28;
			arcade.audio.play("clear");
			int n = clearing.size();
			int before = level();
			score += new int[] {0, 100, 300, 500, 800}[n] * level();
			lines += n;
			if (level() > before) {
				arcade.audio.play("level");
			}
			if (n >= 4) {
				arcade.shake(10);
			}
			cells = new int[0][];
		} else {
			arcade.audio.play("place", 0.6f, 1.2);
			spawn();
		}
	}

	private static int quadrant(int[] c) {
		return c[1] * 2 + (c[0] - 1);
	}

	private void finishClear() {
		if (nuke) {
			// the head went: everything on the board goes with it
			nuke = false;
			for (int y = 0; y < H; y++) {
				for (int x = 0; x < W; x++) {
					if (board[y][x] != 0) {
						double[] p = center(x, y);
						arcade.burst(p[0], p[1], blockColor(board[y][x]), 3, 380);
					}
				}
				board[y] = new int[W];
			}
			score += 2000 * level();
			arcade.shake(16);
			arcade.flash(Color.rgb(255, 80, 88, 0.55));
			arcade.audio.play("level");
			arcade.audio.play("whoosh");
			clearing.clear();
			spawn();
			return;
		}
		for (int row : clearing) {
			for (int x = 0; x < W; x++) {
				double[] p = center(x, row);
				arcade.burst(p[0], p[1], blockColor(board[row][x]), 3, 420);
			}
		}
		Collections.sort(clearing);
		for (int row : clearing) {
			for (int y = row; y > 0; y--) {
				board[y] = board[y - 1].clone();
			}
			board[0] = new int[W];
		}
		clearing.clear();
		spawn();
	}

	private static Color blockColor(int value) {
		return switch (value) {
			case 1 -> Color.web("#D7262D");
			case 2 -> Color.web("#6E6772");
			case 3 -> Color.web("#8A5A3A");
			case 4 -> Color.web("#6C7A43");
			case 5 -> Color.web("#5C5560");
			case 6 -> Color.web("#C9A27E");
			case 7 -> Color.web("#3A3540");
			default -> Color.web("#FF6B6F");
		};
	}

	@Override
	public void update(double dt) {
		t += dt;
		if (over) {
			overFor += dt;
			return;
		}
		if (!started) {
			return;
		}
		if (!clearing.isEmpty()) {
			clearTimer -= dt;
			if (clearTimer <= 0) {
				finishClear();
			}
			return;
		}
		if (left != right) {
			repeat -= dt;
			if (repeat <= 0) {
				move(left ? -1 : 1);
				repeat = 0.05;
			}
		}
		double interval = down ? 0.035 : Math.max(0.05, 0.75 - (level() - 1) * 0.065);
		boolean grounded = !fits(cells, px, py + 1);
		if (grounded) {
			lock += dt;
			if (lock > 0.45) {
				place();
			}
			return;
		}
		fall += dt;
		while (fall >= interval) {
			fall -= interval;
			if (fits(cells, px, py + 1)) {
				py++;
				if (down) {
					score++;
				}
			}
		}
	}

	private double[] center(int x, int y) {
		return new double[] {bx + (x + 0.5) * cell, by + (y + 0.5) * cell};
	}

	@Override
	public void render(GraphicsContext g, double w, double h, double time) {
		arcade.backdrop.draw(g, w, h, time * 10, 0, time);
		cell = Math.floor(Math.min((h - 60) / H, 30));
		double panelW = Math.max(110, cell * 5.5);
		double total = W * cell + 24 + panelW;
		bx = Math.round((w - total) / 2);
		by = Math.round((h - H * cell) / 2 + 6);
		g.setFill(Color.rgb(8, 6, 6, 0.78));
		g.fillRoundRect(bx - 8, by - 8, W * cell + 16, H * cell + 16, 14, 14);
		g.setStroke(Backdrop.mix(arcade.backdrop.background(), arcade.backdrop.accent(), 0.45));
		g.setLineWidth(2);
		g.strokeRoundRect(bx - 8, by - 8, W * cell + 16, H * cell + 16, 14, 14);
		g.setStroke(Color.rgb(255, 240, 240, 0.035));
		g.setLineWidth(1);
		for (int x = 1; x < W; x++) {
			g.strokeLine(bx + x * cell + 0.5, by, bx + x * cell + 0.5, by + H * cell);
		}
		for (int y = 0; y < H; y++) {
			boolean flashing = clearing.contains(y);
			for (int x = 0; x < W; x++) {
				if (board[y][x] != 0) {
					block(g, bx + x * cell, by + y * cell, cell, board[y][x] - 1, over ? 0.45 : 1);
					if (flashing) {
						g.setFill(Color.rgb(255, 245, 240, 0.25 + 0.6 * Math.abs(Math.sin(t * 30))));
						g.fillRect(bx + x * cell, by + y * cell, cell, cell);
					}
				}
			}
		}
		if (!over && cells.length > 0) {
			int ghost = py;
			while (fits(cells, px, ghost + 1)) {
				ghost++;
			}
			g.setStroke(Color.rgb(255, 230, 225, 0.28));
			g.setLineWidth(1.5);
			for (int[] c : cells) {
				if (ghost + c[1] >= 0) {
					g.strokeRect(bx + (px + c[0]) * cell + 2, by + (ghost + c[1]) * cell + 2, cell - 4, cell - 4);
				}
			}
			for (int[] c : cells) {
				if (py + c[1] >= 0) {
					block(g, bx + (px + c[0]) * cell, by + (py + c[1]) * cell, cell, type == HEAD ? 7 + quadrant(c) : type, 1);
				}
			}
		}
		// side panel: fixed pixel spacing (the board's cells get small in the Home card)
		double sx = bx + W * cell + 24;
		g.setFill(Color.rgb(8, 6, 6, 0.72));
		g.fillRoundRect(sx, by - 8, panelW, 92, 12, 12);
		arcade.label(g, "NEXT", sx + 12, by + 12, 11, Color.web("#C9B9B6"), false);
		double mini = Math.min(16, (panelW - 24) / 4);
		for (int[] c : SHAPES[next]) {
			block(g, sx + (panelW - 4 * mini) / 2 + c[0] * mini + (next == 1 || next == HEAD ? mini / 2 : 0), by + 30 + c[1] * mini, mini,
					next == HEAD ? 7 + quadrant(c) : next, 1);
		}
		double ty = by + 98;
		g.setFill(Color.rgb(8, 6, 6, 0.72));
		g.fillRoundRect(sx, ty, panelW, 4 * 40 + 14, 12, 12);
		stat(g, sx + 12, ty + 20, "SCORE", String.valueOf(score));
		stat(g, sx + 12, ty + 60, "LINES", String.valueOf(lines));
		stat(g, sx + 12, ty + 100, "LEVEL", String.valueOf(level()));
		stat(g, sx + 12, ty + 140, "BEST", String.valueOf(Math.max(best, score)));
		if (!started && !over) {
			arcade.banner(g, w, h, "BLOCKFALL", "Space to start", "A/D move · W rotate · S soft drop · Space drop");
		} else if (over && overFor > 0.6) {
			arcade.banner(g, w, h, "GAME OVER", "Score " + score + "  ·  Best " + best, "Space to play again");
		}
	}

	private void stat(GraphicsContext g, double x, double y, String label, String value) {
		arcade.label(g, label, x, y, 11, Color.web("#9C8C89"), false);
		arcade.label(g, value, x, y + 18, 15, Color.web("#F4EFEE"), false);
	}

	/** kind 0..6 = block pieces, 7..10 = the head's quadrants (top left, top right, bottom left, bottom right). */
	private void block(GraphicsContext g, double x, double y, double size, int kind, double brightness) {
		if (kind >= 7) {
			head(g, x, y, size, kind - 7, brightness);
			return;
		}
		g.drawImage(Items.icon(SKINS[kind]), x, y, size, size);
		g.setFill(Color.rgb(255, 255, 255, 0.16));
		g.fillRect(x, y, size, Math.max(1, size / 16 * 1.5));
		g.setFill(Color.rgb(0, 0, 0, 0.28));
		g.fillRect(x, y + size - Math.max(1, size / 16 * 2), size, Math.max(1, size / 16 * 2));
		if (brightness < 1) {
			g.setFill(Color.rgb(10, 8, 8, 1 - brightness));
			g.fillRect(x, y, size, size);
		}
	}

	private void head(GraphicsContext g, double x, double y, double size, int quadrant, double brightness) {
		double pulse = 0.5 + 0.5 * Math.sin(t * 5);
		g.setGlobalBlendMode(javafx.scene.effect.BlendMode.SRC_OVER); // SCREEN left see-through pixels on the canvas
		double cx = x + (quadrant % 2 == 0 ? size : 0);
		double cy = y + (quadrant < 2 ? size : 0);
		g.setFill(new javafx.scene.paint.RadialGradient(0, 0, cx, cy, size * 2.2, false, javafx.scene.paint.CycleMethod.NO_CYCLE,
				new javafx.scene.paint.Stop(0, Color.rgb(255, 70, 80, 0.10 + 0.12 * pulse)), new javafx.scene.paint.Stop(1, Color.TRANSPARENT)));
		g.fillRect(x - size, y - size, size * 3, size * 3);
		g.setGlobalBlendMode(javafx.scene.effect.BlendMode.SRC_OVER);
		SkinRig rig = arcade.rig;
		if (rig != null) {
			double sx = (quadrant % 2) * 4;
			double sy = (quadrant / 2) * 4;
			g.drawImage(rig.face, sx, sy, 4, 4, x, y, size, size);
		} else {
			g.setFill(Color.web("#D7262D"));
			g.fillRect(x, y, size, size);
		}
		// glowing rim on the head's outer edges
		g.setStroke(Color.rgb(255, 120, 125, 0.55 + 0.45 * pulse));
		g.setLineWidth(Math.max(1.5, size / 10));
		double lw = g.getLineWidth() / 2;
		if (quadrant < 2) {
			g.strokeLine(x, y + lw, x + size, y + lw);
		} else {
			g.strokeLine(x, y + size - lw, x + size, y + size - lw);
		}
		if (quadrant % 2 == 0) {
			g.strokeLine(x + lw, y, x + lw, y + size);
		} else {
			g.strokeLine(x + size - lw, y, x + size - lw, y + size);
		}
		if (brightness < 1) {
			g.setFill(Color.rgb(10, 8, 8, 1 - brightness));
			g.fillRect(x, y, size, size);
		}
	}

	@Override
	public void key(KeyCode code, boolean pressed) {
		switch (code) {
			case LEFT, A -> {
				left = pressed;
				if (pressed && started && !over && clearing.isEmpty()) {
					move(-1);
					repeat = 0.16;
				}
			}
			case RIGHT, D -> {
				right = pressed;
				if (pressed && started && !over && clearing.isEmpty()) {
					move(1);
					repeat = 0.16;
				}
			}
			case DOWN, S -> down = pressed;
			case UP, W, X -> {
				if (pressed && started && !over && clearing.isEmpty()) {
					rotate();
				}
			}
			case SPACE, ENTER -> {
				if (!pressed) {
					return;
				}
				if (over) {
					if (overFor > 0.5) {
						reset();
						started = true;
						arcade.audio.play("level");
					}
				} else if (!started) {
					started = true;
					arcade.audio.play("level");
				} else if (clearing.isEmpty()) {
					hardDrop();
				}
			}
			default -> {
			}
		}
	}

	/** Screenshot tour: a half-built stack with a piece on its way down. */
	void tourSetup() {
		java.util.Random r = new java.util.Random(4);
		for (int y = H - 9; y < H; y++) {
			int gap = r.nextInt(W);
			for (int x = 0; x < W; x++) {
				if (x != gap && (y > H - 6 || r.nextDouble() < 0.55)) {
					board[y][x] = 1 + r.nextInt(7);
				}
			}
		}
		type = HEAD;
		cells = new int[4][];
		for (int i = 0; i < 4; i++) {
			cells[i] = SHAPES[type][i].clone();
		}
		px = 3;
		py = 7;
		next = 0;
		started = true;
		lines = 23;
		score = 8450;
		best = 12200;
	}

	@Override
	public boolean restful() {
		return !started || (over && overFor > 4);
	}

	@Override
	public void save(com.google.gson.JsonObject out) {
		out.addProperty("blocksBest", Math.max(best, score));
	}

	@Override
	public void load(com.google.gson.JsonObject in) {
		if (in.has("blocksBest")) {
			best = in.get("blocksBest").getAsInt();
		}
	}
}
