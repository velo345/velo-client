package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.WritableImage;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Dev tool: writes every aura's card animation (and every kill effect's, {@code <out>/killfx-<id>/}) as PNG frames ({@code <out>/<id>/0000.png ...}) using the launcher's
 * own renderer, so the website and the owner panel show auras exactly like the Store does.
 * {@code tools/cosmetics/export_aura_previews.sh} runs it and packs the frames into animated WebP.
 *
 * <p>{@code ./gradlew :launcher:auraPreviews -PauraOut=<dir>}
 */
public final class AuraPreviewExport {

	private AuraPreviewExport() {
	}

	public static void main(String[] args) throws Exception {
		Path out = Path.of(args.length > 0 ? args[0] : "build/aura-previews");
		int size = args.length > 1 ? Integer.parseInt(args[1]) : 360;
		int frames = args.length > 2 ? Integer.parseInt(args[2]) : 96;
		int fps = 24;
		CountDownLatch started = new CountDownLatch(1);
		Platform.startup(started::countDown);
		started.await();
		String[] ids = {"orbit", "ember", "frost", "void", "storm", "matrix", "inferno", "bloom", "heartbeat", "aurora", "hologram", "festive",
				"seraph", "sakura", "galaxy", "crystal", "soul", "butterfly", "bass", "firework", "dragon", "ribbon",
				"raincloud", "koi", "blades", "lantern", "chrono", "pyjama"};
		if (args.length > 3) {
			// Only these (comma-separated), e.g. after adding new auras.
			List<String> only = List.of(args[3].split(","));
			ids = java.util.Arrays.stream(ids).filter(only::contains).toArray(String[]::new);
		}
		for (String id : ids) {
			CountDownLatch done = new CountDownLatch(1);
			Exception[] failure = new Exception[1];
			Platform.runLater(() -> {
				try {
					// The card (with the stand-in body) and the website try-on's two layers (behind / in front of the player).
					String[] names = {id, id + "-back", id + "-front"};
					for (int layer = 0; layer < 3; layer++) {
						List<WritableImage> images = AuraFx.previewFrames(AuraEffects.kind(id), size, size, frames, fps, layer);
						Path dir = Files.createDirectories(out.resolve(names[layer]));
						for (int i = 0; i < images.size(); i++) {
							ImageIO.write(SwingFXUtils.fromFXImage(images.get(i), null), "png", dir.resolve(String.format("%04d.png", i)).toFile());
						}
					}
				} catch (Exception e) {
					failure[0] = e;
				} finally {
					done.countDown();
				}
			});
			done.await();
			if (failure[0] != null) {
				throw failure[0];
			}
			System.out.println("aura " + id + ": " + frames + " frames");
		}
		// The kill effects: one play each (with a moment of the figure standing first), into killfx-<id>/.
		List<String> killIds = net.veloclient.launcher.data.KillFxCatalog.IDS;
		if (args.length > 3) {
			List<String> only = List.of(args[3].split(","));
			killIds = killIds.stream().filter(only::contains).toList();
		}
		for (String id : killIds) {
			CountDownLatch done = new CountDownLatch(1);
			Exception[] failure = new Exception[1];
			Platform.runLater(() -> {
				try {
					List<WritableImage> images = AuraFx.killFxFrames(net.veloclient.velo.client.cosmetics.killfx.KillFxEffects.kind(id), size, size, fps);
					Path dir = Files.createDirectories(out.resolve("killfx-" + id));
					for (int i = 0; i < images.size(); i++) {
						ImageIO.write(SwingFXUtils.fromFXImage(images.get(i), null), "png", dir.resolve(String.format("%04d.png", i)).toFile());
					}
				} catch (Exception e) {
					failure[0] = e;
				} finally {
					done.countDown();
				}
			});
			done.await();
			if (failure[0] != null) {
				throw failure[0];
			}
			System.out.println("kill effect " + id);
		}
		Platform.exit();
	}
}
