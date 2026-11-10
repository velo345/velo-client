package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Your saved skins ({@code ~/.velo-client/skins/}) - shared with the in-game Skins screen, same
 * files and format. Add one from a player's username/UUID (downloaded from Mojang) or a PNG, then
 * equip it on your Minecraft account with one click (Mojang's skin upload API).
 */
public final class SkinLibrary {

	public record Skin(String id, String name, boolean slim, long added) {
	}

	private static final class Data {
		List<Skin> skins = new ArrayList<>();
		String equippedId;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private SkinLibrary() {
	}

	public static Path dir() {
		return VeloPaths.root().resolve("skins");
	}

	public static Path png(Skin skin) {
		return dir().resolve(skin.id() + ".png");
	}

	private static synchronized Data load() {
		try {
			Path file = dir().resolve("library.json");
			if (Files.exists(file)) {
				Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
				if (data != null && data.skins != null) {
					data.skins.removeIf(s -> !Files.exists(dir().resolve(s.id() + ".png")));
					return data;
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// Start empty.
		}
		return new Data();
	}

	private static synchronized void save(Data data) throws IOException {
		Files.createDirectories(dir());
		Files.writeString(dir().resolve("library.json"), GSON.toJson(data), StandardCharsets.UTF_8);
	}

	public static List<Skin> all() {
		return List.copyOf(load().skins);
	}

	public static String equippedId() {
		return load().equippedId;
	}

	/** Run after the equipped skin changed (the Locker keeps the worn loadout in step). */
	public static final java.util.List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

	public static void markEquipped(Skin skin) throws IOException {
		Data data = load();
		data.equippedId = skin.id();
		save(data);
		LISTENERS.forEach(Runnable::run);
	}

	/** Adds a skin PNG (64x64 or legacy 64x32). */
	public static Skin addPng(String name, byte[] png, boolean slim) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
		if (image == null || image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
			throw new IOException("A skin must be a 64x64 (or old 64x32) PNG");
		}
		Data data = load();
		Skin skin = new Skin(UUID.randomUUID().toString().replace("-", ""), name.isBlank() ? "Skin" : name.trim(), slim,
				System.currentTimeMillis());
		Files.createDirectories(dir());
		Files.write(dir().resolve(skin.id() + ".png"), png);
		data.skins.add(0, skin);
		save(data);
		return skin;
	}

	/** Copies a player's current skin (by username or UUID) from Mojang. */
	/** A saved skin with exactly these pixels (the file's encoding doesn't matter), or null. */
	public static Skin findSame(byte[] png) {
		int[] wanted = pixels(png);
		if (wanted == null) {
			return null;
		}
		for (Skin skin : all()) {
			try {
				int[] have = pixels(java.nio.file.Files.readAllBytes(png(skin)));
				if (have != null && java.util.Arrays.equals(have, wanted)) {
					return skin;
				}
			} catch (IOException ignored) {
				// unreadable file: not a match
			}
		}
		return null;
	}

	private static int[] pixels(byte[] png) {
		try {
			java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
			if (image == null) {
				return null;
			}
			int w = image.getWidth(), h = image.getHeight();
			int[] out = image.getRGB(0, 0, w, h, null, 0, w);
			int[] withSize = new int[out.length + 2];
			withSize[0] = w;
			withSize[1] = h;
			System.arraycopy(out, 0, withSize, 2, out.length);
			return withSize;
		} catch (IOException e) {
			return null;
		}
	}

	public static Skin addFromPlayer(String nameOrUuid) throws IOException, InterruptedException {
		String query = nameOrUuid.trim();
		String uuid = query.replace("-", "");
		String displayName = query;
		if (!uuid.matches("[0-9a-fA-F]{32}")) {
			HttpResponse<String> lookup = get("https://api.mojang.com/users/profiles/minecraft/" + query);
			if (lookup.statusCode() != 200) {
				throw new IOException("No Minecraft player called \"" + query + "\"");
			}
			JsonObject json = JsonParser.parseString(lookup.body()).getAsJsonObject();
			uuid = json.get("id").getAsString();
			displayName = json.get("name").getAsString();
		}
		HttpResponse<String> profile = get("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid);
		if (profile.statusCode() != 200) {
			throw new IOException("Couldn't load that player's profile");
		}
		JsonObject json = JsonParser.parseString(profile.body()).getAsJsonObject();
		displayName = json.has("name") ? json.get("name").getAsString() : displayName;
		for (var property : json.getAsJsonArray("properties")) {
			JsonObject p = property.getAsJsonObject();
			if (!"textures".equals(p.get("name").getAsString())) {
				continue;
			}
			JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(p.get("value").getAsString()),
					StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("textures");
			if (textures == null || !textures.has("SKIN")) {
				break;
			}
			JsonObject skin = textures.getAsJsonObject("SKIN");
			boolean slim = skin.has("metadata") && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
			HttpResponse<byte[]> png = HTTP.send(HttpRequest.newBuilder(URI.create(skin.get("url").getAsString().replace("http://", "https://")))
					.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofByteArray());
			return addPng(displayName, png.body(), slim);
		}
		throw new IOException(displayName + " uses the default skin");
	}

	public static void delete(Skin skin) throws IOException {
		Data data = load();
		data.skins.removeIf(s -> s.id().equals(skin.id()));
		if (skin.id().equals(data.equippedId)) {
			data.equippedId = null;
		}
		save(data);
		Files.deleteIfExists(png(skin));
	}

	public static void rename(Skin skin, String name) throws IOException {
		Data data = load();
		data.skins.replaceAll(s -> s.id().equals(skin.id()) ? new Skin(s.id(), name.trim(), s.slim(), s.added()) : s);
		save(data);
	}

	/** Uploads the skin to the signed-in Minecraft account (Mojang's API). */
	public static void equip(Skin skin, String minecraftAccessToken, String uuid) throws IOException, InterruptedException {
		byte[] png = Files.readAllBytes(png(skin));
		String boundary = "----velo" + UUID.randomUUID().toString().replace("-", "");
		var body = new java.io.ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n" + (skin.slim() ? "slim" : "classic")
				+ "\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
				+ "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(png);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile/skins"))
				.header("Authorization", "Bearer " + minecraftAccessToken)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 401) {
			throw new IOException("Your session expired - sign in again");
		}
		if (response.statusCode() == 429) {
			throw new IOException("Too many skin changes - wait a minute and try again");
		}
		if (response.statusCode() / 100 != 2) {
			throw new IOException("Mojang refused the skin (HTTP " + response.statusCode() + ")");
		}
		markEquipped(skin);
		net.veloclient.launcher.social.SkinHeads.update(uuid, png);
	}

	private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
		return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
	}
}
