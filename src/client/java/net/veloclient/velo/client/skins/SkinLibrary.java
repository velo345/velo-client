package net.veloclient.velo.client.skins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;




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
 * Your saved skins ({@code ~/.velo-client/skins/}) - shared with the launcher's Skins section, same
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
		return net.veloclient.velo.config.VeloPaths.root().resolve("skins");
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

	public static void markEquipped(Skin skin) throws IOException {
		Data data = load();
		data.equippedId = skin.id();
		save(data);
	}

	/** Adds a skin PNG (64x64 or legacy 64x32). */
	public static Skin addPng(String name, byte[] png, boolean slim) throws IOException {
		int width;
		int height;
		try (var image = net.minecraft.client.texture.NativeImage.read(png)) {
			width = image.getWidth();
			height = image.getHeight();
		} catch (IOException e) {
			throw new IOException("That isn't a PNG image");
		}
		if (width != 64 || (height != 64 && height != 32)) {
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

	/** The game's own Minecraft access token (what Mojang's skin API wants). */
	public static String gameAccessToken() {
		//? if <26.1 {
		return net.minecraft.client.MinecraftClient.getInstance().getSession().getAccessToken();
		//?} else {
		/*return net.minecraft.client.Minecraft.getInstance().getUser().getAccessToken();
		*///?}
	}

	/** Uploads the skin to the signed-in Minecraft account (Mojang's API). */
	public static void equip(Skin skin, String minecraftAccessToken) throws IOException, InterruptedException {
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
	}

	private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
		return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
	}
}
