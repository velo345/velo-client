package net.veloclient.launcher.sandbox;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Snake, made of your skin: the head is your face (turned the way it crawls), the body is cut from your shirt and the
 * tail is your legs. Rubies make it longer and faster. Moves on a grid but is drawn gliding between cells.
 */
final class SnakeGame implements MiniGame {

	private final Arcade arcade;
	private final SplittableRandom random = new SplittableRandom();
	private int cols;
	private int rows;
	private List<int[]> body = new ArrayList<>();
	private List<int[]> previous = new ArrayList<>();
	private int dx = 1;
	private int dy;
	private final ArrayDeque<int[]> turns = new ArrayDeque<>();
	private double timer;
	private int grow;
	private int[] food;
	private boolean started;
	private boolean alive = true;
	private double deadFor;
	private int score;
	private int best;
	private double t;
	private Image shirtDark;

	SnakeGame(Arcade arcade) {
		this.arcade = arcade;
	}

	@Override
	public String id() {
		return "snake";
	}

	@Override
	public String music() {
		return "snake";
	}

	private double step() {
		return Math.max(0.065, 0.135 - body.size() * 0.0018);
	}

	private void reset() {
		body = new ArrayList<>();
		int cy = rows / 2;
		for (int i = 0; i < 4; i++) {
			body.add(new int[] {cols / 3 - i, cy});
		}
		previous = copy(body);
		dx = 1;
		dy = 0;
		turns.clear();
		grow = 0;
		score = 0;
		alive = true;
		deadFor = 0;
		started = false;
		placeFood();
	}

	private static List<int[]> copy(List<int[]> list) {
		List<int[]> out = new ArrayList<>();
		for (int[] p : list) {
			out.add(p.clone());
		}
		return out;
	}

	private void placeFood() {
		for (int tries = 0; tries < 500; tries++) {
			int x = random.nextInt(cols);
			int y = random.nextInt(rows);
			boolean free = true;
			for (int[] p : body) {
				if (p[0] == x && p[1] == y) {
					free = false;
					break;
				}
			}
			if (free) {
				food = new int[] {x, y};
				return;
			}
		}
	}

	@Override
	public void update(double dt) {
		t += dt;
		if (cols == 0) {
			return;
		}
		if (!alive) {
			deadFor += dt;
			return;
		}
		if (!started) {
			return;
		}
		timer += dt;
		while (timer >= step()) {
			timer -= step();
			tick();
			if (!alive) {
				break;
			}
		}
	}

	private void tick() {
		if (!turns.isEmpty()) {
			int[] turn = turns.poll();
			dx = turn[0];
			dy = turn[1];
			arcade.audio.play("turn");
		}
		previous = copy(body);
		int[] head = body.get(0);
		int nx = head[0] + dx;
		int ny = head[1] + dy;
		boolean hit = nx < 0 || ny < 0 || nx >= cols || ny >= rows;
		for (int i = 0; i < body.size() - (grow > 0 ? 0 : 1) && !hit; i++) {
			if (body.get(i)[0] == nx && body.get(i)[1] == ny) {
				hit = true;
			}
		}
		if (hit) {
			die();
			return;
		}
		body.add(0, new int[] {nx, ny});
		if (grow > 0) {
			grow--;
		} else {
			body.remove(body.size() - 1);
		}
		if (previous.size() > body.size()) {
			previous.remove(previous.size() - 1);
		}
		while (previous.size() < body.size()) {
			previous.add(body.get(previous.size()).clone());
		}
		if (food != null && nx == food[0] && ny == food[1]) {
			grow += 2;
			score++;
			arcade.audio.play("eat");
			double[] c = cellCenter(nx, ny);
			arcade.burst(c[0], c[1], Color.web("#FF4A52"), 14, 0);
			placeFood();
		}
	}

	private void die() {
		alive = false;
		best = Math.max(best, score);
		arcade.audio.play("gameover");
		arcade.shake(10);
		for (int i = 0; i < body.size(); i++) {
			double[] c = cellCenter(body.get(i)[0], body.get(i)[1]);
			arcade.burst(c[0], c[1], i == 0 ? Color.web("#F4EFEE") : Color.web("#D7262D"), 4, 300);
		}
	}

	private double cell;
	private double ox;
	private double oy;

	private double[] cellCenter(int x, int y) {
		return new double[] {ox + (x + 0.5) * cell, oy + (y + 0.5) * cell};
	}

	private void layout(double w, double h) {
		double target = h >= 620 ? 30 : 24;
		if (cols == 0) {
			cols = Math.max(10, (int) ((w - 56) / target));
			rows = Math.max(8, (int) ((h - 110) / target));
			reset();
		}
		cell = Math.floor(Math.min((w - 56) / cols, (h - 110) / rows));
		ox = Math.round((w - cols * cell) / 2);
		oy = Math.round(56 + (h - 56 - 30 - rows * cell) / 2);
	}

	@Override
	public void render(GraphicsContext g, double w, double h, double time) {
		arcade.backdrop.draw(g, w, h, time * 14, 0, time);
		layout(w, h);
		g.setFill(Color.rgb(20, 14, 15, 0.82));
		g.fillRoundRect(ox - 8, oy - 8, cols * cell + 16, rows * cell + 16, 14, 14);
		for (int y = 0; y < rows; y++) {
			for (int x = 0; x < cols; x++) {
				if ((x + y) % 2 == 0) {
					g.setFill(Color.rgb(255, 240, 240, 0.025));
					g.fillRect(ox + x * cell, oy + y * cell, cell, cell);
				}
			}
		}
		g.setStroke(Backdrop.mix(arcade.backdrop.background(), arcade.backdrop.accent(), 0.45));
		g.setLineWidth(2);
		g.strokeRoundRect(ox - 8, oy - 8, cols * cell + 16, rows * cell + 16, 14, 14);

		if (food != null) {
			double[] c = cellCenter(food[0], food[1]);
			double bob = Math.sin(t * 4) * cell * 0.06;
			g.setFill(new RadialGradient(0, 0, c[0], c[1], cell * 1.4, false, CycleMethod.NO_CYCLE,
					new Stop(0, Color.rgb(255, 60, 70, 0.35 + 0.1 * Math.sin(t * 5))), new Stop(1, Color.TRANSPARENT)));
			g.fillRect(c[0] - cell * 1.5, c[1] - cell * 1.5, cell * 3, cell * 3);
			g.drawImage(Items.icon(Items.RUBY), c[0] - cell * 0.5, c[1] - cell * 0.5 + bob, cell, cell);
		}

		SkinRig rig = arcade.rig;
		if (shirtDark == null && rig != null) {
			shirtDark = shade(rig.shirt);
		}
		double a = alive && started ? Math.min(1, timer / step()) : 1;
		if (alive) {
			for (int i = body.size() - 1; i >= 0; i--) {
				int[] now = body.get(i);
				int[] before = i < previous.size() ? previous.get(i) : now;
				double x = ox + (before[0] + (now[0] - before[0]) * a) * cell;
				double y = oy + (before[1] + (now[1] - before[1]) * a) * cell;
				if (rig == null) {
					g.setFill(Color.web("#D7262D"));
					g.fillRect(x + 1, y + 1, cell - 2, cell - 2);
					continue;
				}
				if (i == 0) {
					double angle = dx == 1 ? 90 : dx == -1 ? -90 : dy == 1 ? 180 : 0;
					g.save();
					g.translate(x + cell / 2, y + cell / 2);
					g.rotate(angle);
					double s = cell * 1.12;
					g.setFill(Color.rgb(0, 0, 0, 0.4));
					g.fillRect(-s / 2 + 2, -s / 2 + 3, s, s);
					g.setFill(Color.web("#F4EFEE"));
					g.fillRect(-s / 2 - 2, -s / 2 - 2, s + 4, s + 4);
					g.drawImage(rig.face, -s / 2, -s / 2, s, s);
					g.restore();
				} else {
					Image image = i == body.size() - 1 ? rig.pants : i % 2 == 0 ? rig.shirt : shirtDark;
					double inset = i == body.size() - 1 ? cell * 0.16 : cell * 0.08;
					// a lit rim around every segment so dark skins still read on the dark board
					g.setFill(Color.rgb(0, 0, 0, 0.35));
					g.fillRect(x + inset + 2, y + inset + 3, cell - inset * 2, cell - inset * 2);
					g.setFill(Backdrop.mix(arcade.backdrop.accent(), Color.WHITE, 0.15).deriveColor(0, 1, 1, 0.9));
					g.fillRect(x + inset - 1.5, y + inset - 1.5, cell - inset * 2 + 3, cell - inset * 2 + 3);
					g.drawImage(image, x + inset, y + inset, cell - inset * 2, cell - inset * 2);
				}
			}
		}

		arcade.label(g, "SNAKE", ox, oy - 22, 18, Color.web("#F4EFEE"), false);
		arcade.label(g, "SCORE " + score + "    BEST " + Math.max(best, score), ox + cols * cell, oy - 22, 13,
				Color.web("#C9B9B6"), true);
		if (!started && alive) {
			arcade.banner(g, w, h, "SNAKE", "Arrow keys or WASD to start", "Eat rubies, don't bite yourself");
		} else if (!alive && deadFor > 0.6) {
			arcade.banner(g, w, h, "GAME OVER", "Score " + score + "  ·  Best " + best, "Space to play again");
		}
	}

	private static Image shade(Image image) {
		int w = (int) image.getWidth();
		int h = (int) image.getHeight();
		javafx.scene.image.WritableImage out = new javafx.scene.image.WritableImage(w, h);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				out.getPixelWriter().setArgb(x, y, Items.shade(image.getPixelReader().getArgb(x, y), 0.82));
			}
		}
		return out;
	}

	@Override
	public void key(KeyCode code, boolean down) {
		if (!down) {
			return;
		}
		if (!alive) {
			if ((code == KeyCode.SPACE || code == KeyCode.ENTER) && deadFor > 0.5) {
				reset();
				arcade.audio.play("level");
			}
			return;
		}
		int[] d = switch (code) {
			case LEFT, A -> new int[] {-1, 0};
			case RIGHT, D -> new int[] {1, 0};
			case UP, W -> new int[] {0, -1};
			case DOWN, S -> new int[] {0, 1};
			default -> null;
		};
		if (d == null) {
			return;
		}
		if (!started) {
			started = true;
			timer = 0;
			if (d[0] == -1) {
				d = new int[] {1, 0}; // can't start backwards into yourself
			}
		}
		int[] last = turns.isEmpty() ? new int[] {dx, dy} : turns.peekLast();
		if ((d[0] == -last[0] && d[1] == -last[1]) || (d[0] == last[0] && d[1] == last[1]) || turns.size() >= 2) {
			return;
		}
		turns.add(d);
	}

	/** Screenshot tour: a long, winding snake about to eat. */
	void tourSetup() {
		if (cols == 0) {
			cols = 26;
			rows = 12;
		}
		body = new ArrayList<>();
		int[][] path = {{14, 5}, {13, 5}, {12, 5}, {11, 5}, {10, 5}, {10, 6}, {10, 7}, {11, 7}, {12, 7}, {13, 7}, {14, 7}, {15, 7},
				{16, 7}, {16, 8}, {16, 9}, {15, 9}, {14, 9}, {13, 9}};
		for (int[] p : path) {
			body.add(p.clone());
		}
		previous = copy(body);
		dx = 1;
		dy = 0;
		food = new int[] {17, 5};
		started = true;
		alive = true;
		score = 14;
		best = 31;
	}

	@Override
	public boolean restful() {
		return !started || (!alive && deadFor > 4);
	}

	@Override
	public void save(com.google.gson.JsonObject out) {
		out.addProperty("snakeBest", Math.max(best, score));
	}

	@Override
	public void load(com.google.gson.JsonObject in) {
		if (in.has("snakeBest")) {
			best = in.get("snakeBest").getAsInt();
		}
	}
}
