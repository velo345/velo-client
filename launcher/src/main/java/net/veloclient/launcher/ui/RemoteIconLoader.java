package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Fetches and decodes a remote project icon for an {@link ImageView}.
 *
 * <p>Modrinth's CDN serves almost every project icon as WebP (only a handful
 * of older/manually-uploaded ones are still plain PNG/JPEG) - {@code
 * javafx.scene.image.Image}'s own URL constructor can't decode WebP at all
 * and fails silently, which is why most Modrinth mod/resource-pack/shader
 * icons were falling back to the generic logo. This routes every remote icon
 * through {@link ImageIO} instead (which, with {@code webp-imageio} on the
 * classpath, decodes WebP alongside PNG/JPEG/GIF) and converts the result to
 * a JavaFX {@link Image} via {@link SwingFXUtils} - one decoder for every
 * format instead of relying on JavaFX's own narrower native support.
 */
public final class RemoteIconLoader {

	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	// Small process-wide cache - the same project icon is drawn repeatedly
	// across search results/installed rows/update checks within one session.
	private static final java.util.Map<String, Image> CACHE = new ConcurrentHashMap<>();

	private RemoteIconLoader() {
	}

	/** Sets {@code view}'s image once decoded; calls {@code onFailure} (e.g. to swap in a fallback) if the URL is null/blank or fails to load. */
	public static void load(ImageView view, String url, Runnable onFailure) {
		if (url == null || url.isBlank()) {
			onFailure.run();
			return;
		}
		Image cached = CACHE.get(url);
		if (cached != null) {
			view.setImage(cached);
			return;
		}
		CompletableFuture.supplyAsync(() -> fetchAndDecode(url), Executors.newVirtualThreadPerTaskExecutor())
				.thenAccept(image -> Platform.runLater(() -> {
					if (image != null) {
						CACHE.put(url, image);
						view.setImage(image);
					} else {
						onFailure.run();
					}
				}));
	}

	private static Image fetchAndDecode(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
			HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() >= 400) {
				return null;
			}
			try (InputStream in = response.body()) {
				BufferedImage buffered = ImageIO.read(in);
				if (buffered == null) {
					return null;
				}
				return SwingFXUtils.toFXImage(buffered, null);
			}
		} catch (IOException | InterruptedException | RuntimeException e) {
			return null;
		}
	}
}
