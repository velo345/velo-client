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
 */
public final class Wings3D {

	/** What the preview player is doing, like the game's showcase modes. */
	public enum Motion { IDLE, WALK, FLY, GLIDE, SNEAK, SWIM }

	private record Half(int side, float[] geometry, float[] uv, float[] weight, int[] triangles) {
	}

	private record Model(Half[] halves, Image texture) {
	}

	private static final Map<String, Model> MODELS = new HashMap<>();
	private static final float ATTACH_Y = 0.30f;
	private static final float ATTACH_Z = 0.17f;
	private static final float SHOULDER_X = 0.05f;

	private final Group node = new Group();
	private final AnimationTimer timer;
	private Model model;
	private WingsCatalog.WingType type;
	/** One transform per half, in model order; the meshes themselves never change. */
	private Affine[] transforms = new Affine[0];
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
				if (!RenderGate.active()) {
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
		MeshView[] views = new MeshView[m.halves.length];
		transforms = new Affine[m.halves.length];
		for (int i = 0; i < m.halves.length; i++) {
			Half h = m.halves[i];
			TriangleMesh mesh = new TriangleMesh();
			mesh.getPoints().setAll(h.geometry);
			mesh.getTexCoords().setAll(h.uv);
			int[] faces = new int[h.triangles.length * 2];
			for (int t = 0; t < h.triangles.length; t++) {
				faces[t * 2] = h.triangles[t];
				faces[t * 2 + 1] = h.triangles[t];
			}
			mesh.getFaces().setAll(faces);
			MeshView view = new MeshView(mesh);
			PhongMaterial material = new PhongMaterial();
			material.setDiffuseMap(m.texture);
			material.setSpecularColor(javafx.scene.paint.Color.rgb(40, 40, 40));
			view.setMaterial(material);
			view.setCullFace(CullFace.NONE);
			transforms[i] = new Affine();
			view.getTransforms().setAll(transforms[i]);
			views[i] = view;
		}
		node.getChildren().setAll(views);
		step(System.nanoTime());
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
		float dt = last == 0 ? 0 : Math.min(0.1f, (now - last) / 1e9f);
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
			default -> 1f;
		};
		float speedScale = switch (type.style()) {
			case "bat" -> 1.15f;
			case "crystal" -> 0.9f;
			default -> 1f;
		};
		float flex = switch (type.style()) {
			case "bat" -> 0.6f;
			case "crystal" -> 0.5f;
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
