package net.veloclient.velo.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.config.VeloPaths;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Player face icons for friends lists and notification popups - for any account, including friends
 * who aren't anywhere near you in-game (so the tab list's own skin textures aren't available).
 * The skin comes straight from Mojang's session server (first-party, no third-party avatar
 * service), is cropped to the 8x8 face with the hat layer composited on top, and cached both as a
 * registered texture and on disk ({@code cache/heads/}) for a few hours.
 */
public final class PlayerHeads {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static final ExecutorService FETCHER = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "velo-heads");
		thread.setDaemon(true);
		return thread;
	});
	private static final long DISK_TTL_MILLIS = 6 * 60 * 60 * 1000L;
	private static final Map<String, Identifier> LOADED = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();

	private PlayerHeads() {
	}

	/** Draws {@code uuid}'s face at (x, y), or a colored placeholder with the name's initial while it loads. */
	public static void draw(DrawContext context, String uuid, String name, int x, int y, int size) {
		Identifier texture = textureFor(uuid);
		if (texture != null) {
			context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, size, size, 8, 8, 8, 8);
			return;
		}
		int hue = name == null ? 0 : Math.abs(name.hashCode());
		int color = 0xFF000000 | java.awt.Color.HSBtoRGB((hue % 360) / 360f, 0.45f, 0.55f);
		VeloDraw.fillRounded(context, x, y, size, size, Math.max(1, size / 6), color);
		if (name != null && !name.isEmpty() && size >= 10) {
			var textRenderer = MinecraftClient.getInstance().textRenderer;
			String initial = name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
			context.drawTextWithShadow(textRenderer, initial, x + (size - textRenderer.getWidth(initial)) / 2,
					y + (size - 8) / 2, 0xFFFFFFFF);
		}
	}

	/** The registered head texture, or null while it's still loading (a fetch is started on first call). */
	public static Identifier textureFor(String uuid) {
		if (uuid == null) {
			return null;
		}
		String key = uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
		Identifier loaded = LOADED.get(key);
		if (loaded != null) {
			return loaded;
		}
		if (IN_FLIGHT.putIfAbsent(key, Boolean.TRUE) == null) {
			FETCHER.submit(() -> fetch(key));
		}
		return null;
	}

	private static void fetch(String uuid) {
		try {
			byte[] skin = cachedOrDownloaded(uuid);
			if (skin == null) {
				return;
			}
			NativeImage full = NativeImage.read(new ByteArrayInputStream(skin));
			// Legacy 64x32 skins have no real alpha in the hat layer - vanilla ignores a fully opaque
			// one (otherwise e.g. Notch's head renders as a black square), so do the same.
			boolean useHat = true;
			if (full.getHeight() == 32) {
				boolean anyTransparent = false;
				for (int py = 0; py < 8 && !anyTransparent; py++) {
					for (int px = 0; px < 8; px++) {
						if ((full.getColorArgb(40 + px, 8 + py) >>> 24) < 0x80) {
							anyTransparent = true;
							break;
						}
					}
				}
				useHat = anyTransparent;
			}
			NativeImage head = new NativeImage(8, 8, false);
			for (int py = 0; py < 8; py++) {
				for (int px = 0; px < 8; px++) {
					int base = full.getColorArgb(8 + px, 8 + py) | 0xFF000000;
					int hat = useHat ? full.getColorArgb(40 + px, 8 + py) : 0;
					head.setColorArgb(px, py, (hat >>> 24) > 0x80 ? (hat | 0xFF000000) : base);
				}
			}
			full.close();
			Identifier id = Identifier.of("velo-client", "head/" + uuid);
			MinecraftClient.getInstance().execute(() -> {
				MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "velo-head-" + uuid, head));
				LOADED.put(uuid, id);
			});
		} catch (Exception e) {
			// No skin (or offline) - the placeholder stays; try again in a new session.
		}
	}

	private static byte[] cachedOrDownloaded(String uuid) throws Exception {
		Path file = VeloPaths.root().resolve("cache").resolve("heads").resolve(uuid + ".png");
		if (Files.exists(file) && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < DISK_TTL_MILLIS) {
			return Files.readAllBytes(file);
		}
		try {
			HttpResponse<String> profile = HTTP.send(HttpRequest.newBuilder(
					URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
					.timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
			if (profile.statusCode() != 200) {
				return Files.exists(file) ? Files.readAllBytes(file) : null;
			}
			JsonObject json = JsonParser.parseString(profile.body()).getAsJsonObject();
			String encoded = json.getAsJsonArray("properties").get(0).getAsJsonObject().get("value").getAsString();
			JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))
					.getAsJsonObject().getAsJsonObject("textures");
			if (textures == null || !textures.has("SKIN")) {
				return null;
			}
			String url = textures.getAsJsonObject("SKIN").get("url").getAsString().replace("http://", "https://");
			HttpResponse<byte[]> skin = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
			if (skin.statusCode() != 200) {
				return null;
			}
			Files.createDirectories(file.getParent());
			Files.write(file, skin.body());
			return skin.body();
		} catch (Exception e) {
			return Files.exists(file) ? Files.readAllBytes(file) : null;
		}
	}
}
