package net.veloclient.launcher.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Implements the standard "MSAL device code -> Xbox Live -> XSTS -> Minecraft
 * Services" sign-in chain that every third-party Minecraft launcher uses
 * (documented publicly at wiki.vg/Microsoft_Authentication_Scheme). Requires
 * a Microsoft Entra ID "public client" app registration - see the README for
 * how to get a client id; it's free and the id itself isn't secret.
 *
 * <p>This performs real network calls to login.microsoftonline.com,
 * xboxlive.com, and api.minecraftservices.com. It never touches Mojang's
 * legacy (Yggdrasil) auth, never stores a password, and the resulting
 * session is used only to identify the account - launching the game itself
 * (asset/library download + JVM invocation) is not implemented yet.
 */
public final class MicrosoftAuth {

	private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
	private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
	private static final String XBOX_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
	private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
	private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
	private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
	private static final String SCOPE = "XboxLive.signin offline_access";

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final String clientId;

	public MicrosoftAuth(String clientId) {
		this.clientId = clientId;
	}

	/**
	 * Runs the full device-code sign-in flow, blocking until the user
	 * approves (or the code expires). Call from a background thread.
	 *
	 * @param onCodeReady invoked once with the code/URL to show the user, as soon as it's available
	 */
	public MinecraftSession signInWithDeviceCode(Consumer<DeviceCodeInfo> onCodeReady) {
		DeviceCodeInfo deviceCode = requestDeviceCode();
		onCodeReady.accept(deviceCode);
		String[] msTokens = pollForMicrosoftToken(deviceCode);
		return completeChain(msTokens[0], msTokens[1]);
	}

	/** Re-authenticates using a previously-stored Microsoft refresh token, without prompting the user. */
	public MinecraftSession signInWithRefreshToken(String refreshToken) {
		String form = "grant_type=refresh_token"
				+ "&client_id=" + urlEncode(clientId)
				+ "&refresh_token=" + urlEncode(refreshToken)
				+ "&scope=" + urlEncode(SCOPE);
		JsonObject response = postForm(TOKEN_URL, form);
		if (!response.has("access_token")) {
			throw new AuthException("Refresh token rejected: " + response);
		}
		String newAccessToken = response.get("access_token").getAsString();
		String newRefreshToken = response.has("refresh_token") ? response.get("refresh_token").getAsString() : refreshToken;
		return completeChain(newAccessToken, newRefreshToken);
	}

	private MinecraftSession completeChain(String msAccessToken, String msRefreshToken) {
		XboxToken xbl = authenticateXboxLive(msAccessToken);
		XboxToken xsts = authenticateXsts(xbl.token());
		McLogin mcLogin = loginWithXbox(xsts.userHash(), xsts.token());
		Profile profile = fetchProfile(mcLogin.accessToken());
		long expiresAt = System.currentTimeMillis() + mcLogin.expiresInSeconds() * 1000L;
		return new MinecraftSession(mcLogin.accessToken(), expiresAt, msRefreshToken, profile.uuid(), profile.name());
	}

	private DeviceCodeInfo requestDeviceCode() {
		String form = "client_id=" + urlEncode(clientId) + "&scope=" + urlEncode(SCOPE);
		JsonObject response = postForm(DEVICE_CODE_URL, form);
		return new DeviceCodeInfo(
				response.get("device_code").getAsString(),
				response.get("user_code").getAsString(),
				response.get("verification_uri").getAsString(),
				response.get("expires_in").getAsInt(),
				response.has("interval") ? response.get("interval").getAsInt() : 5,
				response.has("message") ? response.get("message").getAsString() : "");
	}

	/** @return {accessToken, refreshToken} */
	private String[] pollForMicrosoftToken(DeviceCodeInfo deviceCode) {
		long deadline = System.currentTimeMillis() + deviceCode.expiresInSeconds() * 1000L;
		int intervalMillis = Math.max(2, deviceCode.intervalSeconds()) * 1000;
		String form = "grant_type=urn:ietf:params:oauth:grant-type:device_code"
				+ "&client_id=" + urlEncode(clientId)
				+ "&device_code=" + urlEncode(deviceCode.deviceCode());

		while (System.currentTimeMillis() < deadline) {
			sleep(intervalMillis);
			JsonObject response = postForm(TOKEN_URL, form);
			if (response.has("access_token")) {
				String refresh = response.has("refresh_token") ? response.get("refresh_token").getAsString() : null;
				return new String[] {response.get("access_token").getAsString(), refresh};
			}
			String error = response.has("error") ? response.get("error").getAsString() : "unknown_error";
			switch (error) {
				case "authorization_pending" -> { /* keep polling */ }
				case "slow_down" -> intervalMillis += 5000;
				case "expired_token" -> throw new AuthException("The sign-in code expired before it was approved.");
				case "authorization_declined" -> throw new AuthException("Sign-in was declined.");
				default -> throw new AuthException("Microsoft sign-in failed: " + error);
			}
		}
		throw new AuthException("The sign-in code expired before it was approved.");
	}

	private record XboxToken(String token, String userHash) {
	}

	private XboxToken authenticateXboxLive(String msAccessToken) {
		JsonObject properties = new JsonObject();
		properties.addProperty("AuthMethod", "RPS");
		properties.addProperty("SiteName", "user.auth.xboxlive.com");
		properties.addProperty("RpsTicket", "d=" + msAccessToken);
		JsonObject payload = new JsonObject();
		payload.add("Properties", properties);
		payload.addProperty("RelyingParty", "http://auth.xboxlive.com");
		payload.addProperty("TokenType", "JWT");

		JsonObject response = postJson(XBOX_AUTH_URL, payload, null);
		return parseXboxToken(response);
	}

	private XboxToken authenticateXsts(String xblToken) {
		JsonObject properties = new JsonObject();
		properties.addProperty("SandboxId", "RETAIL");
		var tokens = new com.google.gson.JsonArray();
		tokens.add(xblToken);
		properties.add("UserTokens", tokens);
		JsonObject payload = new JsonObject();
		payload.add("Properties", properties);
		payload.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
		payload.addProperty("TokenType", "JWT");

		try {
			JsonObject response = postJson(XSTS_AUTH_URL, payload, null);
			return parseXboxToken(response);
		} catch (AuthException e) {
			throw translateXstsError(e);
		}
	}

	private AuthException translateXstsError(AuthException original) {
		String message = original.getMessage();
		if (message == null) {
			return original;
		}
		if (message.contains("2148916233")) {
			return new AuthException("This Microsoft account has no Xbox Live profile yet. Sign in to xbox.com once with it, then try again.");
		}
		if (message.contains("2148916238")) {
			return new AuthException("This is a child account - it needs to be added to a Microsoft Family group with an adult before it can sign in here.");
		}
		if (message.contains("2148916235")) {
			return new AuthException("Xbox Live is not available in this account's region.");
		}
		if (message.contains("2148916236") || message.contains("2148916237")) {
			return new AuthException("This account needs adult verification on xbox.com before it can sign in here.");
		}
		return original;
	}

	private XboxToken parseXboxToken(JsonObject response) {
		String token = response.get("Token").getAsString();
		String uhs = response.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
		return new XboxToken(token, uhs);
	}

	private record McLogin(String accessToken, int expiresInSeconds) {
	}

	private McLogin loginWithXbox(String userHash, String xstsToken) {
		JsonObject payload = new JsonObject();
		payload.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
		JsonObject response;
		try {
			response = postJson(MC_LOGIN_URL, payload, null);
		} catch (AuthException e) {
			throw translateMinecraftLoginError(e);
		}
		return new McLogin(response.get("access_token").getAsString(), response.get("expires_in").getAsInt());
	}

	private AuthException translateMinecraftLoginError(AuthException original) {
		String message = original.getMessage();
		if (message != null && message.contains("Invalid app registration")) {
			return new AuthException("This app's client id isn't on Mojang's allow-list yet. Since 2025, Mojang "
					+ "manually reviews and approves every new third-party app before it can reach the Java "
					+ "Edition auth APIs (anti-phishing measure) - your Azure app registration itself is fine, "
					+ "it just needs Mojang's separate approval. Submit it via the form linked from "
					+ "https://aka.ms/AppRegInfo and this will start working with no changes needed once approved.");
		}
		return original;
	}

	private record Profile(String uuid, String name) {
	}

	private Profile fetchProfile(String mcAccessToken) {
		JsonObject response = getJson(MC_PROFILE_URL, mcAccessToken);
		if (response.has("error") || !response.has("id")) {
			throw new AuthException("This account doesn't own Minecraft (no profile found).");
		}
		return new Profile(response.get("id").getAsString(), response.get("name").getAsString());
	}

	// ---- HTTP plumbing ----

	private JsonObject postForm(String url, String form) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(form))
				.build();
		return send(request);
	}

	private JsonObject postJson(String url, JsonObject payload, String bearerToken) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
		if (bearerToken != null) {
			builder.header("Authorization", "Bearer " + bearerToken);
		}
		return send(builder.build());
	}

	private JsonObject getJson(String url, String bearerToken) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Authorization", "Bearer " + bearerToken)
				.timeout(Duration.ofSeconds(15))
				.GET()
				.build();
		return send(request);
	}

	private JsonObject send(HttpRequest request) {
		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			if (response.statusCode() >= 400 && !json.has("error") && !json.has("device_code")) {
				throw new AuthException("HTTP " + response.statusCode() + " from " + request.uri() + ": " + response.body());
			}
			return json;
		} catch (AuthException e) {
			throw e;
		} catch (Exception e) {
			throw new AuthException("Network error talking to " + request.uri().getHost(), e);
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AuthException("Sign-in interrupted.");
		}
	}

	private static String urlEncode(String value) {
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
	}
}
