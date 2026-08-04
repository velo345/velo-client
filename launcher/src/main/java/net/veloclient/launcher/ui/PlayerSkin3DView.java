package net.veloclient.launcher.ui;

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
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;

import java.io.ByteArrayInputStream;

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
 * <p>The base and second-layer (hat/jacket/sleeves/pants) textures are
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
		Image texture = upscale(compositeSkin(skin), UPSCALE_FACTOR);
		double armWidth = slim ? 3 : 4;

		Group model = new Group();
		// Model space here deliberately matches Minecraft's own ModelPart
		// convention exactly: x = right(+)/left(-), y = DOWN(+)/up(-) (same
		// sense as JavaFX's own screen Y), z = front(+)/back(-). An earlier
		// version treated +y as "up" for part placement while the per-face UV
		// correspondence was ported verbatim from Minecraft's y-down
		// convention, then tried to reconcile the two with a blanket
		// rig.setScaleY(-1) - that flipped vertex positions but not which
		// texture edge each vertex's UV pointed at, so every face rendered
		// vertically mirrored (confirmed by rendering an actual
		// orientation-labeled test texture, not just reasoned about - see
		// this class's own javadoc). Placing parts with y-down from the start
		// needs no such flip: head has the smallest (most negative) y, legs
		// the largest, vertical range still symmetric around y=0 so the
		// camera aiming at the origin sees the model centered.
		addPart(model, texture, uvW, uvH, 0, 0, 8, 8, 8, 0, -16, 0);
		addPart(model, texture, uvW, uvH, 16, 16, 8, 12, 4, 0, -8, 0);
		addPart(model, texture, uvW, uvH, 40, 16, armWidth, 12, 4, -(4 + armWidth / 2), -8, 0);
		addPart(model, texture, uvW, uvH, 32, 48, armWidth, 12, 4, 4 + armWidth / 2, -8, 0);
		addPart(model, texture, uvW, uvH, 0, 16, 4, 12, 4, -2, 4, 0);
		addPart(model, texture, uvW, uvH, 16, 48, 4, 12, 4, 2, 4, 0);

		Group rig = new Group(model);

		// Yaw is the only interactive rotation - the model always stays
		// upright, it never tilts/pitches.
		Rotate yaw = new Rotate(20, Rotate.Y_AXIS);
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
		wrapper.setPickOnBounds(true);

		double[] lastX = new double[1];
		wrapper.setOnMousePressed(e -> lastX[0] = e.getSceneX());
		wrapper.setOnMouseDragged(e -> {
			double dx = e.getSceneX() - lastX[0];
			yaw.setAngle(yaw.getAngle() + dx * 0.5);
			lastX[0] = e.getSceneX();
		});
		wrapper.addEventHandler(ScrollEvent.SCROLL, e -> {
			double z = camera.getTranslateZ() + e.getDeltaY() * 0.15;
			camera.setTranslateZ(Math.max(-180, Math.min(-35, z)));
			// Without this, the scroll also bubbled up to whatever ScrollPane
			// this viewer sits inside (the profile page) and scrolled that too.
			e.consume();
		});

		return wrapper;
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
