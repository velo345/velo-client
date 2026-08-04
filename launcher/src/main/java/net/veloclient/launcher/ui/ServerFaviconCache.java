package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import net.veloclient.launcher.net.ServerPinger;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Small process-wide cache of decoded server favicons (the same Server List
 * Ping "favicon" field {@link net.veloclient.launcher.net.ServerPinger}
 * reads for the My Servers view), keyed by "host:port" - used wherever a
 * server-launch shortcut needs its target server's icon (the sidebar's
 * Running/Quick Launch rows) without re-pinging the same server on every
 * sidebar rebuild.
 */
public final class ServerFaviconCache {

	private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
	private static final Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();

	private ServerFaviconCache() {
	}

	/** Sets {@code fallback} immediately, then swaps in the real favicon asynchronously once/if it's fetched (a cached result applies synchronously). */
	public static void loadInto(ImageView view, String host, int port, Image fallback) {
		String key = host + ":" + port;
		Image cached = CACHE.get(key);
		if (cached != null) {
			view.setImage(cached);
			return;
		}
		view.setImage(fallback);
		if (!INFLIGHT.add(key)) {
			return;
		}
		CompletableFuture.supplyAsync(() -> {
			try {
				return ServerPinger.ping(host, port);
			} catch (Exception e) {
				return null;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> {
			INFLIGHT.remove(key);
			if (result == null || result.faviconPngBase64() == null) {
				return;
			}
			try {
				byte[] bytes = Base64.getDecoder().decode(result.faviconPngBase64());
				Image image = new Image(new ByteArrayInputStream(bytes), 32, 32, true, true);
				if (!image.isError()) {
					CACHE.put(key, image);
					Platform.runLater(() -> view.setImage(image));
				}
			} catch (IllegalArgumentException ignored) {
				// Malformed base64 - keep the fallback.
			}
		});
	}
}
