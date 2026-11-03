package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import net.veloclient.launcher.launch.GameDataPaths;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A picture for each Minecraft version on the version tiles (new profile, change version): a little scene built from
 * what that version added - 1.21.11's spears and nautilus armor, 26.1's golden dandelion meadow, 26.2's sulfur and
 * cinnabar, 26.3's autumn poplars. Velo ships no Minecraft art: the textures are read from that version's game jar the
 * launcher already downloaded; a version that isn't downloaded yet gets a plain colour scene in its palette.
 */
public final class VersionArt {

	public static final int W = 160;
	public static final int H = 90;

	private record Spec(Color skyTop, Color skyBottom, String ground, String ground2, Color tint, List<String> decor, String decorKind,
			List<String> items) {
	}

	private static final Map<String, Spec> SPECS = Map.of(
			"1.21.11", new Spec(new Color(52, 24, 40), new Color(232, 116, 59), "block/sand", "block/copper_block", null,
					List.of("item/copper_lantern"), "deco", List.of("item/diamond_spear", "item/golden_nautilus_armor", "item/netherite_spear")),
			"26.1", new Spec(new Color(70, 140, 214), new Color(190, 226, 250), "block/grass_block_top", "block/dirt", new Color(110, 176, 64),
					List.of("block/dandelion", "block/golden_dandelion", "block/poppy"), "flower", List.of("block/golden_dandelion")),
			"26.2", new Spec(new Color(30, 10, 24), new Color(160, 50, 40), "block/sulfur", "block/cinnabar_bricks", null,
					List.of("block/sulfur_spike_up_tip"), "deco", List.of("item/sulfur_spike", "item/music_disc_bounce", "item/sulfur_cube_bucket")),
			"26.3", new Spec(new Color(54, 40, 70), new Color(240, 168, 90), "block/poplar_planks", "block/poplar_planks", null,
					List.of("block/orange_poplar_leaves", "block/red_poplar_leaves", "block/yellow_poplar_leaves"), "tree",
					List.of("item/red_cushion", "item/poplar_boat")));

	private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

	private VersionArt() {
	}

	/** The version's picture (2x, crisp): right away when cached, otherwise built off the FX thread and handed to {@code ready}. */
	public static void load(String version, Consumer<Image> ready) {
		Image cached = CACHE.get(version);
		if (cached != null) {
			ready.accept(cached);
			return;
		}
		Thread t = new Thread(() -> {
			Image image;
			try {
				image = SwingFXUtils.toFXImage(compose(version), null);
			} catch (Throwable e) {
				return;
			}
			CACHE.put(version, image);
			Platform.runLater(() -> ready.accept(image));
		}, "version-art");
		t.setDaemon(true);
		t.start();
	}

	/** A version tile: the picture with rounded corners, the version number (and "Newest") over its shaded bottom. */
	public static javafx.scene.layout.StackPane tile(String version, boolean newest, double width) {
		double height = Math.round(width * H / (double) W);
		javafx.scene.image.ImageView view = new javafx.scene.image.ImageView();
		view.setFitWidth(width);
		view.setFitHeight(height);
		view.setSmooth(false);
		javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(width, height);
		clip.setArcWidth(18);
		clip.setArcHeight(18);
		view.setClip(clip);
		javafx.scene.layout.Region placeholder = new javafx.scene.layout.Region();
		placeholder.setPrefSize(width, height);
		placeholder.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 9;");
		load(version, image -> {
			view.setImage(image);
			placeholder.setVisible(false);
		});
		javafx.scene.control.Label number = new javafx.scene.control.Label(version);
		number.setStyle("-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.7), 6, 0.3, 0, 1);");
		javafx.scene.layout.HBox labels = new javafx.scene.layout.HBox(6, number);
		labels.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
		labels.setPadding(new javafx.geometry.Insets(0, 0, 7, 10));
		if (newest) {
			javafx.scene.control.Label tag = new javafx.scene.control.Label("Newest");
			tag.getStyleClass().addAll("version-tag-pill", "version-tag-newest");
			labels.getChildren().add(tag);
		}
		labels.setMouseTransparent(true);
		javafx.scene.layout.StackPane pane = new javafx.scene.layout.StackPane(placeholder, view, labels);
		pane.setPrefSize(width, height);
		pane.setMaxSize(width, height);
		return pane;
	}

	private static BufferedImage compose(String version) throws Exception {
		Spec spec = SPECS.getOrDefault(version, new Spec(new Color(30, 30, 40), new Color(90, 90, 120), null, null, null, List.of(), "deco", List.of()));
		BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.setPaint(new GradientPaint(0, 0, spec.skyTop(), 0, H, spec.skyBottom()));
		g.fillRect(0, 0, W, H);
		// a soft sun
		for (int i = 10; i > 0; i--) {
			g.setColor(new Color(255, 230, 180, 10));
			g.fillOval(Math.round(W * 0.72f - i * 3), Math.round(H * 0.38f - i * 3), i * 6, i * 6);
		}
		Path jar = GameDataPaths.clientJar(version);
		int gy = H - 22;
		if (spec.ground() != null && Files.isRegularFile(jar)) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				BufferedImage ground = texture(zip, spec.ground());
				BufferedImage ground2 = texture(zip, spec.ground2());
				if (spec.tint() != null && ground != null) {
					ground = tint(ground, spec.tint());
				}
				for (int x = 0; x < W; x += 16) {
					draw(g, ground, x, gy);
					draw(g, ground2, x, gy + 16);
				}
				if (spec.decorKind().equals("tree")) {
					BufferedImage log = texture(zip, "block/poplar_log");
					for (int i = 0; i < spec.decor().size(); i++) {
						BufferedImage leaves = texture(zip, spec.decor().get(i));
						int x = 8 + i * 30;
						draw(g, log, x, gy - 16);
						for (int dy : new int[] {-32, -48, -64}) {
							draw(g, leaves, x, gy + dy);
						}
						draw(g, leaves, x - 8, gy - 40);
						draw(g, leaves, x + 8, gy - 40);
					}
				} else {
					for (int i = 0; i < spec.decor().size() * 4; i++) {
						BufferedImage d = texture(zip, spec.decor().get(i % spec.decor().size()));
						int x = 6 + (i * 37) % (W - 20);
						draw(g, d, x, spec.decorKind().equals("flower") ? gy - 15 : gy - 16);
					}
				}
				shade(g);
				int n = spec.items().size();
				int big = n == 1 ? 48 : 32;
				for (int i = 0; i < n; i++) {
					BufferedImage item = texture(zip, spec.items().get(i));
					if (item == null) {
						continue;
					}
					int x = W - 14 - (n - i) * (big - 2);
					int y = (n == 1 ? 10 : 12) + (i % 2) * 8;
					g.drawImage(shadowOf(item), x + 2, y + 3, big, big, null);
					g.drawImage(item, x, y, big, big, null);
				}
			}
		} else {
			// not downloaded yet: hills in the version's palette
			g.setColor(spec.skyTop().darker());
			g.fillOval(-30, gy - 20, 120, 70);
			g.fillOval(70, gy - 12, 140, 70);
			g.setColor(spec.skyTop().darker().darker());
			g.fillRect(0, gy, W, H - gy);
			shade(g);
		}
		g.dispose();
		BufferedImage out = new BufferedImage(W * 2, H * 2, BufferedImage.TYPE_INT_ARGB);
		Graphics2D o = out.createGraphics();
		o.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		o.drawImage(img, 0, 0, W * 2, H * 2, null);
		o.dispose();
		return out;
	}

	/** Darkens the lower part so the version number reads on top. */
	private static void shade(Graphics2D g) {
		for (int y = 0; y < H; y++) {
			float f = Math.max(0f, (y - H * 0.45f) / (H * 0.55f));
			g.setColor(new Color(0, 0, 0, Math.round(140 * (float) Math.pow(f, 1.5))));
			g.fillRect(0, y, W, 1);
		}
	}

	private static void draw(Graphics2D g, BufferedImage tex, int x, int y) {
		if (tex != null) {
			g.drawImage(tex, x, y, 16, 16, null);
		}
	}

	private static BufferedImage texture(ZipFile zip, String name) {
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
				// first frame only (animated textures are vertical strips)
				BufferedImage argb = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = argb.createGraphics();
				g.drawImage(raw, 0, 0, 16, 16, 0, 0, Math.min(16, raw.getWidth()), Math.min(16, raw.getHeight()), null);
				g.dispose();
				return argb;
			}
		} catch (Exception e) {
			return null;
		}
	}

	/** Grass and friends are grey in the jar - the game tints them by biome. */
	private static BufferedImage tint(BufferedImage src, Color c) {
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < src.getHeight(); y++) {
			for (int x = 0; x < src.getWidth(); x++) {
				int p = src.getRGB(x, y);
				int a = p >>> 24;
				int l = ((p >> 16 & 0xFF) * 3 + (p >> 8 & 0xFF) * 6 + (p & 0xFF)) / 10;
				out.setRGB(x, y, a << 24 | (l * c.getRed() / 255) << 16 | (l * c.getGreen() / 255) << 8 | l * c.getBlue() / 255);
			}
		}
		return out;
	}

	private static BufferedImage shadowOf(BufferedImage src) {
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < src.getHeight(); y++) {
			for (int x = 0; x < src.getWidth(); x++) {
				int a = src.getRGB(x, y) >>> 24;
				out.setRGB(x, y, (a / 2) << 24);
			}
		}
		return out;
	}
}
