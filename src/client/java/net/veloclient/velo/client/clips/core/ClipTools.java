package net.veloclient.velo.client.clips.core;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Clip work with FFmpeg that the game and the launcher share (plain Java - the launcher build copies this package):
 * finding or fetching FFmpeg, reading a clip's length/size/audio, trimming, squeezing a clip for a share link and
 * uploading it. FFmpeg is a separate program under its own licence, fetched from the public builds into
 * {@code <velo root>/tools/ffmpeg/} - never shipped with Velo.
 */
public final class ClipTools {

	/** What FFmpeg says about a video. {@code fps} 0 when unknown. */
	public record Info(double seconds, int width, int height, double fps, boolean audio) {
	}

	/** Shared clips are deleted by the server this many days after sharing (it tells the real number, this is the fallback). */
	public static final int DEFAULT_RETENTION_DAYS = 60;
	/** The longest piece a share link keeps (the end of a longer clip). */
	public static final double SHARE_MAX_SECONDS = 120;

	private static final Map<String, String> ENCODERS = new ConcurrentHashMap<>();

	private ClipTools() {
	}

	private static boolean windows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static boolean mac() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		return os.contains("mac") || os.contains("darwin");
	}

	// ---- Finding FFmpeg ----

	/** The FFmpeg in {@code toolsDir} (Velo's own download) or on the PATH, or null. */
	public static Path findFfmpeg(Path toolsDir) {
		Path own = toolsDir.resolve(windows() ? "ffmpeg.exe" : "ffmpeg");
		if (Files.isExecutable(own) && works(own.toString())) {
			return own;
		}
		return works("ffmpeg") ? Path.of("ffmpeg") : null;
	}

	private static boolean works(String command) {
		try {
			Process process = new ProcessBuilder(command, "-hide_banner", "-version").redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();
			return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	/** Downloads FFmpeg once into {@code toolsDir}; returns it. Blocks (run it off the UI thread). */
	public static Path downloadFfmpeg(Path toolsDir) throws Exception {
		Files.createDirectories(toolsDir);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm");
		String url = windows() ? "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"
				: mac() ? "https://evermeet.cx/ffmpeg/getrelease/zip"
				: "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-" + (arm ? "linuxarm64" : "linux64") + "-gpl.tar.xz";
		Path target = toolsDir.resolve(windows() ? "ffmpeg.exe" : "ffmpeg");
		HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
		Path archive = toolsDir.resolve(url.endsWith(".tar.xz") ? "download.tar.xz" : "download.zip");
		HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build(),
				HttpResponse.BodyHandlers.ofFile(archive));
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode());
		}
		try {
			if (url.endsWith(".tar.xz")) {
				Path unpack = toolsDir.resolve("unpack");
				Files.createDirectories(unpack);
				Process tar = new ProcessBuilder("tar", "-xJf", archive.toString(), "-C", unpack.toString()).redirectErrorStream(true).start();
				tar.getInputStream().readAllBytes();
				if (!tar.waitFor(5, TimeUnit.MINUTES) || tar.exitValue() != 0) {
					throw new IOException("Couldn't unpack FFmpeg (tar)");
				}
				try (Stream<Path> files = Files.walk(unpack)) {
					Path found = files.filter(p -> p.getFileName().toString().equals("ffmpeg") && p.getParent().getFileName().toString().equals("bin"))
							.findFirst().orElseThrow(() -> new IOException("No ffmpeg in the download"));
					Files.move(found, target, StandardCopyOption.REPLACE_EXISTING);
				}
				try (Stream<Path> files = Files.walk(unpack)) {
					for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
						Files.deleteIfExists(p);
					}
				}
			} else {
				String wanted = windows() ? "ffmpeg.exe" : "ffmpeg";
				boolean found = false;
				try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
					ZipEntry entry;
					while ((entry = zip.getNextEntry()) != null) {
						String name = entry.getName();
						if (!entry.isDirectory() && (name.equals(wanted) || name.endsWith("/" + wanted))) {
							Path temp = target.resolveSibling(target.getFileName() + ".part");
							Files.copy(zip, temp, StandardCopyOption.REPLACE_EXISTING);
							Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
							found = true;
							break;
						}
					}
				}
				if (!found) {
					throw new IOException("No " + wanted + " in the download");
				}
			}
			target.toFile().setExecutable(true);
		} finally {
			Files.deleteIfExists(archive);
		}
		return target;
	}

	/** Whether this FFmpeg has {@code name} (libsvtav1, libopus, libx264...). */
	public static boolean hasEncoder(Path ffmpeg, String name) {
		String list = ENCODERS.computeIfAbsent(ffmpeg.toString(), k -> {
			try {
				Process p = new ProcessBuilder(k, "-hide_banner", "-encoders").redirectErrorStream(true).start();
				String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				p.waitFor(10, TimeUnit.SECONDS);
				return out;
			} catch (Exception e) {
				return "";
			}
		});
		return list.contains(" " + name + " ");
	}

	// ---- Reading ----

	private static final Pattern DURATION = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
	private static final Pattern VIDEO = Pattern.compile("Stream #[^\\n]*Video:[^\\n]*?(\\d{2,5})x(\\d{2,5})");
	private static final Pattern FPS = Pattern.compile("Stream #[^\\n]*Video:[^\\n]*?([\\d.]+) (?:fps|tbr)");

	/** Length, size and frame rate of a file or URL (FFmpeg's own read of it); throws when it isn't a video. */
	public static Info probe(Path ffmpeg, String source) throws IOException {
		try {
			Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-i", source).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			p.waitFor(20, TimeUnit.SECONDS);
			Matcher d = DURATION.matcher(out);
			Matcher v = VIDEO.matcher(out);
			if (!v.find()) {
				throw new IOException(out.contains("No such file") ? "The clip file is missing" : "Not a video FFmpeg can read");
			}
			double seconds = d.find() ? Integer.parseInt(d.group(1)) * 3600 + Integer.parseInt(d.group(2)) * 60 + Double.parseDouble(d.group(3)) : 0;
			Matcher f = FPS.matcher(out);
			double fps = f.find() ? Double.parseDouble(f.group(1)) : 0;
			boolean audio = Pattern.compile("Stream #[^\\n]*Audio:").matcher(out).find();
			return new Info(seconds, Integer.parseInt(v.group(1)), Integer.parseInt(v.group(2)), fps, audio);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted");
		}
	}

	// ---- Editing ----

	/**
	 * Saves {@code from..to} seconds of {@code in} as {@code out}: frame-exact (re-encoded with x264 at high quality, audio
	 * as AAC). Without x264 in this FFmpeg it cuts at the nearest keyframes instead (stream copy).
	 */
	public static void trim(Path ffmpeg, Path in, Path out, double from, double to) throws IOException, InterruptedException {
		double length = Math.max(0.1, to - from);
		List<String> command = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y",
				"-ss", fmt(from), "-i", in.toString(), "-t", fmt(length)));
		if (hasEncoder(ffmpeg, "libx264")) {
			command.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k"));
		} else {
			command.addAll(List.of("-c", "copy", "-avoid_negative_ts", "make_zero"));
		}
		command.addAll(List.of("-movflags", "+faststart", out.toString()));
		run(command, 1800);
	}

	/** A JPEG still at {@code seconds} (480 wide). */
	public static void thumbnail(Path ffmpeg, Path clip, double seconds, Path jpg) throws IOException, InterruptedException {
		run(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y", "-ss", fmt(Math.max(0, seconds)), "-i", clip.toString(),
				"-frames:v", "1", "-vf", "scale=480:-2", "-q:v", "4", jpg.toString()), 30);
	}

	/**
	 * Squeezes a clip for a share link: AV1 (SVT) + Opus at most 720p60, denoised so PvP footage stays small (~1.5
	 * Mbit/s); H.264 + AAC with the same cap when this FFmpeg has no AV1 encoder. {@code lastSeconds} > 0 keeps only
	 * the end of the clip.
	 */
	public static void compressForSharing(Path ffmpeg, Path in, Path out, double lastSeconds, int threads) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y"));
		if (lastSeconds > 0) {
			command.addAll(List.of("-sseof", "-" + fmt(lastSeconds)));
		}
		command.addAll(List.of("-i", in.toString(), "-vf", "hqdn3d=3:2:4:4,scale=-2:'min(720,ih)':flags=lanczos,fps='min(60,source_fps)'",
				"-pix_fmt", "yuv420p"));
		if (hasEncoder(ffmpeg, "libsvtav1")) {
			command.addAll(List.of("-c:v", "libsvtav1", "-preset", "8", "-crf", "50", "-g", "300", "-svtav1-params",
					"tune=0:enable-overlays=1:scd=1:film-grain=0:mbr=1000:lp=" + threads));
		} else {
			command.addAll(List.of("-c:v", "libx264", "-preset", "slow", "-tune", "animation", "-crf", "32", "-maxrate", "1500k", "-bufsize", "3000k",
					"-profile:v", "high", "-threads", String.valueOf(threads)));
		}
		if (hasEncoder(ffmpeg, "libopus")) {
			command.addAll(List.of("-c:a", "libopus", "-b:a", "48k", "-ac", "2"));
		} else {
			command.addAll(List.of("-c:a", "aac", "-b:a", "64k"));
		}
		command.addAll(List.of("-movflags", "+faststart", out.toString()));
		run(command, 3600);
	}

	// ---- Sharing ----

	/** What the Velo server answered for an upload: the link and when it's deleted (0 when it didn't say). */
	public record Shared(String id, String url, long expiresAt) {
	}

	/**
	 * Uploads an already compressed MP4 to the Velo server ({@code POST /v1/clips/upload}, Velo+ only) with its
	 * thumbnail; throws with the server's message on refusal.
	 */
	public static Shared upload(HttpClient http, String baseUrl, String token, Path mp4, Path thumbnail, Map<String, String> meta)
			throws IOException, InterruptedException {
		StringBuilder query = new StringBuilder();
		for (Map.Entry<String, String> e : meta.entrySet()) {
			if (e.getValue() != null) {
				query.append(query.isEmpty() ? '?' : '&').append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
			}
		}
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/clips/upload" + query))
				.timeout(Duration.ofMinutes(15)).header("Authorization", "Bearer " + token).header("Content-Type", "video/mp4")
				.POST(HttpRequest.BodyPublishers.ofFile(mp4)).build(), HttpResponse.BodyHandlers.ofString());
		String body = response.body();
		if (response.statusCode() / 100 != 2) {
			throw new IOException(errorOf(body, response.statusCode()));
		}
		String id = field(body, "id");
		String url = field(body, "url");
		String expires = number(body, "expiresAt");
		if (thumbnail != null && Files.exists(thumbnail) && Files.size(thumbnail) < 1024 * 1024) {
			try {
				http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/clips/thumb?id=" + id)).timeout(Duration.ofMinutes(1))
						.header("Authorization", "Bearer " + token).header("Content-Type", "image/jpeg")
						.POST(HttpRequest.BodyPublishers.ofFile(thumbnail)).build(), HttpResponse.BodyHandlers.discarding());
			} catch (IOException ignored) {
				// A clip without a thumbnail still plays.
			}
		}
		return new Shared(id, url, expires == null ? 0 : Long.parseLong(expires));
	}

	/** Reports someone else's shared clip (any signed-in player). */
	public static void report(HttpClient http, String baseUrl, String token, String clipId, String category, String reason)
			throws IOException, InterruptedException {
		String json = "{\"id\":" + quote(clipId) + ",\"category\":" + quote(category) + ",\"reason\":" + quote(reason == null ? "" : reason) + "}";
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/clips/report")).timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new IOException(errorOf(response.body(), response.statusCode()));
		}
	}

	/** The clip id in a share link ({@code https://velo-client.com/c/<id>}), or null. */
	public static String shareId(String url) {
		Matcher m = Pattern.compile("velo-client\\.com/c/([0-9A-Za-z]{10})\\b").matcher(url == null ? "" : url);
		return m.find() ? m.group(1) : null;
	}

	private static String errorOf(String body, int status) {
		String error = field(body, "error");
		return error != null ? error : "HTTP " + status;
	}

	/** A string field of a small flat JSON answer (no Gson here, so the launcher copy has no extra dependency). */
	private static String field(String json, String key) {
		Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json == null ? "" : json);
		return m.find() ? m.group(1).replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\") : null;
	}

	private static String number(String json, String key) {
		Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(json == null ? "" : json);
		return m.find() ? m.group(1) : null;
	}

	private static String quote(String s) {
		StringBuilder out = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}

	static String fmt(double seconds) {
		return String.format(Locale.ROOT, "%.3f", seconds);
	}

	private static void run(List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output;
		try (InputStream in = p.getInputStream()) {
			output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new IOException("FFmpeg took too long");
		}
		if (p.exitValue() != 0) {
			throw new IOException("FFmpeg failed: " + output.lines().reduce((a, b) -> b).orElse("exit " + p.exitValue()));
		}
	}
}
