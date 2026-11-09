package net.veloclient.library;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Crosshairs and heart / food / armor icons made on velo-client.com/library/hud, saved under a short code for
 * {@code /velo import <code>} in the game. No sign-in needed; small, checked PNGs only; unused codes expire after 180 days.
 */
final class Designs {

	private static final Set<String> TYPES = Set.of("crosshair", "heart", "food", "armor");
	private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
	private static final SecureRandom RANDOM = new SecureRandom();
	private final Path dir;

	Designs(Path dataDir) throws IOException {
		dir = Files.createDirectories(dataDir.resolve("designs"));
	}

	Map<String, Object> save(JsonObject body) throws IOException {
		check(body);
		try (var list = Files.list(dir)) {
			if (list.count() > 200_000) {
				throw new Http.Fail(507, "Design storage is full right now");
			}
		}
		String id;
		do {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 7; i++) {
				sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
			}
			id = sb.toString();
		} while (Files.exists(dir.resolve(id + ".json")));
		JsonObject out = new JsonObject();
		out.addProperty("type", body.get("type").getAsString());
		out.addProperty("name", name(body));
		out.add("images", body.getAsJsonObject("images"));
		Files.writeString(dir.resolve(id + ".json"), out.toString(), StandardCharsets.UTF_8);
		return Http.map("code", id, "command", "/velo import " + id);
	}

	JsonObject get(String id) throws IOException {
		if (id == null || !id.matches("[a-z0-9]{6,10}")) {
			throw new Http.Fail(404, "No such design");
		}
		Path f = dir.resolve(id + ".json");
		if (!Files.exists(f)) {
			throw new Http.Fail(404, "No such design");
		}
		Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
		return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	void sweep() {
		long cutoff = System.currentTimeMillis() - 180L * 86_400_000L;
		try (var list = Files.list(dir)) {
			list.filter(p -> {
				try {
					return Files.getLastModifiedTime(p).toMillis() < cutoff;
				} catch (IOException e) {
					return false;
				}
			}).forEach(p -> p.toFile().delete());
		} catch (IOException ignored) {
			// next time
		}
	}

	private static String name(JsonObject b) {
		String n = b.has("name") && b.get("name").isJsonPrimitive() ? b.get("name").getAsString().replaceAll("[\\p{Cntrl}§]", "").strip() : "";
		return n.isEmpty() ? "Imported" : n.length() > 32 ? n.substring(0, 32) : n;
	}

	/** Type known, images are PNGs of sane size: crosshair square 5-63 px, icons square 5-32 px. Also used for presets. */
	static void check(JsonObject b) {
		String type = b.has("type") && b.get("type").isJsonPrimitive() ? b.get("type").getAsString() : "";
		if (!TYPES.contains(type) || !b.has("images") || !b.get("images").isJsonObject()) {
			throw new Http.Fail(400, "Unknown design");
		}
		JsonObject images = b.getAsJsonObject("images");
		Set<String> allowed = type.equals("crosshair") ? Set.of("idle") : Set.of("full", "half", "empty");
		if (images.size() == 0 || images.size() > 3) {
			throw new Http.Fail(400, "No pictures in the design");
		}
		for (var e : images.entrySet()) {
			if (!allowed.contains(e.getKey()) || !e.getValue().isJsonPrimitive()) {
				throw new Http.Fail(400, "Unexpected picture " + e.getKey());
			}
			byte[] png;
			try {
				png = Base64.getDecoder().decode(e.getValue().getAsString().replaceFirst("^data:image/png;base64,", ""));
			} catch (IllegalArgumentException ex) {
				throw new Http.Fail(400, "Pictures must be PNGs");
			}
			if (png.length > 24 * 1024) {
				throw new Http.Fail(400, "Picture too large");
			}
			BufferedImage img;
			try {
				img = ImageIO.read(new ByteArrayInputStream(png));
			} catch (IOException ex) {
				img = null;
			}
			int max = type.equals("crosshair") ? 63 : 32;
			if (img == null || img.getWidth() != img.getHeight() || img.getWidth() < 5 || img.getWidth() > max) {
				throw new Http.Fail(400, "Pictures must be square PNGs, 5 to " + max + " px");
			}
		}
	}
}
