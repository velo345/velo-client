package net.veloclient.launcher.ui;

import javafx.animation.AnimationTimer;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import net.veloclient.launcher.data.GifFrames;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * A real, reusable rotatable 3D render of a player's skin (built for the
 * profile view, meant to be reused anywhere else a skin preview is useful -
 * a future cosmetics store, etc). Six body-part boxes (head/body/arms/legs),
 * built from scratch as textured {@link TriangleMesh}es, vertex-for-vertex
 * ported from vanilla's own {@code ModelPart.Cuboid} bake (not re-derived by
 * hand) so each face samples the exact same UV region the real player model
 * does. Verified by an actual offscreen render (see the PR/commit history for
 * how) against both a synthetic per-face-labeled debug skin and a real
 * Mojang skin texture - not just reasoned about, since this class went
 * through two prior versions that looked correct on paper and weren't (one
 * had the UV rect formula itself wrong; the next divided every UV coordinate
 * by the *upscaled* texture's dimensions instead of the original skin's,
 * collapsing every part's sample into a sliver near the texture origin).
 *
 * <p>The second layer (hat/jacket/sleeves/pants) is rendered as real 3D voxels - every opaque
 * overlay pixel becomes a small block standing off the body, like the "3D Skin Layers" mod - built
 * as one opaque mesh (hidden sides between neighbouring pixels skipped), so it has none of the
 * transparency-sorting problems described next. Historically the layers were
 * pre-composited into one flattened texture per body part (alpha-blended
 * onto a copy of the base layer's own pixels) instead of rendering the
 * overlay as a separate second mesh - JavaFX's 3D pipeline has no
 * order-independent transparency, so two overlapping alpha meshes per part
 * produced z-fighting/draw-order artifacts. That flattened texture is then
 * upscaled with nearest-neighbor (not JavaFX's only-option bilinear 3D
 * texture filtering, which is what made the tiny 64px skin look smeared once
 * magnified onto a person-sized mesh) before becoming the material's
 * diffuse map - blowing it up first means the inevitable bilinear blending
 * only ever blends between same-colored blocks, so edges stay crisp.
 */
public final class PlayerSkin3DView {

	// Power-of-two texture size (8x a 64px skin lands exactly on 512) - some
	// GPU/driver combinations handle NPOT textures on 3D materials poorly, so
	// this avoids that risk even though it wasn't the cause of this class's
	// actual UV bugs (see addPart's uvW/uvH parameters for that).
	private static final int UPSCALE_FACTOR = 8;

	private PlayerSkin3DView() {
	}

	/**
	 * @param slim whether this is an Alex-style slim-arm skin (3px-wide arms)
	 * rather than the classic Steve-style 4px-wide arms - from the account's
	 * own skin variant, not guessable from the texture alone.
	 * @return a resizable {@link Node} that fills whatever size its parent
	 * gives it, or null if the skin couldn't be read. Drag horizontally to
	 * orbit (the model always stays upright - no tilt), scroll to zoom.
	 */
	public static Node createViewer(byte[] skinPngBytes, boolean slim) {
		return createViewer(skinPngBytes, slim, null);
	}

	/** Same as {@link #createViewer(byte[], boolean)}, with an optional cape rendered behind the back - the Store's "try before you buy" preview. A single-frame {@code capeFrames} renders as a static cape; more than one animates, cycling on each frame's own delay until the returned node leaves the scene. */
	public static Node createViewer(byte[] skinPngBytes, boolean slim, List<GifFrames.Frame> capeFrames) {
		return createViewer(skinPngBytes, slim, capeFrames, true);
	}

	/**
	 * @param interactive when false, drag-to-orbit and scroll-to-zoom are
	 *                    disabled entirely - the home screen's background
	 *                    player render is a fixed, slightly-turned display
	 *                    piece (not something meant to be spun around), while
	 *                    every other caller (Store preview, profile page)
	 *                    keeps the interactive behavior.
	 */
	public static Node createViewer(byte[] skinPngBytes, boolean slim, List<GifFrames.Frame> capeFrames, boolean interactive) {
		return createViewer(skinPngBytes, slim, capeFrames, interactive, 20, false);
	}

	/**
	 * Showcase variant for cosmetics: starts turned so the back (and cape) faces the viewer and
	 * slowly spins on its own; dragging takes over and the spin resumes a few seconds later.
	 */
	public static Node createShowcase(byte[] skinPngBytes, boolean slim, List<GifFrames.Frame> capeFrames) {
		return createViewer(skinPngBytes, slim, capeFrames, true, 150, true);
	}

	/**
	 * The Store's wings showcase: your skin, turned so the back faces you, slowly spinning, with a
	 * {@link Wings3D} on its back - {@code onWings} gets it to pick the pair and the motion.
	 */
	public static Node createWingsShowcase(byte[] skinPngBytes, boolean slim, java.util.function.Consumer<Wings3D> onWings) {
		return createViewer(skinPngBytes, slim, null, true, 155, true, model -> onWings.accept(new Wings3D(model)));
	}

	public static Node createViewer(byte[] skinPngBytes, boolean slim, List<GifFrames.Frame> capeFrames, boolean interactive,
			double initialYaw, boolean autoSpin) {
		return createViewer(skinPngBytes, slim, capeFrames, interactive, initialYaw, autoSpin, null);
	}

	/** {@code decorate}: adds more to the player model (wings), in its own space. */
	public static Node createViewer(byte[] skinPngBytes, boolean slim, List<GifFrames.Frame> capeFrames, boolean interactive,
			double initialYaw, boolean autoSpin, java.util.function.Consumer<Group> decorate) {
		if (skinPngBytes == null) {
			return null;
		}
		Image raw;
		try {
			raw = new Image(new ByteArrayInputStream(skinPngBytes));
		} catch (RuntimeException e) {
			return null;
		}
		if (raw.isError() || raw.getWidth() < 64 || raw.getHeight() < 32) {
			return null;
		}
		// Legacy 64x32 and HD (128x128, ...) skins both load: normalised to the modern square layout first.
		WritableImage skin = normalize(raw);
		if (skin == null) {
			return null;
		}
		// All UV / pixel coordinates below live in the 64x64 layout; an HD skin just has `scale` texels per unit.
		int scale = (int) (skin.getWidth() / 64);
		double uvW = 64;
		double uvH = 64;
		Image texture = upscale(skin, Math.max(1, UPSCALE_FACTOR / scale));
		double armWidth = slim ? 3 : 4;

		Group model = new Group();
		// Model space deliberately matches Minecraft's ModelPart convention: x = right(+)/left(-),
		// y = DOWN(+)/up(-) (same sense as JavaFX's screen Y), so the per-face UV correspondence
		// ported from vanilla needs no flip. Head has the most negative y, legs the largest.
		Part[] parts = {
				new Part(0, 0, 8, 8, 8, 0, -16, 0, 32, 0),
				new Part(16, 16, 8, 12, 4, 0, -8, 0, 16, 32),
				new Part(40, 16, armWidth, 12, 4, -(4 + armWidth / 2), -8, 0, 40, 32),
				new Part(32, 48, armWidth, 12, 4, 4 + armWidth / 2, -8, 0, 48, 48),
				new Part(0, 16, 4, 12, 4, -2, 4, 0, 0, 32),
				new Part(16, 48, 4, 12, 4, 2, 4, 0, 0, 48),
		};
		for (Part part : parts) {
			addPart(model, texture, uvW, uvH, part.u, part.v, part.w, part.h, part.d, part.cx, part.cy, part.cz);
		}
		MeshView layers = buildLayerVoxels(skin, uvW, uvH, parts, scale);
		if (layers != null) {
			model.getChildren().add(layers);
		}

		AnimationTimer capeTimer = addCape(model, capeFrames);
		if (decorate != null) {
			decorate.accept(model);
		}

		Group rig = new Group(model);

		// Yaw is the only interactive rotation - the model always stays
		// upright, it never tilts/pitches.
		Rotate yaw = new Rotate(initialYaw, Rotate.Y_AXIS);
		rig.getTransforms().add(yaw);

		PerspectiveCamera camera = new PerspectiveCamera(true);
		camera.setNearClip(0.1);
		camera.setFarClip(1000);
		camera.setTranslateZ(-80);
		camera.setFieldOfView(30);

		Group sceneRoot = new Group(rig, camera);
		sceneRoot.getChildren().addAll(
				new AmbientLight(Color.rgb(150, 150, 150)),
				keyLight(-1, -1, -1, 0.9),
				keyLight(1, -0.4, -1, 0.5));

		StackPane wrapper = new StackPane();
		SubScene subScene = new SubScene(sceneRoot, 10, 10, true, SceneAntialiasing.BALANCED);
		subScene.setFill(Color.TRANSPARENT);
		subScene.setCamera(camera);
		subScene.widthProperty().bind(wrapper.widthProperty());
		subScene.heightProperty().bind(wrapper.heightProperty());
		wrapper.getChildren().add(subScene);
		wrapper.setPickOnBounds(interactive);
		// For overlays drawn in the model's space (auras, see AuraFx).
		wrapper.getProperties().put("velo.yaw", yaw);
		wrapper.getProperties().put("velo.camera", camera);
		wrapper.getProperties().put("velo.subScene", subScene);

		long[] lastInteraction = {0};
		if (interactive) {
			double[] lastX = new double[1];
			wrapper.setOnMousePressed(e -> {
				lastX[0] = e.getSceneX();
				lastInteraction[0] = System.nanoTime();
			});
			wrapper.setOnMouseDragged(e -> {
				double dx = e.getSceneX() - lastX[0];
				yaw.setAngle(yaw.getAngle() + dx * 0.5);
				lastX[0] = e.getSceneX();
				lastInteraction[0] = System.nanoTime();
			});
			wrapper.addEventHandler(ScrollEvent.SCROLL, e -> {
				double z = camera.getTranslateZ() + e.getDeltaY() * 0.15;
				camera.setTranslateZ(Math.max(-180, Math.min(-35, z)));
				// Without this, the scroll also bubbled up to whatever ScrollPane
				// this viewer sits inside (the profile page) and scrolled that too.
				e.consume();
			});
		}

		if (autoSpin) {
			AnimationTimer spin = new AnimationTimer() {
				private long last;

				@Override
				public void handle(long now) {
					if (!RenderGate.active()) {
						return;
					}
					double dt = last == 0 ? 0 : Math.min(0.05, (now - last) / 1e9);
					last = now;
					if (now - lastInteraction[0] > 3_000_000_000L) {
						yaw.setAngle(yaw.getAngle() + dt * 18);
					}
				}
			};
			wrapper.sceneProperty().addListener((obs, oldScene, newScene) -> {
				if (newScene == null) {
					spin.stop();
				} else {
					spin.start();
				}
			});
		}

		if (capeTimer != null) {
			// AnimationTimer keeps running (and holding this whole viewer alive
			// via its own pulse-listener registration) until stopped explicitly -
			// tying that to the wrapper leaving the scene means switching away
			// from a Store preview/profile page actually stops the timer instead
			// of leaking one per visit.
			wrapper.sceneProperty().addListener((obs, oldScene, newScene) -> {
				if (newScene == null) {
					capeTimer.stop();
				}
			});
		}

		return wrapper;
	}

	/**
	 * Your skin doing an emote: like {@link #createViewer} but every body part sits in its own group around its joint
	 * (with its overlay voxels), posed every frame from the animation {@code animation} supplies (null = standing).
	 * The emote's particles rise as glyphs in the scene. Drag to turn, scroll to zoom.
	 */
	public static Node createEmoteViewer(byte[] skinPngBytes, boolean slim,
			java.util.function.Supplier<net.veloclient.launcher.data.EmoteCatalog.Animation> animation) {
		if (skinPngBytes == null) {
			return null;
		}
		Image raw;
		try {
			raw = new Image(new ByteArrayInputStream(skinPngBytes));
		} catch (RuntimeException e) {
			return null;
		}
		if (raw.isError() || raw.getWidth() < 64 || raw.getHeight() < 32) {
			return null;
		}
		WritableImage skin = normalize(raw);
		if (skin == null) {
			return null;
		}
		int scale = (int) (skin.getWidth() / 64);
		Image texture = upscale(skin, Math.max(1, UPSCALE_FACTOR / scale));
		double armWidth = slim ? 3 : 4;
		Part[] parts = {
				new Part(0, 0, 8, 8, 8, 0, -16, 0, 32, 0),
				new Part(16, 16, 8, 12, 4, 0, -8, 0, 16, 32),
				new Part(40, 16, armWidth, 12, 4, -(4 + armWidth / 2), -8, 0, 40, 32),
				new Part(32, 48, armWidth, 12, 4, 4 + armWidth / 2, -8, 0, 48, 48),
				new Part(0, 16, 4, 12, 4, -2, 4, 0, 0, 32),
				new Part(16, 48, 4, 12, 4, 2, 4, 0, 0, 48),
		};
		// Joints in this space (Minecraft's model space shifted up by 8: the neck is at y = -8).
		double[][] joints = {{0, -8, 0}, {0, -8, 0}, {-5, -6, 0}, {5, -6, 0}, {-1.9, 4, 0}, {1.9, 4, 0}};
		Group model = new Group();
		Group[] groups = new Group[6];
		javafx.scene.transform.Translate[] pivots = new javafx.scene.transform.Translate[6];
		Rotate[][] rotations = new Rotate[6][];
		for (int i = 0; i < parts.length; i++) {
			Part p = parts[i];
			Group g = new Group();
			Part local = new Part(p.u, p.v, p.w, p.h, p.d, p.cx - joints[i][0], p.cy - joints[i][1], p.cz - joints[i][2], p.ou, p.ov);
			addPart(g, texture, 64, 64, local.u, local.v, local.w, local.h, local.d, local.cx, local.cy, local.cz);
			MeshView layer = buildLayerVoxels(skin, 64, 64, new Part[] {local}, scale);
			if (layer != null) {
				g.getChildren().add(layer);
			}
			pivots[i] = new javafx.scene.transform.Translate(joints[i][0], joints[i][1], joints[i][2]);
			rotations[i] = new Rotate[] {new Rotate(0, Rotate.Z_AXIS), new Rotate(0, Rotate.Y_AXIS), new Rotate(0, Rotate.X_AXIS)};
			g.getTransforms().addAll(pivots[i], rotations[i][0], rotations[i][1], rotations[i][2]);
			groups[i] = g;
			model.getChildren().add(g);
		}
		Group rig = new Group(model);
		// a little turned, front towards the viewer (20 like the profile viewer)
		Rotate yaw = new Rotate(20, Rotate.Y_AXIS);
		rig.getTransforms().add(yaw);
		PerspectiveCamera camera = new PerspectiveCamera(true);
		camera.setNearClip(0.1);
		camera.setFarClip(1000);
		camera.setTranslateZ(-86);
		camera.setFieldOfView(30);
		Group particles = new Group();
		Group sceneRoot = new Group(rig, particles, camera);
		sceneRoot.getChildren().addAll(new AmbientLight(Color.rgb(150, 150, 150)), keyLight(-1, -1, -1, 0.9), keyLight(1, -0.4, -1, 0.5));
		StackPane wrapper = new StackPane();
		SubScene subScene = new SubScene(sceneRoot, 10, 10, true, SceneAntialiasing.BALANCED);
		subScene.setFill(Color.TRANSPARENT);
		subScene.setCamera(camera);
		subScene.widthProperty().bind(wrapper.widthProperty());
		subScene.heightProperty().bind(wrapper.heightProperty());
		wrapper.getChildren().add(subScene);
		double[] lastX = new double[1];
		wrapper.setOnMousePressed(e -> lastX[0] = e.getSceneX());
		wrapper.setOnMouseDragged(e -> {
			yaw.setAngle(yaw.getAngle() + (e.getSceneX() - lastX[0]) * 0.5);
			lastX[0] = e.getSceneX();
		});
		wrapper.addEventHandler(ScrollEvent.SCROLL, e -> {
			camera.setTranslateZ(Math.max(-180, Math.min(-40, camera.getTranslateZ() + e.getDeltaY() * 0.15)));
			e.consume();
		});
		float[] pose = EmoteFigureFx.REST.clone();
		java.util.Random random = new java.util.Random();
		AnimationTimer timer = new AnimationTimer() {
			private long start;
			private net.veloclient.launcher.data.EmoteCatalog.Animation shown;
			private double fired = -1;

			@Override
			public void handle(long now) {
				if (!RenderGate.active()) {
					return;
				}
				var a = animation.get();
				if (a != shown) {
					shown = a;
					start = now;
					fired = -1;
				}
				double t = (now - start) / 1e9;
				System.arraycopy(EmoteFigureFx.REST, 0, pose, 0, pose.length);
				if (a != null) {
					double cycle = a.duration() + (a.loop() ? 0 : 0.6);
					double local = t % cycle;
					if (local <= a.duration()) {
						a.pose(local * a.fps(), pose);
					}
					int first = (int) Math.floor(Math.max(0, fired) / cycle);
					int last = (int) Math.floor(t / cycle);
					for (int k = first; k <= last; k++) {
						for (var e : a.events()) {
							double at = k * cycle + e.t();
							if (e.particle() != null && at > fired && at <= t) {
								spawn(particles, e, EmoteFigureFx.anchor(pose, EmoteFigureFx.anchorOf(e.at())), Math.toRadians(yaw.getAngle()), random);
							}
						}
					}
					fired = t;
				}
				for (int i = 0; i < 6; i++) {
					int o = i * 6;
					pivots[i].setX(pose[o]);
					pivots[i].setY(pose[o + 1] - 8);
					pivots[i].setZ(pose[o + 2]);
					rotations[i][0].setAngle(Math.toDegrees(pose[o + 5]));
					rotations[i][1].setAngle(Math.toDegrees(pose[o + 4]));
					rotations[i][2].setAngle(Math.toDegrees(pose[o + 3]));
				}
				// particles rise and fade
				particles.getChildren().removeIf(n -> {
					double[] life = (double[]) n.getUserData();
					double age = (now - (long) life[0]) / 1e9;
					if (age > life[1]) {
						return true;
					}
					n.setTranslateX(life[2] + life[4] * age);
					n.setTranslateY(life[3] + life[5] * age);
					n.setOpacity(1 - age / life[1]);
					return false;
				});
			}
		};
		wrapper.sceneProperty().addListener((obs, o, n) -> {
			if (n == null) {
				timer.stop();
			} else {
				timer.start();
			}
		});
		return wrapper;
	}

	/** An emote particle as a glyph in the scene, at a model-space point turned with the viewer's yaw. */
	private static void spawn(Group particles, net.veloclient.launcher.data.EmoteCatalog.Event e, double[] point, double yaw, java.util.Random random) {
		int n = Math.min(6, Math.max(1, e.count() / 3));
		for (int i = 0; i < n; i++) {
			Object[] look = EmoteFigureFx.glyph(e);
			javafx.scene.text.Text glyph = new javafx.scene.text.Text((String) look[0]);
			glyph.setFill((Color) look[1]);
			glyph.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 3.2));
			double spread = Math.max(0.5, e.spread() * 16);
			double x = point[0] + (random.nextDouble() - 0.5) * spread;
			double y = point[1] - 8 + (random.nextDouble() - 0.5) * spread;
			double z = point[2] + (random.nextDouble() - 0.5) * spread;
			// the rig turns around Y: same rotation for the particle's start
			double rx = x * Math.cos(yaw) + z * Math.sin(yaw);
			double rz = -x * Math.sin(yaw) + z * Math.cos(yaw);
			glyph.setTranslateZ(rz - 2);
			glyph.setUserData(new double[] {System.nanoTime(), 0.9 + random.nextDouble() * 0.5, rx, y, (random.nextDouble() - 0.5) * 2,
					-3 - e.up() * 20 - random.nextDouble() * 2});
			particles.getChildren().add(glyph);
		}
		if (particles.getChildren().size() > 60) {
			particles.getChildren().remove(0, particles.getChildren().size() - 60);
		}
	}

	/**
	 * The real Minecraft cape model: a 10x16x1 box with the cape template's own UV layout (outside,
	 * inside, edges), hinged at the shoulders, hanging out from the back at a slight angle and
	 * swaying gently. Animated capes swap the texture per frame. Returns the driving timer.
	 */
	private static AnimationTimer addCape(Group parent, List<GifFrames.Frame> frames) {
		if (frames == null || frames.isEmpty()) {
			return null;
		}
		boolean animated = frames.size() > 1;
		CapeModel cape = capeModel(frames.get(0).image(), animated ? ANIMATED_TEXTURE_WIDTH : STILL_TEXTURE_WIDTH);
		parent.getChildren().add(cape.node);
		// Each viewer owns its frame textures (made on first show, dropped with the viewer) - a global cache
		// keyed on the decoded frames kept every frame of every cape ever previewed alive.
		// One material per frame, switched on the mesh: swapping the texture inside one material leaked
		// native memory in JavaFX's ES2 pipeline on every swap (see VoxelSceneryView's water).
		PhongMaterial[] materials = new PhongMaterial[frames.size()];
		materials[0] = cape.material;

		AnimationTimer timer = new AnimationTimer() {
			private int index;
			private long frameStartNanos = -1;

			@Override
			public void handle(long now) {
				if (!RenderGate.active()) {
					return;
				}
				cape.swing.setAngle(CAPE_REST_ANGLE + Math.sin(now / 9e8) * 3.5 + Math.sin(now / 3.7e8) * 0.8);
				if (frames.size() <= 1) {
					return;
				}
				if (frameStartNanos < 0) {
					frameStartNanos = now;
					return;
				}
				long elapsedMs = (now - frameStartNanos) / 1_000_000L;
				if (elapsedMs < frames.get(index).delayMillis()) {
					return;
				}
				frameStartNanos = now;
				index = (index + 1) % frames.size();
				if (materials[index] == null) {
					PhongMaterial material = new PhongMaterial();
					material.setDiffuseMap(capeTexture(frames.get(index).image(), ANIMATED_TEXTURE_WIDTH));
					materials[index] = material;
				}
				cape.mesh.setMaterial(materials[index]);
			}
		};
		timer.start();
		return timer;
	}

	private static final double CAPE_REST_ANGLE = 9;

	private record CapeModel(Group node, Rotate swing, PhongMaterial material, MeshView mesh) {
	}

	/** Builds the cape (texture in the standard 64x32 layout - or its {@link GifFrames#decodeCape} crop - at any resolution, upscaled to about {@code textureWidth}) hinged at y=0. */
	private static CapeModel capeModel(BufferedImage image, int textureWidth) {
		boolean region = GifFrames.isCapeRegion(image);
		double texW = region ? GifFrames.CAPE_REGION_WIDTH : 64;
		double texH = region ? GifFrames.CAPE_REGION_HEIGHT : 64.0 * image.getHeight() / Math.max(1, image.getWidth());
		MeshView mesh = new MeshView(buildBoxMesh(0, 0, 10, 16, 1, texW, texH));
		PhongMaterial material = new PhongMaterial();
		material.setDiffuseMap(capeTexture(image, textureWidth));
		mesh.setMaterial(material);
		mesh.setCullFace(CullFace.NONE);
		// The cape template's "outside" is its north face; turning the box around makes that face
		// point away from the body, exactly like vanilla's cape renderer does.
		mesh.getTransforms().addAll(new javafx.scene.transform.Translate(0, 0, 0.5), new Rotate(180, Rotate.Y_AXIS));
		Rotate swing = new Rotate(CAPE_REST_ANGLE, Rotate.X_AXIS);
		Group hinge = new Group(mesh);
		hinge.getTransforms().addAll(new javafx.scene.transform.Translate(0, -8, 2.05), swing);
		return new CapeModel(hinge, swing, material, mesh);
	}

	/**
	 * Texture widths. A still cape is one texture, so it gets 2048 px (at 512 an HD cape looked softer than in game).
	 * Animated capes keep a texture per frame: their frames are cape-region crops at the file's own resolution (352 px
	 * for a 1024 px cape), taken as they are - no resampling, so nothing blurs. Whole 1024 px layouts were 2 MB each,
	 * 80 MB per 40-frame cape. Thumbnails only need about twice their on-screen size; they used the
	 * 2048 path, so hovering one animated tile built 40 x 8 MB textures on the UI thread.
	 */
	private static final int STILL_TEXTURE_WIDTH = 2048;
	private static final int ANIMATED_TEXTURE_WIDTH = 512;

	/** Cape frame -> crisp texture, nearest-neighbour upscaled to about {@code targetWidth} so 3D filtering can't smear it. */
	private static Image capeTexture(BufferedImage image, int targetWidth) {
		int w = image.getWidth();
		int h = image.getHeight();
		int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
		int factor = Math.max(1, targetWidth / Math.max(1, w));
		return upscale(argb, w, h, factor);
	}

	/**
	 * A transparent 3D render of just the cape at a three-quarter angle - for cards and lists,
	 * where a live 3D view per item would be wasteful. Rendered once per call (FX thread).
	 */
	public static Image capeThumbnail(BufferedImage capeTexture, double width, double height) {
		// The cape covers about half the thumbnail: a texture twice the thumbnail's size is already more than enough texels.
		int textureWidth = (int) Math.min(1024, Math.max(256, Math.max(width, height) * 2));
		CapeModel cape = capeModel(capeTexture, textureWidth);
		cape.swing.setAngle(0);
		Group model = new Group(cape.node);
		// The outside of the cape faces +z (away from the body); turn it towards the camera at a three-quarter angle.
		model.getTransforms().addAll(new Rotate(180 - 32, Rotate.Y_AXIS));
		model.setTranslateY(0);
		Group root = new Group(model,
				new AmbientLight(Color.rgb(185, 185, 185)),
				keyLight(-1, -1, -1.2, 0.7),
				keyLight(1, -0.3, -1, 0.3));
		PerspectiveCamera camera = new PerspectiveCamera(true);
		camera.setFieldOfView(26);
		camera.setNearClip(0.1);
		camera.setFarClip(500);
		camera.setTranslateZ(-44);
		camera.setTranslateY(0);
		SubScene sub = new SubScene(root, width, height, true, SceneAntialiasing.BALANCED);
		sub.setFill(Color.TRANSPARENT);
		sub.setCamera(camera);
		// A SubScene renders through its own camera only inside a Scene - a throwaway offscreen one.
		javafx.scene.Scene holder = new javafx.scene.Scene(new Group(sub), width, height, Color.TRANSPARENT);
		javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
		params.setFill(Color.TRANSPARENT);
		Image image = sub.snapshot(params, null);
		holder.setRoot(new Group());
		return image;
	}

	// ---- 3D skin layers ----

	private record Part(int u, int v, double w, double h, double d, double cx, double cy, double cz, int ou, int ov) {
	}

	private static final double LAYER_THICKNESS = 0.5;
	private static final double LAYER_GAP = 0.02;

	/** One mesh with a small block for every opaque overlay pixel of every part (null if none). */
	private static MeshView buildLayerVoxels(Image skin, double uvW, double uvH, Part[] parts, int scale) {
		PixelReader reader = skin.getPixelReader();
		MeshBuilder list = new MeshBuilder();
		java.util.Map<Integer, Integer> palette = new java.util.LinkedHashMap<>();
		for (Part part : parts) {
			if (part.ou < 0) {
				continue;
			}
			double w = part.w;
			double h = part.h;
			double d = part.d;
			double x0 = -w / 2;
			double x1 = w / 2;
			double y0 = 0;
			double y1 = h;
			double z0 = -d / 2;
			double z1 = d / 2;
			double[] p1 = {x0, y0, z0};
			double[] p2 = {x1, y0, z0};
			double[] p3 = {x1, y1, z0};
			double[] p4 = {x0, y1, z0};
			double[] p5 = {x0, y0, z1};
			double[] p6 = {x1, y0, z1};
			double[] p7 = {x1, y1, z1};
			double[] p8 = {x0, y1, z1};
			double u = part.ou;
			double v = part.ov;
			double j = u;
			double k = u + d;
			double l = u + d + w;
			double m = u + d + w + w;
			double n = u + d + w + d;
			double o = u + d + w + d + w;
			double p = v;
			double q = v + d;
			double r = v + d + h;
			double[] offset = {part.cx, part.cy, part.cz};
			double[] center = {0, h / 2, 0};
			// Same six faces / corner order / UV rects as buildBoxMesh.
			Object[][] faces = {
					{p6, p5, p1, p2, k, p, l, q},
					{p3, p4, p8, p7, l, q, m, p},
					{p1, p5, p8, p4, j, q, k, r},
					{p2, p1, p4, p3, k, q, l, r},
					{p6, p2, p3, p7, l, q, n, r},
					{p5, p6, p7, p8, n, q, o, r},
			};
			for (Object[] face : faces) {
				addLayerFace(list, reader, (double[]) face[0], (double[]) face[1], (double[]) face[2],
						(double) face[4], (double) face[5], (double) face[6], (double) face[7], center, offset, uvW, uvH, scale, palette);
			}
		}
		if (list.points.isEmpty()) {
			return null;
		}
		TriangleMesh mesh = new TriangleMesh();
		mesh.getPoints().addAll(toFloatArray(list.points));
		mesh.getTexCoords().addAll(toFloatArray(list.texCoords));
		mesh.getFaces().addAll(toIntArray(list.faces));
		MeshView view = new MeshView(mesh);
		PhongMaterial material = new PhongMaterial();
		material.setDiffuseMap(paletteTexture(palette));
		view.setMaterial(material);
		view.setCullFace(CullFace.NONE);
		return view;
	}

	// The voxels' colours: one solid PALETTE_CELL-sized square each. JavaFX mipmaps 3D textures, and a voxel sampling
	// a single texel of the skin got blended with its transparent neighbours at the small mip levels these tiny
	// faces use - the second layer came out greyish next to the base skin. Solid cells keep the exact colour.
	private static final int PALETTE_SIZE = 512;
	private static final int PALETTE_CELL = 8;
	private static final int PALETTE_COLUMNS = PALETTE_SIZE / PALETTE_CELL;

	private static float[] paletteUv(java.util.Map<Integer, Integer> palette, int argb) {
		int max = PALETTE_COLUMNS * PALETTE_COLUMNS;
		Integer index = palette.get(argb);
		if (index == null) {
			index = palette.size() < max ? palette.size() : 0;
			if (palette.size() < max) {
				palette.put(argb, index);
			}
		}
		return new float[] {
				(float) ((index % PALETTE_COLUMNS) * PALETTE_CELL + PALETTE_CELL / 2.0) / PALETTE_SIZE,
				(float) ((index / PALETTE_COLUMNS) * PALETTE_CELL + PALETTE_CELL / 2.0) / PALETTE_SIZE};
	}

	private static Image paletteTexture(java.util.Map<Integer, Integer> palette) {
		WritableImage image = new WritableImage(PALETTE_SIZE, PALETTE_SIZE);
		javafx.scene.image.PixelWriter writer = image.getPixelWriter();
		int[] cell = new int[PALETTE_CELL * PALETTE_CELL];
		for (var entry : palette.entrySet()) {
			java.util.Arrays.fill(cell, entry.getKey());
			int i = entry.getValue();
			writer.setPixels((i % PALETTE_COLUMNS) * PALETTE_CELL, (i / PALETTE_COLUMNS) * PALETTE_CELL, PALETTE_CELL, PALETTE_CELL,
					javafx.scene.image.PixelFormat.getIntArgbInstance(), cell, 0, PALETTE_CELL);
		}
		return image;
	}

	private static void addLayerFace(MeshBuilder list, PixelReader reader, double[] c0, double[] c1, double[] c2,
			double u1, double v1, double u2, double v2, double[] center, double[] offset, double uvW, double uvH, int scale,
			java.util.Map<Integer, Integer> palette) {
		int minU = (int) Math.min(u1, u2);
		int maxU = (int) Math.max(u1, u2);
		int minV = (int) Math.min(v1, v2);
		int maxV = (int) Math.max(v1, v2);
		// Outward normal of this (axis-aligned) face.
		double[] faceCenter = new double[3];
		for (int i = 0; i < 3; i++) {
			faceCenter[i] = (c0[i] + c2[i]) / 2 - center[i];
		}
		double[] normal = new double[3];
		int axis = Math.abs(faceCenter[0]) > Math.abs(faceCenter[1])
				? (Math.abs(faceCenter[0]) > Math.abs(faceCenter[2]) ? 0 : 2)
				: (Math.abs(faceCenter[1]) > Math.abs(faceCenter[2]) ? 1 : 2);
		normal[axis] = Math.signum(faceCenter[axis]);
		for (int tv = minV; tv < maxV; tv++) {
			for (int tu = minU; tu < maxU; tu++) {
				if (!opaque(reader, tu, tv, uvW, uvH, scale)) {
					continue;
				}
				double[] a = facePoint(c0, c1, c2, u1, v1, u2, v2, tu, tv);
				double[] b = facePoint(c0, c1, c2, u1, v1, u2, v2, tu + 1, tv);
				double[] c = facePoint(c0, c1, c2, u1, v1, u2, v2, tu + 1, tv + 1);
				double[] e = facePoint(c0, c1, c2, u1, v1, u2, v2, tu, tv + 1);
				double[][] inner = {a, b, c, e};
				double[][] outer = new double[4][];
				for (int i = 0; i < 4; i++) {
					inner[i] = add(add(inner[i], offset), scale(normal, LAYER_GAP));
					outer[i] = add(inner[i], scale(normal, LAYER_THICKNESS));
				}
				float[] uv = paletteUv(palette, reader.getArgb(tu * scale + scale / 2, tv * scale + scale / 2) | 0xFF000000);
				float su = uv[0];
				float sv = uv[1];
				addQuad(list, outer[0], outer[1], outer[2], outer[3], su, sv, normal);
				// Sides only where the neighbouring pixel on this face is empty.
				if (tv == minV || !opaque(reader, tu, tv - 1, uvW, uvH, scale)) {
					addQuad(list, inner[0], inner[1], outer[1], outer[0], su, sv, null);
				}
				if (tu == maxU - 1 || !opaque(reader, tu + 1, tv, uvW, uvH, scale)) {
					addQuad(list, inner[1], inner[2], outer[2], outer[1], su, sv, null);
				}
				if (tv == maxV - 1 || !opaque(reader, tu, tv + 1, uvW, uvH, scale)) {
					addQuad(list, inner[2], inner[3], outer[3], outer[2], su, sv, null);
				}
				if (tu == minU || !opaque(reader, tu - 1, tv, uvW, uvH, scale)) {
					addQuad(list, inner[3], inner[0], outer[0], outer[3], su, sv, null);
				}
			}
		}
	}

	/** Whether texel (x, y) of the 64x64 layout is opaque; on an HD skin the centre of its scale x scale block decides. */
	private static boolean opaque(PixelReader reader, int x, int y, double w, double h, int scale) {
		if (x < 0 || y < 0 || x >= w || y >= h) {
			return false;
		}
		return reader.getColor(x * scale + scale / 2, y * scale + scale / 2).getOpacity() > 0.05;
	}

	/** Point on a face for texel coordinate (U, V), using the face's own UV-to-corner mapping. */
	private static double[] facePoint(double[] c0, double[] c1, double[] c2, double u1, double v1, double u2, double v2, double tu, double tv) {
		double s = (tu - u1) / (u2 - u1);
		double t = (tv - v1) / (v2 - v1);
		return new double[] {
				c1[0] + s * (c0[0] - c1[0]) + t * (c2[0] - c1[0]),
				c1[1] + s * (c0[1] - c1[1]) + t * (c2[1] - c1[1]),
				c1[2] + s * (c0[2] - c1[2]) + t * (c2[2] - c1[2]),
		};
	}

	/** A quad sampling one texel; when {@code normal} is given, wound to face along it. */
	private static void addQuad(MeshBuilder list, double[] a, double[] b, double[] c, double[] d, float u, float v, double[] normal) {
		if (normal != null) {
			double[] n = cross(sub(b, a), sub(c, a));
			if (n[0] * normal[0] + n[1] * normal[1] + n[2] * normal[2] > 0) {
				double[] swap = b;
				b = d;
				d = swap;
			}
		}
		int pBase = list.points.size() / 3;
		for (double[] point : new double[][] {a, b, c, d}) {
			list.points.add(point[0]);
			list.points.add(point[1]);
			list.points.add(point[2]);
		}
		int tBase = list.texCoords.size() / 2;
		list.texCoords.add((double) u);
		list.texCoords.add((double) v);
		list.faces.add(pBase);
		list.faces.add(tBase);
		list.faces.add(pBase + 1);
		list.faces.add(tBase);
		list.faces.add(pBase + 2);
		list.faces.add(tBase);
		list.faces.add(pBase);
		list.faces.add(tBase);
		list.faces.add(pBase + 2);
		list.faces.add(tBase);
		list.faces.add(pBase + 3);
		list.faces.add(tBase);
	}

	private static double[] add(double[] a, double[] b) {
		return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
	}

	private static double[] sub(double[] a, double[] b) {
		return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static double[] scale(double[] a, double f) {
		return new double[] {a[0] * f, a[1] * f, a[2] * f};
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static WritableImage copyOf(Image image) {
		int w = (int) image.getWidth();
		int h = (int) image.getHeight();
		WritableImage out = new WritableImage(image.getPixelReader(), w, h);
		return out;
	}

	private static PointLight keyLight(double dirX, double dirY, double dirZ, double brightness) {
		PointLight light = new PointLight(Color.gray(brightness + 0.1));
		light.setTranslateX(dirX * 120);
		light.setTranslateY(dirY * 120);
		light.setTranslateZ(dirZ * 120);
		return light;
	}

	/**
	 * Any readable skin -> the modern layout (square, 64 x scale): a legacy 64x32 skin gets its left
	 * limbs mirrored from the right ones exactly like vanilla does, and its opaque "hat" area is
	 * dropped (vanilla's old transparency hack). Null when the size isn't a known skin size.
	 */
	private static WritableImage normalize(Image source) {
		int w = (int) source.getWidth();
		int h = (int) source.getHeight();
		if (w % 64 != 0) {
			return null;
		}
		int scale = w / 64;
		boolean legacy = h * 2 == w;
		if (!legacy && h != w) {
			return null;
		}
		WritableImage out = new WritableImage(w, w);
		PixelReader reader = source.getPixelReader();
		PixelWriter writer = out.getPixelWriter();
		writer.setPixels(0, 0, w, h, reader, 0, 0);
		if (legacy) {
			// Transparent below the old image, then the mirrored limbs.
			for (int y = h; y < w; y++) {
				for (int x = 0; x < w; x++) {
					writer.setColor(x, y, Color.TRANSPARENT);
				}
			}
			boolean hatSolid = true;
			for (int y = 0; y < 16 * scale && hatSolid; y++) {
				for (int x = 32 * scale; x < w; x++) {
					if (reader.getColor(x, y).getOpacity() < 0.5) {
						hatSolid = false;
						break;
					}
				}
			}
			if (hatSolid) {
				for (int y = 0; y < 16 * scale; y++) {
					for (int x = 32 * scale; x < w; x++) {
						writer.setColor(x, y, Color.TRANSPARENT);
					}
				}
			}
			int[][] copies = {
					{4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4}, {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12},
					{8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12},
					{44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4}, {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12},
					{48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12},
			};
			for (int[] c : copies) {
				for (int dy = 0; dy < c[5] * scale; dy++) {
					for (int dx = 0; dx < c[4] * scale; dx++) {
						int sx = c[0] * scale + dx;
						int sy = c[1] * scale + dy;
						int tx = (c[0] + c[2]) * scale + (c[4] * scale - 1 - dx);
						int ty = (c[1] + c[3]) * scale + dy;
						writer.setColor(tx, ty, reader.getColor(sx, sy));
					}
				}
			}
		}
		return out;
	}

	// ---- Texture compositing (merge overlay layer onto base layer, once, before any rendering) ----

	private static WritableImage compositeSkin(Image skin) {
		int w = (int) skin.getWidth();
		int h = (int) skin.getHeight();
		WritableImage out = new WritableImage(w, h);
		PixelReader reader = skin.getPixelReader();
		PixelWriter writer = out.getPixelWriter();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				writer.setColor(x, y, reader.getColor(x, y));
			}
		}
		if (h >= 64) {
			blendPart(reader, writer, 0, 0, 8, 8, 8, 32, 0); // hat -> head
			blendPart(reader, writer, 16, 16, 8, 12, 4, 16, 32); // jacket -> body
			blendPart(reader, writer, 40, 16, 4, 12, 4, 40, 32); // right sleeve -> right arm
			blendPart(reader, writer, 32, 48, 4, 12, 4, 48, 48); // left sleeve -> left arm
			blendPart(reader, writer, 0, 16, 4, 12, 4, 0, 32); // right pant -> right leg
			blendPart(reader, writer, 16, 48, 4, 12, 4, 0, 48); // left pant -> left leg
		}
		return out;
	}

	private static void blendPart(PixelReader reader, PixelWriter writer, int baseU, int baseV, int bw, int bh, int bd, int overlayU, int overlayV) {
		for (int[] baseRect : faceRectsPx(baseU, baseV, bw, bh, bd)) {
			int[] overlayRect = {baseRect[0] - baseU + overlayU, baseRect[1] - baseV + overlayV, baseRect[2], baseRect[3]};
			for (int dy = 0; dy < baseRect[3]; dy++) {
				for (int dx = 0; dx < baseRect[2]; dx++) {
					Color c = reader.getColor(overlayRect[0] + dx, overlayRect[1] + dy);
					if (c.getOpacity() > 0.05) {
						writer.setColor(baseRect[0] + dx, baseRect[1] + dy, c);
					}
				}
			}
		}
	}

	/** The union of face pixel-rects touched by a box's UV unwrap (used only for overlay compositing, not mesh UV - order/identity doesn't matter here). */
	private static int[][] faceRectsPx(int u, int v, int w, int h, int d) {
		return new int[][] {
				{u + d, v, w, d}, {u + d + w, v, w, d},
				{u, v + d, d, h}, {u + d, v + d, w, h}, {u + d + w, v + d, d, h}, {u + d + w + d, v + d, w, h},
		};
	}

	private static Image upscale(WritableImage source, int factor) {
		int sw = (int) source.getWidth();
		int sh = (int) source.getHeight();
		int[] argb = new int[sw * sh];
		source.getPixelReader().getPixels(0, 0, sw, sh, javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, sw);
		return upscale(argb, sw, sh, factor);
	}

	/** Nearest-neighbour upscale done on plain arrays and written in one go (per-pixel writes cost ~100 ms per texture). */
	private static Image upscale(int[] argb, int sw, int sh, int factor) {
		if (factor <= 1) {
			WritableImage out = new WritableImage(sw, sh);
			out.getPixelWriter().setPixels(0, 0, sw, sh, javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, sw);
			return out;
		}
		int ow = sw * factor;
		int oh = sh * factor;
		int[] big = new int[ow * oh];
		for (int y = 0; y < sh; y++) {
			int row = y * factor * ow;
			for (int x = 0; x < sw; x++) {
				java.util.Arrays.fill(big, row + x * factor, row + x * factor + factor, argb[y * sw + x]);
			}
			for (int dy = 1; dy < factor; dy++) {
				System.arraycopy(big, row, big, row + dy * ow, ow);
			}
		}
		WritableImage out = new WritableImage(ow, oh);
		out.getPixelWriter().setPixels(0, 0, ow, oh, javafx.scene.image.PixelFormat.getIntArgbInstance(), big, 0, ow);
		return out;
	}

	// ---- Mesh building - vertex-for-vertex port of vanilla ModelPart.Cuboid ----

	private static void addPart(Group parent, Image texture, double uvW, double uvH, int u, int v, double w, double h, double d, double cx, double cy, double cz) {
		TriangleMesh mesh = buildBoxMesh(u, v, w, h, d, uvW, uvH);
		MeshView view = new MeshView(mesh);
		PhongMaterial material = new PhongMaterial();
		material.setDiffuseMap(texture);
		view.setMaterial(material);
		// NONE rather than BACK: this is a direct port of vanilla's own quad
		// vertex order, which vanilla's renderer doesn't rely on face culling
		// to interpret the same way OpenGL/JavaFX's default right-hand-rule
		// culling would - trusting a guessed winding here risks silently
		// culling the "wrong" (visible) side per face again. Both sides are
		// cheap to draw for six small boxes.
		view.setCullFace(CullFace.NONE);
		view.setTranslateX(cx);
		view.setTranslateY(cy);
		view.setTranslateZ(cz);
		parent.getChildren().add(view);
	}

	/**
	 * One box, {@code w}x{@code h}x{@code d} model units, built exactly like
	 * {@code ModelPart.Cuboid}'s constructor: same 8 corners (here centered on
	 * X/Z instead of vanilla's min-corner-at-origin, purely a coordinate
	 * shift - {@link #addPart} then translates it into place), same six
	 * per-face vertex groupings, same UV remap per vertex.
	 */
	private static TriangleMesh buildBoxMesh(double u, double v, double w, double h, double d, double texW, double texH) {
		double x0 = -w / 2, x1 = w / 2;
		double y0 = 0, y1 = h;
		double z0 = -d / 2, z1 = d / 2;

		// Named to match vanilla's vertex/vertex2/.../vertex8 exactly.
		double[] p1 = {x0, y0, z0};
		double[] p2 = {x1, y0, z0};
		double[] p3 = {x1, y1, z0};
		double[] p4 = {x0, y1, z0};
		double[] p5 = {x0, y0, z1};
		double[] p6 = {x1, y0, z1};
		double[] p7 = {x1, y1, z1};
		double[] p8 = {x0, y1, z1};

		double j = u;
		double k = u + d;
		double l = u + d + w;
		double m = u + d + w + w;
		double n = u + d + w + d;
		double o = u + d + w + d + w;
		double p = v;
		double q = v + d;
		double r = v + d + h;

		TriangleMesh mesh = new TriangleMesh();
		MeshBuilder list = new MeshBuilder();

		// DOWN
		addFace(list, p6, p5, p1, p2, k, p, l, q, texW, texH);
		// UP
		addFace(list, p3, p4, p8, p7, l, q, m, p, texW, texH);
		// WEST (right, viewer's left when facing the model)
		addFace(list, p1, p5, p8, p4, j, q, k, r, texW, texH);
		// NORTH (front)
		addFace(list, p2, p1, p4, p3, k, q, l, r, texW, texH);
		// EAST (left)
		addFace(list, p6, p2, p3, p7, l, q, n, r, texW, texH);
		// SOUTH (back)
		addFace(list, p5, p6, p7, p8, n, q, o, r, texW, texH);

		mesh.getPoints().addAll(toFloatArray(list.points));
		mesh.getTexCoords().addAll(toFloatArray(list.texCoords));
		mesh.getFaces().addAll(toIntArray(list.faces));
		return mesh;
	}

	/** One quad's 4 corners (already in vanilla's own winding order) as two triangles, each vertex carrying its own point + UV. */
	private static void addFace(MeshBuilder list, double[] c0, double[] c1, double[] c2, double[] c3,
			double u1, double v1, double u2, double v2, double texW, double texH) {
		int pBase = list.points.size() / 3;
		list.points.add(c0[0]); list.points.add(c0[1]); list.points.add(c0[2]);
		list.points.add(c1[0]); list.points.add(c1[1]); list.points.add(c1[2]);
		list.points.add(c2[0]); list.points.add(c2[1]); list.points.add(c2[2]);
		list.points.add(c3[0]); list.points.add(c3[1]); list.points.add(c3[2]);

		int tBase = list.texCoords.size() / 2;
		// remap(): [0]=(u2,v1) [1]=(u1,v1) [2]=(u1,v2) [3]=(u2,v2)
		list.texCoords.add(u2 / texW); list.texCoords.add(v1 / texH);
		list.texCoords.add(u1 / texW); list.texCoords.add(v1 / texH);
		list.texCoords.add(u1 / texW); list.texCoords.add(v2 / texH);
		list.texCoords.add(u2 / texW); list.texCoords.add(v2 / texH);

		list.faces.add(pBase); list.faces.add(tBase);
		list.faces.add(pBase + 1); list.faces.add(tBase + 1);
		list.faces.add(pBase + 2); list.faces.add(tBase + 2);

		list.faces.add(pBase); list.faces.add(tBase);
		list.faces.add(pBase + 2); list.faces.add(tBase + 2);
		list.faces.add(pBase + 3); list.faces.add(tBase + 3);
	}

	private static float[] toFloatArray(java.util.List<Double> values) {
		float[] result = new float[values.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = values.get(i).floatValue();
		}
		return result;
	}

	private static int[] toIntArray(java.util.List<Integer> values) {
		int[] result = new int[values.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = values.get(i);
		}
		return result;
	}

	/** Plain mutable accumulator - avoids re-deriving array offsets by hand across the six {@link #addFace} calls per box. */
	private static final class MeshBuilder {
		final java.util.List<Double> points = new java.util.ArrayList<>();
		final java.util.List<Double> texCoords = new java.util.ArrayList<>();
		final java.util.List<Integer> faces = new java.util.ArrayList<>();
	}
}
