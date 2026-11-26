package net.veloclient.launcher.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.util.Duration;
import net.veloclient.launcher.LauncherLog;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Dev only ({@code -Dvelo.promo=<folder>}): captures the launcher for promo videos. Scenes are grabbed frame by frame
 * (JPG + a times.txt with the milliseconds of every frame) so the editor can replay them smoothly, and Home is also
 * saved layer by layer (transparent PNGs + layers.json) so a video can pull it apart in 3D. Exits when done.
 */
public final class PromoCapture {

	public interface App {
		void home();

		void locker();

		void store();

		void customize();

		void library();

		void profile();

		Node[] homeLayers();
	}

	private static final double SCALE = 1.5;

	private static final ExecutorService WRITERS = Executors.newFixedThreadPool(5);
	private static final Semaphore QUEUE = new Semaphore(28);

	private final Stage stage;
	private final Path out;
	private final App app;
	private final List<Runnable> steps = new ArrayList<>();

	private PromoCapture(Stage stage, Path out, App app) {
		this.stage = stage;
		this.out = out;
		this.app = app;
	}

	public static void run(Stage stage, Path out, App app) {
		new PromoCapture(stage, out, app).start();
	}

	private void start() {
		try {
			Files.createDirectories(out);
		} catch (Exception e) {
			LauncherLog.warn("promo folder", e);
			Platform.exit();
			return;
		}
		Platform.runLater(() -> {
			stage.setWidth(1500);
			stage.setHeight(940);
		});
		String only = System.getProperty("velo.promoOnly", "");
		if (only.isEmpty() || only.contains("home")) {
			// the real account loads its skin, friends and cosmetics first
			scene("home", 9000, 200, () -> app.home(), null);
			after(300, this::layers);
		}
		if (only.isEmpty() || only.contains("pages")) {
			scene("to-locker", 900, 110, app::locker, null);
			scene("locker-idle", 3000, 60, null, null);
			scene("to-store", 900, 110, app::store, null);
			scene("store-idle", 3000, 40, null, null);
			scene("to-customize", 900, 80, app::customize, null);
			scene("to-library", 900, 70, app::library, null);
			scene("to-profile", 900, 100, app::profile, null);
			scene("to-home", 900, 90, app::home, null);
		}
		after(600, () -> {
			WRITERS.shutdown();
			try {
				WRITERS.awaitTermination(120, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
				// quitting anyway
			}
			LauncherLog.info("[promo] done");
			Platform.exit();
		});
		next();
	}

	private void next() {
		if (!steps.isEmpty()) {
			steps.removeFirst().run();
		}
	}

	private void after(int millis, Runnable action) {
		steps.add(() -> {
			PauseTransition wait = new PauseTransition(Duration.millis(millis));
			wait.setOnFinished(e -> {
				try {
					action.run();
				} catch (Exception ex) {
					LauncherLog.warn("[promo] step failed", ex);
				}
				next();
			});
			wait.play();
		});
	}

	/** Waits {@code settle} ms, runs {@code trigger}, then grabs {@code frames} frames as fast as the toolkit renders them. */
	private void scene(String name, int settle, int frames, Runnable trigger, Runnable end) {
		steps.add(() -> {
			PauseTransition wait = new PauseTransition(Duration.millis(settle));
			wait.setOnFinished(e -> {
				Path dir = out.resolve(name);
				try {
					Files.createDirectories(dir);
					hideCoins();
					if (trigger != null) {
						trigger.run();
					}
				} catch (Exception ex) {
					LauncherLog.warn("[promo] " + name, ex);
				}
				StringBuilder times = new StringBuilder();
				long t0 = System.nanoTime();
				Runnable[] grab = new Runnable[1];
				int[] count = {0};
				grab[0] = () -> {
					int i = count[0]++;
					if (i >= frames) {
						try {
							Files.writeString(dir.resolve("times.txt"), times);
						} catch (Exception ex) {
							LauncherLog.warn("[promo] times", ex);
						}
						LauncherLog.info("[promo] " + name + " " + frames + " frames in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
						if (end != null) {
							end.run();
						}
						next();
						return;
					}
					times.append((System.nanoTime() - t0) / 1_000_000).append('\n');
					hideCoins();
					WritableImage image = window(SCALE);
					save(image, dir.resolve(String.format("f%04d.jpg", i)), false);
					// one pulse later, so the animation moved on and the window got to draw
					PauseTransition pulse = new PauseTransition(Duration.millis(1));
					pulse.setOnFinished(ev -> grab[0].run());
					pulse.play();
				};
				grab[0].run();
			});
			wait.play();
		});
	}

	/**
	 * Exactly the window, nothing around it: the root's bounds reach past the window (shadows), and a plain snapshot
	 * painted that margin white (owner 2026-11-26: "no white edges").
	 */
	private WritableImage window(double scale) {
		var scene = stage.getScene();
		SnapshotParameters params = new SnapshotParameters();
		params.setTransform(Transform.scale(scale, scale));
		params.setViewport(new javafx.geometry.Rectangle2D(0, 0, Math.round(scene.getWidth() * scale), Math.round(scene.getHeight() * scale)));
		params.setFill(Color.web("#120d10"));
		return scene.getRoot().snapshot(params, null);
	}

	private void hideCoins() {
		for (Node node : stage.getScene().getRoot().lookupAll(".coins-pill")) {
			node.setVisible(false);
		}
	}

	/** Every layer of Home on its own (transparent), with where it sits in the window. */
	private void layers() {
		try {
			Path dir = out.resolve("layers");
			Files.createDirectories(dir);
			hideCoins();
			StringBuilder json = new StringBuilder("{\"scale\":2,\"window\":[" + (int) stage.getScene().getWidth() + "," + (int) stage.getScene().getHeight() + "],\"layers\":[");
			Node[] nodes = app.homeLayers();
			int n = 0;
			for (Node node : nodes) {
				if (node == null || node.getScene() == null || !node.isVisible()) {
					continue;
				}
				SnapshotParameters params = new SnapshotParameters();
				params.setFill(Color.TRANSPARENT);
				params.setTransform(Transform.scale(2, 2));
				WritableImage image = node.snapshot(params, null);
				var b = node.localToScene(node.getBoundsInLocal());
				save(image, dir.resolve("l" + n + ".png"), true);
				json.append(n == 0 ? "" : ",").append(String.format(java.util.Locale.ROOT,
						"{\"file\":\"l%d.png\",\"cls\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"w\":%.1f,\"h\":%.1f}", n,
						(node.getClass().getSimpleName() + " " + String.join(" ", node.getStyleClass())).replace("\"", ""),
						b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()));
				n++;
			}
			save(window(2), dir.resolve("whole.png"), true);
			Files.writeString(dir.resolve("layers.json"), json.append("]}").toString());
			LauncherLog.info("[promo] " + n + " layers");
		} catch (Exception e) {
			LauncherLog.warn("[promo] layers", e);
		}
	}

	private void save(WritableImage image, Path file, boolean alpha) {
		int w = (int) image.getWidth();
		int h = (int) image.getHeight();
		int[] pixels = new int[w * h];
		image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
		QUEUE.acquireUninterruptibly();
		WRITERS.submit(() -> {
			try {
				BufferedImage out = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
				out.setRGB(0, 0, w, h, pixels, 0, w);
				if (alpha) {
					ImageIO.write(out, "png", file.toFile());
				} else {
					var writer = ImageIO.getImageWritersByFormatName("jpg").next();
					var param = writer.getDefaultWriteParam();
					param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
					param.setCompressionQuality(0.93f);
					try (var stream = ImageIO.createImageOutputStream(file.toFile())) {
						writer.setOutput(stream);
						writer.write(null, new IIOImage(out, null, null), param);
					}
					writer.dispose();
				}
			} catch (Exception e) {
				LauncherLog.warn("[promo] write " + file, e);
			} finally {
				QUEUE.release();
			}
		});
	}
}
