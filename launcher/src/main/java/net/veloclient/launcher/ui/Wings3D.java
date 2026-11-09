package net.veloclient.launcher.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Affine;
import net.veloclient.launcher.data.WingsCatalog;
import net.veloclient.velo.client.cosmetics.wings.WingRigs;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 3D wings on the launcher's player model ({@link PlayerSkin3DView}), animated the same way the
 * game does it (a port of the mod's {@code WingAnimator}): a slow open-and-close wingbeat, the outer
 * part trailing the inner. {@link #show} swaps the pair, {@link #motion} picks what the player is
 * "doing" (idle, walk, fly, glide, sneak, swim). Stops by itself when the model leaves the screen.
 *
 * <p>Model space is the viewer's: pixels, y down, +z behind the player, shoulders at y = -8.
 *
 * <p>Each half is a static mesh moved by one {@link Affine} per frame. Rewriting the mesh points
 * every frame (to bend the outer part separately) made JavaFX rebuild the whole mesh - normals,
 * tangents and fresh native vertex buffers - on every pulse: most of the launcher's CPU while a
 * winged player was on screen, and native memory that grew ~2 MB a second for as long as it was
 * (1.5 GB after an afternoon). The bend is now taken at a point two thirds of the way out.
 *
 * <p>Pairs with arms (tentacles, flames, feathers, blades...) move them one by one the way the game does
 * ({@link WingRigs#pose}) without ever touching a mesh: each half is cut into its bones ({@link WingRigs#bone}), one
 * small static mesh per bone, and each bone piece is moved by its own {@link Affine} ({@link WingRigs#bones}) - a
 * rigid version of the game's smooth bend, under a millimetre off on average. (Rewriting points per frame leaked
 * ~2 MB/s of native memory in Prism, even with a direct-memory cap; baking a loop into a mesh per frame kept ~100 MB
 * per pair on the heap and ran the launcher out of memory in the Locker.) Membranes don't billow here. Glows ({@link WingRigs.Glow})
 * are a see-through copy on top whose texture coordinates are (what the glow is keyed on, how much it glows), so only
 * a small {@value #GLOW_W} x {@value #GLOW_H} pattern texture is redrawn each frame. Particles are small quads of
 * their own, moved by their {@link Affine}s. Clockwork
 * gears are meshes of their own, each turned by its own {@link Affine} on top of its half's. Polished pairs shine: a
 * see-through copy of each half lies on top, its texture coordinates running across the wing, and only a
 * {@value #SHINE_STRIP} x 1 strip texture is redrawn each frame to sweep the glint ({@link WingRigs#glint}) over it.
 */
public final class Wings3D {

	/** What the preview player is doing, like the game's showcase modes. */
	public enum Motion { IDLE, WALK, FLY, GLIDE, SNEAK, SWIM }

	private record Half(int side, float[] geometry, float[] uv, float[] weight, int[] triangles) {
	}

	private record Model(Half[] halves, Image texture) {
	}

	private static final Map<String, Model> MODELS = new HashMap<>();
	/** A pair's arms per half ({@link WingRigs#arms}; found once, small), by pair id. */
	private static final Map<String, WingRigs.Arms[]> ARMS = new HashMap<>();
	/** How wild the arms are. */
	private static final float WRITHE_AMOUNT = 0.45f;
	private static final float ATTACH_Y = 0.30f;
	private static final float ATTACH_Z = 0.17f;
	private static final float SHOULDER_X = 0.05f;

	private final Group node = new Group();
	private final AnimationTimer timer;
	private Model model;
	private WingsCatalog.WingType type;
	/** One transform per half, in model order; the meshes themselves never change. */
	private Affine[] transforms = new Affine[0];
	private MeshView[] halfViews = new MeshView[0];
	/** A moving pair's arms per half (entries may be null), its bone pieces' transforms (per half, per bone) and their latest pose. */
	private WingRigs.Arms[] arms;
	private Affine[][] boneTransforms = new Affine[0][];
	private float[][] bonePose = new float[0][];
	private double writheClock;
	/** The glow of the pair (null without one), its overlay views and pattern texture. */
	private WingRigs.Glow glow;
	private MeshView[] glowViews = new MeshView[0];
	private javafx.scene.image.WritableImage glowStrip;
	private static final int GLOW_W = 256;
	private static final int GLOW_H = 16;
	/** Particles: per view its kind, index and half, and its transform. */
	private WingRigs.Particles[] particleKinds = new WingRigs.Particles[0];
	private int[] particleIndex = new int[0];
	private int[] particleHalf = new int[0];
	private Affine[] particleTransforms = new Affine[0];
	private int[][] particleSources = new int[0][];
	private double fxSeconds;
	/** A recolourable pair: its material (tinted every frame - the rainbow cycles). */
	private PhongMaterial wingMaterial;
	private boolean recolorable;
	/** The shown pair's id (its own colour, {@link WingRigs#ownTint}). */
	private String recolorId;
	private int shownTint = -2;
	private static final Map<Integer, javafx.scene.image.Image> SPRITES = new HashMap<>();
	/** Per gear view: its turn, centre (x, y), ratio and side. */
	private Affine[] gearTransforms = new Affine[0];
	private float[] gearInfo = new float[0];
	private double gearAngle;
	/** The glint strip of a polished pair (null otherwise), its colour and clock. */
	private javafx.scene.image.WritableImage shineStrip;
	private int shineColor;
	private double shineSeconds;
	private static final int SHINE_STRIP = 256;
	/** Where along a half (0 shoulder, 1 tip) its rigid pose takes the flex. */
	private static final float BEND_AT = 0.65f;
	private Motion motion = Motion.IDLE;
	private double phase;
	private float sweep = 0.45f;
	private float amplitude = 0.24f;
	private float frequency = 0.28f;
	private float lift = 0.05f;
	private long last;
	/** How they're worn (size, position, spread, flap) - the game's per-pair settings. */
	private WingsCatalog.Settings settings = WingsCatalog.Settings.DEFAULT;

	public Wings3D(Group parent) {
		parent.getChildren().add(node);
		timer = new AnimationTimer() {
			@Override
			public void handle(long now) {
				if (!RenderGate.visible(node)) {
					return;
				}
				step(now);
			}
		};
		node.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (newScene == null) {
				timer.stop();
			} else {
				timer.start();
			}
		});
	}

	public Wings3D motion(Motion motion) {
		this.motion = motion;
		return this;
	}

	/** Shows them the way they're set to be worn (live while the sliders move). */
	public Wings3D settings(WingsCatalog.Settings settings) {
		this.settings = settings == null ? WingsCatalog.Settings.DEFAULT : settings;
		return this;
	}

	/** Shows this pair (null hides the wings). */
	public void show(String id) {
		WingsCatalog.WingType next = id == null ? null : WingsCatalog.byId(id).orElse(null);
		if (next == null) {
			node.getChildren().clear();
			type = null;
			model = null;
			arms = null;
			gearTransforms = new Affine[0];
			shineStrip = null;
			glow = null;
			glowStrip = null;
			glowViews = new MeshView[0];
			particleTransforms = new Affine[0];
			return;
		}
		if (type != null && type.id().equals(next.id())) {
			return;
		}
		Model m = load(next.id());
		if (m == null) {
			return;
		}
		type = next;
		model = m;
		// Performance mode: just the wingbeat.
		boolean light = net.veloclient.launcher.data.LauncherSettings.get().performanceMode;
		WingRigs.Effect effect = light ? null : WingRigs.effect(next.id());
		WingRigs.Motion armMotion = light ? null : "tentacle".equals(next.style()) ? WingRigs.Motion.WRITHE : effect != null ? effect.motion() : null;
		glow = effect != null ? effect.glow() : null;
		// Per half, the glow overlay's texture coordinates: (what it's keyed on, how much it glows).
		float[][] glowUv = glow == null ? null : glowCoordinates(m, glow);
		arms = armMotion == null ? null : arms(next.id(), m, armMotion);
		boneTransforms = new Affine[m.halves.length][];
		bonePose = new float[m.halves.length][];
		PhongMaterial material = new PhongMaterial();
		material.setDiffuseMap(m.texture);
		wingMaterial = material;
		recolorable = WingRigs.recolorable(next.id());
		recolorId = next.id();
		material.setSpecularColor(javafx.scene.paint.Color.rgb(40, 40, 40));
		if (WingRigs.shine(next.id()) != null) {
			// Polished metal: a bright, tight highlight that slides over the feathers as they turn.
			int c = WingRigs.shine(next.id()).color();
			material.setSpecularColor(javafx.scene.paint.Color.rgb((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF));
			material.setSpecularPower(22);
		}
		java.util.List<MeshView> views = new java.util.ArrayList<>();
		java.util.List<Affine> gears = new java.util.ArrayList<>();
		java.util.List<Float> info = new java.util.ArrayList<>();
		WingRigs.Rig rig = WingRigs.rig(next.id());
		WingRigs.Shine shine = light ? null : WingRigs.shine(next.id());
		PhongMaterial shineMaterial = null;
		shineStrip = null;
		if (shine != null) {
			shineStrip = new javafx.scene.image.WritableImage(SHINE_STRIP, 1);
			shineColor = shine.color() & 0xFFFFFF;
			shineMaterial = new PhongMaterial(javafx.scene.paint.Color.WHITE);
			shineMaterial.setDiffuseMap(shineStrip);
			shineMaterial.setSelfIlluminationMap(shineStrip);
			shineMaterial.setSpecularColor(javafx.scene.paint.Color.TRANSPARENT);
		}
		java.util.List<MeshView> overlays = new java.util.ArrayList<>();
		PhongMaterial glowMaterial = null;
		glowStrip = null;
		if (glow != null) {
			glowStrip = new javafx.scene.image.WritableImage(GLOW_W, GLOW_H);
			glowMaterial = new PhongMaterial(javafx.scene.paint.Color.WHITE);
			glowMaterial.setDiffuseMap(glowStrip);
			glowMaterial.setSelfIlluminationMap(glowStrip);
			glowMaterial.setSpecularColor(javafx.scene.paint.Color.TRANSPARENT);
		}
		glowViews = new MeshView[glow == null ? 0 : m.halves.length];
		transforms = new Affine[m.halves.length];
		halfViews = new MeshView[m.halves.length];
		for (int i = 0; i < m.halves.length; i++) {
			Half h = m.halves[i];
			transforms[i] = new Affine();
			WingRigs.Arms halfArms = arms == null ? null : arms[i];
			if (halfArms != null) {
				// One piece per bone (and the body), each moved by its bone's transform.
				java.util.Map<Integer, java.util.List<Integer>> pieces = new java.util.TreeMap<>();
				for (int tri = 0; tri < h.triangles.length; tri += 3) {
					pieces.computeIfAbsent(WingRigs.bone(halfArms, h.triangles[tri]), b -> new java.util.ArrayList<>()).add(tri);
				}
				boneTransforms[i] = new Affine[halfArms.arms() * WingRigs.Arms.SEGMENTS];
				float[] lifted = glowMaterial != null ? lifted(h.geometry) : null;
				for (var piece : pieces.entrySet()) {
					int bone = piece.getKey();
					Affine[] chain = bone < 0 ? new Affine[] {transforms[i]} : new Affine[] {transforms[i], boneTransforms[i][bone] = new Affine()};
					views.add(view(piece(h.geometry, h.uv, h.triangles, piece.getValue()), material, chain));
					if (glowMaterial != null) {
						overlays.add(view(piece(lifted, glowUv[i], h.triangles, piece.getValue()), glowMaterial, chain));
					}
				}
			} else {
				halfViews[i] = view(mesh(h.geometry, h.uv, h.triangles), material, transforms[i]);
				views.add(halfViews[i]);
				if (glowMaterial != null) {
					glowViews[i] = view(mesh(lifted(h.geometry), glowUv[i], h.triangles), glowMaterial, transforms[i]);
					overlays.add(glowViews[i]);
				}
			}
			if (shineMaterial != null && halfArms == null) {
				// A hair outside the wing so it doesn't fight it for depth; u = where across the wing.
				float[] points = new float[h.geometry.length];
				float[] across = new float[h.uv.length];
				for (int v = 0; v < h.geometry.length / 3; v++) {
					points[v * 3] = h.geometry[v * 3] * 1.004f;
					points[v * 3 + 1] = h.geometry[v * 3 + 1] * 1.004f;
					points[v * 3 + 2] = h.geometry[v * 3 + 2] * 1.004f;
					across[v * 2] = (WingRigs.band(h.geometry[v * 3], h.geometry[v * 3 + 1], h.side) * (SHINE_STRIP - 1) + 0.5f) / SHINE_STRIP;
					across[v * 2 + 1] = 0.5f;
				}
				overlays.add(view(mesh(points, across, h.triangles), shineMaterial, transforms[i]));
			}
			if (rig == null) {
				continue;
			}
			float scale = next.scale();
			for (WingRigs.Gear gear : rig.gears()) {
				WingRigs.GearMesh g = WingRigs.gearMesh(gear, rig, h.side, scale);
				float cx = h.side * gear.x() * scale;
				float cy = -gear.y() * scale;
				float cz = -gear.z() * scale;
				int n = g.uv().length / 2;
				float[] points = new float[n * 3];
				for (int v = 0; v < n; v++) {
					points[v * 3] = g.geometry()[v * 6] + cx;
					points[v * 3 + 1] = g.geometry()[v * 6 + 1] + cy;
					points[v * 3 + 2] = g.geometry()[v * 6 + 2] + cz;
				}
				Affine turn = new Affine();
				views.add(view(mesh(points, g.uv(), g.triangles()), material, transforms[i], turn));
				gears.add(turn);
				info.addAll(java.util.List.of(cx, cy, gear.ratio(), (float) h.side));
			}
		}
		gearTransforms = gears.toArray(new Affine[0]);
		gearInfo = new float[info.size()];
		for (int i = 0; i < gearInfo.length; i++) {
			gearInfo[i] = info.get(i);
		}
		// Drawn last, over the wings.
		views.addAll(overlays);
		views.addAll(particles(effect, m, glowUv));
		node.getChildren().setAll(views);
		step(System.nanoTime());
	}

	private static TriangleMesh mesh(float[] points, float[] uv, int[] triangles) {
		TriangleMesh mesh = new TriangleMesh();
		mesh.getPoints().setAll(points);
		mesh.getTexCoords().setAll(uv);
		int[] faces = new int[triangles.length * 2];
		for (int t = 0; t < triangles.length; t++) {
			faces[t * 2] = triangles[t];
			faces[t * 2 + 1] = triangles[t];
		}
		mesh.getFaces().setAll(faces);
		return mesh;
	}

	private static MeshView view(TriangleMesh mesh, PhongMaterial material, Affine... transforms) {
		MeshView view = new MeshView(mesh);
		view.setMaterial(material);
		view.setCullFace(CullFace.NONE);
		view.getTransforms().setAll(transforms);
		return view;
	}

	/** The points a hair outside the wing, so an overlay doesn't fight it for depth. */
	private static float[] lifted(float[] points) {
		float[] out = new float[points.length];
		for (int i = 0; i < points.length; i++) {
			out[i] = points[i] * 1.004f;
		}
		return out;
	}

	/** Per half, each vertex's glow overlay coordinates: u = its glow key, v = how much it glows (texture colour). */
	private static float[][] glowCoordinates(Model m, WingRigs.Glow glow) {
		javafx.scene.image.PixelReader reader = m.texture.getPixelReader();
		int tw = (int) m.texture.getWidth();
		int th = (int) m.texture.getHeight();
		float[][] out = new float[m.halves.length][];
		for (int h = 0; h < m.halves.length; h++) {
			Half half = m.halves[h];
			int n = half.geometry.length / 3;
			float far = 1e-4f;
			for (int v = 0; v < n; v++) {
				far = Math.max(far, length(half.geometry, v));
			}
			float[] uv = new float[n * 2];
			for (int v = 0; v < n; v++) {
				int argb = reader == null ? 0 : reader.getArgb(Math.max(0, Math.min(tw - 1, (int) (half.uv[v * 2] * tw))),
						Math.max(0, Math.min(th - 1, (int) (half.uv[v * 2 + 1] * th))));
				float mask = WingRigs.mask(glow, argb);
				float key = WingRigs.glowKey(glow.pattern(), half.geometry[v * 3], half.geometry[v * 3 + 1], half.geometry[v * 3 + 2], half.side,
						length(half.geometry, v) / far);
				uv[v * 2] = (key * (GLOW_W - 1) + 0.5f) / GLOW_W;
				uv[v * 2 + 1] = (mask * (GLOW_H - 1) + 0.5f) / GLOW_H;
			}
			out[h] = uv;
		}
		return out;
	}

	private static float length(float[] points, int v) {
		return (float) Math.sqrt(points[v * 3] * points[v * 3] + points[v * 3 + 1] * points[v * 3 + 1] + points[v * 3 + 2] * points[v * 3 + 2]);
	}

	/** The arms of each half of pair {@code id}, found once. */
	private static synchronized WingRigs.Arms[] arms(String id, Model m, WingRigs.Motion motion) {
		WingRigs.Arms[] cached = ARMS.get(id);
		if (cached != null) {
			return cached;
		}
		WingRigs.Arms[] found = new WingRigs.Arms[m.halves.length];
		for (int h = 0; h < found.length; h++) {
			found[h] = WingRigs.arms(m.halves[h].geometry, 3, m.halves[h].triangles, motion);
		}
		ARMS.put(id, found);
		return found;
	}

	/** The pair's particle quads (one view per particle, hidden until it shows), each moved by its own transform. */
	private java.util.List<MeshView> particles(WingRigs.Effect effect, Model m, float[][] glowUv) {
		java.util.List<MeshView> views = new java.util.ArrayList<>();
		java.util.List<WingRigs.Particles> kinds = new java.util.ArrayList<>();
		java.util.List<Integer> index = new java.util.ArrayList<>();
		java.util.List<Integer> halves = new java.util.ArrayList<>();
		java.util.List<Affine> moves = new java.util.ArrayList<>();
		particleSources = new int[m.halves.length][];
		if (effect != null && effect.particles().length > 0) {
			TriangleMesh quad = new TriangleMesh();
			quad.getPoints().setAll(-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0);
			quad.getTexCoords().setAll(0, 0, 1, 0, 1, 1, 0, 1);
			quad.getFaces().setAll(0, 0, 1, 1, 2, 2, 0, 0, 2, 2, 3, 3);
			for (int h = 0; h < m.halves.length; h++) {
				particleSources[h] = sources(effect, m, h, glowUv);
				for (WingRigs.Particles kind : effect.particles()) {
					PhongMaterial material = new PhongMaterial(javafx.scene.paint.Color.WHITE);
					javafx.scene.image.Image sprite = sprite(WingRigs.cell(kind.kind()), kind.color());
					material.setDiffuseMap(sprite);
					material.setSelfIlluminationMap(sprite);
					material.setSpecularColor(javafx.scene.paint.Color.TRANSPARENT);
					for (int k = 0; k < kind.count(); k++) {
						Affine move = new Affine();
						move.setToTransform(1e-4, 0, 0, 0, 0, 1e-4, 0, 0, 0, 0, 1e-4, 0);
						views.add(view(quad, material, transforms[h], move));
						kinds.add(kind);
						index.add(k);
						halves.add(h);
						moves.add(move);
					}
				}
			}
		}
		particleKinds = kinds.toArray(new WingRigs.Particles[0]);
		particleIndex = index.stream().mapToInt(Integer::intValue).toArray();
		particleHalf = halves.stream().mapToInt(Integer::intValue).toArray();
		particleTransforms = moves.toArray(new Affine[0]);
		return views;
	}

	/** The vertices a half's particles start from (same picking as the game's WingModel). */
	private static int[] sources(WingRigs.Effect effect, Model m, int h, float[][] glowUv) {
		Half half = m.halves[h];
		int n = half.geometry.length / 3;
		float far = 1e-4f;
		for (int v = 0; v < n; v++) {
			far = Math.max(far, length(half.geometry, v));
		}
		java.util.List<Integer> picked = new java.util.ArrayList<>();
		for (int v = 0; v < n; v++) {
			boolean use = switch (effect.source()) {
				case GLOW -> glowUv != null && (glowUv[h][v * 2 + 1] * GLOW_H - 0.5f) / (GLOW_H - 1) > 0.5f;
				case TIPS -> length(half.geometry, v) / far > 0.6f;
				case ANY -> true;
			};
			if (use) {
				picked.add(v);
			}
		}
		if (picked.size() < 8) {
			picked.clear();
			for (int v = 0; v < n; v++) {
				picked.add(v);
			}
		}
		return picked.stream().mapToInt(Integer::intValue).toArray();
	}

	/** An aura atlas cell tinted {@code color}, full bright (64 px). */
	private static synchronized javafx.scene.image.Image sprite(int cell, int color) {
		int key = cell << 24 ^ (color & 0xFFFFFF);
		javafx.scene.image.Image cached = SPRITES.get(key);
		if (cached != null) {
			return cached;
		}
		javafx.scene.image.WritableImage out = new javafx.scene.image.WritableImage(64, 64);
		try (InputStream in = Wings3D.class.getResourceAsStream("/net/veloclient/launcher/aura/atlas.png")) {
			if (in != null) {
				javafx.scene.image.Image atlas = new javafx.scene.image.Image(in);
				javafx.scene.image.PixelReader reader = atlas.getPixelReader();
				int cellSize = (int) atlas.getWidth() / 8;
				int x0 = (cell % 8) * cellSize;
				int y0 = (cell / 8) * cellSize;
				for (int y = 0; y < 64; y++) {
					for (int x = 0; x < 64; x++) {
						int a = reader.getArgb(x0 + x * cellSize / 64, y0 + y * cellSize / 64) >>> 24;
						int r = ((color >> 16) & 0xFF) * a / 255;
						int g = ((color >> 8) & 0xFF) * a / 255;
						int b = (color & 0xFF) * a / 255;
						out.getPixelWriter().setArgb(x, y, a << 24 | r << 16 | g << 8 | b);
					}
				}
			}
		} catch (Exception e) {
			// No sprite: the particle stays invisible.
		}
		SPRITES.put(key, out);
		return out;
	}

	/** A mesh of just the triangles (by first index) {@code triangles}, with its own copy of the points they use. */
	private static TriangleMesh piece(float[] points, float[] uv, int[] all, java.util.List<Integer> triangles) {
		Map<Integer, Integer> remap = new HashMap<>();
		java.util.List<Integer> order = new java.util.ArrayList<>();
		int[] local = new int[triangles.size() * 3];
		int n = 0;
		for (int tri : triangles) {
			for (int c = 0; c < 3; c++) {
				int v = all[tri + c];
				Integer at = remap.get(v);
				if (at == null) {
					at = order.size();
					remap.put(v, at);
					order.add(v);
				}
				local[n++] = at;
			}
		}
		float[] p = new float[order.size() * 3];
		float[] t = new float[order.size() * 2];
		for (int i = 0; i < order.size(); i++) {
			int v = order.get(i);
			System.arraycopy(points, v * 3, p, i * 3, 3);
			System.arraycopy(uv, v * 2, t, i * 2, 2);
		}
		return mesh(p, t, local);
	}

	private static synchronized Model load(String id) {
		Model cached = MODELS.get(id);
		if (cached != null) {
			return cached;
		}
		WingsCatalog.WingType type = WingsCatalog.byId(id).orElse(null);
		if (type == null) {
			return null;
		}
		try (InputStream raw = WingsCatalog.open(id + ".vmesh"); InputStream png = WingsCatalog.open("textures/" + id + ".png")) {
			if (raw == null || png == null) {
				return null;
			}
			DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
			in.readFully(new byte[4]);
			in.readInt();
			int count = in.readInt();
			Half[] halves = new Half[count];
			float scale = type.scale();
			for (int h = 0; h < count; h++) {
				float hx = in.readFloat();
				float hy = in.readFloat();
				float hz = in.readFloat();
				float span = Math.max(1e-3f, in.readFloat());
				int n = in.readInt();
				float[] g = new float[n * 3];
				float[] uv = new float[n * 2];
				float[] w = new float[n];
				float sideSum = 0;
				for (int v = 0; v < n; v++) {
					float px = in.readFloat() - hx;
					float py = in.readFloat() - hy;
					float pz = in.readFloat() - hz;
					in.readFloat();
					in.readFloat();
					in.readFloat();
					g[v * 3] = px * scale;
					g[v * 3 + 1] = -py * scale;
					g[v * 3 + 2] = -pz * scale;
					uv[v * 2] = in.readFloat();
					uv[v * 2 + 1] = in.readFloat();
					w[v] = Math.min(1f, Math.abs(px) / span);
					sideSum += px;
				}
				int[] tris = new int[in.readInt()];
				for (int i = 0; i < tris.length; i++) {
					tris[i] = in.readInt();
				}
				halves[h] = new Half(sideSum < 0 ? -1 : 1, g, uv, w, tris);
			}
			Model model = new Model(halves, new Image(png));
			MODELS.put(id, model);
			return model;
		} catch (Exception e) {
			return null;
		}
	}

	/** One frame: ease towards the motion's wingbeat, then pose each half's transform. */
	private void step(long now) {
		// Never backwards: show() steps with System.nanoTime(), the timer's next pulse can carry an older stamp.
		float dt = last == 0 ? 0 : Math.max(0f, Math.min(0.1f, (now - last) / 1e9f));
		last = now;
		if (model == null) {
			return;
		}
		float targetSweep;
		float targetAmp;
		float targetFreq;
		float targetLift;
		switch (motion) {
			case SWIM -> {
				targetSweep = 1.0f;
				targetAmp = 0.2f;
				targetFreq = 0.55f;
				targetLift = -0.12f;
			}
			case GLIDE -> {
				targetSweep = -0.08f;
				targetAmp = 0.07f;
				targetFreq = 1.2f;
				targetLift = 0.06f;
			}
			case SNEAK -> {
				targetSweep = 0.95f;
				targetAmp = 0.08f;
				targetFreq = 0.3f;
				targetLift = -0.2f;
			}
			case FLY -> {
				targetSweep = 0.1f;
				targetAmp = 0.6f;
				targetFreq = 0.95f;
				targetLift = 0.1f;
			}
			case WALK -> {
				targetSweep = 0.33f;
				targetAmp = 0.43f;
				targetFreq = 0.68f;
				targetLift = 0.05f;
			}
			default -> {
				targetSweep = 0.45f;
				targetAmp = 0.24f;
				targetFreq = 0.28f;
				targetLift = 0.05f;
			}
		}
		float ampScale = switch (type.style()) {
			case "bat" -> 1.1f;
			case "crystal" -> 1.3f;
			case "tentacle" -> 0.6f;
			default -> 1f;
		};
		float speedScale = switch (type.style()) {
			case "bat" -> 1.15f;
			case "crystal" -> 0.9f;
			case "tentacle" -> 0.8f;
			default -> 1f;
		};
		float flex = switch (type.style()) {
			case "bat" -> 0.6f;
			case "crystal" -> 0.5f;
			case "tentacle" -> 0.3f;
			default -> 1f;
		};
		targetAmp *= ampScale * settings.flapStrength();
		targetSweep += (float) Math.toRadians(settings.spread());
		targetFreq *= speedScale * settings.flapSpeed();
		float k = 1f - (float) Math.exp(-dt / 0.35f);
		sweep += (targetSweep - sweep) * k;
		amplitude += (targetAmp - amplitude) * k;
		frequency += (targetFreq - frequency) * k;
		lift += (targetLift - lift) * k;
		phase = (phase + dt * frequency * Math.PI * 2) % (Math.PI * 2);
		float flap = (float) (0.5 - 0.5 * Math.cos(phase));
		float poseSweep = sweep + amplitude * (flap - 0.35f);
		float poseLift = lift - amplitude * 0.12f * (float) Math.sin(phase);
		float bend = -amplitude * 0.25f * flex * (float) Math.sin(phase);

		if (arms != null) {
			float speed = switch (motion) {
				case WALK -> 0.85f;
				case FLY -> 1.2f;
				case GLIDE -> 1.4f;
				case SNEAK -> 0.4f;
				case SWIM -> 1.2f;
				default -> 0.55f;
			};
			writheClock = (writheClock + dt * speed * settings.flapSpeed()) % WingRigs.Arms.LOOP;
			for (int h = 0; h < arms.length && h < boneTransforms.length; h++) {
				if (arms[h] == null || boneTransforms[h] == null) {
					continue;
				}
				float[] b = bonePose[h] = WingRigs.bones(arms[h], WRITHE_AMOUNT, (float) writheClock, model.halves[h].side, bonePose[h]);
				Affine[] moves = boneTransforms[h];
				for (int bone = 0; bone < moves.length; bone++) {
					if (moves[bone] != null) {
						int o = bone * 12;
						moves[bone].setToTransform(b[o], b[o + 1], b[o + 2], b[o + 9], b[o + 3], b[o + 4], b[o + 5], b[o + 10],
								b[o + 6], b[o + 7], b[o + 8], b[o + 11]);
					}
				}
			}
		}
		fxSeconds = (fxSeconds + dt) % 3400.0;
		int tint = recolorable ? WingRigs.tint(recolorId, settings.color(), (float) fxSeconds) : -1;
		if (recolorable && tint != shownTint && wingMaterial != null) {
			shownTint = tint;
			int c = tint < 0 ? 0xFFFFFF : tint;
			wingMaterial.setDiffuseColor(javafx.scene.paint.Color.rgb((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF));
		}
		WingRigs.Glow drawnGlow = glow != null && tint >= 0 ? WingRigs.tinted(glow, tint) : glow;
		if (glowStrip != null) {
			javafx.scene.image.PixelWriter writer = glowStrip.getPixelWriter();
			for (int y = 0; y < GLOW_H; y++) {
				for (int x = 0; x < GLOW_W; x++) {
					int c = WingRigs.glowColor(drawnGlow, x / (GLOW_W - 1f), y / (GLOW_H - 1f), (float) fxSeconds);
					int alpha = c >>> 24;
					// Nothing to add where nothing glows.
					writer.setArgb(x, y, alpha << 24 | ((c >> 16) & 0xFF) * alpha / 255 << 16 | ((c >> 8) & 0xFF) * alpha / 255 << 8 | (c & 0xFF) * alpha / 255);
				}
			}
		}
		float[] out = new float[7];
		for (int p = 0; p < particleTransforms.length; p++) {
			int h = particleHalf[p];
			int[] sources = particleSources[h];
			WingRigs.particle(particleKinds[p], particleIndex[p], h, (float) fxSeconds, sources.length, out);
			Affine move = particleTransforms[p];
			if (out[5] < 0.02f || out[4] <= 0) {
				move.setToTransform(1e-4, 0, 0, 0, 0, 1e-4, 0, 0, 0, 0, 1e-4, 0);
				continue;
			}
			int v = sources[(int) out[0]];
			float[] rest = model.halves[h].geometry;
			double x = rest[v * 3];
			double y = rest[v * 3 + 1];
			double z = rest[v * 3 + 2];
			int bone = arms != null && arms[h] != null ? WingRigs.bone(arms[h], v) : -1;
			if (bone >= 0 && h < bonePose.length && bonePose[h] != null) {
				float[] b = bonePose[h];
				int o = bone * 12;
				double bx = b[o] * x + b[o + 1] * y + b[o + 2] * z + b[o + 9];
				double by = b[o + 3] * x + b[o + 4] * y + b[o + 5] * z + b[o + 10];
				z = b[o + 6] * x + b[o + 7] * y + b[o + 8] * z + b[o + 11];
				x = bx;
				y = by;
			}
			// No see-through for 3D shapes: fading is shrinking.
			double s = out[4] * (0.35 + 0.65 * Math.min(1f, out[5]));
			double c = Math.cos(out[6]) * s;
			double sn = Math.sin(out[6]) * s;
			move.setToTransform(c, -sn, 0, x + out[1], sn, c, 0, y + out[2], 0, 0, s, z + out[3]);
		}
		if (shineStrip != null) {
			shineSeconds = (shineSeconds + dt) % 3400.0;
			javafx.scene.image.PixelWriter writer = shineStrip.getPixelWriter();
			for (int x = 0; x < SHINE_STRIP; x++) {
				float band = x / (SHINE_STRIP - 1f);
				float hot = WingRigs.glintCore(band, (float) shineSeconds);
				float a = Math.min(1f, hot + WingRigs.glint(band, (float) shineSeconds) * 0.45f);
				int alpha = Math.round(a * 235);
				// Tinted at the edges, white-hot in the core; nothing to add where there's no glint.
				float white = Math.min(1f, hot * 1.4f);
				int r = Math.round(((shineColor >> 16) & 0xFF) + (255 - ((shineColor >> 16) & 0xFF)) * white) * alpha / 255;
				int g = Math.round(((shineColor >> 8) & 0xFF) + (255 - ((shineColor >> 8) & 0xFF)) * white) * alpha / 255;
				int b = Math.round((shineColor & 0xFF) + (255 - (shineColor & 0xFF)) * white) * alpha / 255;
				writer.setArgb(x, 0, alpha << 24 | r << 16 | g << 8 | b);
			}
		}
		if (gearTransforms.length > 0) {
			gearAngle = (gearAngle + dt * (0.6 + 4.0 * amplitude * frequency) * settings.flapSpeed()) % (Math.PI * 2 * 84);
			for (int g = 0; g < gearTransforms.length; g++) {
				float px = gearInfo[g * 4];
				float py = gearInfo[g * 4 + 1];
				double a = gearInfo[g * 4 + 3] * gearAngle * gearInfo[g * 4 + 2];
				double c = Math.cos(a);
				double s = Math.sin(a);
				gearTransforms[g].setToTransform(c, -s, 0, px - c * px + s * py, s, c, 0, py - s * px - c * py, 0, 0, 1, 0);
			}
		}

		float attachY = ATTACH_Y + type.offsetY() + settings.offsetY();
		float attachZ = ATTACH_Z + settings.offsetZ();
		float size = settings.scale();
		for (int i = 0; i < model.halves.length && i < transforms.length; i++) {
			int s = model.halves[i].side;
			float l = -s * poseLift;
			float cosL = (float) Math.cos(l);
			float sinL = (float) Math.sin(l);
			float yaw = -s * (poseSweep + bend * BEND_AT * BEND_AT);
			float cy = (float) Math.cos(yaw);
			float sy = (float) Math.sin(yaw);
			// pixels = 16 * (RotY(yaw) * RotZ(lift) * size * p + shoulder) - (0, 8, 0), p in the half's own geometry.
			float px = 16 * size;
			transforms[i].setToTransform(
					px * cy * cosL, -px * cy * sinL, px * sy, 16 * s * SHOULDER_X,
					px * sinL, px * cosL, 0, 16 * attachY - 8,
					-px * sy * cosL, px * sy * sinL, px * cy, 16 * attachZ);
		}
	}
}
