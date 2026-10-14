package net.veloclient.bot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** The Velo server's Discord endpoints (admin token): redeeming link codes and unlinking. */
final class ServerApi {

	/** An answer to show the Discord user as it is. */
	static final class ApiException extends Exception {
		ApiException(String message) {
			super(message);
		}
	}

	record Linked(String uuid, String username) {
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private final String baseUrl;
	private final String adminToken;

	ServerApi(String baseUrl, String adminToken) {
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.adminToken = adminToken;
	}

	Linked link(String code, String discordId, String discordName) throws ApiException {
		JsonObject body = new JsonObject();
		body.addProperty("code", code);
		body.addProperty("discordId", discordId);
		body.addProperty("discordName", discordName);
		JsonObject answer = post("/v1/admin/discord/link", body);
		return new Linked(answer.get("uuid").getAsString(), answer.get("username").getAsString());
	}

	boolean unlink(String discordId) throws ApiException {
		JsonObject body = new JsonObject();
		body.addProperty("discordId", discordId);
		return post("/v1/admin/discord/unlink", body).get("removed").getAsBoolean();
	}

	private JsonObject post(String path, JsonObject body) throws ApiException {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
					.header("Content-Type", "application/json").header("X-Velo-Admin-Token", adminToken)
					.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			JsonObject json;
			try {
				json = JsonParser.parseString(response.body()).getAsJsonObject();
			} catch (RuntimeException e) {
				json = new JsonObject();
			}
			if (response.statusCode() / 100 != 2) {
				throw new ApiException(json.has("error") ? json.get("error").getAsString() : "The Velo server answered HTTP " + response.statusCode());
			}
			return json;
		} catch (ApiException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException("Interrupted");
		} catch (Exception e) {
			throw new ApiException("Couldn't reach the Velo server");
		}
	}
}
