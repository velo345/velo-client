package net.veloclient.launcher.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The tester programme's server side (server TesterRoutes), for the Velo Client Tester launcher: am I a tester, the
 * builds with their "what to test" notes, downloads, and the events that tell the owner who really tests - launched,
 * minutes played, features used, feedback per test item. Blocking calls; run them off the FX thread.
 */
public final class TesterApi {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	private TesterApi() {
	}

	public static final class TesterError extends Exception {
		public final int status;

		TesterError(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private static HttpRequest.Builder request(String path, Duration timeout) throws TesterError {
		String token = LauncherSocial.sessionToken();
		if (token == null) {
			throw new TesterError(401, "Sign in to the Velo Network first");
		}
		return HttpRequest.newBuilder(URI.create(LauncherSocial.baseUrl() + path)).timeout(timeout).header("Authorization", "Bearer " + token);
	}

	private static JsonObject send(HttpRequest request) throws TesterError {
		try {
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			JsonObject json;
			try {
				json = JsonParser.parseString(response.body()).getAsJsonObject();
			} catch (RuntimeException e) {
				json = new JsonObject();
			}
			if (response.statusCode() / 100 != 2) {
				throw new TesterError(response.statusCode(), json.has("error") ? json.get("error").getAsString() : "Server error " + response.statusCode());
			}
			return json;
		} catch (IOException e) {
			throw new TesterError(0, "Couldn't reach the Velo server");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new TesterError(0, "Interrupted");
		}
	}

	/** {tester, owner, mine: {version: stat}}. */
	public static JsonObject me() throws TesterError {
		return send(request("/v1/tester/me", Duration.ofSeconds(15)).GET().build());
	}

	/** Every build, newest first: {version, notes, files: [{name, size}]}. */
	public static JsonArray builds() throws TesterError {
		JsonObject json = send(request("/v1/tester/builds", Duration.ofSeconds(15)).GET().build());
		return json.has("builds") ? json.getAsJsonArray("builds") : new JsonArray();
	}

	/** Owners: every tester's stats per build. */
	public static JsonObject overview() throws TesterError {
		return send(request("/v1/tester/overview", Duration.ofSeconds(20)).GET().build());
	}

	/** Downloads one build file into {@code target} (via a .part file); {@code progress} gets 0..1. */
	public static void download(String version, String name, long size, Path target, Consumer<Double> progress) throws TesterError, IOException {
		String path = "/v1/tester/file?version=" + enc(version) + "&name=" + enc(name);
		HttpResponse<InputStream> response;
		try {
			response = HTTP.send(request(path, Duration.ofMinutes(20)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new TesterError(0, "Interrupted");
		}
		if (response.statusCode() != 200) {
			response.body().close();
			throw new TesterError(response.statusCode(), "Download failed (" + response.statusCode() + ")");
		}
		Files.createDirectories(target.getParent());
		Path part = target.resolveSibling(target.getFileName() + ".part");
		long done = 0;
		try (InputStream in = response.body(); var out = Files.newOutputStream(part)) {
			byte[] buffer = new byte[1 << 16];
			for (int n; (n = in.read(buffer)) > 0; ) {
				out.write(buffer, 0, n);
				done += n;
				if (progress != null && size > 0) {
					progress.accept(Math.min(1.0, done / (double) size));
				}
			}
		}
		Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Fire and forget: launch / session / seen / feature / feedback (see server TesterService.event). */
	public static CompletableFuture<Void> event(JsonObject event) {
		return CompletableFuture.runAsync(() -> {
			try {
				send(request("/v1/tester/event", Duration.ofSeconds(15)).header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(event.toString())).build());
			} catch (TesterError ignored) {
				// not a tester / offline: nothing to record
			}
		});
	}

	public static JsonObject eventOf(String kind, String version) {
		JsonObject e = new JsonObject();
		e.addProperty("kind", kind);
		e.addProperty("version", version);
		return e;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
