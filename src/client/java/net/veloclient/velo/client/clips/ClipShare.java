package net.veloclient.velo.client.clips;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.network.VeloServerClient;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Clips Pro share links: squeezes a clip down hard on your PC first ({@link ProClipRecorder#compressForSharing}: AV1 +
 * Opus, at most 1080p60 and the last 2 minutes - a tiny fraction of the recording), uploads it to the
 * Velo server and hands back {@code https://velo-client.com/c/<id>}, which plays in any browser and embeds in Discord.
 * The link is kept in the clip's sidecar, so sharing it again just copies it.
 */
public final class ClipShare {

	private static final double SHARE_MAX_SECONDS = 120;

	private ClipShare() {
	}

	/** The link of a clip that was shared before, or null. */
	public static String sharedUrl(JsonObject meta) {
		if (meta == null || !meta.has("shareUrl")) {
			return null;
		}
		// Past its time the server has deleted it: share again.
		long expires = meta.has("shareExpires") ? meta.get("shareExpires").getAsLong() : 0;
		return expires > 0 && expires < System.currentTimeMillis() ? null : meta.get("shareUrl").getAsString();
	}

	/**
	 * Shares {@code clip} (an .mp4 or .avi from velo-clips). {@code progress} gets status lines, {@code done} the link or
	 * null; both on the render thread. The link is copied to the clipboard.
	 */
	public static void share(Path clip, JsonObject meta, Consumer<String> progress, Consumer<String> done) {
		share(clip, meta, -1, -1, progress, done);
	}

	/**
	 * Like {@link #share(Path, JsonObject, Consumer, Consumer)}, but only {@code from..to} seconds of it (the trimmed part
	 * in the player; negative = all of it). A trimmed share isn't remembered as the clip's link.
	 */
	public static void share(Path clip, JsonObject meta, double from, double to, Consumer<String> progress, Consumer<String> done) {
		Thread.ofVirtual().name("velo-clip-share").start(() -> {
			String url = null;
			Path temp = null;
			Path part = null;
			boolean trimmed = from >= 0 && to > from;
			try {
				if (FfmpegTool.binary() == null) {
					throw new IOException("Clips Pro needs FFmpeg - turn on Clips Pro in the Clips settings first");
				}
				Path dir = ClipRecorder.clipsDir().resolve(".share");
				Files.createDirectories(dir);
				Path source = clip;
				double seconds = meta != null && meta.has("seconds") ? meta.get("seconds").getAsDouble() : 0;
				if (trimmed) {
					say(progress, "Cutting the trimmed part...");
					part = dir.resolve(clip.getFileName().toString().replaceFirst("\\.(avi|mp4)$", "") + ".part.mp4");
					net.veloclient.velo.client.clips.core.ClipTools.trim(FfmpegTool.binary(), clip, part, from, to);
					source = part;
					seconds = to - from;
				}
				double keep = seconds > SHARE_MAX_SECONDS ? SHARE_MAX_SECONDS : 0;
				say(progress, keep > 0 ? "Compressing the last 2 minutes..." : "Compressing...");
				temp = dir.resolve(clip.getFileName().toString().replaceFirst("\\.(avi|mp4)$", "") + ".share.mp4");
				ProClipRecorder.compressForSharing(source, temp, keep);
				say(progress, String.format(java.util.Locale.ROOT, "Uploading %.1f MB...", Files.size(temp) / 1024.0 / 1024.0));
				JsonObject answer = upload(temp, meta, keep > 0 ? keep : seconds);
				url = answer.get("url").getAsString();
				long expires = answer.has("expiresAt") ? answer.get("expiresAt").getAsLong() : 0;
				Path thumb = clip.resolveSibling(clip.getFileName().toString().replaceFirst("\\.(avi|mp4)$", "") + ".jpg");
				if (Files.exists(thumb) && Files.size(thumb) < 1024 * 1024) {
					try {
						send(request("/v1/clips/thumb?id=" + answer.get("id").getAsString()).header("Content-Type", "image/jpeg")
								.POST(HttpRequest.BodyPublishers.ofFile(thumb)).build());
					} catch (Exception ignored) {
						// A clip without a thumbnail still plays.
					}
				}
				if (!trimmed) {
					remember(clip, url, expires);
				}
				int days = expires > 0 ? (int) Math.round((expires - System.currentTimeMillis()) / 86_400_000.0)
						: net.veloclient.velo.client.clips.core.ClipTools.DEFAULT_RETENTION_DAYS;
				say(progress, "Link copied · online for " + days + " days, then deleted");
			} catch (Exception e) {
				say(progress, "Couldn't share: " + e.getMessage());
			} finally {
				for (Path leftover : new Path[] {temp, part}) {
					if (leftover != null) {
						try {
							Files.deleteIfExists(leftover);
						} catch (IOException ignored) {
							// Cleared next time.
						}
					}
				}
			}
			String result = url;
			MinecraftClient.getInstance().execute(() -> {
				if (result != null) {
					MinecraftClient.getInstance().keyboard.setClipboard(result);
				}
				done.accept(result);
			});
		});
	}

	private static void say(Consumer<String> progress, String text) {
		MinecraftClient.getInstance().execute(() -> progress.accept(text));
	}

	private static HttpRequest.Builder request(String path) {
		String base = VeloServerClient.serverBaseUrl();
		String token = VeloServerClient.sessionToken();
		if (base == null || token == null) {
			throw new IllegalStateException("Not connected to the Velo Network yet");
		}
		return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMinutes(15)).header("Authorization", "Bearer " + token);
	}

	private static String send(HttpRequest request) throws Exception {
		HttpResponse<String> response = VeloServerClient.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			String message = "HTTP " + response.statusCode();
			try {
				message = JsonParser.parseString(response.body()).getAsJsonObject().get("error").getAsString();
			} catch (Exception ignored) {
				// Not JSON.
			}
			throw new IOException(message);
		}
		return response.body();
	}

	private static JsonObject upload(Path file, JsonObject meta, double seconds) throws Exception {
		StringBuilder query = new StringBuilder("?seconds=").append(seconds);
		if (meta != null) {
			for (String key : new String[] {"width", "height", "fps", "trigger"}) {
				if (meta.has(key)) {
					query.append('&').append(key).append('=').append(URLEncoder.encode(meta.get(key).getAsString(), StandardCharsets.UTF_8));
				}
			}
			if (meta.has("recap")) {
				JsonObject recap = meta.getAsJsonObject("recap");
				String outcome = recap.has("outcome") ? recap.get("outcome").getAsString() : "";
				String opponent = recap.has("opponent") && !recap.get("opponent").isJsonNull() ? recap.get("opponent").getAsString() : null;
				if (opponent != null) {
					String title = ("WON".equals(outcome) ? "Won vs " : "LOST".equals(outcome) ? "Lost vs " : "Fight vs ") + opponent;
					query.append("&title=").append(URLEncoder.encode(title, StandardCharsets.UTF_8));
				}
			}
		}
		return JsonParser.parseString(send(request("/v1/clips/upload" + query).header("Content-Type", "video/mp4")
				.POST(HttpRequest.BodyPublishers.ofFile(file)).build())).getAsJsonObject();
	}

	/** Writes the link into the clip's sidecar so it isn't uploaded twice. */
	private static void remember(Path clip, String url, long expires) {
		Path json = clip.resolveSibling(clip.getFileName().toString().replaceFirst("\\.(avi|mp4)$", "") + ".json");
		try {
			JsonObject meta = Files.exists(json) ? JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
			meta.addProperty("shareUrl", url);
			if (expires > 0) {
				meta.addProperty("shareExpires", expires);
			}
			Files.writeString(json, meta.toString(), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Only means sharing again uploads again.
		}
	}
}
