package net.veloclient.launcher.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Home's Christmas backdrop (dev launcher, 2026-11): a snowy village at night as real pixel art - drawn into a small
 * int buffer ({@link #S} screen pixels per art pixel) and shown with nearest-neighbour scaling.
 * <ul>
 * <li>dithered night sky, a pixel moon, twinkling stars and a slowly waving aurora,</li>
 * <li>two snow-capped mountain ranges drifting sideways, two rows of snowy pines,</li>
 * <li>a village on the hill (warm flickering windows, smoking chimneys, icicles, wreaths),</li>
 * <li>the front snow bank: snowman, a street lamp, a big Christmas tree with chasing lights and presents,</li>
 * <li>string lights along the top, snowfall in three depths and a sleigh crossing the sky now and then.</li>
 * </ul>
 * Static layers are built once per size; each frame copies them and adds what moves (one {@code setPixels} call).
 * {@link RidgelineView} owns it in winter mode, so pausing, the animate setting and the player all keep working.
 */
final class ChristmasScene extends Region {

	/** Screen pixels per art pixel. */
	static final int S = 3;
	/** Where the player stands (fraction of the width) - the snow bank is flat around it. */
	static final double PLAYER_X = 0.735;

	private final int w;
	private final int h;
	private final int[] sky;
	private final int[] front;
	private final int[] frame;
	private final WritableImage image;
	private final Timeline timeline;
	private final int[] farMountain;
	private final int[] nearMountain;
	private final List<int[]> windows = new ArrayList<>();      // x, y, w, h
	private final List<int[]> treeLights = new ArrayList<>();   // x, y, colour index, phase*1000
	private final List<int[]> garland = new ArrayList<>();      // x, y, colour index
	private final List<int[]> chimneys = new ArrayList<>();     // x, y
	private final List<double[]> smoke = new ArrayList<>();     // x, y, age, seed
	private final double[][] flakes;                            // x, y, speed, layer, phase
	private final int[][] stars;                                // x, y, base*100, phase*100, big
	private int lampX;
	private int lampY;
	private int starX;
	private int starY;
	private double t;
	private long lastNanos;
	private double nextSmoke;
	private double sleighAt = 9;

	private static final int[] LIGHTS = {0xFFFF4A4A, 0xFF46F08A, 0xFFFFD24A, 0xFF55B8FF, 0xFFFF7AD9};

	ChristmasScene(double viewW, double viewH) {
		w = Math.max(40, (int) Math.ceil(viewW / S));
		h = Math.max(40, (int) Math.ceil(viewH / S));
		sky = new int[w * h];
		front = new int[w * h];
		frame = new int[w * h];
		farMountain = new int[w];
		nearMountain = new int[w];
		SplittableRandom random = new SplittableRandom(1224);
		buildSky(random);
		buildMountains();
		buildFront(random);
		stars = new int[Math.max(30, w * h / 260)][];
		for (int i = 0; i < stars.length; i++) {
			int sx = random.nextInt(w);
			int sy = random.nextInt(Math.max(1, (int) (h * 0.55)));
			stars[i] = new int[] {sx, sy, 25 + random.nextInt(60), random.nextInt(628), random.nextDouble() < 0.12 ? 1 : 0};
		}
		flakes = new double[Math.max(60, w * h / 110)][];
		for (int i = 0; i < flakes.length; i++) {
			int layer = random.nextDouble() < 0.5 ? 0 : random.nextDouble() < 0.7 ? 1 : 2;
			flakes[i] = new double[] {random.nextDouble() * w, random.nextDouble() * h, (4 + layer * 5) * (0.75 + random.nextDouble() * 0.5),
					layer, random.nextDouble() * Math.PI * 2};
		}
		image = new WritableImage(w, h);
		ImageView view = new ImageView(image);
		view.setSmooth(false);
		view.setFitWidth(w * S);
		view.setFitHeight(h * S);
		getChildren().add(view);
		setMouseTransparent(true);
		timeline = new Timeline(new KeyFrame(Duration.millis(1000.0 / 30), e -> tick()));
		timeline.setCycleCount(Animation.INDEFINITE);
		draw();
	}

	/** The frame loop, paused and played by the owning view. */
	Animation animation() {
		return timeline;
	}

	/** Screen y of the snow surface where the player stands. */
	double playerGroundY() {
		return frontGround((int) (w * PLAYER_X)) * S;
	}

	// ------------------------------------------------------------------ static layers

	private void buildSky(SplittableRandom random) {
		int top = 0xFF060A1C;
		int mid = 0xFF111A42;
		int low = 0xFF2A2F66;
		int horizon = 0xFF4A3F78;
		for (int y = 0; y < h; y++) {
			double v = y / (double) h;
			for (int x = 0; x < w; x++) {
				double d = (BAYER[(y & 3) * 4 + (x & 3)] + 0.5) / 16.0 - 0.5;
				double q = v + d * 0.07;
				int c = q < 0.35 ? mix(top, mid, q / 0.35) : q < 0.62 ? mix(mid, low, (q - 0.35) / 0.27) : mix(low, horizon, Math.min(1, (q - 0.62) / 0.2));
				sky[y * w + x] = band(c, 10);
			}
		}
		// The moon: a dithered halo, then the disc with craters and a shaded edge.
		int mx = (int) (w * 0.83);
		int my = (int) (h * 0.2);
		int r = Math.max(6, h / 13);
		for (int y = my - r * 4; y <= my + r * 4; y++) {
			for (int x = mx - r * 4; x <= mx + r * 4; x++) {
				if (x < 0 || y < 0 || x >= w || y >= h) {
					continue;
				}
				double dist = Math.hypot(x - mx, y - my);
				if (dist <= r) {
					double shade = (x - mx + (y - my) * 0.4) / (double) r;
					int c = shade > 0.55 ? 0xFFD8D3B8 : 0xFFF5F1DC;
					sky[y * w + x] = c;
				} else if (dist < r * 4) {
					double glow = Math.pow(1 - (dist - r) / (r * 3.0), 2.2) * 0.38;
					double d = (BAYER[(y & 3) * 4 + (x & 3)] + 0.5) / 16.0;
					double stepped = Math.floor((glow + d * 0.08) * 8) / 8;
					sky[y * w + x] = mix(sky[y * w + x], 0xFFBFC8FF, stepped);
				}
			}
		}
		int[][] craters = {{-2, -1, 2}, {2, 2, 1}, {1, -3, 1}, {-3, 3, 1}};
		for (int[] c : craters) {
			int cx = mx + c[0] * r / 6;
			int cy = my + c[1] * r / 6;
			int cr = Math.max(1, c[2] * r / 6);
			for (int y = -cr; y <= cr; y++) {
				for (int x = -cr; x <= cr; x++) {
					if (x * x + y * y <= cr * cr) {
						set(sky, cx + x, cy + y, 0xFFCFC9AC);
					}
				}
			}
		}
	}

	private void buildMountains() {
		for (int x = 0; x < w; x++) {
			double u = x / (double) w * Math.PI * 2;
			double far = Math.sin(u * 2 + 0.4) * 0.5 + Math.sin(u * 5 + 1.3) * 0.28 + Math.sin(u * 11) * 0.12;
			double near = Math.sin(u * 3 + 2.1) * 0.5 + Math.sin(u * 7 + 0.2) * 0.25 + Math.sin(u * 13 + 4) * 0.1;
			farMountain[x] = (int) (Math.round((h * 0.56 - far * h * 0.1) / 2) * 2);
			nearMountain[x] = (int) (Math.round((h * 0.64 - near * h * 0.075) / 2) * 2);
		}
	}

	private int midGround(int x) {
		return (int) (h * 0.755 + Math.sin(x * 0.045) * 2 + Math.sin(x * 0.013 + 1) * 3);
	}

	private int frontGround(int x) {
		double base = h * 0.865 + Math.sin(x * 0.03 + 0.5) * 2.2 + Math.sin(x * 0.011) * 2;
		int px = (int) (w * PLAYER_X);
		double flat = h * 0.865 + Math.sin(px * 0.03 + 0.5) * 2.2 + Math.sin(px * 0.011) * 2;
		double near = Math.max(0, 1 - Math.abs(x - px) / (w * 0.06));
		return (int) Math.round(base + (flat - base) * Math.min(1, near * 1.6));
	}

	private void buildFront(SplittableRandom random) {
		// Far pines along the hill behind the village (bluish with distance).
		for (int x = -4; x < w + 4; x += 3 + random.nextInt(4)) {
			int base = midGround(x) - 2;
			pine(x, base, (int) (h * (0.07 + random.nextDouble() * 0.06)), 0xFF16284A, 0xFF1E3660, 0xFFAFC0E8);
		}
		// The hill: snow, shaded bluer to the back.
		for (int x = 0; x < w; x++) {
			for (int y = midGround(x); y < h; y++) {
				double d = (BAYER[(y & 3) * 4 + (x & 3)] + 0.5) / 16.0;
				double v = (y - midGround(x)) / (h * 0.15);
				set(front, x, y, y == midGround(x) ? 0xFFE6EEFF : mix(0xFFC4D2F2, 0xFF8E9ED0, Math.floor((v + d * 0.25) * 4) / 4 * 0.6));
			}
		}
		// Nearer pines at the hill's edge, left (under the Home text) and between the houses.
		for (int x = -2; x < w * 0.46; x += 5 + random.nextInt(6)) {
			pine(x, midGround(x) + 1, (int) (h * (0.1 + random.nextDouble() * 0.09)), 0xFF0F2A24, 0xFF1B4536, 0xFFE3ECFF);
		}
		// The village.
		house((int) (w * 0.5), midGround((int) (w * 0.5)) + 1, 26, 12, 0xFF5A3A2C, 0xFF7A2429, random);
		pine((int) (w * 0.555), midGround((int) (w * 0.555)) + 1, (int) (h * 0.15), 0xFF0F2A24, 0xFF1B4536, 0xFFE3ECFF);
		house((int) (w * 0.615), midGround((int) (w * 0.615)) + 1, 34, 15, 0xFF4A3528, 0xFF2E4A3E, random);
		house((int) (w * 0.69), midGround((int) (w * 0.69)) + 1, 22, 11, 0xFF6A4632, 0xFF7A2429, random);
		pine((int) (w * 0.79), midGround((int) (w * 0.79)) + 1, (int) (h * 0.13), 0xFF0F2A24, 0xFF1B4536, 0xFFE3ECFF);
		pine((int) (w * 0.97), midGround((int) (w * 0.97)) + 1, (int) (h * 0.17), 0xFF0F2A24, 0xFF1B4536, 0xFFE3ECFF);

		// The front snow bank (where the player stands): bright top, bluish dithered shade, a few sparkles.
		for (int x = 0; x < w; x++) {
			int g = frontGround(x);
			for (int y = g; y < h; y++) {
				double d = (BAYER[(y & 3) * 4 + (x & 3)] + 0.5) / 16.0;
				double v = (y - g) / (double) Math.max(1, h - g);
				int c = y == g ? 0xFFFFFFFF : y == g + 1 ? 0xFFEAF1FF : mix(0xFFDCE6FF, 0xFFA9B8E2, Math.floor((v + d * 0.3) * 4) / 4);
				set(front, x, y, c);
			}
		}
		// Warm light from the windows lies on the hill's snow in front of each house.
		for (int[] win : windows) {
			int cx = win[0] + win[2] / 2;
			int gy = midGround(cx);
			for (int y = gy; y < gy + 7; y++) {
				for (int x = cx - 7; x <= cx + 7; x++) {
					double fall = 1 - Math.hypot((x - cx) / 7.0, (y - gy - 1) / 4.5);
					double d = (BAYER[(y & 3) * 4 + (x & 3)] + 0.5) / 16.0;
					if (fall > 0 && get(front, x, y) != 0 && fall * 0.9 > d) {
						set(front, x, y, mix(get(front, x, y), 0xFFFFC77A, 0.32));
					}
				}
			}
		}

		snowman((int) (w * 0.64), frontGround((int) (w * 0.64)));
		lamp((int) (w * 0.815), frontGround((int) (w * 0.815)));
		christmasTree((int) (w * 0.905), frontGround((int) (w * 0.905)) + 1, (int) (h * 0.5), random);
		stringLights();
	}

	/** A stepped pine: tiers widen downwards, every ledge (a pixel with nothing above it) carries snow. */
	private void pine(int cx, int base, int height, int dark, int light, int snow) {
		height = Math.max(6, height);
		int trunk = Math.max(1, height / 9);
		int top = base - height;
		int tiers = height >= 26 ? 4 : height >= 14 ? 3 : 2;
		double maxHalf = height * 0.36;
		int crown = height - trunk;
		int[] half = new int[crown];
		for (int i = 0; i < crown; i++) {
			double rel = i / (double) crown;
			double tier = rel * tiers;
			int idx = (int) tier;
			double frac = tier - idx;
			half[i] = (int) Math.round(maxHalf * (idx + 1) / tiers * (0.4 + 0.6 * frac));
		}
		for (int y = base - trunk; y < base; y++) {
			set(front, cx, y, 0xFF3A2A22);
			if (height > 22) {
				set(front, cx + 1, y, 0xFF2A1E18);
			}
		}
		for (int i = 0; i < crown; i++) {
			int y = top + i;
			for (int x = -half[i]; x <= half[i]; x++) {
				boolean ledge = i == 0 || Math.abs(x) > half[i - 1];
				int c = ledge ? snow : x > 0 ? light : dark;
				if (!ledge && i > 1 && Math.abs(x) == half[i] && ((x + y) & 1) == 0) {
					c = snow; // snow caught on the branch tips
				}
				set(front, cx + x, y, c);
			}
		}
	}

	private void house(int cx, int base, int width, int wallH, int wall, int roof, SplittableRandom random) {
		int x0 = cx - width / 2;
		int top = base - wallH;
		for (int y = top; y < base; y++) {
			for (int x = x0; x < x0 + width; x++) {
				int c = (y - top) % 3 == 2 ? shade(wall, 0.78) : x == x0 || x == x0 + width - 1 ? shade(wall, 0.7) : wall;
				set(front, x, y, c);
			}
		}
		// Roof at 45 degrees with an overhang, shingle rows and a thick snow layer along the slopes.
		int over = 3;
		for (int k = 0; ; k++) {
			int halfW = width / 2 + over - k;
			if (halfW < 1) {
				break;
			}
			int y = top - 1 - k;
			for (int x = -halfW; x <= halfW; x++) {
				boolean slope = Math.abs(x) >= halfW - 1;
				int c = slope ? (Math.abs(x) == halfW ? 0xFFF2F6FF : 0xFFD6E0FA) : (k % 2 == 0 ? roof : shade(roof, 0.82));
				set(front, cx + x, y, c);
			}
			set(front, cx, y - 1, 0xFFF2F6FF);
		}
		// Icicles under the eaves.
		for (int x = x0 - over; x <= x0 + width + over - 1; x++) {
			if (random.nextDouble() < 0.4) {
				int len = 1 + random.nextInt(3);
				for (int i = 0; i < len; i++) {
					set(front, x, top + i, i == len - 1 ? 0xFFBFE6FF : 0xFFE6F6FF);
				}
			}
		}
		// Chimney with a snow cap.
		int chx = cx + width / 4;
		int roofAt = top - 1 - (width / 2 + over - Math.abs(chx - cx));
		for (int y = roofAt - 5; y <= roofAt + 1; y++) {
			for (int x = chx; x < chx + 4; x++) {
				set(front, x, y, ((x + y) & 3) == 0 ? 0xFF5E2A24 : 0xFF7E3A30);
			}
		}
		for (int x = chx - 1; x < chx + 5; x++) {
			set(front, x, roofAt - 6, 0xFFF2F6FF);
		}
		chimneys.add(new int[] {chx + 2, roofAt - 7});
		// Door with a wreath, then the windows (their light flickers each frame).
		int doorX = cx - 2;
		for (int y = base - 7; y < base; y++) {
			for (int x = doorX; x < doorX + 5; x++) {
				set(front, x, y, x == doorX + 3 && y == base - 4 ? 0xFFE0B050 : 0xFF2A1A14);
			}
		}
		int[][] wreath = {{1, 0}, {2, 0}, {3, 0}, {0, 1}, {4, 1}, {0, 2}, {4, 2}, {1, 3}, {3, 3}};
		for (int[] p : wreath) {
			set(front, doorX + p[0], base - 11 + p[1], 0xFF2F8A4A);
		}
		set(front, doorX + 2, base - 8, 0xFFE03A3A);
		int winY = top + Math.max(2, wallH / 4);
		int winW = 6;
		int winH = Math.max(4, wallH / 3);
		int[] xs = width >= 30 ? new int[] {x0 + 3, doorX - winW - 3 > x0 + 3 + winW ? doorX - winW - 3 : x0 + 3, doorX + 8, x0 + width - winW - 3}
				: new int[] {x0 + 3, x0 + width - winW - 3};
		for (int wx : java.util.Arrays.stream(xs).distinct().toArray()) {
			windows.add(new int[] {wx, winY, winW, winH});
			for (int x = wx - 1; x <= wx + winW; x++) {
				set(front, x, winY + winH, 0xFFF2F6FF); // snow on the sill
			}
		}
	}

	private void snowman(int cx, int ground) {
		int r1 = Math.max(5, h / 24);
		int r2 = Math.max(4, r1 * 3 / 4);
		int r3 = Math.max(3, r1 / 2 + 1);
		int c1 = ground - r1;
		int c2 = c1 - r1 - r2 + 2;
		int c3 = c2 - r2 - r3 + 2;
		ball(cx, c1, r1);
		ball(cx, c2, r2);
		ball(cx, c3, r3);
		set(front, cx - 1, c3 - 1, 0xFF15151C);
		set(front, cx + 1, c3 - 1, 0xFF15151C);
		set(front, cx + 1, c3 + 1, 0xFFFF8A2A);
		set(front, cx + 2, c3 + 1, 0xFFFF8A2A);
		set(front, cx + 3, c3 + 1, 0xFFE0661E);
		for (int x = cx - r3; x <= cx + r3; x++) {
			set(front, x, c3 + r3 - 1, 0xFFC8283A);
		}
		set(front, cx + r3 - 1, c3 + r3, 0xFFC8283A);
		set(front, cx + r3 - 1, c3 + r3 + 1, 0xFFA01E2E);
		for (int y = c2 - 2; y <= c2 + 2; y += 2) {
			set(front, cx, y, 0xFF15151C);
		}
		for (int i = 1; i <= r2 + 3; i++) {
			set(front, cx - r2 - i + 1, c2 - i / 2, 0xFF5A3A26);
			set(front, cx + r2 + i - 1, c2 - i / 2, 0xFF5A3A26);
		}
		for (int x = cx - r3; x <= cx + r3; x++) {
			set(front, x, c3 - r3, 0xFF15151C);
		}
		for (int y = c3 - r3 - 4; y < c3 - r3; y++) {
			for (int x = cx - r3 + 1; x <= cx + r3 - 1; x++) {
				set(front, x, y, y == c3 - r3 - 2 ? 0xFFC8283A : 0xFF1C1C26);
			}
		}
	}

	private void ball(int cx, int cy, int r) {
		for (int y = -r; y <= r; y++) {
			for (int x = -r; x <= r; x++) {
				if (x * x + y * y <= r * r + r / 2) {
					double shade = (-x - y * 0.6) / r;
					set(front, cx + x, cy + y, shade > 0.45 ? 0xFFB8C6EA : shade > 0.1 ? 0xFFE2EAFF : 0xFFFFFFFF);
				}
			}
		}
	}

	private void lamp(int x, int ground) {
		int height = Math.max(18, (int) (h * 0.26));
		for (int y = ground - height; y < ground; y++) {
			set(front, x, y, 0xFF1B1D2A);
		}
		set(front, x - 1, ground - 1, 0xFF1B1D2A);
		set(front, x + 1, ground - 1, 0xFF1B1D2A);
		lampX = x;
		lampY = ground - height - 3;
		for (int y = lampY - 2; y <= lampY + 2; y++) {
			for (int dx = -2; dx <= 2; dx++) {
				boolean edge = Math.abs(dx) == 2 || y == lampY - 2 || y == lampY + 2;
				set(front, x + dx, y, edge ? 0xFF1B1D2A : 0xFFFFDE8A);
			}
		}
		set(front, x, lampY - 3, 0xFF1B1D2A);
		for (int dx = -3; dx <= 3; dx++) {
			set(front, x + dx, lampY - 3 - (Math.abs(dx) < 2 ? 1 : 0), 0xFFF2F6FF); // snow on the lamp's hood
		}
		// Its pool of light on the snow.
		for (int y = ground; y < ground + 6; y++) {
			for (int dx = -12; dx <= 12; dx++) {
				double fall = 1 - Math.hypot(dx / 12.0, (y - ground) / 5.0);
				double d = (BAYER[(y & 3) * 4 + ((x + dx) & 3)] + 0.5) / 16.0;
				if (fall > 0 && fall > d * 0.9) {
					set(front, x + dx, y, mix(get(front, x + dx, y), 0xFFFFD58A, 0.35));
				}
			}
		}
	}

	private void christmasTree(int cx, int base, int height, SplittableRandom random) {
		int top = base - height;
		pine(cx, base, height, 0xFF13402E, 0xFF22603F, 0xFFF2F6FF);
		int crown = height - Math.max(1, height / 9);
		int tiers = 4;
		double maxHalf = height * 0.36;
		// Five strands of lights wound downwards, baubles in between.
		for (int strand = 0; strand < 5; strand++) {
			double startRel = 0.12 + strand * 0.17;
			for (int i = 0; i < 9; i++) {
				double rel = startRel + i * 0.018;
				if (rel >= 0.97) {
					break;
				}
				int row = (int) (rel * crown);
				double tier = rel * tiers;
				double frac = tier - (int) tier;
				int halfW = (int) Math.round(maxHalf * ((int) tier + 1) / tiers * (0.4 + 0.6 * frac));
				int x = cx - halfW + 1 + (int) Math.round(i / 8.0 * (halfW * 2 - 2));
				treeLights.add(new int[] {x, top + row, (strand + i) % LIGHTS.length, random.nextInt(6283)});
			}
		}
		for (int i = 0; i < 9; i++) {
			double rel = 0.25 + random.nextDouble() * 0.7;
			int row = (int) (rel * crown);
			double tier = rel * tiers;
			double frac = tier - (int) tier;
			int halfW = (int) Math.round(maxHalf * ((int) tier + 1) / tiers * (0.4 + 0.6 * frac)) - 1;
			int x = cx + (int) Math.round((random.nextDouble() * 2 - 1) * Math.max(1, halfW - 1));
			int c = new int[] {0xFFD8283A, 0xFFE8B83A, 0xFF3A7AE0, 0xFFC0C8D8}[i % 4];
			set(front, x, top + row, c);
			set(front, x + 1, top + row, c);
			set(front, x, top + row + 1, shade(c, 0.7));
			set(front, x + 1, top + row + 1, shade(c, 0.8));
			set(front, x + 1, top + row, mix(c, 0xFFFFFFFF, 0.5));
		}
		starX = cx;
		starY = top - 2;
		// Presents under the tree.
		int[][] gifts = {{-10, 6, 5, 0xFFC8283A, 0xFFF0D060}, {-4, 5, 4, 0xFF2F7ADF, 0xFFF2F6FF}, {5, 7, 6, 0xFF2F9A5A, 0xFFE8283A},
				{12, 5, 4, 0xFFE8B83A, 0xFFC8283A}};
		for (int[] g : gifts) {
			int gx = cx + g[0];
			for (int y = base - g[2]; y < base; y++) {
				for (int x = gx; x < gx + g[1]; x++) {
					boolean ribbon = x == gx + g[1] / 2 || y == base - g[2] / 2 - 1;
					set(front, x, y, ribbon ? g[4] : (x == gx + g[1] - 1 ? shade(g[3], 0.75) : g[3]));
				}
			}
			set(front, gx + g[1] / 2 - 1, base - g[2] - 1, g[4]);
			set(front, gx + g[1] / 2 + 1, base - g[2] - 1, g[4]);
		}
	}

	private void stringLights() {
		int spans = 5;
		for (int s = 0; s < spans; s++) {
			int x0 = s * w / spans;
			int x1 = (s + 1) * w / spans;
			int sag = Math.max(4, h / 22);
			int index = 0;
			for (int x = x0; x <= x1; x++) {
				double u = (x - x0) / (double) Math.max(1, x1 - x0);
				int y = 2 + (int) Math.round(sag * 4 * u * (1 - u));
				set(front, x, y, 0xFF1A1C26);
				if ((x - x0) % 6 == 3) {
					garland.add(new int[] {x, y + 1, index++ % LIGHTS.length});
				}
			}
		}
	}

	// ------------------------------------------------------------------ frames

	private void tick() {
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 1 / 30.0 : Math.min(0.1, (now - lastNanos) / 1e9);
		lastNanos = now;
		t += dt;
		for (double[] f : flakes) {
			f[1] += f[2] * dt;
			f[0] += (Math.sin(t * (0.6 + f[3] * 0.2) + f[4]) * (0.6 + f[3] * 0.5) + 1.1 + f[3] * 0.5) * dt;
			if (f[1] >= h) {
				f[1] -= h + 2;
			}
			if (f[0] >= w) {
				f[0] -= w;
			}
		}
		nextSmoke -= dt;
		if (nextSmoke <= 0) {
			nextSmoke = 0.45;
			for (int[] c : chimneys) {
				smoke.add(new double[] {c[0], c[1], 0, Math.random() * 10});
			}
		}
		for (int i = smoke.size() - 1; i >= 0; i--) {
			double[] p = smoke.get(i);
			p[2] += dt;
			p[1] -= 3.2 * dt;
			p[0] += (1.4 + Math.sin(p[2] * 1.3 + p[3]) * 0.8) * dt;
			if (p[2] > 6) {
				smoke.remove(i);
			}
		}
		draw();
	}

	private void draw() {
		System.arraycopy(sky, 0, frame, 0, frame.length);
		aurora();
		for (int[] s : stars) {
			double v = s[2] / 100.0 * (0.55 + 0.45 * Math.sin(t * (0.8 + (s[3] % 7) * 0.25) + s[3] / 100.0));
			int c = mix(frame[s[1] * w + s[0]], 0xFFF4F0FF, Math.floor(v * 4) / 4);
			frame[s[1] * w + s[0]] = c;
			if (s[4] == 1 && v > 0.5) {
				int arm = mix(frame[s[1] * w + s[0]], 0xFFF4F0FF, v * 0.45);
				put(s[0] - 1, s[1], arm);
				put(s[0] + 1, s[1], arm);
				put(s[0], s[1] - 1, arm);
				put(s[0], s[1] + 1, arm);
			}
		}
		sleigh();
		mountains(farMountain, t * 0.5, 0xFF2B3470, 0xFF38438A, 0xFFB6C2EC);
		mountains(nearMountain, t * 1.1, 0xFF1E2655, 0xFF27326A, 0xFFD2DCF6);
		for (int i = 0; i < frame.length; i++) {
			int c = front[i];
			if (c != 0) {
				frame[i] = c;
			}
		}
		lights();
		for (double[] p : smoke) {
			double a = (1 - p[2] / 6) * 0.42;
			int size = p[2] > 2.5 ? 2 : 1;
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					blend((int) p[0] + x, (int) p[1] + y, 0xFFB8C0D8, a);
				}
			}
		}
		for (double[] f : flakes) {
			int x = (int) f[0];
			int y = (int) f[1];
			int layer = (int) f[3];
			if (layer == 0) {
				blend(x, y, 0xFFDDE6FF, 0.45);
			} else if (layer == 1) {
				blend(x, y, 0xFFFFFFFF, 0.85);
			} else {
				blend(x, y, 0xFFFFFFFF, 0.95);
				blend(x + 1, y, 0xFFFFFFFF, 0.6);
				blend(x, y + 1, 0xFFFFFFFF, 0.6);
				blend(x + 1, y + 1, 0xFFE6EEFF, 0.4);
			}
		}
		image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), frame, 0, w);
	}

	private void aurora() {
		double baseY = h * 0.2;
		for (int x = 0; x < w; x++) {
			double center = baseY + Math.sin(x * 0.021 + t * 0.23) * h * 0.06 + Math.sin(x * 0.053 - t * 0.15) * h * 0.025;
			double strength = Math.max(0, Math.sin(x * 0.012 + t * 0.11) * 0.6 + 0.4);
			// Soft vertical curtains that slide along slowly.
			strength *= 0.6 + 0.4 * (0.5 + 0.5 * Math.sin(x * 0.17 + t * 0.5)) * (0.6 + 0.4 * Math.sin(x * 0.043 - t * 0.31));
			if (strength <= 0.02) {
				continue;
			}
			double height = h * (0.13 + 0.05 * Math.sin(x * 0.031 + t * 0.2));
			int y0 = (int) (center - height);
			int y1 = (int) (center + h * 0.02);
			for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
				double rel = (y - y0) / (double) (y1 - y0);
				double fall = rel < 0.85 ? Math.pow(rel / 0.85, 2.2) : 1 - (rel - 0.85) / 0.15 * 0.7;
				double a = strength * fall * 0.3;
				double d = (BAYER[(y & 3) * 4 + (x & 3)] + 0.5) / 16.0;
				a = Math.floor((a + d * 0.04) * 12) / 12;
				if (a > 0) {
					int c = rel < 0.5 ? mix(0xFF6A5CFF, 0xFF3FE0C8, rel / 0.5) : mix(0xFF3FE0C8, 0xFF5CFFA0, (rel - 0.5) / 0.5);
					frame[y * w + x] = mix(frame[y * w + x], c, a);
				}
			}
		}
	}

	private void mountains(int[] heights, double offset, int body, int lit, int snow) {
		int shift = (int) offset;
		for (int x = 0; x < w; x++) {
			int src = Math.floorMod(x + shift, w);
			int top = heights[src];
			int prev = heights[Math.floorMod(src - 1, w)];
			int bottom = (int) (h * 0.8);
			for (int y = Math.max(0, top); y < Math.min(h, bottom); y++) {
				int depth = y - top;
				int c = depth < 2 || depth < 4 && ((x + y) & 1) == 0 ? snow : top < prev ? lit : body;
				frame[y * w + x] = c;
			}
		}
	}

	private void lights() {
		for (int[] win : windows) {
			double flicker = 0.85 + 0.15 * Math.sin(t * 7.3 + win[0] * 0.7) * Math.sin(t * 3.1 + win[1]);
			int glow = mix(0xFFB86A2A, 0xFFFFD98A, flicker);
			for (int y = win[1]; y < win[1] + win[3]; y++) {
				for (int x = win[0]; x < win[0] + win[2]; x++) {
					boolean frameBar = x == win[0] + win[2] / 2 || y == win[1] + win[3] / 2;
					put(x, y, frameBar ? 0xFF3A2418 : (y == win[1] ? mix(glow, 0xFFFFF2C8, 0.4) : glow));
				}
			}
		}
		for (int[] l : treeLights) {
			double phase = l[3] / 1000.0;
			boolean on = Math.sin(t * 2.2 + phase) > -0.35;
			int c = LIGHTS[(l[2] + (int) (t / 2.5)) % LIGHTS.length];
			if (on) {
				put(l[0], l[1], c);
				blend(l[0] - 1, l[1], c, 0.35);
				blend(l[0] + 1, l[1], c, 0.35);
				blend(l[0], l[1] - 1, c, 0.3);
				blend(l[0], l[1] + 1, c, 0.3);
			} else {
				put(l[0], l[1], shade(c, 0.35));
			}
		}
		int chase = (int) (t * 3);
		for (int i = 0; i < garland.size(); i++) {
			int[] g = garland.get(i);
			boolean on = (i + chase) % 4 != 0;
			int c = LIGHTS[g[2]];
			put(g[0], g[1], on ? c : shade(c, 0.4));
			put(g[0], g[1] + 1, on ? mix(c, 0xFFFFFFFF, 0.25) : shade(c, 0.3));
			if (on) {
				blend(g[0] - 1, g[1], c, 0.3);
				blend(g[0] + 1, g[1], c, 0.3);
				blend(g[0], g[1] + 2, c, 0.35);
			}
		}
		// The street lamp's halo and the tree's star.
		double lampGlow = 0.3 + 0.04 * Math.sin(t * 5);
		halo(lampX, lampY, 7, 0xFFFFD58A, lampGlow);
		double twinkle = 0.55 + 0.45 * Math.sin(t * 2.6);
		halo(starX, starY, 6, 0xFFFFE27A, 0.25 + twinkle * 0.25);
		int gold = 0xFFFFD84A;
		int bright = mix(gold, 0xFFFFFFFF, twinkle * 0.6);
		put(starX, starY, bright);
		for (int i = 1; i <= 2; i++) {
			put(starX - i, starY, gold);
			put(starX + i, starY, gold);
			put(starX, starY - i, gold);
			put(starX, starY + i, gold);
		}
		put(starX - 1, starY - 1, shade(gold, 0.8));
		put(starX + 1, starY - 1, shade(gold, 0.8));
		put(starX - 1, starY + 1, shade(gold, 0.8));
		put(starX + 1, starY + 1, shade(gold, 0.8));
		if (twinkle > 0.8) {
			put(starX, starY - 3, mix(frame[Math.max(0, starY - 3) * w + starX], 0xFFFFFFFF, 0.7));
			put(starX - 3, starY, mix(frame[starY * w + Math.max(0, starX - 3)], 0xFFFFFFFF, 0.5));
			put(starX + 3, starY, mix(frame[starY * w + Math.min(w - 1, starX + 3)], 0xFFFFFFFF, 0.5));
		}
	}

	private void halo(int cx, int cy, int r, int color, double strength) {
		for (int y = cy - r; y <= cy + r; y++) {
			for (int x = cx - r; x <= cx + r; x++) {
				double d = Math.hypot(x - cx, y - cy) / r;
				if (d < 1 && x >= 0 && y >= 0 && x < w && y < h) {
					double dith = (BAYER[(y & 3) * 4 + (x & 3)] + 0.5) / 16.0;
					double a = Math.floor(((1 - d) * (1 - d) * strength + dith * 0.06) * 6) / 6;
					if (a > 0) {
						frame[y * w + x] = mix(frame[y * w + x], color, a);
					}
				}
			}
		}
	}

	/** Every ~40 s a sleigh with reindeer crosses the sky (right to left) - a silhouette with one red nose. */
	private void sleigh() {
		double period = 42;
		double phase = (t + 42 - sleighAt) % period;
		double duration = 16;
		if (phase > duration) {
			return;
		}
		double u = phase / duration;
		int x = (int) Math.round(w + 30 - u * (w + 70));
		int y = (int) Math.round(h * 0.12 + Math.sin(u * Math.PI * 2) * 3 + u * h * 0.05);
		int c = 0xFF0A0D20;
		String[] art = {
				"..........#.#.......#.#.......#.#.....",
				"r#.......##.##.....##.##.....##.##....",
				"####....######....######....######....",
				".#.#....#....#....#....#....#....#.#..",
				"................................#####.",
				"................................######",
				"................................#..#.."};
		for (int row = 0; row < art.length; row++) {
			for (int col = 0; col < art[row].length(); col++) {
				char ch = art[row].charAt(col);
				if (ch == '#') {
					put(x + col, y + row, c);
				} else if (ch == 'r') {
					put(x + col, y + row, 0xFFFF3A3A);
				}
			}
		}
		// The reins: a thin line from the sleigh to the first reindeer.
		for (int col = 4; col < 33; col++) {
			if (col % 2 == 0) {
				put(x + col, y + 3, c);
			}
		}
	}

	// ------------------------------------------------------------------ pixels

	private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

	private void set(int[] buf, int x, int y, int c) {
		if (x >= 0 && y >= 0 && x < w && y < h) {
			buf[y * w + x] = c;
		}
	}

	private int get(int[] buf, int x, int y) {
		return x >= 0 && y >= 0 && x < w && y < h ? buf[y * w + x] : 0;
	}

	private void put(int x, int y, int c) {
		if (x >= 0 && y >= 0 && x < w && y < h) {
			frame[y * w + x] = c;
		}
	}

	private void blend(int x, int y, int c, double a) {
		if (x >= 0 && y >= 0 && x < w && y < h) {
			frame[y * w + x] = mix(frame[y * w + x], c, a);
		}
	}

	static int mix(int a, int b, double t) {
		t = Math.max(0, Math.min(1, t));
		int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return 0xFF000000 | r << 16 | g << 8 | bl;
	}

	private static int shade(int c, double f) {
		return mix(0xFF000000, c, f);
	}

	/** Snaps a colour to steps of {@code step} per channel - visible banding is part of the pixel look. */
	private static int band(int c, int step) {
		int r = ((c >> 16) & 0xFF) / step * step;
		int g = ((c >> 8) & 0xFF) / step * step;
		int b = (c & 0xFF) / step * step;
		return 0xFF000000 | r << 16 | g << 8 | b;
	}
}
