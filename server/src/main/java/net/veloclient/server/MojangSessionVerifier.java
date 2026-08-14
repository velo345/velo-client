package net.veloclient.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Proves a client actually owns the Mojang/Microsoft account it claims,
 * without this server ever seeing that account's access token - the same
 * "join a server" handshake vanilla Minecraft servers use for online-mode
 * auth (see wiki.vg/Protocol_Encryption#Authentication):
 *
 * <ol>
 *   <li>Client asks us for a random {@code serverId} ({@link
 *       SessionRegistry#createChallenge}).</li>
 *   <li>Client calls Mojang's own {@code session/minecraft/join} directly
 *       with its real access token and that {@code serverId} - this server
 *       is never involved in that call and never sees the token.</li>
 *   <li>Client tells us it did that; we ask Mojang's {@code hasJoined}
 *       endpoint (server-to-server, no token needed) whether a join with
 *       that username/serverId pair actually happened, and get the real
 *       account UUID back if so.</li>
 * </ol>
 */
final class MojangSessionVerifier {

	private static final String HAS_JOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined";

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private MojangSessionVerifier() {
	}

	/** Returns the verified account UUID (dashless, lowercase) if the join really happened, or null otherwise. */
	static String verify(String username, String serverId) {
		String url = HAS_JOINED_URL + "?username=" + urlEncode(username) + "&serverId=" + urlEncode(serverId);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		try {
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
				return null;
			}
			JsonElement parsed = JsonParser.parseString(response.body());
			if (!parsed.isJsonObject()) {
				return null;
			}
			JsonObject json = parsed.getAsJsonObject();
			if (!json.has("id")) {
				return null;
			}
			return json.get("id").getAsString().replace("-", "").toLowerCase();
		} catch (Exception e) {
			return null;
		}
	}

	private static String urlEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
