package net.veloclient.velo.client.youtube;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The player's own thumbnail textures. The shared loader frees every downloaded picture 30 s after the last menu
 * closes - while a video kept playing as picture-in-picture that left the result list pointing at freed textures (empty
 * tiles when you went back). These stay until there are too many (oldest go first), scaled down to 320 x 180.
 */
final class YtThumbs {

	private static final int MAX = 160;
	private static final Map<String, Identifier> TEXTURES = new LinkedHashMap<>(64, 0.75f, true);
	private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
	private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
		Thread t = new Thread(r, "velo-yt-thumbs");
		t.setDaemon(true);
		return t;
	});

	private YtThumbs() {
	}

	/** The thumbnail for {@code key} (render thread), or null while it loads / when there is none. */
	static Identifier get(String key, String url) {
		Identifier id = TEXTURES.get(key);
		if (id != null || url == null || FAILED.contains(key) || !LOADING.add(key)) {
			return id;
		}
		POOL.submit(() -> {
			try {
				byte[] bytes = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
						HttpResponse.BodyHandlers.ofByteArray()).body();
				java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
				if (src == null) {
					FAILED.add(key);
					return;
				}
				// cover-crop to 16:9 and scale to 320 x 180 (YouTube's 4:3 "hq" frames have black bars)
				int w = 320;
				int h = 180;
				java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
				java.awt.Graphics2D g = out.createGraphics();
				g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				double scale = Math.max(w / (double) src.getWidth(), h / (double) src.getHeight());
				int dw = (int) Math.ceil(src.getWidth() * scale);
				int dh = (int) Math.ceil(src.getHeight() * scale);
				g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
				g.dispose();
				ByteArrayOutputStream png = new ByteArrayOutputStream();
				javax.imageio.ImageIO.write(out, "png", png);
				NativeImage image = NativeImage.read(new ByteArrayInputStream(png.toByteArray()));
				String name = "yt/" + key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
				if (name.length() > 80) {
					name = "yt/" + Integer.toHexString(key.hashCode()) + "_" + Integer.toHexString(key.length());
				}
				Identifier texId = Identifier.of("velo-client", name);
				MinecraftClient.getInstance().execute(() -> {
					MinecraftClient client = MinecraftClient.getInstance();
					client.getTextureManager().registerTexture(texId, new NativeImageBackedTexture(() -> key, image));
					TEXTURES.put(key, texId);
					while (TEXTURES.size() > MAX) {
						Iterator<Map.Entry<String, Identifier>> it = TEXTURES.entrySet().iterator();
						Identifier old = it.next().getValue();
						it.remove();
						client.getTextureManager().destroyTexture(old);
					}
				});
			} catch (Exception e) {
				FAILED.add(key);
			} finally {
				LOADING.remove(key);
			}
		});
		return null;
	}
}
