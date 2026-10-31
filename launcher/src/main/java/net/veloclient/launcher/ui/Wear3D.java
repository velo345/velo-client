package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import net.veloclient.launcher.data.WearCatalog;
import net.veloclient.launcher.data.WingsCatalog;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Bodywear on the launcher's player model ({@link PlayerSkin3DView}): each worn item's mesh on its bone, its texture
 * recoloured like in the game. Rebuilds itself whenever what's worn, a colour or the try-on changes, and stops
 * listening when it leaves the screen. Meshes and textures load off the UI thread and are cached.
 *
 * <p>The model's space is Minecraft's model space moved up 8 pixels (the neck at y = -8), so a bone-space vertex goes
 * to bone pivot + vertex: head and body (0, -8, 0), arms (±5, -6, 0), legs (±2, 4, 0).
 */
public final class Wear3D {

	private static final Map<String, TriangleMesh> MESHES = new HashMap<>();
	private static final Map<String, Image> TEXTURES = new HashMap<>();
	/** Every model's bodywear group on screen, for {@link #nudge}. */
	private static final java.util.Set<Group> HOLDERS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	private Wear3D() {
	}

	/** Adds the worn bodywear to {@code model} and keeps it up to date. */
	public static void attach(Group model, boolean slim) {
		Group holder = new Group();
		model.getChildren().add(holder);
		HOLDERS.add(holder);
		Runnable rebuild = () -> Platform.runLater(() -> build(holder, slim));
		holder.sceneProperty().addListener((obs, was, now) -> {
			if (now == null) {
				WearCatalog.LISTENERS.remove(rebuild);
			} else if (!WearCatalog.LISTENERS.contains(rebuild)) {
				WearCatalog.LISTENERS.add(rebuild);
			}
		});
		WearCatalog.LISTENERS.add(rebuild);
		build(holder, slim);
	}

	private static void build(Group holder, boolean slim) {
		var shown = WearCatalog.shown();
		java.util.List<WearCatalog.WearType> types = WearCatalog.all();
		Thread.ofVirtual().start(() -> {
			java.util.List<MeshView> views = new java.util.ArrayList<>();
			for (WearCatalog.Worn worn : shown) {
				WearCatalog.WearType type = types.stream().filter(t -> t.id().equals(worn.id())).findFirst().orElse(null);
				if (type == null) {
					continue;
				}
				Image texture = texture(type, worn.color());
				if (texture == null) {
					continue;
				}
				PhongMaterial material = new PhongMaterial();
				material.setDiffuseMap(texture);
				material.setSpecularColor(javafx.scene.paint.Color.rgb(30, 30, 30));
				for (int side = 0; side < (type.mirrorBone() != null ? 2 : 1); side++) {
					String bone = side == 0 ? type.bone() : type.mirrorBone();
					TriangleMesh mesh = mesh(type.files().get(side == 0 ? "mesh" : "mesh_l"), bone, slim);
					if (mesh == null) {
						continue;
					}
					MeshView view = new MeshView(mesh);
					view.setMaterial(material);
					view.setCullFace(CullFace.NONE);
					view.setUserData(worn.id());
					place(view, worn.offset(), worn.depth());
					views.add(view);
				}
			}
			Platform.runLater(() -> holder.getChildren().setAll(views));
		});
	}

	/** Placement in skin pixels: down, and out from the face (-z). */
	private static void place(MeshView view, int offset, int depth) {
		view.setTranslateY(offset / 4.0);
		view.setTranslateZ(-depth / 4.0);
	}

	/** Moves item {@code id} on every model right away, while a placement slider is dragged (saved when let go). FX thread. */
	public static void nudge(String id, int offset, int depth) {
		for (Group holder : HOLDERS) {
			for (javafx.scene.Node node : holder.getChildren()) {
				if (node instanceof MeshView view && id.equals(view.getUserData())) {
					place(view, offset, depth);
				}
			}
		}
	}

	/** A bone's pivot in the launcher model's space. */
	private static double[] pivot(String bone) {
		return switch (bone) {
			case "rightArm" -> new double[] {-5, -6, 0};
			case "leftArm" -> new double[] {5, -6, 0};
			case "rightLeg" -> new double[] {-2, 4, 0};
			case "leftLeg" -> new double[] {2, 4, 0};
			default -> new double[] {0, -8, 0};
		};
	}

	private static synchronized TriangleMesh mesh(String sha, String bone, boolean slim) {
		String key = sha + "|" + bone + "|" + slim;
		TriangleMesh cached = MESHES.get(key);
		if (cached != null || sha == null) {
			return cached;
		}
		Path file = WingsCatalog.file(sha);
		if (file == null) {
			return null;
		}
		try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file)))) {
			in.readFully(new byte[4]);
			in.readInt();
			int n = in.readInt();
			double[] pivot = pivot(bone);
			// Slim arms are 3 pixels wide: arm items squeeze towards the arm's inner edge, like in the game.
			float squeeze = !slim ? 0 : "leftArm".equals(bone) ? -1f : "rightArm".equals(bone) ? 1f : 0f;
			float[] points = new float[n * 3];
			float[] uv = new float[n * 2];
			for (int v = 0; v < n; v++) {
				float x = in.readFloat();
				float y = in.readFloat();
				float z = in.readFloat();
				in.readFloat();
				in.readFloat();
				in.readFloat();
				if (squeeze != 0) {
					x = squeeze + (x - squeeze) * 0.75f;
				}
				points[v * 3] = (float) (x + pivot[0]);
				points[v * 3 + 1] = (float) (y + pivot[1]);
				points[v * 3 + 2] = (float) (z + pivot[2]);
				uv[v * 2] = in.readFloat();
				uv[v * 2 + 1] = in.readFloat();
			}
			int count = in.readInt();
			int[] faces = new int[count * 2];
			for (int i = 0; i < count; i++) {
				int index = in.readInt();
				faces[i * 2] = index;
				faces[i * 2 + 1] = index;
			}
			TriangleMesh mesh = new TriangleMesh();
			mesh.getPoints().setAll(points);
			mesh.getTexCoords().setAll(uv);
			mesh.getFaces().setAll(faces);
			MESHES.put(key, mesh);
			return mesh;
		} catch (Exception e) {
			return null;
		}
	}

	/** The item's texture, its mask's pixels recoloured to {@code code} ({@code WearTint}, the same as the game). */
	static synchronized Image texture(WearCatalog.WearType type, int code) {
		int c = type.tintable() && net.veloclient.velo.client.cosmetics.wear.WearTint.valid(code) ? code : 0;
		String key = type.id() + ":" + c;
		Image cached = TEXTURES.get(key);
		if (cached != null) {
			return cached;
		}
		Path texFile = WingsCatalog.file(type.files().get("texture"));
		Path maskFile = c > 0 ? WingsCatalog.file(type.files().get("mask")) : null;
		if (texFile == null || c > 0 && maskFile == null) {
			return null;
		}
		try {
			Image base = new Image(Files.newInputStream(texFile));
			Image out = base;
			if (c > 0) {
				Image mask = new Image(Files.newInputStream(maskFile));
				int w = (int) base.getWidth();
				int h = (int) base.getHeight();
				PixelReader br = base.getPixelReader();
				PixelReader mr = mask.getPixelReader();
				int[] pixels = new int[w * h];
				int[] amounts = new int[w * h];
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						pixels[y * w + x] = br.getArgb(x, y);
						amounts[y * w + x] = (mr.getArgb((int) (x * mask.getWidth() / w), (int) (y * mask.getHeight() / h)) >> 16) & 0xFF;
					}
				}
				net.veloclient.velo.client.cosmetics.wear.WearTint.colorize(pixels, amounts, net.veloclient.velo.client.cosmetics.wear.WearTint.rgb(c));
				WritableImage tinted = new WritableImage(w, h);
				tinted.getPixelWriter().setPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
				out = tinted;
			}
			TEXTURES.put(key, out);
			return out;
		} catch (Exception e) {
			return null;
		}
	}
}
