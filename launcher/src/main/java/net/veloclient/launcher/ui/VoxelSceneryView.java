package net.veloclient.launcher.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The home screen backdrop: a floating Minecraft-style island - grass, dirt, a stone underside
 * tapering to a point, oak trees, a pond, clouds - slowly turning in 3D, graded towards the theme.
 *
 * <p>Looks: pixel-art textures built from small palettes (like Minecraft's own), Minecraft's
 * per-direction face shading, and smooth ambient occlusion (corners and the ground under ledges
 * darken). Water is its own translucent, glossy surface.
 *
 * <p>Cost: the opaque island is ONE mesh with hidden faces never emitted. Shading and occlusion are
 * baked into atlas tiles (one tile per texture + face direction + corner-occlusion combination
 * actually used), so the island renders under a single ambient light with no per-pixel lighting.
 * Tiles sit in power-of-two cells with wide gutters, so mipmapping never bleeds a neighbouring
 * tile in as a dark outline. The turn animation stops whenever the view leaves the scene.
 */
public final class VoxelSceneryView extends Region {

	private static final int RADIUS = 13;
	private static final int TOP = 16;
	private static final int BOTTOM = -16;
	private static final int SIZE = RADIUS * 2 + 8;
	private static final int HEIGHT = TOP - BOTTOM + 1;

	private static final int AIR = 0;
	private static final int GRASS = 1;
	private static final int DIRT = 2;
	private static final int STONE = 3;
	private static final int LOG = 4;
	private static final int LEAVES = 5;
	private static final int WATER = 6;
	private static final int SAND = 7;
	private static final int ORE = 8;
	private static final int CLOUD = 9;

	private static final int T_GRASS_TOP = 0;
	private static final int T_GRASS_SIDE = 1;
	private static final int T_DIRT = 2;
	private static final int T_STONE = 3;
	private static final int T_LOG_SIDE = 4;
	private static final int T_LOG_TOP = 5;
	private static final int T_LEAVES = 6;
	private static final int T_SAND = 7;
	private static final int T_ORE = 8;
	private static final int T_CLOUD = 9;
	private static final int TEXTURES = 10;

	/** Minecraft's face brightness: up, down, north/south, west/east. */
	private static final double[] DIR_SHADE = {1.0, 0.5, 0.8, 0.8, 0.62, 0.62};
	/** Brightness for 0..3 occluding neighbours at a corner. */
	private static final double[] AO = {1.0, 0.8, 0.64, 0.5};

	private static final int TEX = 16;
	// JavaFX mipmaps material textures, so tiles sit in power-of-two-aligned cells with a wide gutter
	// of repeated edge pixels: mip levels then never average two different tiles together (that's
	// what showed up as dark/grey outlines along block edges).
	private static final int UPSCALE = 2;
	private static final int GUTTER = 16;
	private static final int CELL = TEX * UPSCALE + GUTTER * 2;

	private final SubScene subScene;
	private final Rotate spin = new Rotate(0, Rotate.Y_AXIS);
	private final PhongMaterial material = new PhongMaterial();
	private final PhongMaterial waterMaterial = new PhongMaterial();
	private final AnimationTimer timer;
	private final byte[][][] blocks = new byte[SIZE][HEIGHT][SIZE];
	/** Tile key (texture, direction, 4 corner AO levels) -> atlas slot, in first-use order. */
	private final Map<Integer, Integer> tiles = new LinkedHashMap<>();
	private int atlasColumns = 1;
	private Color accent = Color.web("#ff4444");
	private Color backdrop = Color.web("#0f0a0a");

	public VoxelSceneryView() {
		generate(new Random(0x5E10C1L));

		// Mesh building registers every tile it uses; the atlas layout follows from that list.
		MeshBuilder solid = new MeshBuilder();
		MeshBuilder water = new MeshBuilder();
		buildFaces(solid, water);
		int rows = (tiles.size() + atlasColumns - 1) / atlasColumns;
		solid.finish(atlasColumns * CELL, rows * CELL);
		water.finish(1, 1);

		MeshView island = new MeshView(solid.mesh);
		island.setMaterial(material);
		island.setCullFace(CullFace.NONE);
		MeshView waterView = new MeshView(water.mesh);
		waterView.setMaterial(waterMaterial);
		waterView.setCullFace(CullFace.NONE);

		// Clouds are part of the island mesh, so they turn with it; they sit above it, inside its radius.
		Group pivot = new Group(island, waterView);
		pivot.getTransforms().add(spin);
		Group world = new Group(pivot);
		world.getTransforms().addAll(new Translate(0, 3.6, 0), new Rotate(24, Rotate.X_AXIS));

		AmbientLight ambient = new AmbientLight(Color.WHITE);
		ambient.getScope().add(island);
		// Only the water is lit for real: a key light so its surface shows a moving glint as it turns.
		AmbientLight waterAmbient = new AmbientLight(Color.gray(0.7));
		waterAmbient.getScope().add(waterView);
		PointLight sun = new PointLight(Color.WHITE);
		sun.setTranslateX(-30);
		sun.setTranslateY(-60);
		sun.setTranslateZ(-30);
		sun.getScope().add(waterView);
		Group root3d = new Group(world, ambient, waterAmbient, sun);

		PerspectiveCamera camera = new PerspectiveCamera(true);
		camera.setFieldOfView(30);
		camera.setNearClip(0.5);
		camera.setFarClip(400);
		camera.setTranslateZ(-58);

		subScene = new SubScene(root3d, 10, 10, true, SceneAntialiasing.BALANCED);
		subScene.setCamera(camera);
		subScene.setFill(Color.TRANSPARENT);
		subScene.setMouseTransparent(true);
		getChildren().add(subScene);
		setMinSize(0, 0);

		long[] last = {0};
		timer = new AnimationTimer() {
			@Override
			public void handle(long now) {
				double dt = last[0] == 0 ? 0 : Math.min(0.05, (now - last[0]) / 1e9);
				last[0] = now;
				spin.setAngle((spin.getAngle() + dt * 5) % 360);
				waterView.setTranslateY(Math.sin(now / 7e8) * 0.03);
			}
		};
		sceneProperty().addListener((obs, was, scene) -> {
			if (scene == null) {
				timer.stop();
				last[0] = 0;
			} else {
				timer.start();
			}
		});
		setTheme(accent, backdrop);
	}

	/** Re-grades the island and backdrop to the theme (rebuilds only the texture atlas). */
	public void setTheme(Color accent, Color background) {
		this.accent = accent;
		this.backdrop = background;
		material.setDiffuseMap(buildAtlas());
		Color water = Color.hsb(205 + (((accent.getHue() - 205) % 360 + 540) % 360 - 180) * 0.25, 0.62, 0.8);
		waterMaterial.setDiffuseColor(Color.color(water.getRed(), water.getGreen(), water.getBlue(), 0.7));
		waterMaterial.setSpecularColor(Color.gray(0.9));
		waterMaterial.setSpecularPower(48);
		setBackground(new Background(
				new BackgroundFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
						new Stop(0, background.interpolate(accent, 0.22)),
						new Stop(0.55, background.interpolate(accent, 0.07)),
						new Stop(1, background)), null, null),
				new BackgroundFill(new RadialGradient(0, 0, 0.5, 0.45, 0.55, true, CycleMethod.NO_CYCLE,
						new Stop(0, accent.deriveColor(0, 1, 1, 0.20)),
						new Stop(1, accent.deriveColor(0, 1, 1, 0))), null, null)));
	}

	@Override
	protected void layoutChildren() {
		subScene.setWidth(getWidth());
		subScene.setHeight(getHeight());
	}

	// ------------------------------------------------------------------ world

	private void set(int x, int y, int z, int block) {
		int ix = x + SIZE / 2;
		int iy = y - BOTTOM;
		int iz = z + SIZE / 2;
		if (ix >= 0 && ix < SIZE && iy >= 0 && iy < HEIGHT && iz >= 0 && iz < SIZE) {
			blocks[ix][iy][iz] = (byte) block;
		}
	}

	private int get(int x, int y, int z) {
		int ix = x + SIZE / 2;
		int iy = y - BOTTOM;
		int iz = z + SIZE / 2;
		if (ix < 0 || ix >= SIZE || iy < 0 || iy >= HEIGHT || iz < 0 || iz >= SIZE) {
			return AIR;
		}
		return blocks[ix][iy][iz];
	}

	/** Smooth 2D noise in roughly [-1, 1] (summed seeded sine waves - plenty for terrain this small). */
	private static double noise(double x, double z, long salt) {
		long s = salt * 0x9E3779B97F4A7C15L;
		double a = Math.sin(x * 0.55 + (s & 0xFF) * 0.1) * Math.cos(z * 0.47 + ((s >> 8) & 0xFF) * 0.1);
		double b = Math.sin(x * 1.31 + z * 0.73 + ((s >> 16) & 0xFF) * 0.1) * 0.5;
		double c = Math.cos(z * 1.9 - x * 0.37 + ((s >> 24) & 0xFF) * 0.1) * 0.25;
		return (a + b + c) / 1.75;
	}

	private void generate(Random random) {
		int[][] top = new int[SIZE][SIZE];
		for (int[] column : top) {
			java.util.Arrays.fill(column, Integer.MIN_VALUE);
		}
		for (int x = -RADIUS - 2; x <= RADIUS + 2; x++) {
			for (int z = -RADIUS - 2; z <= RADIUS + 2; z++) {
				double edge = RADIUS * (0.86 + 0.14 * noise(x, z, 7));
				double d = Math.sqrt(x * x + z * z) / edge;
				if (d >= 1) {
					continue;
				}
				int surface = (int) Math.round(1.2 * noise(x * 0.8, z * 0.8, 3) + (1 - d) * 1.4);
				int depth = (int) Math.round(Math.pow(1 - d, 0.7) * 12 + noise(x, z, 11) * 1.5) + 2;
				for (int y = surface - depth; y <= surface; y++) {
					int block;
					if (y == surface) {
						block = GRASS;
					} else if (y >= surface - 2) {
						block = DIRT;
					} else {
						block = random.nextDouble() < 0.05 ? ORE : STONE;
					}
					set(x, y, z, block);
				}
				top[x + SIZE / 2][z + SIZE / 2] = surface;
			}
		}
		// A pond carved one block into the ground: the water sits below the grass line, on sand.
		int px = 5;
		int pz = -5;
		int waterLevel = top[px + SIZE / 2][pz + SIZE / 2] - 1;
		for (int x = px - 4; x <= px + 4; x++) {
			for (int z = pz - 4; z <= pz + 4; z++) {
				double d = Math.hypot(x - px, z - pz) + noise(x, z, 5) * 0.4;
				int surface = top[x + SIZE / 2][z + SIZE / 2];
				if (surface == Integer.MIN_VALUE) {
					continue;
				}
				if (d < 2.6) {
					for (int y = waterLevel + 1; y <= surface; y++) {
						set(x, y, z, AIR);
					}
					for (int y = Math.min(waterLevel, surface); y <= waterLevel; y++) {
						set(x, y, z, WATER);
					}
					set(x, waterLevel - 1, z, SAND);
					top[x + SIZE / 2][z + SIZE / 2] = Integer.MIN_VALUE;
				} else if (d < 3.6 && get(x, surface, z) == GRASS) {
					set(x, surface, z, SAND);
				}
			}
		}
		// Trees stay off the middle, where the player model stands.
		tree(-7, 2, top, 5);
		tree(-3, 8, top, 4);
		tree(-9, -5, top, 4);
		tree(8, 5, top, 5);
		tree(9, -8, top, 4);
		tree(3, 9, top, 4);
		cloud(-11, 12, -4, 6, 3);
		cloud(4, 13, 6, 7, 3);
		cloud(6, 11, -10, 4, 3);
	}

	private void tree(int x, int z, int[][] top, int height) {
		int base = top[x + SIZE / 2][z + SIZE / 2];
		if (base == Integer.MIN_VALUE || get(x, base, z) != GRASS) {
			return;
		}
		set(x, base, z, DIRT);
		for (int y = 1; y <= height; y++) {
			set(x, base + y, z, LOG);
		}
		int crown = base + height;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = -1; dy <= 2; dy++) {
					int r = dy >= 1 ? 1 : 2;
					if (Math.abs(dx) > r || Math.abs(dz) > r) {
						continue;
					}
					boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
					if (corner && (dy == 2 || Math.floorMod(x * 31 + z * 17 + dy + dx, 3) == 0)) {
						continue;
					}
					if (get(x + dx, crown + dy, z + dz) == AIR) {
						set(x + dx, crown + dy, z + dz, LEAVES);
					}
				}
			}
		}
	}

	private void cloud(int x, int y, int z, int w, int d) {
		for (int dx = 0; dx < w; dx++) {
			for (int dz = 0; dz < d; dz++) {
				if ((dx == 0 || dx == w - 1) && (dz == 0 || dz == d - 1)) {
					continue;
				}
				set(x + dx, y, z + dz, CLOUD);
			}
		}
	}

	private static boolean occludes(int block) {
		return block != AIR && block != WATER && block != CLOUD;
	}

	// ------------------------------------------------------------------ mesh

	/** Face directions: 0 up, 1 down, 2 north(-z), 3 south(+z), 4 west(-x), 5 east(+x). */
	private static final int[][] DIRS = {{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
	/** Corner offsets per face in UV order (u0v0, u1v0, u1v1, u0v1); side faces list their top edge first. */
	private static final int[][][] CORNERS = {
			{{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}},
			{{0, 0, 1}, {1, 0, 1}, {1, 0, 0}, {0, 0, 0}},
			{{1, 1, 0}, {0, 1, 0}, {0, 0, 0}, {1, 0, 0}},
			{{0, 1, 1}, {1, 1, 1}, {1, 0, 1}, {0, 0, 1}},
			{{0, 1, 0}, {0, 1, 1}, {0, 0, 1}, {0, 0, 0}},
			{{1, 1, 1}, {1, 1, 0}, {1, 0, 0}, {1, 0, 1}},
	};

	private static final class MeshBuilder {
		final TriangleMesh mesh = new TriangleMesh();
		final List<float[]> quads = new ArrayList<>();
		final List<int[]> tileOf = new ArrayList<>();

		void finish(int atlasWidth, int atlasHeight) {
			float[] points = new float[quads.size() * 12];
			float[] uvs = new float[quads.size() * 8];
			int[] faces = new int[quads.size() * 12];
			for (int q = 0; q < quads.size(); q++) {
				System.arraycopy(quads.get(q), 0, points, q * 12, 12);
				int[] slot = tileOf.get(q);
				float u0 = 0;
				float v0 = 0;
				float u1 = 1;
				float v1 = 1;
				if (slot != null) {
					float x = slot[0] * CELL + GUTTER;
					float y = slot[1] * CELL + GUTTER;
					u0 = x / atlasWidth;
					v0 = y / atlasHeight;
					u1 = (x + TEX * UPSCALE) / atlasWidth;
					v1 = (y + TEX * UPSCALE) / atlasHeight;
				}
				float[] uv = {u0, v0, u1, v0, u1, v1, u0, v1};
				System.arraycopy(uv, 0, uvs, q * 8, 8);
				int p = q * 4;
				int[] f = {p, p, p + 1, p + 1, p + 2, p + 2, p, p, p + 2, p + 2, p + 3, p + 3};
				System.arraycopy(f, 0, faces, q * 12, 12);
			}
			mesh.getPoints().setAll(points);
			mesh.getTexCoords().setAll(uvs);
			mesh.getFaces().setAll(faces);
		}
	}

	private void buildFaces(MeshBuilder solid, MeshBuilder water) {
		for (int x = -SIZE / 2; x < SIZE - SIZE / 2; x++) {
			for (int y = BOTTOM; y <= TOP; y++) {
				for (int z = -SIZE / 2; z < SIZE - SIZE / 2; z++) {
					int block = get(x, y, z);
					if (block == AIR) {
						continue;
					}
					for (int dir = 0; dir < 6; dir++) {
						int neighbor = get(x + DIRS[dir][0], y + DIRS[dir][1], z + DIRS[dir][2]);
						if (block == WATER) {
							if (neighbor == AIR) {
								water.quads.add(quad(x, y, z, dir, 0.875f));
								water.tileOf.add(null);
							}
							continue;
						}
						if (!faceVisible(block, neighbor)) {
							continue;
						}
						int aoKey = block == CLOUD ? 0 : cornerOcclusion(x, y, z, dir);
						int key = (textureFor(block, dir) * 6 + dir) * 256 + aoKey;
						int slot = tiles.computeIfAbsent(key, k -> tiles.size());
						solid.quads.add(quad(x, y, z, dir, 1f));
						solid.tileOf.add(new int[] {slot, 0});
					}
				}
			}
		}
		// Slots become (column, row) now that the final atlas size is known.
		atlasColumns = Math.max(1, (int) Math.ceil(Math.sqrt(tiles.size())));
		for (int[] slot : solid.tileOf) {
			int index = slot[0];
			slot[0] = index % atlasColumns;
			slot[1] = index / atlasColumns;
		}
	}

	/** Packs the four corner occlusion levels (0-3 each, UV order) of one face into a byte. */
	private int cornerOcclusion(int x, int y, int z, int dir) {
		int[] n = DIRS[dir];
		int key = 0;
		for (int c = 0; c < 4; c++) {
			int[] corner = CORNERS[dir][c];
			// The two in-plane axes, each pointing towards this corner.
			int[] t1 = new int[3];
			int[] t2 = new int[3];
			int axisCount = 0;
			for (int axis = 0; axis < 3; axis++) {
				if (n[axis] != 0) {
					continue;
				}
				int[] t = axisCount == 0 ? t1 : t2;
				t[axis] = corner[axis] == 1 ? 1 : -1;
				axisCount++;
			}
			int bx = x + n[0];
			int by = y + n[1];
			int bz = z + n[2];
			boolean side1 = occludes(get(bx + t1[0], by + t1[1], bz + t1[2]));
			boolean side2 = occludes(get(bx + t2[0], by + t2[1], bz + t2[2]));
			boolean cornerBlock = occludes(get(bx + t1[0] + t2[0], by + t1[1] + t2[1], bz + t1[2] + t2[2]));
			int level = side1 && side2 ? 3 : (side1 ? 1 : 0) + (side2 ? 1 : 0) + (cornerBlock ? 1 : 0);
			key |= level << (c * 2);
		}
		return key;
	}

	private static float[] quad(int x, int y, int z, int dir, float topHeight) {
		float[] out = new float[12];
		for (int c = 0; c < 4; c++) {
			int[] corner = CORNERS[dir][c];
			float cy = corner[1] == 1 ? topHeight : 0;
			// JavaFX's Y axis points down, so world-up becomes -y.
			out[c * 3] = x + corner[0] - 0.5f;
			out[c * 3 + 1] = -(y + cy);
			out[c * 3 + 2] = z + corner[2] - 0.5f;
		}
		return out;
	}

	private static boolean faceVisible(int block, int neighbor) {
		if (neighbor == AIR || neighbor == WATER) {
			return true;
		}
		return (neighbor == LEAVES || neighbor == CLOUD) && neighbor != block;
	}

	private static int textureFor(int block, int dir) {
		return switch (block) {
			case GRASS -> dir == 0 ? T_GRASS_TOP : dir == 1 ? T_DIRT : T_GRASS_SIDE;
			case DIRT -> T_DIRT;
			case STONE -> T_STONE;
			case ORE -> T_ORE;
			case LOG -> dir <= 1 ? T_LOG_TOP : T_LOG_SIDE;
			case LEAVES -> T_LEAVES;
			case SAND -> T_SAND;
			default -> T_CLOUD;
		};
	}

	// ------------------------------------------------------------------ textures

	private WritableImage buildAtlas() {
		int rows = Math.max(1, (tiles.size() + atlasColumns - 1) / atlasColumns);
		WritableImage atlas = new WritableImage(atlasColumns * CELL, rows * CELL);
		PixelWriter writer = atlas.getPixelWriter();
		Color[][][] textures = new Color[TEXTURES][][];
		for (int t = 0; t < TEXTURES; t++) {
			textures[t] = texture(t);
		}
		int inner = TEX * UPSCALE;
		for (Map.Entry<Integer, Integer> entry : tiles.entrySet()) {
			int key = entry.getKey();
			int aoKey = key & 0xFF;
			int dir = (key >> 8) % 6;
			int texture = (key >> 8) / 6;
			int slot = entry.getValue();
			int ox = (slot % atlasColumns) * CELL + GUTTER;
			int oy = (slot / atlasColumns) * CELL + GUTTER;
			double shade = texture == T_CLOUD ? (dir == 1 ? 0.86 : 1.0) : DIR_SHADE[dir];
			double[] corner = new double[4];
			for (int c = 0; c < 4; c++) {
				corner[c] = AO[(aoKey >> (c * 2)) & 3];
			}
			for (int py = -GUTTER; py < inner + GUTTER; py++) {
				for (int px = -GUTTER; px < inner + GUTTER; px++) {
					// The gutter repeats the tile's own edge pixels.
					int cx = Math.max(0, Math.min(inner - 1, px));
					int cy = Math.max(0, Math.min(inner - 1, py));
					Color base = textures[texture][cx / UPSCALE][cy / UPSCALE];
					// Occlusion is interpolated across the face, like Minecraft's smooth lighting.
					double u = (cx + 0.5) / inner;
					double v = (cy + 0.5) / inner;
					double ao = (corner[0] * (1 - u) + corner[1] * u) * (1 - v) + (corner[3] * (1 - u) + corner[2] * u) * v;
					writer.setArgb(ox + px, oy + py, argb(base, shade * ao));
				}
			}
		}
		return atlas;
	}

	/** Picks a palette entry from a 0..1 value - quantizing is what gives the crisp pixel-art look. */
	private static Color pick(Color[] palette, double value) {
		int index = (int) Math.floor(Math.max(0, Math.min(0.9999, value)) * palette.length);
		return palette[index];
	}

	private static Color[] ramp(Color base, double... factors) {
		Color[] out = new Color[factors.length];
		for (int i = 0; i < factors.length; i++) {
			double f = factors[i];
			out[i] = f >= 1 ? base.interpolate(Color.WHITE, f - 1) : base.interpolate(Color.BLACK, 1 - f);
		}
		return out;
	}

	/** One 16x16 pixel-art texture, graded towards the theme accent. */
	private Color[][] texture(int texture) {
		Random random = new Random(1000L + texture * 7919L);
		// Foliage takes the accent's hue at a natural saturation/brightness - a stylized "theme biome".
		Color foliage = Color.hsb(accent.getHue(), Math.min(0.6, Math.max(0.4, accent.getSaturation() * 0.66)), 0.68);
		Color[] grass = ramp(foliage, 0.76, 0.87, 0.96, 1.05);
		Color[] dirt = ramp(Color.web("#86603f"), 0.7, 0.82, 0.92, 1.02);
		Color[] stone = ramp(Color.web("#848489").interpolate(backdrop, 0.18), 0.74, 0.85, 0.94, 1.03);
		Color[] sand = ramp(Color.web("#dccc95"), 0.87, 0.93, 1.0, 1.03);
		Color[] bark = ramp(Color.web("#6b5133"), 0.64, 0.78, 0.9, 1.0);
		Color[] leaves = ramp(foliage.deriveColor(0, 1.05, 0.9, 1), 0.62, 0.78, 0.9, 1.0);
		Color[][] out = new Color[TEX][TEX];
		for (int x = 0; x < TEX; x++) {
			for (int y = 0; y < TEX; y++) {
				double n = random.nextDouble();
				// Mixing in a low-frequency blotch term makes tones form clusters instead of static.
				double blotch = 0.5 + 0.5 * noise(x * 0.9, y * 0.9, texture * 13L + 1);
				double v = n * 0.55 + blotch * 0.45;
				Color c;
				switch (texture) {
					case T_GRASS_TOP -> c = pick(grass, v);
					case T_GRASS_SIDE -> {
						int hang = 3 + (Math.floorMod(x * 7 + 3, 5) < 2 ? 1 : 0) + (x % 6 == 2 ? 1 : 0);
						c = y < hang ? pick(grass, v * 0.85) : pick(dirt, v);
					}
					case T_DIRT -> c = pick(dirt, v);
					case T_STONE -> c = pick(stone, v);
					case T_ORE -> {
						boolean vein = noise(x * 1.7, y * 1.7, 99) > 0.45 && n > 0.25;
						c = vein ? accent.deriveColor(0, 0.9, n > 0.7 ? 1.1 : 0.9, 1) : pick(stone, v);
					}
					case T_LOG_SIDE -> c = pick(bark, (x % 4 == 1 ? 0.1 : 0.5) + n * 0.45);
					case T_LOG_TOP -> {
						double ring = Math.max(Math.abs(x - 7.5), Math.abs(y - 7.5));
						c = ring > 6.5 ? bark[1] : ((int) ring) % 2 == 0 ? Color.web("#b08d58") : Color.web("#9a7748");
					}
					case T_LEAVES -> c = pick(leaves, n < 0.12 ? 0.05 : v);
					case T_SAND -> c = pick(sand, v);
					default -> c = Color.web("#f6f6fa");
				}
				out[x][y] = c;
			}
		}
		return out;
	}

	private static int argb(Color c, double factor) {
		int r = (int) Math.round(Math.max(0, Math.min(1, c.getRed() * factor)) * 255);
		int g = (int) Math.round(Math.max(0, Math.min(1, c.getGreen() * factor)) * 255);
		int b = (int) Math.round(Math.max(0, Math.min(1, c.getBlue() * factor)) * 255);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
}
