package net.veloclient.launcher.ui;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import net.veloclient.launcher.data.WingsCatalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Thumbnails of cosmetics for the owner panel - still pictures only (no live 3D or animation), decoded
 * straight to thumbnail size off the UI thread and kept in a small LRU, so a long list of items
 * costs a few megabytes however many rows there are.
 */
final class OwnerPreviews {

	private static final int MAX_CACHED = 96;
	private static final Map<String, Image> CACHE = new LinkedHashMap<>(128, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
			return size() > MAX_CACHED;
		}
	};

	private OwnerPreviews() {
	}

	/** A framed thumbnail for a cosmetic item (a server catalog entry), filled in as soon as its picture is ready. */
	static StackPane thumb(JsonObject item, double width, double height) {
		String id = item.has("id") ? item.get("id").getAsString() : "";
		boolean cape = id.startsWith("cape_");
		ImageView view = new ImageView();
		view.setPreserveRatio(true);
		view.setSmooth(true);
		view.setFitWidth(cape ? height * 0.62 : width - 8);
		view.setFitHeight(height - 8);
		StackPane frame = new StackPane(view);
		frame.setMinSize(width, height);
		frame.setPrefSize(width, height);
		frame.setMaxSize(width, height);
		String accent = item.has("accent") && !item.get("accent").isJsonNull() ? item.get("accent").getAsString() : "#ff4d4d";
		frame.setStyle("-fx-background-radius: 10; -fx-background-color: radial-gradient(center 50% 60%, radius 70%, " + accent
				+ "66 0%, #16161d00 100%), #17171d;");
		load(item, id, width, height, image -> view.setImage(image));
		return frame;
	}

	/** A small thumbnail for an item id we only know by name (not in the catalog). */
	static StackPane placeholder(String id, double width, double height) {
		JsonObject item = new JsonObject();
		item.addProperty("id", id);
		return thumb(item, width, height);
	}

	private static void load(JsonObject item, String id, double width, double height, java.util.function.Consumer<Image> done) {
		Image cached;
		synchronized (CACHE) {
			cached = CACHE.get(id);
		}
		if (cached != null) {
			done.accept(cached);
			return;
		}
		if (id.startsWith("aura_")) {
			var kind = net.veloclient.velo.client.cosmetics.aura.AuraEffects.kind(id.substring(5));
			if (kind != null) {
				Platform.runLater(() -> {
					Image image = AuraFx.snapshot(kind, width, height);
					remember(id, image);
					done.accept(image);
				});
			}
			return;
		}
		String files = item.has("files") && item.get("files").isJsonObject() ? "files" : null;
		String sha = null;
		if (files != null) {
			JsonObject f = item.getAsJsonObject("files");
			String key = id.startsWith("cape_") ? "gif" : "preview";
			sha = f.has(key) && !f.get(key).isJsonNull() ? f.get(key).getAsString() : null;
		}
		if (sha == null) {
			// Wings bundled with the launcher: their own preview picture.
			var local = net.veloclient.launcher.data.WingsCatalog.all().stream().filter(w -> w.itemId().equals(id)).findFirst();
			local.ifPresent(wings -> CompletableFuture.supplyAsync(() -> {
				try (var in = net.veloclient.launcher.data.WingsCatalog.openPreview(wings.id())) {
					return in == null ? null : new Image(in, (width - 8) * 2, (height - 8) * 2, true, true);
				} catch (Exception e) {
					return null;
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(image -> {
				if (image != null) {
					Platform.runLater(() -> {
						remember(id, image);
						done.accept(image);
					});
				}
			}));
			return;
		}
		String file = sha;
		boolean cape = id.startsWith("cape_");
		CompletableFuture.supplyAsync(() -> cape ? capeFace(file, height) : wingsPicture(file, width, height), Executors.newVirtualThreadPerTaskExecutor())
				.thenAccept(image -> {
					if (image != null) {
						Platform.runLater(() -> {
							remember(id, image);
							done.accept(image);
						});
					}
				});
	}

	private static void remember(String id, Image image) {
		synchronized (CACHE) {
			CACHE.put(id, image);
		}
	}

	/** Decoded at twice the display size at most, never the file's full resolution. */
	private static Image wingsPicture(String sha, double width, double height) {
		try (java.io.InputStream in = WingsCatalog.openFile(sha)) {
			return new Image(in, width * 2, height * 2, true, true);
		} catch (Exception e) {
			return null;
		}
	}

	/** Frame 0 of a cape GIF, cropped to the outside of the cape (10 x 16 cape pixels of the 64 x 32 layout), shrunk to thumbnail size. */
	private static Image capeFace(String sha, double height) {
		try (java.io.InputStream in = WingsCatalog.openFile(sha); javax.imageio.stream.ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(in)) {
			var reader = javax.imageio.ImageIO.getImageReadersByFormatName("gif").next();
			reader.setInput(iis, true);
			java.awt.image.BufferedImage frame = reader.read(0);
			reader.dispose();
			int scale = Math.max(1, frame.getWidth() / 64);
			java.awt.image.BufferedImage face = frame.getSubimage(scale, scale, 10 * scale, 16 * scale);
			// Nearest-neighbour up to ~2x the display height (cape textures are tiny pixel art).
			int factor = Math.max(1, (int) Math.round(height * 2 / face.getHeight()));
			java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(face.getWidth() * factor, face.getHeight() * factor,
					java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D g = scaled.createGraphics();
			g.drawImage(face, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
			g.dispose();
			return javafx.embed.swing.SwingFXUtils.toFXImage(scaled, null);
		} catch (Exception e) {
			return null;
		}
	}
}
