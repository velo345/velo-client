package net.veloclient.launcher.sandbox;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Party members' skins for Velo World co-op, fetched once per player from Mojang's profile service. */
final class RemoteSkins {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final Map<String, byte[][]> CACHE = new ConcurrentHashMap<>();

	private RemoteSkins() {
	}

	/** {png, {1 if slim}} or null (default skin / offline). */
	static byte[][] fetch(String uuid) {
		String id = uuid.replace("-", "");
		byte[][] cached = CACHE.get(id);
		if (cached != null) {
			return cached;
		}
		try {
			HttpResponse<String> profile = HTTP.send(HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id))
					.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
			if (profile.statusCode() != 200) {
				return null;
			}
			JsonObject json = JsonParser.parseString(profile.body()).getAsJsonObject();
			for (var property : json.getAsJsonArray("properties")) {
				JsonObject p = property.getAsJsonObject();
				if (!"textures".equals(p.get("name").getAsString())) {
					continue;
				}
				JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(p.get("value").getAsString()),
						StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("textures");
				if (textures == null || !textures.has("SKIN")) {
					return null;
				}
				JsonObject skin = textures.getAsJsonObject("SKIN");
				boolean slim = skin.has("metadata") && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
				HttpResponse<byte[]> png = HTTP.send(HttpRequest.newBuilder(URI.create(skin.get("url").getAsString().replace("http://", "https://")))
						.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofByteArray());
				byte[][] out = {png.body(), {(byte) (slim ? 1 : 0)}};
				CACHE.put(id, out);
				return out;
			}
		} catch (Exception ignored) {
			// shown with the stand-in skin
		}
		return null;
	}

	static String myName() {
		var state = net.veloclient.launcher.social.LauncherSocial.snapshot();
		return state != null && state.me != null && state.me.username != null ? state.me.username : "Player";
	}
}
