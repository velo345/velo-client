package net.veloclient.velo.client.youtube;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.config.VeloPaths;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * yt-dlp for the YouTube overlay: finds videos (search) and turns a video into the stream links FFmpeg plays. Like FFmpeg
 * it's a separate program (Unlicense), the one on the PATH or a copy downloaded once into {@code <velo root>/tools/yt-dlp/};
 * it updates itself when YouTube changes something and a video stops resolving.
 */
public final class YtDlp {

	/** One search result. */
	public record Result(String id, String title, String channel, double seconds, String thumb, boolean live) {
		public Result(String id, String title, String channel, double seconds) {
			this(id, title, channel, seconds, null, false);
		}

		public String thumbnail() {
			return thumb != null ? thumb : "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
		}
	}

	/** A resolved video: its picture stream, its sound stream (null = in the picture stream) and what FFmpeg needs. */
	public record Stream(String id, String title, String channel, double seconds, int width, int height, double fps, String video, String audio,
			String userAgent, boolean live) {
	}

	private static final Pattern TWITCH = Pattern.compile("(?:https?://)?(?:www\\.|m\\.)?twitch\\.tv/(videos/\\d+|[A-Za-z0-9_]{3,25})(?:[/?#]|$)");
	private static final Pattern CLIP = Pattern.compile("(?:clips\\.twitch\\.tv/(?:embed\\?clip=)?|twitch\\.tv/[A-Za-z0-9_]+/clip/)([A-Za-z0-9_-]{4,100})");
	private static final Pattern ID = Pattern.compile("(?:v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})");
	private static volatile Path binary;
	private static volatile boolean updatedThisSession;
	private static final Object DOWNLOAD_LOCK = new Object();

	private YtDlp() {
	}

	private static boolean windows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static boolean mac() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		return os.contains("mac") || os.contains("darwin");
	}

	private static Path bundled() {
		return VeloPaths.root().resolve("tools").resolve("yt-dlp").resolve(windows() ? "yt-dlp.exe" : "yt-dlp");
	}

	/** The video id in a YouTube link (watch, youtu.be, shorts, live, embed), or null when it isn't one. */
	public static String videoId(String text) {
		if (text == null) {
			return null;
		}
		String t = text.trim();
		if (t.matches("[A-Za-z0-9_-]{11}") && !t.contains(" ")) {
			return t;
		}
		Matcher m = ID.matcher(t);
		return m.find() ? m.group(1) : null;
	}

	/** A Twitch channel or VOD link as a clean https link, or null when {@code text} isn't one. */
	public static String twitchLink(String text) {
		if (text == null) {
			return null;
		}
		Matcher clip = CLIP.matcher(text.trim());
		if (clip.find()) {
			return "https://clips.twitch.tv/" + clip.group(1);
		}
		Matcher m = TWITCH.matcher(text.trim());
		if (!m.find()) {
			return null;
		}
		String path = m.group(1);
		if (path.equalsIgnoreCase("directory") || path.equalsIgnoreCase("videos")) {
			return null;
		}
		return "https://www.twitch.tv/" + (path.startsWith("videos/") ? path : path.toLowerCase(Locale.ROOT));
	}

	/**
	 * A page of YouTube Shorts: from the hashtag page of {@code query} ("" = #shorts, the general feed), only real
	 * shorts (vertical, about a minute). {@code skip} pages further down for an endless feed.
	 */
	public static List<Result> shorts(String query, int skip, int count) throws IOException {
		// YouTube search filtered to short videos (the #shorts hashtag feed is mostly kids' videos YouTube won't hand
		// out without a login); only real shorts (up to 75 s) are kept. Nothing typed = a Minecraft feed.
		String q = query == null || query.isBlank() ? "minecraft" : query.trim();
		String url = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8)
				+ "&sp=EgQQARgB";
		List<Result> results = new ArrayList<>();
		collectShorts(runUpdating(List.of("--flat-playlist", "--no-warnings", "-J", "--playlist-start", String.valueOf(skip + 1),
				"--playlist-end", String.valueOf(skip + count * 3), url), 60), results);
		if (results.size() < 4 && skip == 0) {
			collectShorts(runUpdating(List.of("--flat-playlist", "--no-warnings", "-J", "--playlist-end", String.valueOf(count * 2),
					"https://www.youtube.com/hashtag/shorts"), 60), results);
		}
		return results;
	}

	private static void collectShorts(String json, List<Result> out) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		JsonArray entries = root.has("entries") ? root.getAsJsonArray("entries") : new JsonArray();
		for (JsonElement e : entries) {
			JsonObject o = e.getAsJsonObject();
			String id = str(o, "id");
			String url = nonNull(str(o, "url"), "");
			double seconds = num(o, "duration");
			if (id == null || id.length() != 11 || !(url.contains("/shorts/") || seconds > 0 && seconds <= 75)) {
				continue;
			}
			if (out.stream().noneMatch(r -> r.id().equals(id))) {
				String channel = str(o, "channel");
				out.add(new Result(id, nonNull(str(o, "title"), "Short"), channel != null ? channel : nonNull(str(o, "uploader"), ""), seconds));
			}
		}
	}

	/** True for a link straight to one Twitch video or clip (not a channel). */
	public static boolean twitchVideo(String link) {
		return link != null && (link.contains("/videos/") || link.contains("clips.twitch.tv/"));
	}

	/** A Twitch channel's live stream (when it's live) and its latest videos. {@code channel} is a name or a link. */
	public static List<Result> twitch(String channel, int count) throws IOException {
		String link = twitchLink(channel);
		String name = link != null ? link.substring(link.lastIndexOf('/') + 1) : channel.trim().replaceAll("[^A-Za-z0-9_]", "").toLowerCase(Locale.ROOT);
		if (name.isEmpty()) {
			return List.of();
		}
		String home = "https://www.twitch.tv/" + name;
		// the live check and the video list run at the same time
		java.util.concurrent.CompletableFuture<Result> live = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				JsonObject o = JsonParser.parseString(run(List.of("--no-warnings", "-J", "--skip-download", "-f", "worst/best", home), 40)).getAsJsonObject();
				String title = nonNull(str(o, "description"), nonNull(str(o, "title"), name));
				return new Result(home, title, nonNull(str(o, "uploader"), name), 0, twitchThumb(str(o, "thumbnail")), true);
			} catch (Exception offline) {
				return null;
			}
		});
		List<Result> results = new ArrayList<>();
		try {
			String json = runUpdating(List.of("--flat-playlist", "--no-warnings", "-J", "--playlist-end", String.valueOf(count), home + "/videos"), 45);
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			JsonArray entries = root.has("entries") ? root.getAsJsonArray("entries") : new JsonArray();
			for (JsonElement e : entries) {
				JsonObject o = e.getAsJsonObject();
				String url = str(o, "url");
				if (url == null || !url.contains("twitch.tv/videos/")) {
					continue;
				}
				results.add(new Result(url, nonNull(str(o, "title"), "Twitch video"), nonNull(str(o, "uploader"), name), num(o, "duration"),
						twitchThumb(str(o, "thumbnail")), false));
			}
		} catch (IOException e) {
			if (live.join() == null) {
				throw e;
			}
		}
		Result now = live.join();
		if (now != null) {
			results.add(0, now);
		}
		return results;
	}

	private static String twitchThumb(String url) {
		return url == null ? null : url.replace("%{width}", "320").replace("%{height}", "180").replace("{width}", "320").replace("{height}", "180");
	}

	/** The yt-dlp to use, downloading it first if this PC has none (blocks - call from a worker thread). */
	public static Path ensure() throws IOException {
		Path known = binary;
		if (known != null) {
			return known;
		}
		synchronized (DOWNLOAD_LOCK) {
			if (binary != null) {
				return binary;
			}
			String forced = System.getProperty("velo.ytdlp");
			if (forced != null && works(forced)) {
				binary = Path.of(forced);   // dev/test runs
				return binary;
			}
			// Always Velo's own, current copy: YouTube changes every few weeks and a yt-dlp from the PATH is often months
			// old (an 8-month-old one got 403 on every audio stream, 2026-11-15). The PATH one is only a last resort.
			if (Files.isExecutable(bundled()) && works(bundled().toString())) {
				binary = bundled();
				refreshIfOld();
			} else {
				try {
					download();
					if (!works(bundled().toString())) {
						throw new IOException("The downloaded yt-dlp doesn't run on this PC");
					}
					binary = bundled();
				} catch (IOException e) {
					if (!works("yt-dlp")) {
						throw e;
					}
					binary = Path.of("yt-dlp");
				}
			}
			return binary;
		}
	}

	/** Updates Velo's yt-dlp in the background when it's older than 3 days (once per session). */
	private static void refreshIfOld() {
		try {
			long age = System.currentTimeMillis() - Files.getLastModifiedTime(bundled()).toMillis();
			if (age < 3L * 24 * 3600 * 1000 || updatedThisSession) {
				return;
			}
		} catch (IOException e) {
			return;
		}
		updatedThisSession = true;
		Thread.ofVirtual().name("velo-yt-dlp-update").start(() -> {
			try {
				Process p = new ProcessBuilder(bundled().toString(), "-U").redirectErrorStream(true).start();
				p.getInputStream().readAllBytes();
				p.waitFor(120, TimeUnit.SECONDS);
				Files.setLastModifiedTime(bundled(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
			} catch (Exception ignored) {
				// next session
			}
		});
	}

	private static boolean works(String command) {
		try {
			Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();
			return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	private static void download() throws IOException {
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm");
		String file = windows() ? "yt-dlp.exe" : mac() ? "yt-dlp_macos" : arm ? "yt-dlp_linux_aarch64" : "yt-dlp_linux";
		String url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/" + file;
		Files.createDirectories(bundled().getParent());
		Path part = bundled().resolveSibling(bundled().getFileName() + ".part");
		try {
			HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
			HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
					HttpResponse.BodyHandlers.ofFile(part));
			if (response.statusCode() != 200) {
				throw new IOException("HTTP " + response.statusCode());
			}
			Files.move(part, bundled(), StandardCopyOption.REPLACE_EXISTING);
			bundled().toFile().setExecutable(true);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Download interrupted");
		} finally {
			Files.deleteIfExists(part);
		}
	}

	/** Runs yt-dlp and returns its standard output; throws with yt-dlp's own last error line on failure. */
	private static String run(List<String> args, int timeoutSeconds) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(ensure().toString());
		command.addAll(args);
		Process process = new ProcessBuilder(command).start();
		byte[][] err = new byte[1][];
		Thread errors = Thread.ofVirtual().start(() -> {
			try {
				err[0] = process.getErrorStream().readAllBytes();
			} catch (IOException ignored) {
				err[0] = new byte[0];
			}
		});
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		try {
			if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("YouTube took too long to answer");
			}
			errors.join(2000);
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted");
		}
		if (process.exitValue() != 0) {
			String text = err[0] == null ? "" : new String(err[0], StandardCharsets.UTF_8);
			String last = text.lines().filter(l -> l.contains("ERROR")).reduce((a, b) -> b).orElse(text.lines().reduce((a, b) -> b).orElse("yt-dlp failed"));
			throw new IOException(last.replace("ERROR: ", "").trim());
		}
		return out;
	}

	/** Runs yt-dlp; when it fails once, updates it (YouTube changes often) and tries again. */
	private static String runUpdating(List<String> args, int timeoutSeconds) throws IOException {
		try {
			return run(args, timeoutSeconds);
		} catch (IOException first) {
			// only errors a newer yt-dlp fixes are worth the wait for an update (up to a minute) - not "this video is
			// unavailable / private / not live", which used to block the first video of a session for nothing
			String m = String.valueOf(first.getMessage());
			boolean fixable = m.contains("Unable to extract") || m.contains("Sign in to confirm") || m.contains("HTTP Error 403")
					|| m.contains("nsig") || m.contains("player") || m.contains("Failed to parse") || m.contains("JSON");
			if (!fixable || updatedThisSession || !ensure().equals(bundled())) {
				throw first;
			}
			updatedThisSession = true;
			try {
				run(List.of("-U"), 60);
				VeloClient.LOGGER.info("YouTube: updated yt-dlp after '{}'", first.getMessage());
			} catch (IOException updateFailed) {
				throw first;
			}
			return run(args, timeoutSeconds);
		}
	}

	/** Up to {@code count} videos for {@code query}. */
	public static List<Result> search(String query, int count) throws IOException {
		String json = runUpdating(List.of("--flat-playlist", "--no-warnings", "-J", "ytsearch" + count + ":" + query), 45);
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		List<Result> results = new ArrayList<>();
		JsonArray entries = root.has("entries") ? root.getAsJsonArray("entries") : new JsonArray();
		for (JsonElement e : entries) {
			JsonObject o = e.getAsJsonObject();
			String id = str(o, "id");
			if (id == null || id.length() != 11) {
				continue;
			}
			String channel = str(o, "channel");
			results.add(new Result(id, nonNull(str(o, "title"), "Untitled"), channel != null ? channel : nonNull(str(o, "uploader"), ""),
					num(o, "duration")));
		}
		return results;
	}

	private record Cached(Stream stream, long at) {
	}

	private static final java.util.Map<String, Cached> RESOLVED = new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.Set<String> PREFETCHING = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Resolves {@code id} in the background so playing it later starts right away (the next Shorts). */
	public static void prefetch(String id, int maxHeight) {
		String key = id + "@" + maxHeight;
		Cached c = RESOLVED.get(key);
		if (c != null && System.currentTimeMillis() - c.at() < 240_000 || !PREFETCHING.add(key)) {
			return;
		}
		Thread.ofVirtual().name("velo-yt-prefetch").start(() -> {
			try {
				resolve(id, maxHeight);
			} catch (Exception ignored) {
				// it fails again when played - and is skipped then
			} finally {
				PREFETCHING.remove(key);
			}
		});
	}

	/** Resolves a video to playable streams, at most {@code maxHeight} pixels tall (resolved links are kept 4 minutes). */
	public static Stream resolve(String id, int maxHeight) throws IOException {
		String key = id + "@" + maxHeight;
		Cached c = RESOLVED.get(key);
		if (c != null && System.currentTimeMillis() - c.at() < 240_000) {
			return c.stream();
		}
		Stream s = resolveNow(id, maxHeight);
		if (!s.live()) {
			RESOLVED.put(key, new Cached(s, System.currentTimeMillis()));
			if (RESOLVED.size() > 60) {
				RESOLVED.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().at() > 240_000);
			}
		}
		return s;
	}

	private static Stream resolveNow(String id, int maxHeight) throws IOException {
		// H.264 first (FFmpeg decodes it fastest, often in hardware); never AV1 (slow in software).
		String h = String.valueOf(maxHeight);
		if (id.startsWith("https://")) {
			return resolveTwitch(id, h);
		}
		String format = "bv*[height<=?" + h + "][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=?" + h + "][vcodec!^=av01]+ba/b[height<=?" + h + "]/b";
		String json;
		try {
			json = runUpdating(List.of("--no-playlist", "--no-warnings", "-J", "-f", format, "https://www.youtube.com/watch?v=" + id), 60);
		} catch (IOException e) {
			String m = String.valueOf(e.getMessage());
			if (!m.contains("not available") && !m.contains("Requested format")) {
				throw e;
			}
			// Shorts (and some videos) only come through YouTube's Android client right now
			json = run(List.of("--no-playlist", "--no-warnings", "-J", "--extractor-args", "youtube:player_client=android,default", "-f", format,
					"https://www.youtube.com/watch?v=" + id), 60);
		}
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		String video;
		String audio = null;
		String formatAgent = null;
		int width;
		int height;
		double fps;
		if (o.has("requested_formats") && o.get("requested_formats").isJsonArray() && o.getAsJsonArray("requested_formats").size() >= 2) {
			JsonArray parts = o.getAsJsonArray("requested_formats");
			JsonObject v = parts.get(0).getAsJsonObject();
			JsonObject a = parts.get(1).getAsJsonObject();
			if ("none".equals(str(v, "vcodec"))) {
				JsonObject swap = v;
				v = a;
				a = swap;
			}
			video = str(v, "url");
			audio = str(a, "url");
			formatAgent = agent(v);
			width = (int) num(v, "width");
			height = (int) num(v, "height");
			fps = num(v, "fps");
		} else {
			video = str(o, "url");
			formatAgent = agent(o);
			width = (int) num(o, "width");
			height = (int) num(o, "height");
			fps = num(o, "fps");
		}
		if (video == null) {
			throw new IOException("YouTube didn't give a playable stream for this video");
		}
		// each format carries the headers of the YouTube client it came from (web, Android VR, ...)
		String userAgent = formatAgent;
		String channel = str(o, "channel");
		return new Stream(id, nonNull(str(o, "title"), "YouTube"), channel != null ? channel : nonNull(str(o, "uploader"), ""), num(o, "duration"),
				width > 0 ? width : 1280, height > 0 ? height : 720, fps > 0 ? fps : 30, video, audio, userAgent,
				o.has("is_live") && !o.get("is_live").isJsonNull() && o.get("is_live").getAsBoolean());
	}

	/** A Twitch live stream or video: one HLS stream with picture and sound, played by FFmpeg directly. */
	private static Stream resolveTwitch(String link, String h) throws IOException {
		String format = "best[height<=?" + h + "][vcodec!^=av01]/best[height<=?" + h + "]/best";
		JsonObject o = JsonParser.parseString(runUpdating(List.of("--no-playlist", "--no-warnings", "-J", "-f", format, link), 60)).getAsJsonObject();
		String video = str(o, "url");
		if (video == null) {
			throw new IOException(link.contains("twitch.tv/") && !link.contains("/videos/") && !link.contains("clips.") ? "This channel isn't live right now"
					: "This link has no video that can be played");
		}
		boolean live = o.has("is_live") && !o.get("is_live").isJsonNull() && o.get("is_live").getAsBoolean();
		String title = live ? nonNull(str(o, "description"), nonNull(str(o, "title"), "Live")) : nonNull(str(o, "title"), "Video");
		int width = (int) num(o, "width");
		int height = (int) num(o, "height");
		double fps = num(o, "fps");
		return new Stream(link, title, nonNull(str(o, "uploader"), ""), live ? 0 : num(o, "duration"), width > 0 ? width : 1280, height > 0 ? height : 720,
				fps > 0 ? fps : 30, video, null, agent(o), live);
	}

	private static String agent(JsonObject format) {
		return format.has("http_headers") && format.get("http_headers").isJsonObject() ? str(format.getAsJsonObject("http_headers"), "User-Agent") : null;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static double num(JsonObject o, String key) {
		JsonElement e = o.get(key);
		try {
			return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
		} catch (RuntimeException ex) {
			return 0;
		}
	}

	private static String nonNull(String s, String fallback) {
		return s == null || s.isBlank() ? fallback : s;
	}
}
