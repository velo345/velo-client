package net.veloclient.velo.client.cosmetics;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.network.VeloServerClient;
import net.veloclient.velo.client.store.StoreAssets;
import net.veloclient.velo.client.store.StoreCatalog;
import net.veloclient.velo.client.store.StoreItem;
import net.veloclient.velo.config.VeloPaths;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves the cape id another player publishes through {@link
 * net.veloclient.velo.client.network.VeloUserRegistry} into a renderable
 * {@link CapeDefinition}, for {@link
 * net.veloclient.velo.client.cosmetics.render.CapeFeatureRenderer}'s
 * other-player render path. Two kinds of id:
 * <ul>
 *   <li>a Store catalog id - every client ships that art bundled
 *       ({@link StoreItem#gifResource()}), so nothing is downloaded;</li>
 *   <li>{@code custom:<sha256>} - a cape that player imported and uploaded
 *       (see {@code CustomCapeUploader}), downloaded once from the Velo server,
 *       checked against its hash, and cached on disk under
 *       {@code cache/remote-capes/}.</li>
 * </ul>
 *
 * <p>{@link #resolve} never blocks: loading/decoding happens on a background
 * thread and it returns empty until the texture is registered (on the render
 * thread, which is the only part that has to be). The previous version decoded
 * the GIF synchronously inside the render call, so the first time a cape came
 * into view the frame hitched.
 */
public final class RemoteCapeCache {

	private static final String CUSTOM_PREFIX = "custom:";
	private static final long RETRY_AFTER_FAILURE_MILLIS = 60_000;
	private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;

	private static final Map<String, Optional<CapeDefinition>> RESOLVED = new ConcurrentHashMap<>();
	private static final Map<String, Long> IN_FLIGHT_OR_FAILED_AT = new ConcurrentHashMap<>();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "velo-remote-cape");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private RemoteCapeCache() {
	}

	public static Optional<CapeDefinition> resolve(String capeId) {
		if (capeId == null || capeId.isBlank()) {
			return Optional.empty();
		}
		Optional<CapeDefinition> done = RESOLVED.get(capeId);
		if (done != null) {
			return done;
		}
		long now = System.currentTimeMillis();
		Long started = IN_FLIGHT_OR_FAILED_AT.get(capeId);
		if (started == null || (started < 0 && now + started > RETRY_AFTER_FAILURE_MILLIS)) {
			IN_FLIGHT_OR_FAILED_AT.put(capeId, now);
			LOADER.execute(() -> load(capeId));
		}
		return Optional.empty();
	}

	private static void load(String capeId) {
		try {
			if (capeId.startsWith(CUSTOM_PREFIX)) {
				loadCustom(capeId, capeId.substring(CUSTOM_PREFIX.length()));
			} else {
				loadStore(capeId);
			}
		} catch (Exception e) {
			VeloClient.LOGGER.debug("Could not load remote cape {}", capeId, e);
			// Negative = "failed at"; resolve() retries after RETRY_AFTER_FAILURE_MILLIS (a server
			// that was briefly unreachable shouldn't hide that cape for the whole session).
			IN_FLIGHT_OR_FAILED_AT.put(capeId, -System.currentTimeMillis());
		}
	}

	private static void loadStore(String capeCatalogId) throws IOException {
		Optional<StoreItem> item = StoreCatalog.byId(capeCatalogId);
		if (item.isEmpty()) {
			RESOLVED.put(capeCatalogId, Optional.empty());
			return;
		}
		byte[] gif;
		try (InputStream in = StoreAssets.openGif(item.get())) {
			gif = in.readAllBytes();
		}
		GifDecoder.Result decoded = GifDecoder.decode(new ByteArrayInputStream(gif));
		// Same id the Store's own preview tiles register under, so if this player already
		// looked at that item in the Store the texture is simply shared.
		CapeDefinition definition = new CapeDefinition(capeCatalogId, item.get().name(), null,
				CapePhysicsPreset.defaults(), false, true, capeCatalogId);
		MinecraftClient.getInstance().execute(() -> {
			try {
				AnimatedCapeAsset.registerDecoded(capeCatalogId, decoded);
				RESOLVED.put(capeCatalogId, Optional.of(definition));
			} catch (IOException | RuntimeException e) {
				RESOLVED.put(capeCatalogId, Optional.empty());
			}
		});
	}

	private static void loadCustom(String capeId, String hash) throws Exception {
		if (!hash.matches("[0-9a-f]{64}")) {
			RESOLVED.put(capeId, Optional.empty());
			return;
		}
		Path cacheDir = VeloPaths.root().resolve("cache").resolve("remote-capes");
		Files.createDirectories(cacheDir);
		Path cached = cacheDir.resolve(hash);
		byte[] bytes = Files.exists(cached) ? Files.readAllBytes(cached) : null;
		if (bytes == null || !hash.equals(sha256(bytes))) {
			bytes = download(hash);
			Files.write(cached, bytes);
		}
		String definitionId = "remote_" + hash;
		boolean gif = bytes.length > 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
		CapeDefinition definition = new CapeDefinition(definitionId, "Custom cape", cached,
				CapePhysicsPreset.defaults(), false, gif, null);
		if (gif) {
			GifDecoder.Result decoded = GifDecoder.decode(new ByteArrayInputStream(bytes));
			MinecraftClient.getInstance().execute(() -> {
				try {
					AnimatedCapeAsset.registerDecoded(definitionId, decoded);
					RESOLVED.put(capeId, Optional.of(definition));
				} catch (IOException | RuntimeException e) {
					RESOLVED.put(capeId, Optional.empty());
				}
			});
			return;
		}
		NativeImage image = capeHalf(NativeImage.read(new ByteArrayInputStream(bytes)));
		MinecraftClient.getInstance().execute(() -> {
			try {
				CapeManager.registerPreloadedTexture(definitionId, image, "remote cape " + hash);
				RESOLVED.put(capeId, Optional.of(definition));
			} catch (RuntimeException e) {
				image.close();
				RESOLVED.put(capeId, Optional.empty());
			}
		});
	}

	/** A square (cape + elytra) upload only needs its top half here - remote capes don't override elytras. */
	private static NativeImage capeHalf(NativeImage image) {
		if (image.getHeight() < image.getWidth()) {
			return image;
		}
		int width = image.getWidth();
		int height = image.getHeight() / 2;
		NativeImage top = new NativeImage(width, height, false);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				top.setColorArgb(x, y, image.getColorArgb(x, y));
			}
		}
		image.close();
		return top;
	}

	private static byte[] download(String hash) throws Exception {
		String base = VeloServerClient.serverBaseUrl();
		if (base == null) {
			throw new IOException("No Velo server configured");
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/cape/" + hash))
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build();
		HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream in = response.body()) {
			if (response.statusCode() != 200) {
				throw new IOException("Cape download returned " + response.statusCode());
			}
			byte[] bytes = in.readNBytes(MAX_DOWNLOAD_BYTES + 1);
			if (bytes.length > MAX_DOWNLOAD_BYTES) {
				throw new IOException("Cape download too large");
			}
			// Content-addressed: whatever came back must hash to the id we asked for.
			if (!hash.equals(sha256(bytes))) {
				throw new IOException("Cape download failed its integrity check");
			}
			return bytes;
		}
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}
