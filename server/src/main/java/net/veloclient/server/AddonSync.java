package net.veloclient.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the Velo Client Addons from the latest release of the <b>private</b> GitHub repo
 * ({@code VELO_PLUS_GITHUB_REPO}, e.g. {@code velo345/velo-client-plus}) into the {@link AddonStore}, using a
 * read-only token ({@code VELO_PLUS_GITHUB_TOKEN}). Release assets are named
 * {@code velo-addons-<version>+<minecraft>.jar}. This is how Velo+ members get their download without
 * having - or needing - any access to that repo: they ask the server (through the launcher), the server
 * hands over the jar it fetched. Runs hourly and on demand ({@code POST /v1/admin/addons/sync}).
 */
final class AddonSync {

	private static final Pattern ASSET = Pattern.compile("velo-addons-(.+)\\+([0-9a-z.\\-]+)\\.jar");
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	private final AddonStore store;
	private final String repo;
	private final String token;

	AddonSync(AddonStore store, String repo, String token) {
		this.store = store;
		this.repo = repo;
		this.token = token;
	}

	boolean enabled() {
		return repo != null && token != null;
	}

	/** Fetches whatever is new; returns how many jars were stored. */
	synchronized int sync() throws Exception {
		if (!enabled()) {
			throw new IllegalStateException("VELO_PLUS_GITHUB_REPO / VELO_PLUS_GITHUB_TOKEN aren't set");
		}
		HttpResponse<String> response = HTTP.send(request("https://api.github.com/repos/" + repo + "/releases/latest", "application/vnd.github+json"),
				HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("GitHub answered HTTP " + response.statusCode() + " for " + repo + "'s latest release");
		}
		int stored = 0;
		for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("assets")) {
			JsonObject asset = element.getAsJsonObject();
			Matcher m = ASSET.matcher(asset.get("name").getAsString());
			if (!m.matches()) {
				continue;
			}
			String version = m.group(1);
			String mc = m.group(2);
			AddonStore.Entry have = store.entry(mc);
			if (have != null && have.version().equals(version) && have.size() == asset.get("size").getAsLong()) {
				continue;
			}
			HttpResponse<byte[]> jar = HTTP.send(request("https://api.github.com/repos/" + repo + "/releases/assets/" + asset.get("id").getAsLong(),
					"application/octet-stream"), HttpResponse.BodyHandlers.ofByteArray());
			if (jar.statusCode() != 200) {
				System.out.println("[addons] Couldn't download " + asset.get("name").getAsString() + " (HTTP " + jar.statusCode() + ")");
				continue;
			}
			store.put(mc, version, jar.body());
			System.out.println("[addons] Stored velo-addons " + version + " for Minecraft " + mc + " from " + repo);
			stored++;
		}
		return stored;
	}

	private HttpRequest request(String url, String accept) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).header("Authorization", "Bearer " + token)
				.header("Accept", accept).header("X-GitHub-Api-Version", "2022-11-28").GET().build();
	}
}
