package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Downloads a PNG/JPEG from an arbitrary URL and registers it as a real
 * Minecraft texture, for in-game screens that need to show remote images
 * (schematic thumbnails in {@link SchematicsScreen}'s "Browse online" tab -
 * the same shape as the launcher's {@code RemoteIconLoader}, just producing
 * a Minecraft {@link Identifier} instead of a JavaFX {@code Image}).
 *
 * <p>{@link NativeImage#read} decodes via stb_image, which handles both PNG
 * and JPEG - same decoder {@link net.veloclient.velo.client.cosmetics.CapeManager}
 * already relies on for bundled cape textures, just fed network bytes
 * instead of a local/zip file here.
 */
public final class RemoteTextureLoader {

	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final Map<String, Identifier> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> FAILED = new ConcurrentHashMap<>();

	private RemoteTextureLoader() {
	}

	/** @param onLoaded called on the render thread once the texture is registered; never called if the URL is blank, already known to fail, or the fetch/decode fails. */
	public static void load(String url, Consumer<Identifier> onLoaded) {
		if (url == null || url.isBlank() || FAILED.containsKey(url)) {
			return;
		}
		Identifier cached = CACHE.get(url);
		if (cached != null) {
			onLoaded.accept(cached);
			return;
		}
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
				HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
				if (response.statusCode() >= 400) {
					FAILED.put(url, true);
					return;
				}
				NativeImage image = NativeImage.read(new ByteArrayInputStream(response.body()));
				Identifier identifier = Identifier.of("velo-client", "remote/" + Integer.toHexString(url.hashCode()));
				MinecraftClient.getInstance().execute(() -> {
					MinecraftClient.getInstance().getTextureManager().registerTexture(identifier, new NativeImageBackedTexture(() -> url, image));
					CACHE.put(url, identifier);
					onLoaded.accept(identifier);
				});
			} catch (Exception e) {
				FAILED.put(url, true);
			}
		});
	}
}
