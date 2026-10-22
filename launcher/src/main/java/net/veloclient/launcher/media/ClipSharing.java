package net.veloclient.launcher.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import net.veloclient.launcher.social.LauncherSocial;
import net.veloclient.launcher.ui.ClipPlayerView;
import net.veloclient.velo.client.clips.core.ClipTools;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Clips Pro share links from the launcher, the same way the game makes them: the clip (or just its trimmed part) is
 * squeezed hard on this PC (AV1 + Opus, at most 720p60, the last 2 minutes), uploaded with your Velo session (Velo+
 * only - the server refuses everyone else) and comes back as {@code https://velo-client.com/c/<id>}, online for 60
 * days. The link is kept in the clip's sidecar so sharing the whole clip again just reuses it.
 */
public final class ClipSharing {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	private ClipSharing() {
	}

	/** The link of an earlier share that's still online, or null. */
	public static String sharedUrl(JsonObject meta) {
		if (meta == null || !meta.has("shareUrl")) {
			return null;
		}
		long expires = meta.has("shareExpires") ? meta.get("shareExpires").getAsLong() : 0;
		return expires > 0 && expires < System.currentTimeMillis() ? null : meta.get("shareUrl").getAsString();
	}

	/**
	 * Shares {@code clip}; {@code from}/{@code to} in seconds (negative = all of it). {@code progress} and {@code done}
	 * (the result, or null + a message through {@code progress}) run on the UI thread.
	 */
	public static void share(Path clip, JsonObject meta, double from, double to, Consumer<String> progress, Consumer<ClipTools.Shared> done) {
		Thread.ofVirtual().name("velo-clip-share").start(() -> {
			ClipTools.Shared shared = null;
			Path dir = clip.getParent().resolve(".share");
			String base = clip.getFileName().toString().replaceFirst("\\.mp4$", "");
			Path part = dir.resolve(base + ".part.mp4");
			Path small = dir.resolve(base + ".share.mp4");
			boolean trimmed = from >= 0 && to > from;
			try {
				String token = LauncherSocial.sessionToken();
				String server = LauncherSocial.baseUrl();
				if (token == null || server == null) {
					throw new IllegalStateException("Sign in to the Velo Network first (Friends, top right)");
				}
				Path ffmpeg = ClipPlayerView.ffmpeg();
				if (ffmpeg == null) {
					throw new IllegalStateException("FFmpeg isn't ready yet - open the clip once so the launcher can get it");
				}
				Files.createDirectories(dir);
				Path source = clip;
				double seconds = meta != null && meta.has("seconds") ? meta.get("seconds").getAsDouble() : 0;
				if (trimmed) {
					say(progress, "Cutting the trimmed part...");
					ClipTools.trim(ffmpeg, clip, part, from, to);
					source = part;
					seconds = to - from;
				}
				double keep = seconds > ClipTools.SHARE_MAX_SECONDS ? ClipTools.SHARE_MAX_SECONDS : 0;
				say(progress, keep > 0 ? "Compressing the last 2 minutes..." : "Compressing...");
				ClipTools.compressForSharing(ffmpeg, source, small, keep, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
				say(progress, String.format(java.util.Locale.ROOT, "Uploading %.1f MB...", Files.size(small) / 1024.0 / 1024.0));
				Map<String, String> query = new LinkedHashMap<>();
				query.put("seconds", String.valueOf(keep > 0 ? keep : seconds));
				if (meta != null) {
					for (String key : new String[] {"width", "height", "fps", "trigger"}) {
						if (meta.has(key)) {
							query.put(key, meta.get(key).getAsString());
						}
					}
				}
				shared = ClipTools.upload(HTTP, server, token, small, clip.resolveSibling(base + ".jpg"), query);
				if (!trimmed) {
					remember(clip, base, shared);
				}
			} catch (Exception e) {
				say(progress, "Couldn't share: " + e.getMessage());
			} finally {
				try {
					Files.deleteIfExists(part);
					Files.deleteIfExists(small);
				} catch (Exception ignored) {
					// Cleared next time.
				}
			}
			ClipTools.Shared result = shared;
			Platform.runLater(() -> done.accept(result));
		});
	}

	private static void say(Consumer<String> progress, String text) {
		Platform.runLater(() -> progress.accept(text));
	}

	private static void remember(Path clip, String base, ClipTools.Shared shared) {
		Path json = clip.resolveSibling(base + ".json");
		try {
			JsonObject meta = Files.exists(json) ? JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
			meta.addProperty("shareUrl", shared.url());
			if (shared.expiresAt() > 0) {
				meta.addProperty("shareExpires", shared.expiresAt());
			}
			Files.writeString(json, meta.toString(), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Only means sharing again uploads again.
		}
	}
}
