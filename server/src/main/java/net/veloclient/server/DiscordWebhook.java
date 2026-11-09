package net.veloclient.server;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Posts a message (embeds) to a Discord channel webhook, in the background; a failure is only logged. */
final class DiscordWebhook {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private DiscordWebhook() {
	}

	/** Discord's webhook URLs, with or without an API version - the bot's /setup webhooks hands out ".../api/v10/webhooks/...". */
	private static final java.util.regex.Pattern URL = java.util.regex.Pattern.compile(
			"https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/\\d+/[\\w-]+");

	static boolean valid(String url) {
		return url != null && URL.matcher(url.trim()).matches();
	}

	static void send(String url, JsonObject payload) {
		if (!valid(url)) {
			return;
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)).build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
			if (response.statusCode() / 100 != 2) {
				System.out.println("[discord] Webhook answered HTTP " + response.statusCode() + ": " + response.body());
			}
		}).exceptionally(error -> {
			System.out.println("[discord] Webhook failed: " + error.getMessage());
			return null;
		});
	}
}
