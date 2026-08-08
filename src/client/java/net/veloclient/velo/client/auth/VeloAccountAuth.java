package net.veloclient.velo.client.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.config.VeloPaths;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Minimal re-implementation of the launcher's own MicrosoftAuth's
 * "refresh token -> Xbox Live -> XSTS -> Minecraft Services" chain (see
 * launcher's {@code auth/MicrosoftAuth.java} for the full sign-in flow and
 * wiki.vg/Microsoft_Authentication_Scheme for the spec). Deliberately
 * duplicated rather than shared code, since the launcher and the Fabric mod
 * are separate applications/classpaths (same reasoning as this mod's own
 * {@link VeloPaths} vs the launcher's). Reads/writes the exact same
 * {@code ~/.velo-client/config/account.json} the launcher maintains, so a
 * refresh done from here is picked up by the launcher too on its next
 * start, and vice versa.
 *
 * <p>Used only by {@code SessionAutoFixerModule} to silently re-authenticate
 * the active account in place when a server rejects the current session as
 * invalid/expired - the in-game equivalent of vanilla's own "Invalid session
 * (Try restarting your game and the launcher)" advice, without actually
 * needing to restart either.
 */
public final class VeloAccountAuth {

	// Same Microsoft Entra ID "public client" app registration id the
	// launcher uses (see MicrosoftAuth's own doc comment - it isn't secret).
	// A refresh token issued to that app id can only be redeemed by it.
	private static final String CLIENT_ID = "6cc50134-1c8d-43dc-9265-e107d0540248";
	private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
	private static final String XBOX_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
	private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
	private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
	private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
	private static final String SCOPE = "XboxLive.signin offline_access";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public record RefreshedSession(String accessToken, String uuid, String username) {
	}

	private VeloAccountAuth() {
	}

	/**
	 * Refreshes whichever account is marked active in account.json and
	 * writes the new tokens back so the fix survives past this one
	 * reconnect. Blocking - call from a background thread. Throws with a
	 * short, already user-presentable message on failure (no saved account,
	 * refresh token rejected, network error, ...).
	 */
	public static RefreshedSession refreshActiveAccount() throws AuthRefreshException {
		Path path = VeloPaths.config().resolve("account.json");
		if (!Files.exists(path)) {
			throw new AuthRefreshException("No Velo account signed in - sign in via the launcher.");
		}
		JsonObject root = readAccountsFile(path);
		if (!root.has("accounts") || !root.has("activeUuid") || root.get("activeUuid").isJsonNull()) {
			throw new AuthRefreshException("No Velo account signed in - sign in via the launcher.");
		}
		String activeUuid = root.get("activeUuid").getAsString();
		JsonArray accounts = root.getAsJsonArray("accounts");
		JsonObject activeAccount = null;
		for (JsonElement element : accounts) {
			JsonObject account = element.getAsJsonObject();
			if (account.has("uuid") && activeUuid.equals(account.get("uuid").getAsString())) {
				activeAccount = account;
				break;
			}
		}
		if (activeAccount == null || !activeAccount.has("microsoftRefreshToken")
				|| activeAccount.get("microsoftRefreshToken").isJsonNull()) {
			throw new AuthRefreshException("Saved Velo account has no refresh token - sign in again via the launcher.");
		}
		String refreshToken = activeAccount.get("microsoftRefreshToken").getAsString();

		String form = "grant_type=refresh_token"
				+ "&client_id=" + urlEncode(CLIENT_ID)
				+ "&refresh_token=" + urlEncode(refreshToken)
				+ "&scope=" + urlEncode(SCOPE);
		JsonObject tokenResponse = postForm(TOKEN_URL, form);
		if (!tokenResponse.has("access_token")) {
			throw new AuthRefreshException("Microsoft rejected the saved sign-in - sign in again via the launcher.");
		}
		String msAccessToken = tokenResponse.get("access_token").getAsString();
		String newRefreshToken = tokenResponse.has("refresh_token")
				? tokenResponse.get("refresh_token").getAsString() : refreshToken;

		XboxToken xbl = authenticateXboxLive(msAccessToken);
		XboxToken xsts = authenticateXsts(xbl.token());
		McLogin mcLogin = loginWithXbox(xsts.userHash(), xsts.token());
		Profile profile = fetchProfile(mcLogin.accessToken());

		long expiresAt = System.currentTimeMillis() + mcLogin.expiresInSeconds() * 1000L;
		activeAccount.addProperty("minecraftAccessToken", mcLogin.accessToken());
		activeAccount.addProperty("accessTokenExpiresAtEpochMillis", expiresAt);
		activeAccount.addProperty("microsoftRefreshToken", newRefreshToken);
		activeAccount.addProperty("uuid", profile.uuid());
		activeAccount.addProperty("username", profile.name());
		root.addProperty("activeUuid", profile.uuid());
		writeAccountsFile(path, root);

		return new RefreshedSession(mcLogin.accessToken(), profile.uuid(), profile.name());
	}

	private static JsonObject readAccountsFile(Path path) throws AuthRefreshException {
		try {
			return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			throw new AuthRefreshException("Couldn't read the saved Velo account.");
		}
	}

	private static void writeAccountsFile(Path path, JsonObject root) {
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			GSON.toJson(root, writer);
		} catch (IOException e) {
			// Non-fatal: the refreshed token still works for this session,
			// it just won't be picked up by the launcher on its next start.
		}
	}

	private record XboxToken(String token, String userHash) {
	}

	private static XboxToken authenticateXboxLive(String msAccessToken) throws AuthRefreshException {
		JsonObject properties = new JsonObject();
		properties.addProperty("AuthMethod", "RPS");
		properties.addProperty("SiteName", "user.auth.xboxlive.com");
		properties.addProperty("RpsTicket", "d=" + msAccessToken);
		JsonObject payload = new JsonObject();
		payload.add("Properties", properties);
		payload.addProperty("RelyingParty", "http://auth.xboxlive.com");
		payload.addProperty("TokenType", "JWT");
		return parseXboxToken(postJson(XBOX_AUTH_URL, payload));
	}

	private static XboxToken authenticateXsts(String xblToken) throws AuthRefreshException {
		JsonObject properties = new JsonObject();
		properties.addProperty("SandboxId", "RETAIL");
		JsonArray tokens = new JsonArray();
		tokens.add(xblToken);
		properties.add("UserTokens", tokens);
		JsonObject payload = new JsonObject();
		payload.add("Properties", properties);
		payload.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
		payload.addProperty("TokenType", "JWT");
		try {
			return parseXboxToken(postJson(XSTS_AUTH_URL, payload));
		} catch (AuthRefreshException e) {
			throw translateXstsError(e);
		}
	}

	private static AuthRefreshException translateXstsError(AuthRefreshException original) {
		String message = original.getMessage();
		if (message == null) {
			return original;
		}
		if (message.contains("2148916233")) {
			return new AuthRefreshException("This Microsoft account has no Xbox Live profile - sign in via the launcher to fix it.");
		}
		if (message.contains("2148916238") || message.contains("2148916236") || message.contains("2148916237")) {
			return new AuthRefreshException("This account needs verification on xbox.com - sign in via the launcher to fix it.");
		}
		return original;
	}

	private static XboxToken parseXboxToken(JsonObject response) throws AuthRefreshException {
		if (!response.has("Token") || !response.has("DisplayClaims")) {
			throw new AuthRefreshException("Xbox Live rejected the saved sign-in - sign in again via the launcher.");
		}
		String token = response.get("Token").getAsString();
		String uhs = response.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
				.get(0).getAsJsonObject().get("uhs").getAsString();
		return new XboxToken(token, uhs);
	}

	private record McLogin(String accessToken, int expiresInSeconds) {
	}

	private static McLogin loginWithXbox(String userHash, String xstsToken) throws AuthRefreshException {
		JsonObject payload = new JsonObject();
		payload.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
		JsonObject response = postJson(MC_LOGIN_URL, payload);
		if (!response.has("access_token")) {
			throw new AuthRefreshException("Minecraft Services rejected the saved sign-in.");
		}
		return new McLogin(response.get("access_token").getAsString(), response.get("expires_in").getAsInt());
	}

	private record Profile(String uuid, String name) {
	}

	private static Profile fetchProfile(String mcAccessToken) throws AuthRefreshException {
		JsonObject response = getJson(MC_PROFILE_URL, mcAccessToken);
		if (response.has("error") || !response.has("id")) {
			throw new AuthRefreshException("This account doesn't own Minecraft.");
		}
		return new Profile(response.get("id").getAsString(), response.get("name").getAsString());
	}

	// ---- HTTP plumbing ----

	private static JsonObject postForm(String url, String form) throws AuthRefreshException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(form))
				.build();
		return send(request);
	}

	private static JsonObject postJson(String url, JsonObject payload) throws AuthRefreshException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
				.build();
		return send(request);
	}

	private static JsonObject getJson(String url, String bearerToken) throws AuthRefreshException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Authorization", "Bearer " + bearerToken)
				.timeout(Duration.ofSeconds(15))
				.GET()
				.build();
		return send(request);
	}

	private static JsonObject send(HttpRequest request) throws AuthRefreshException {
		try {
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			JsonElement parsed = JsonParser.parseString(response.body());
			if (!parsed.isJsonObject()) {
				throw new AuthRefreshException("Unexpected response from " + request.uri().getHost());
			}
			return parsed.getAsJsonObject();
		} catch (AuthRefreshException e) {
			throw e;
		} catch (Exception e) {
			throw new AuthRefreshException("Network error talking to " + request.uri().getHost());
		}
	}

	private static String urlEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/** Thrown with a short, already user-presentable message - shown directly as the fix button's new label. */
	public static final class AuthRefreshException extends Exception {
		public AuthRefreshException(String message) {
			super(message);
		}
	}
}
