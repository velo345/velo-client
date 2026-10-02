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

	public static Node createViewer(byte[] skinPngBytes, boolean slim, List<GifFrames.Frame> capeFrames, boolean interactive,
			double initialYaw, boolean autoSpin) {
		if (skinPngBytes == null) {
			return null;
		}
		Image skin = new Image(new ByteArrayInputStream(skinPngBytes));
		if (skin.isError() || skin.getWidth() < 64 || skin.getHeight() < 64) {
			return null;
		}
		// UV coordinates below are computed in the skin's own original
		// 64x64-ish pixel space (uvW/uvH) - the *bound* diffuse map is the
		// upscaled 512x512 texture, but UV is a resolution-independent
		// fraction, so normalizing against the upscaled size instead of the
		// space the u/v/w/h/d box parameters are actually written in would
		// (and did, in an earlier version of this file) divide every
		// coordinate by 8x too much, collapsing every box's texture sample
		// into a tiny sliver near the texture's top-left corner.
		double uvW = skin.getWidth();
		double uvH = skin.getHeight();
		Image texture = upscale(copyOf(skin), UPSCALE_FACTOR);
		double armWidth = slim ? 3 : 4;

		Group model = new Group();
		// Model space deliberately matches Minecraft's ModelPart convention: x = right(+)/left(-),
		// y = DOWN(+)/up(-) (same sense as JavaFX's screen Y), so the per-face UV correspondence
		// ported from vanilla needs no flip. Head has the most negative y, legs the largest.
		boolean modern = skin.getHeight() >= 64;
		Part[] parts = {
				new Part(0, 0, 8, 8, 8, 0, -16, 0, 32, 0),
				new Part(16, 16, 8, 12, 4, 0, -8, 0, 16, 32),
				new Part(40, 16, armWidth, 12, 4, -(4 + armWidth / 2), -8, 0, 40, 32),
				new Part(32, 48, armWidth, 12, 4, 4 + armWidth / 2, -8, 0, 48, 48),
				new Part(0, 16, 4, 12, 4, -2, 4, 0, 0, 32),
				new Part(16, 48, 4, 12, 4, 2, 4, 0, 0, 48),
		};
		if (!modern) {
			// Legacy 64x32 skins: no separate left limbs (mirrored from the right) and only a hat layer.
			parts[3] = new Part(40, 16, armWidth, 12, 4, 4 + armWidth / 2, -8, 0, -1, -1);
			parts[5] = new Part(0, 16, 4, 12, 4, 2, 4, 0, -1, -1);
			parts[1] = new Part(16, 16, 8, 12, 4, 0, -8, 0, -1, -1);
			parts[2] = new Part(40, 16, armWidth, 12, 4, -(4 + armWidth / 2), -8, 0, -1, -1);
			parts[4] = new Part(0, 16, 4, 12, 4, -2, 4, 0, -1, -1);
		}
		for (Part part : parts) {
			addPart(model, texture, uvW, uvH, part.u, part.v, part.w, part.h, part.d, part.cx, part.cy, part.cz);
		}
		MeshView layers = buildLayerVoxels(skin, texture, uvW, uvH, parts);
		if (layers != null) {
			model.getChildren().add(layers);
		}

		AnimationTimer capeTimer = addCape(model, capeFrames);

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
	 * The real Minecraft cape model: a 10x16x1 box with the cape template's own UV layout (outside,
	 * inside, edges), hinged at the shoulders, hanging out from the back at a slight angle and
	 * swaying gently. Animated capes swap the texture per frame. Returns the driving timer.
	 */
	private static AnimationTimer addCape(Group parent, List<GifFrames.Frame> frames) {
		if (frames == null || frames.isEmpty()) {
			return null;
		}
		CapeModel cape = capeModel(frames.get(0).image());
		parent.getChildren().add(cape.node);

		AnimationTimer timer = new AnimationTimer() {
			private int index;
			private long frameStartNanos = -1;

			@Override
			public void handle(long now) {
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
				cape.material.setDiffuseMap(capeTexture(frames.get(index).image()));
			}
		};
		timer.start();
		return timer;
	}

	private static final double CAPE_REST_ANGLE = 9;

	private record CapeModel(Group node, Rotate swing, PhongMaterial material) {
	}

	/** Builds the cape (texture in the standard 64x32 layout at any resolution) hinged at y=0. */
	private static CapeModel capeModel(BufferedImage image) {
		double texW = 64;
		double texH = 64.0 * image.getHeight() / Math.max(1, image.getWidth());
		MeshView mesh = new MeshView(buildBoxMesh(0, 0, 10, 16, 1, texW, texH));
		PhongMaterial material = new PhongMaterial();
		material.setDiffuseMap(capeTexture(image));
		mesh.setMaterial(material);
		mesh.setCullFace(CullFace.NONE);
		// The cape template's "outside" is its north face; turning the box around makes that face
		// point away from the body, exactly like vanilla's cape renderer does.
		mesh.getTransforms().addAll(new javafx.scene.transform.Translate(0, 0, 0.5), new Rotate(180, Rotate.Y_AXIS));
		Rotate swing = new Rotate(CAPE_REST_ANGLE, Rotate.X_AXIS);
		Group hinge = new Group(mesh);
		hinge.getTransforms().addAll(new javafx.scene.transform.Translate(0, -8, 2.05), swing);
		return new CapeModel(hinge, swing, material);
	}

	/** Cape frame -> crisp texture (nearest-neighbour upscaled so 3D filtering can't smear it). */
	private static Image capeTexture(BufferedImage image) {
		Image fx = SwingFXUtils.toFXImage(image, null);
		int factor = Math.max(1, 512 / Math.max(1, image.getWidth()));
		return factor > 1 ? upscale(copyOf(fx), factor) : fx;
	}

	/**
	 * A transparent 3D render of just the cape at a three-quarter angle - for cards and lists,
	 * where a live 3D view per item would be wasteful. Rendered once per call (FX thread).
	 */
	public static Image capeThumbnail(BufferedImage capeTexture, double width, double height) {
		CapeModel cape = capeModel(capeTexture);
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
	private static MeshView buildLayerVoxels(Image skin, Image texture, double uvW, double uvH, Part[] parts) {
		PixelReader reader = skin.getPixelReader();
		MeshBuilder list = new MeshBuilder();
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
						(double) face[4], (double) face[5], (double) face[6], (double) face[7], center, offset, uvW, uvH);
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
		material.setDiffuseMap(texture);
		view.setMaterial(material);
		view.setCullFace(CullFace.NONE);
		return view;
	}

	private static void addLayerFace(MeshBuilder list, PixelReader reader, double[] c0, double[] c1, double[] c2,
			double u1, double v1, double u2, double v2, double[] center, double[] offset, double uvW, double uvH) {
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
				if (!opaque(reader, tu, tv, uvW, uvH)) {
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
				float su = (float) ((tu + 0.5) / uvW);
				float sv = (float) ((tv + 0.5) / uvH);
				addQuad(list, outer[0], outer[1], outer[2], outer[3], su, sv, normal);
				// Sides only where the neighbouring pixel on this face is empty.
				if (tv == minV || !opaque(reader, tu, tv - 1, uvW, uvH)) {
					addQuad(list, inner[0], inner[1], outer[1], outer[0], su, sv, null);
				}
				if (tu == maxU - 1 || !opaque(reader, tu + 1, tv, uvW, uvH)) {
					addQuad(list, inner[1], inner[2], outer[2], outer[1], su, sv, null);
				}
				if (tv == maxV - 1 || !opaque(reader, tu, tv + 1, uvW, uvH)) {
					addQuad(list, inner[2], inner[3], outer[3], outer[2], su, sv, null);
				}
				if (tu == minU || !opaque(reader, tu - 1, tv, uvW, uvH)) {
					addQuad(list, inner[3], inner[0], outer[0], outer[3], su, sv, null);
				}
			}
		}
	}

	private static boolean opaque(PixelReader reader, int x, int y, double w, double h) {
		if (x < 0 || y < 0 || x >= w || y >= h) {
			return false;
		}
		return reader.getColor(x, y).getOpacity() > 0.05;
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
		WritableImage out = new WritableImage(sw * factor, sh * factor);
		PixelReader reader = source.getPixelReader();
		PixelWriter writer = out.getPixelWriter();
		for (int y = 0; y < sh; y++) {
			for (int x = 0; x < sw; x++) {
				Color c = reader.getColor(x, y);
				for (int dy = 0; dy < factor; dy++) {
					for (int dx = 0; dx < factor; dx++) {
						writer.setColor(x * factor + dx, y * factor + dy, c);
					}
				}
			}
		}
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
