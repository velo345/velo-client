package net.veloclient.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

/**
 * Players' Minecraft skins for the website and the owner panel (heads in lists, the try-on preview), so browsers never
 * talk to Mojang or a third-party head service themselves. Skins come from Mojang's session server, are cached on
 * disk ({@code skins/}) and refreshed at most every 6 hours per player.
 */
final class SkinService {

	private static final long REFRESH_MILLIS = 6 * 3_600_000L;
	private static final long FAILURE_BACKOFF_MILLIS = 10 * 60_000L;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build();

	record Skin(byte[] png, boolean slim, long fetchedAt) {
	}

	private final Path dir;
	private final Map<String, Skin> memory = new ConcurrentHashMap<>();
	private final Map<String, Long> failedAt = new ConcurrentHashMap<>();
	private final Map<String, byte[]> heads = new ConcurrentHashMap<>();

	SkinService(Path dataDir) throws IOException {
		dir = Files.createDirectories(dataDir.resolve("skins"));
	}

	/** The player's skin (cached), or null if they have none / Mojang can't be reached and nothing is cached. */
	Skin skin(String uuid) {
		Skin cached = memory.get(uuid);
		long now = System.currentTimeMillis();
		if (cached == null) {
			cached = fromDisk(uuid);
			if (cached != null) {
				memory.put(uuid, cached);
			}
		}
		if (cached != null && now - cached.fetchedAt() < REFRESH_MILLIS) {
			return cached;
		}
		Long failed = failedAt.get(uuid);
		if (failed != null && now - failed < FAILURE_BACKOFF_MILLIS) {
			return cached;
		}
		try {
			Skin fresh = fetch(uuid);
			if (fresh != null) {
				memory.put(uuid, fresh);
				heads.remove(uuid);
				Files.write(dir.resolve(uuid + ".png"), fresh.png());
				Files.writeString(dir.resolve(uuid + ".json"), "{\"slim\":" + fresh.slim() + ",\"fetchedAt\":" + fresh.fetchedAt() + "}");
				failedAt.remove(uuid);
				return fresh;
			}
		} catch (Exception e) {
			System.out.println("[skins] " + uuid + ": " + e.getMessage());
		}
		failedAt.put(uuid, now);
		return cached;
	}

	/** The face with its hat layer, 64 x 64 (nearest-neighbour, crisp pixels). */
	byte[] head(String uuid) throws IOException {
		byte[] cached = heads.get(uuid);
		Skin skin = skin(uuid);
		if (skin == null) {
			return null;
		}
		if (cached != null) {
			return cached;
		}
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(skin.png()));
		if (img == null || img.getWidth() < 64) {
			return null;
		}
		int s = img.getWidth() / 64;
		BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(img, 0, 0, 64, 64, 8 * s, 8 * s, 16 * s, 16 * s, null);
		g.drawImage(img, 0, 0, 64, 64, 40 * s, 8 * s, 48 * s, 16 * s, null);
		g.dispose();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(out, "png", bytes);
		byte[] png = bytes.toByteArray();
		heads.put(uuid, png);
		return png;
	}

	private Skin fromDisk(String uuid) {
		try {
			Path png = dir.resolve(uuid + ".png");
			Path meta = dir.resolve(uuid + ".json");
			if (!Files.exists(png) || !Files.exists(meta)) {
				return null;
			}
			JsonObject m = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
			return new Skin(Files.readAllBytes(png), m.get("slim").getAsBoolean(), m.get("fetchedAt").getAsLong());
		} catch (Exception e) {
			return null;
		}
	}

	private static Skin fetch(String uuid) throws IOException, InterruptedException {
		HttpResponse<String> profile = HTTP.send(HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
				.timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
		if (profile.statusCode() != 200) {
			return null;
		}
		JsonObject json = JsonParser.parseString(profile.body()).getAsJsonObject();
		for (var p : json.getAsJsonArray("properties")) {
			JsonObject prop = p.getAsJsonObject();
			if (!"textures".equals(prop.get("name").getAsString())) {
				continue;
			}
			JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(prop.get("value").getAsString()), StandardCharsets.UTF_8))
					.getAsJsonObject().getAsJsonObject("textures");
			if (textures == null || !textures.has("SKIN")) {
				return null;
			}
			JsonObject skin = textures.getAsJsonObject("SKIN");
			String url = skin.get("url").getAsString().replace("http://", "https://");
			if (!url.startsWith("https://textures.minecraft.net/")) {
				return null;
			}
			boolean slim = skin.has("metadata") && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
			HttpResponse<byte[]> png = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
			if (png.statusCode() != 200 || png.body().length > 512 * 1024 || ImageIO.read(new ByteArrayInputStream(png.body())) == null) {
				return null;
			}
			return new Skin(png.body(), slim, System.currentTimeMillis());
		}
		return null;
	}
}
