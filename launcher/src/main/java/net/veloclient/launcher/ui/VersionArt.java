package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import net.veloclient.launcher.launch.GameDataPaths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A picture for each Minecraft version on the version tiles: a small 3D diorama of what that version added, ray traced
 * with sun, soft shadows, ambient occlusion, water reflections, haze and bloom ({@link VoxelArt}) -
 * 1.21.11: a beach at sunset with spears and nautilus armour by copper lanterns; 26.1: a golden dandelion meadow;
 * 26.2: a glowing sulfur and cinnabar cave; 26.3: an autumn poplar forest.
 *
 * <p>Velo ships no Minecraft art: textures come from that version's game jar the launcher already downloaded (a version
 * not downloaded yet is drawn in its colours). Each picture is rendered once in the background and kept in
 * {@code cache/version-art/}, so tiles show instantly afterwards.
 */
public final class VersionArt {

	/** Rendered picture size (2x the tile for sharp HiDPI). */
	public static final int W = 704;
	public static final int H = 396;
	/** Width of a version tile in the launcher. */
	public static final int TILE_W = 200;
	/** Bump when a scene changes, so cached pictures are rendered again. */
	private static final int REVISION = 2;

	private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
	private static final java.util.concurrent.ExecutorService RENDER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "version-art");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY + 1);
		return t;
	});

	/** Background pre-rendering uses half the cores, so the launcher (or a game starting) never stutters for it. */
	private static final java.util.concurrent.ForkJoinPool QUIET =
			new java.util.concurrent.ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

	private VersionArt() {
	}

	/**
	 * Renders the pictures that aren't cached yet once, in the background (called a while after the launcher started),
	 * so the version tiles never wait. Only versions whose game files are downloaded; skipped while a game runs.
	 */
	public static void prewarm(java.util.List<String> versions, java.util.function.BooleanSupplier gameRunning) {
		RENDER.submit(() -> {
			for (String version : versions) {
				if (gameRunning.getAsBoolean() || CACHE.containsKey(version) || Files.isRegularFile(cacheFile(version))
						|| !Files.isRegularFile(GameDataPaths.clientJar(version))) {
					continue;
				}
				try {
					QUIET.submit(() -> {
						try {
							picture(version);
						} catch (Exception ignored) {
							// tried again when the tile is shown
						}
					}).get();
				} catch (Exception ignored) {
					return;
				}
			}
		});
	}

	/** The version's picture: at once when known, else loaded from disk or rendered off the FX thread, then {@code ready}. */
	public static void load(String version, Consumer<Image> ready) {
		Image cached = CACHE.get(version);
		if (cached != null) {
			ready.accept(cached);
			return;
		}
		RENDER.submit(() -> {
			Image image;
			try {
				image = SwingFXUtils.toFXImage(picture(version), null);
			} catch (Throwable e) {
				net.veloclient.launcher.LauncherLog.warn("Version picture for " + version + " failed", e);
				return;
			}
			CACHE.put(version, image);
			Platform.runLater(() -> ready.accept(image));
		});
	}

	private static Path cacheFile(String version) {
		return net.veloclient.launcher.data.VeloPaths.root().resolve("cache").resolve("version-art").resolve(version + "-r" + REVISION + ".png");
	}

	/** From disk when it was rendered before with the real game textures, else rendered now (and saved if it can be). */
	static BufferedImage picture(String version) throws Exception {
		// Rendered in Blender at release time (tools/release/version_tiles.py): a diorama of what's new in that version,
		// with real lighting. The ray-traced scene below is only for versions without one.
		if (!Boolean.getBoolean("velo.versionArtNoCache")) {
			try (var in = VersionArt.class.getResourceAsStream("/net/veloclient/launcher/images/versions/" + version + ".jpg")) {
				if (in != null) {
					BufferedImage bundled = ImageIO.read(in);
					if (bundled != null) {
						return bundled;
					}
				}
			}
		}
		Path file = cacheFile(version);
		boolean noCache = Boolean.getBoolean("velo.versionArtNoCache"); // previews while designing the scenes
		if (!noCache && Files.isRegularFile(file)) {
			BufferedImage disk = ImageIO.read(file.toFile());
			if (disk != null) {
				return disk;
			}
		}
		Path jar = GameDataPaths.clientJar(version);
		boolean real = Files.isRegularFile(jar);
		BufferedImage image;
		if (real) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				image = VoxelArt.render(scene(version, new Textures(zip)), W, H, 4);
			}
			try {
				if (noCache) {
					return image;
				}
				Files.createDirectories(file.getParent());
				ImageIO.write(image, "png", file.toFile());
			} catch (Exception ignored) {
				// rendered again next time
			}
		} else {
			image = VoxelArt.render(scene(version, new Textures(null)), W, H, 2);
		}
		return image;
	}

	/** A version tile: the picture with rounded corners, the version number (and "Newest") over its shaded bottom. */
	public static javafx.scene.layout.StackPane tile(String version, boolean newest, double width) {
		double height = Math.round(width * H / (double) W);
		javafx.scene.image.ImageView view = new javafx.scene.image.ImageView();
		view.setFitWidth(width);
		view.setFitHeight(height);
		view.setSmooth(true);
		javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(width, height);
		clip.setArcWidth(20);
		clip.setArcHeight(20);
		view.setClip(clip);
		javafx.scene.layout.Region placeholder = new javafx.scene.layout.Region();
		placeholder.setPrefSize(width, height);
		placeholder.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 10;");
		// a soft dark band at the bottom so the version number always reads
		javafx.scene.layout.Region shade = new javafx.scene.layout.Region();
		shade.setPrefSize(width, height);
		shade.setMaxSize(width, height);
		shade.setStyle("-fx-background-radius: 10; -fx-background-color: linear-gradient(to bottom, transparent 45%, rgba(0,0,0,0.62) 100%);");
		shade.setMouseTransparent(true);
		view.setOpacity(0);
		load(version, image -> {
			view.setImage(image);
			placeholder.setVisible(false);
			javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(260), view);
			fade.setToValue(1);
			fade.play();
		});
		javafx.scene.control.Label number = new javafx.scene.control.Label(version);
		number.setStyle("-fx-text-fill: white; -fx-font-size: 17px; -fx-font-weight: bold; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.75), 8, 0.3, 0, 1);");
		javafx.scene.layout.HBox labels = new javafx.scene.layout.HBox(7, number);
		labels.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
		labels.setPadding(new javafx.geometry.Insets(0, 0, 9, 12));
		if (newest) {
			javafx.scene.control.Label tag = new javafx.scene.control.Label("Newest");
			tag.getStyleClass().addAll("version-tag-pill", "version-tag-newest");
			labels.getChildren().add(tag);
		}
		labels.setMouseTransparent(true);
		javafx.scene.layout.StackPane pane = new javafx.scene.layout.StackPane(placeholder, view, shade, labels);
		pane.setPrefSize(width, height);
		pane.setMaxSize(width, height);
		return pane;
	}

	// ---------------------------------------------------------------- textures

	/** Block and item textures from the game jar (first frame, 16x16), or flat colours without one. */
	private static final class Textures {
		private final ZipFile zip;
		private final Map<String, VoxelArt.Tex> cache = new HashMap<>();

		Textures(ZipFile zip) {
			this.zip = zip;
		}

		VoxelArt.Tex get(String name, int tint, int fallback) {
			return cache.computeIfAbsent(name + "#" + tint, k -> {
				int[] px = read(name);
				return px == null ? VoxelArt.Tex.flat(fallback) : VoxelArt.Tex.of(px, tint);
			});
		}

		VoxelArt.Tex get(String name, int fallback) {
			return get(name, 0, fallback);
		}

		/** Grass side: the dirt side with the tinted grass overlay on top of it. */
		VoxelArt.Tex grassSide(int tint, int fallback) {
			int[] side = read("block/grass_block_side");
			int[] overlay = read("block/grass_block_side_overlay");
			if (side == null) {
				return VoxelArt.Tex.flat(fallback);
			}
			if (overlay != null) {
				for (int i = 0; i < 256; i++) {
					int o = overlay[i];
					if ((o >>> 24) > 128) {
						side[i] = 0xFF000000 | mul(o, tint);
					}
				}
			}
			return VoxelArt.Tex.of(side, 0);
		}

		private int[] read(String name) {
			if (zip == null) {
				return null;
			}
			try {
				ZipEntry entry = zip.getEntry("assets/minecraft/textures/" + name + ".png");
				if (entry == null) {
					return null;
				}
				try (InputStream in = zip.getInputStream(entry)) {
					BufferedImage raw = ImageIO.read(in);
					if (raw == null) {
						return null;
					}
					int[] out = new int[256];
					int size = Math.min(raw.getWidth(), raw.getHeight()); // first frame of an animated strip
					for (int y = 0; y < 16; y++) {
						for (int x = 0; x < 16; x++) {
							out[y * 16 + x] = raw.getRGB(x * size / 16, y * size / 16);
						}
					}
					return out;
				}
			} catch (Exception e) {
				return null;
			}
		}

		private static int mul(int argb, int tint) {
			int r = ((argb >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
			int g = ((argb >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
			int b = (argb & 0xFF) * (tint & 0xFF) / 255;
			return r << 16 | g << 8 | b;
		}
	}

	// ---------------------------------------------------------------- scenes

	private static final int GRASS = 0x79C05A;
	private static final int FOLIAGE = 0x59AE30;
	private static final int WATER = 0x3F76E4;

	private static VoxelArt.Scene scene(String version, Textures t) {
		return switch (version) {
			case "1.21.11" -> beach(t);
			case "26.2" -> sulfurCave(t);
			case "26.3" -> poplarForest(t);
			default -> meadow(t);
		};
	}

	/** Smooth random heights (a few octaves of value noise), 0..1. */
	private static float[][] heights(int nx, int nz, long seed, float scale) {
		SplittableRandom rnd = new SplittableRandom(seed);
		int gx = nx / 4 + 3, gz = nz / 4 + 3;
		float[][] grid = new float[gx][gz];
		for (int x = 0; x < gx; x++) {
			for (int z = 0; z < gz; z++) {
				grid[x][z] = (float) rnd.nextDouble();
			}
		}
		float[][] out = new float[nx][nz];
		for (int x = 0; x < nx; x++) {
			for (int z = 0; z < nz; z++) {
				float fx = x / (4f * scale), fz = z / (4f * scale);
				int x0 = (int) fx, z0 = (int) fz;
				float ax = smooth(fx - x0), az = smooth(fz - z0);
				x0 = Math.min(gx - 2, x0);
				z0 = Math.min(gz - 2, z0);
				float a = grid[x0][z0] * (1 - ax) + grid[x0 + 1][z0] * ax;
				float b = grid[x0][z0 + 1] * (1 - ax) + grid[x0 + 1][z0 + 1] * ax;
				out[x][z] = a * (1 - az) + b * az;
			}
		}
		return out;
	}

	private static float smooth(float t) {
		return t * t * (3 - 2 * t);
	}

	private static VoxelArt.Block block(VoxelArt.Tex all) {
		return new VoxelArt.Block(all, all, all);
	}

	private static void tree(VoxelArt.Scene s, int x, int y, int z, int trunk, VoxelArt.Block log, VoxelArt.Block leaves, int radius) {
		for (int i = 0; i < trunk; i++) {
			s.set(x, y + i, z, log);
		}
		int top = y + trunk;
		for (int dy = -2; dy <= 1; dy++) {
			int r = dy >= 0 ? radius - 1 : radius;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
					if (corner && (dy == 1 || ((x * 31 + dz * 7 + dy) & 1) == 0)) {
						continue;
					}
					if (s.get(x + dx, top + dy, z + dz) == null) {
						s.set(x + dx, top + dy, z + dz, leaves);
					}
				}
			}
		}
	}

	/** 26.1: rolling grass with golden dandelions everywhere, oak trees, a bright summer sky. */
	private static VoxelArt.Scene meadow(Textures t) {
		int nx = 30, ny = 12, nz = 26;
		VoxelArt.Scene s = new VoxelArt.Scene(nx, ny, nz);
		VoxelArt.Block grass = new VoxelArt.Block(t.get("block/grass_block_top", GRASS, 0x5FA040), t.grassSide(GRASS, 0x6E8B3D),
				t.get("block/dirt", 0x7A5534));
		VoxelArt.Block dirt = block(t.get("block/dirt", 0x7A5534));
		VoxelArt.Block log = new VoxelArt.Block(t.get("block/oak_log_top", 0x9C7A4A), t.get("block/oak_log", 0x6B5032), t.get("block/oak_log_top", 0x9C7A4A));
		VoxelArt.Block leaves = block(t.get("block/oak_leaves", FOLIAGE, 0x3F8A2A));
		leaves.cutout = true;
		float[][] h = heights(nx, nz, 26_1L, 1.6f);
		int[][] top = new int[nx][nz];
		for (int x = 0; x < nx; x++) {
			for (int z = 0; z < nz; z++) {
				int height = 2 + Math.round(h[x][z] * 3 + z * 0.08f);
				top[x][z] = height;
				for (int y = 0; y < height; y++) {
					s.set(x, y, z, y == height - 1 ? grass : dirt);
				}
			}
		}
		tree(s, 6, top[6][15], 15, 4, log, leaves, 2);
		tree(s, 23, top[23][19], 19, 5, log, leaves, 2);
		tree(s, 15, top[15][24], 24, 4, log, leaves, 2);
		SplittableRandom rnd = new SplittableRandom(261);
		String[] flowers = {"block/golden_dandelion", "block/golden_dandelion", "block/golden_dandelion", "block/dandelion", "block/poppy", "block/cornflower"};
		for (int i = 0; i < 170; i++) {
			int x = rnd.nextInt(nx), z = rnd.nextInt(nz - 2);
			if (s.get(x, top[x][z], z) != null) {
				continue;
			}
			float fx = x + 0.2f + (float) rnd.nextDouble() * 0.6f, fz = z + 0.2f + (float) rnd.nextDouble() * 0.6f;
			if (rnd.nextInt(3) == 0) {
				s.sprites.add(new VoxelArt.Sprite(fx, top[x][z], fz, 1, 1, rnd.nextInt(90), 0, t.get("block/short_grass", GRASS, 0x5FA040), true, 0));
			} else {
				String f = flowers[rnd.nextInt(flowers.length)];
				s.sprites.add(new VoxelArt.Sprite(fx, top[x][z], fz, 1, 1, rnd.nextInt(90), 0, t.get(f, 0xF2C230), true, 0));
			}
		}
		// the star of 26.1, up front
		s.sprites.add(new VoxelArt.Sprite(15.5f, top[15][5], 5.4f, 1.3f, 1.3f, 10, 0, t.get("block/golden_dandelion", 0xF2C230), true, 0.15f));
		s.camera = new float[] {15, top[15][1] + 2.6f, -2.5f};
		s.target = new float[] {15, top[15][12] + 0.5f, 13};
		s.fov = 50;
		s.sun = new float[] {-0.55f, 0.62f, -0.35f};
		s.sunColor = new float[] {3.0f, 2.8f, 2.35f};
		s.skyZenith = new float[] {0.09f, 0.26f, 0.72f};
		s.skyHorizon = new float[] {0.62f, 0.78f, 0.95f};
		s.ambient = new float[] {0.36f, 0.45f, 0.62f};
		s.fog = 0.008f;
		s.exposure = 0.92f;
		s.bloom = 0.22f;
		return s;
	}

	/** 1.21.11: a sand island at sunset, spears stuck in the sand and nautilus armour by glowing copper lanterns. */
	private static VoxelArt.Scene beach(Textures t) {
		int nx = 32, ny = 9, nz = 30;
		VoxelArt.Scene s = new VoxelArt.Scene(nx, ny, nz);
		VoxelArt.Block sand = block(t.get("block/sand", 0xDBCF9C));
		VoxelArt.Block water = block(t.get("block/water_still", WATER, 0x3F76E4));
		water.water = true;
		float[][] h = heights(nx, nz, 1_21_11L, 1.8f);
		int[][] top = new int[nx][nz];
		for (int x = 0; x < nx; x++) {
			for (int z = 0; z < nz; z++) {
				float dx = (x - 15.5f) / 12f, dz = (z - 11f) / 8f;
				float island = 1 - (dx * dx + dz * dz);
				int height = island > 0 ? 2 + Math.round(island * 2.5f + h[x][z] * 0.8f) : 1;
				top[x][z] = height;
				for (int y = 0; y < height; y++) {
					s.set(x, y, z, sand);
				}
				if (height <= 2) {
					s.set(x, 2, z, water);
				}
			}
		}
		// lantern posts
		int[][] posts = {{9, 9}, {22, 10}, {15, 15}};
		for (int[] p : posts) {
			int y = top[p[0]][p[1]];
			// copper lanterns standing in the sand
			s.sprites.add(new VoxelArt.Sprite(p[0] + 0.5f, y, p[1] + 0.5f, 1.25f, 1.25f, 0, 0, t.get("item/copper_lantern", 0xD8803C), false, 2.2f));
			s.lights.add(new VoxelArt.Light(p[0] + 0.5f, y + 0.7f, p[1] - 0.2f, 4.0f, 2.3f, 0.9f, 7));
		}
		// spears in the sand (the item art is diagonal already - stood up it reads as stuck in at an angle)
		String[] spears = {"item/diamond_spear", "item/golden_spear", "item/netherite_spear", "item/copper_spear"};
		float[][] at = {{12.2f, 7.6f}, {18.6f, 7.2f}, {14.8f, 5.5f}, {20.8f, 12.6f}};
		for (int i = 0; i < spears.length; i++) {
			int x = (int) at[i][0], z = (int) at[i][1];
			s.sprites.add(new VoxelArt.Sprite(at[i][0], top[x][z] - 0.25f, at[i][1], 2.1f, 2.1f, 8 - i * 11, 0, t.get(spears[i], 0x9AD8E0), false, 0));
		}
		s.sprites.add(new VoxelArt.Sprite(16.6f, top[16][7], 6.4f, 1.6f, 1.6f, -14, 0, t.get("item/golden_nautilus_armor", 0xE8B33A), false, 0));
		s.sprites.add(new VoxelArt.Sprite(10.6f, top[10][6], 5.6f, 1.1f, 1.1f, 20, 0, t.get("item/nautilus_shell", 0xD9C3A0), false, 0));
		s.camera = new float[] {15.8f, 6.0f, -3.2f};
		s.target = new float[] {15.8f, 2.7f, 9};
		s.fov = 50;
		// low sun ahead: a warm sky behind the island, long shadows towards you
		s.sun = new float[] {0.42f, 0.1f, 0.9f};
		s.sunColor = new float[] {2.8f, 1.45f, 0.7f};
		s.skyZenith = new float[] {0.1f, 0.12f, 0.32f};
		s.skyHorizon = new float[] {0.95f, 0.42f, 0.2f};
		s.ambient = new float[] {0.36f, 0.3f, 0.38f};
		s.fog = 0.01f;
		s.exposure = 0.9f;
		s.bloom = 0.5f;
		return s;
	}

	/** 26.2: a cave of sulfur and cinnabar around a lava pool, sulfur spikes from floor and ceiling. */
	private static VoxelArt.Scene sulfurCave(Textures t) {
		int nx = 30, ny = 13, nz = 22;
		VoxelArt.Scene s = new VoxelArt.Scene(nx, ny, nz);
		VoxelArt.Block sulfur = block(t.get("block/sulfur", 0xC9B65A));
		VoxelArt.Block potent = block(t.get("block/potent_sulfur", 0xD7C060));
		VoxelArt.Block cinnabar = block(t.get("block/cinnabar", 0xA4382F));
		VoxelArt.Block bricks = block(t.get("block/cinnabar_bricks", 0x8E3028));
		VoxelArt.Block lava = block(t.get("block/lava_still", 0xE8701E));
		lava.emit = 2.6f;
		float[][] h = heights(nx, nz, 26_2L, 1.4f);
		float[][] c = heights(nx, nz, 99L, 1.2f);
		int[][] floor = new int[nx][nz];
		for (int x = 0; x < nx; x++) {
			for (int z = 0; z < nz; z++) {
				float dx = (x - 15f) / 7.5f, dz = (z - 9f) / 4.6f;
				boolean pool = dx * dx + dz * dz < 1;
				int height = pool ? 1 : z < 4 ? 1 + Math.round(h[x][z]) : 2 + Math.round(h[x][z] * 2 + z * 0.08f);
				floor[x][z] = height;
				for (int y = 0; y < height; y++) {
					VoxelArt.Block b = h[x][z] > 0.62f ? potent : (x + z) % 7 == 0 ? bricks : (x * 3 + z) % 5 < 2 ? cinnabar : sulfur;
					s.set(x, y, z, b);
				}
				if (pool) {
					s.set(x, 1, z, lava);
				}
				// the cave: walls rising at the back and sides, a ceiling
				int ceil = ny - 2 - Math.round(c[x][z] * 3);
				for (int y = ceil; y < ny; y++) {
					s.set(x, y, z, (x + y) % 6 == 0 ? cinnabar : sulfur);
				}
				if (z > nz - 4 || x < 2 || x > nx - 3) {
					for (int y = height; y < ceil; y++) {
						s.set(x, y, z, (y + z) % 5 == 0 ? cinnabar : sulfur);
					}
				}
			}
		}
		SplittableRandom rnd = new SplittableRandom(262);
		for (int i = 0; i < 46; i++) {
			int x = 2 + rnd.nextInt(nx - 4), z = 2 + rnd.nextInt(nz - 6);
			float fx = x + 0.5f, fz = z + 0.5f;
			if (s.get(x, floor[x][z], z) == null && floor[x][z] > 1 && rnd.nextBoolean()) {
				s.sprites.add(new VoxelArt.Sprite(fx, floor[x][z], fz, 1, 1, rnd.nextInt(90), 0, t.get("block/sulfur_spike_up_tip", 0xD7C060), true, 0));
			}
			int ceil = ny - 2 - Math.round(c[x][z] * 3);
			if (s.get(x, ceil - 1, z) == null) {
				s.sprites.add(new VoxelArt.Sprite(fx, ceil - 1, fz, 1, 1, rnd.nextInt(90), 0, t.get("block/sulfur_spike_down_tip", 0xD7C060), true, 0));
			}
		}
		// lava light
		s.lights.add(new VoxelArt.Light(15, 2.6f, 9, 7.5f, 2.8f, 0.8f, 16));
		s.lights.add(new VoxelArt.Light(9, 2.6f, 8, 4.0f, 1.5f, 0.4f, 11));
		s.lights.add(new VoxelArt.Light(21, 2.6f, 10, 4.0f, 1.5f, 0.4f, 11));
		s.camera = new float[] {15, 7.2f, -1.5f};
		s.target = new float[] {15, 1.6f, 10};
		s.fov = 58;
		s.openSky = false;
		s.sun = new float[] {0.2f, -1f, 0.3f};
		s.sunColor = new float[] {0, 0, 0};
		s.skyZenith = new float[] {0.05f, 0.015f, 0.02f};
		s.skyHorizon = new float[] {0.16f, 0.05f, 0.04f};
		s.ambient = new float[] {0.16f, 0.1f, 0.09f};
		s.fog = 0.015f;
		s.exposure = 1.35f;
		s.bloom = 0.55f;
		return s;
	}

	/** 26.3: tall poplars in red, orange and yellow, low golden sun through the trees. */
	private static VoxelArt.Scene poplarForest(Textures t) {
		int nx = 32, ny = 16, nz = 28;
		VoxelArt.Scene s = new VoxelArt.Scene(nx, ny, nz);
		int autumn = 0xB4A84E;
		VoxelArt.Block grass = new VoxelArt.Block(t.get("block/grass_block_top", autumn, 0x9C9A48), t.grassSide(autumn, 0x8A7A3D),
				t.get("block/dirt", 0x7A5534));
		VoxelArt.Block dirt = block(t.get("block/dirt", 0x7A5534));
		VoxelArt.Block log = new VoxelArt.Block(t.get("block/poplar_log_top", 0xC9B892), t.get("block/poplar_log", 0xD8D0BC), t.get("block/poplar_log_top", 0xC9B892));
		String[] kinds = {"block/orange_poplar_leaves", "block/red_poplar_leaves", "block/yellow_poplar_leaves"};
		int[] flat = {0xD0681A, 0xB8301E, 0xE0B530};
		VoxelArt.Block[] leaves = new VoxelArt.Block[3];
		for (int i = 0; i < 3; i++) {
			leaves[i] = block(t.get(kinds[i], flat[i]));
			leaves[i].cutout = true;
		}
		float[][] h = heights(nx, nz, 26_3L, 1.7f);
		int[][] top = new int[nx][nz];
		for (int x = 0; x < nx; x++) {
			for (int z = 0; z < nz; z++) {
				int height = 2 + Math.round(h[x][z] * 2 + z * 0.05f);
				top[x][z] = height;
				for (int y = 0; y < height; y++) {
					s.set(x, y, z, y == height - 1 ? grass : dirt);
				}
			}
		}
		int[][] trees = {{5, 9}, {11, 16}, {19, 11}, {25, 17}, {8, 22}, {15, 24}, {22, 25}, {28, 10}, {2, 16}};
		SplittableRandom rnd = new SplittableRandom(263);
		for (int i = 0; i < trees.length; i++) {
			int x = trees[i][0], z = trees[i][1], y = top[x][z];
			int trunk = 7 + rnd.nextInt(3);
			for (int k = 0; k < trunk; k++) {
				s.set(x, y + k, z, log);
			}
			VoxelArt.Block l = leaves[i % 3];
			// a poplar: a tall narrow crown
			for (int k = 3; k <= trunk + 1; k++) {
				int r = k == trunk + 1 || k == 3 ? 0 : 1;
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (s.get(x + dx, y + k, z + dz) == null) {
							s.set(x + dx, y + k, z + dz, l);
						}
					}
				}
			}
		}
		for (int i = 0; i < 120; i++) {
			int x = rnd.nextInt(nx), z = rnd.nextInt(nz - 2);
			if (s.get(x, top[x][z], z) != null) {
				continue;
			}
			s.sprites.add(new VoxelArt.Sprite(x + 0.5f, top[x][z], z + 0.5f, 1, 1, rnd.nextInt(90), 0, t.get("block/short_grass", autumn, 0x9C9A48), true, 0));
		}
		s.camera = new float[] {16, top[16][2] + 3.2f, -3};
		s.target = new float[] {16, top[16][14] + 3.2f, 14};
		s.fov = 52;
		s.sun = new float[] {-0.75f, 0.22f, 0.6f};
		s.sunColor = new float[] {3.6f, 2.3f, 1.15f};
		s.skyZenith = new float[] {0.14f, 0.2f, 0.45f};
		s.skyHorizon = new float[] {0.95f, 0.6f, 0.34f};
		s.ambient = new float[] {0.3f, 0.27f, 0.3f};
		s.fog = 0.011f;
		s.exposure = 0.95f;
		s.bloom = 0.35f;
		return s;
	}
}
