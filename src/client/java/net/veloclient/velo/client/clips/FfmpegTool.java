package net.veloclient.velo.client.clips;

import net.veloclient.velo.VeloClient;
import net.veloclient.velo.config.VeloPaths;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * FFmpeg for Clips Pro: the one on the PATH if there is one, else a copy downloaded once into
 * {@code <velo root>/tools/ffmpeg/} (FFmpeg is a separate program under its own GPL licence - it's fetched from the
 * public FFmpeg builds, never shipped with Velo). Also finds the fastest H.264 encoder that really works on this PC:
 * NVIDIA (NVENC), AMD (AMF), Intel (Quick Sync) or Apple (VideoToolbox), then the x264 software encoder.
 */
public final class FfmpegTool {

	/** An encoder and the arguments that make it fast and steady at a fixed bitrate. */
	public record Encoder(String name, String label, List<String> args) {
	}

	private static volatile Path binary;
	private static volatile Encoder encoder;
	private static volatile boolean downloading;
	private static volatile String problem;

	private FfmpegTool() {
	}

	private static boolean windows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static boolean mac() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		return os.contains("mac") || os.contains("darwin");
	}

	private static Path toolsDir() {
		return VeloPaths.root().resolve("tools").resolve("ffmpeg");
	}

	private static Path bundled() {
		return toolsDir().resolve(windows() ? "ffmpeg.exe" : "ffmpeg");
	}

	public static boolean downloading() {
		return downloading;
	}

	/** Why FFmpeg isn't usable (null when it is or we don't know yet). */
	public static String problem() {
		return problem;
	}

	/** The FFmpeg to use, or null if there's none yet (call {@link #ensure} to get one). Cheap after the first call. */
	public static Path binary() {
		Path known = binary;
		if (known != null) {
			return known;
		}
		if (Files.isExecutable(bundled()) && works(bundled().toString())) {
			binary = bundled();
		} else if (works("ffmpeg")) {
			binary = Path.of("ffmpeg");
		}
		return binary;
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

	/** Makes sure FFmpeg is there (downloading it once if needed), in the background; {@code done} gets null or the problem. */
	public static void ensure(Consumer<String> done) {
		if (binary() != null) {
			done.accept(null);
			return;
		}
		if (downloading) {
			return;
		}
		downloading = true;
		Thread.ofVirtual().name("velo-ffmpeg-download").start(() -> {
			String result = null;
			try {
				download();
				binary = null;
				if (binary() == null) {
					result = "The downloaded FFmpeg doesn't run on this PC";
				}
			} catch (Exception e) {
				VeloClient.LOGGER.warn("Couldn't download FFmpeg for Clips Pro", e);
				result = "Couldn't download FFmpeg: " + e.getMessage();
			} finally {
				downloading = false;
			}
			problem = result;
			done.accept(result);
		});
	}

	private static String downloadUrl() {
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm");
		if (windows()) {
			return "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";
		}
		if (mac()) {
			return "https://evermeet.cx/ffmpeg/getrelease/zip";
		}
		return "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-" + (arm ? "linuxarm64" : "linux64") + "-gpl.tar.xz";
	}

	private static void download() throws Exception {
		Files.createDirectories(toolsDir());
		String url = downloadUrl();
		HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
		Path archive = toolsDir().resolve(url.endsWith(".tar.xz") ? "download.tar.xz" : "download.zip");
		HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build(),
				HttpResponse.BodyHandlers.ofFile(archive));
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode());
		}
		try {
			if (url.endsWith(".tar.xz")) {
				// The JDK can't read xz; every Linux has tar that can.
				Path unpack = toolsDir().resolve("unpack");
				Files.createDirectories(unpack);
				Process tar = new ProcessBuilder("tar", "-xJf", archive.toString(), "-C", unpack.toString()).redirectErrorStream(true).start();
				tar.getInputStream().readAllBytes();
				if (!tar.waitFor(5, TimeUnit.MINUTES) || tar.exitValue() != 0) {
					throw new IOException("Couldn't unpack FFmpeg (tar)");
				}
				try (Stream<Path> files = Files.walk(unpack)) {
					Path found = files.filter(p -> p.getFileName().toString().equals("ffmpeg") && p.getParent().getFileName().toString().equals("bin"))
							.findFirst().orElseThrow(() -> new IOException("No ffmpeg in the download"));
					Files.move(found, bundled(), StandardCopyOption.REPLACE_EXISTING);
				}
				deleteTree(unpack);
			} else {
				String wanted = windows() ? "ffmpeg.exe" : "ffmpeg";
				boolean found = false;
				try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
					ZipEntry entry;
					while ((entry = zip.getNextEntry()) != null) {
						String name = entry.getName();
						if (!entry.isDirectory() && (name.equals(wanted) || name.endsWith("/" + wanted))) {
							copy(zip, bundled());
							found = true;
							break;
						}
					}
				}
				if (!found) {
					throw new IOException("No " + wanted + " in the download");
				}
			}
			bundled().toFile().setExecutable(true);
		} finally {
			Files.deleteIfExists(archive);
		}
	}

	private static void copy(InputStream in, Path target) throws IOException {
		Path temp = target.resolveSibling(target.getFileName() + ".part");
		Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void deleteTree(Path dir) throws IOException {
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	private static volatile String encoderList;

	/** Whether this FFmpeg has {@code name} (e.g. libsvtav1, libopus). */
	public static boolean hasEncoder(String name) {
		Path ffmpeg = binary();
		if (ffmpeg == null) {
			return false;
		}
		if (encoderList == null) {
			try {
				Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-encoders").redirectErrorStream(true).start();
				encoderList = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				p.waitFor(10, TimeUnit.SECONDS);
			} catch (Exception e) {
				encoderList = "";
			}
		}
		return encoderList.contains(" " + name + " ");
	}

	/** The best H.264 encoder that actually encodes here (tested once with a short clip of nothing). */
	public static Encoder encoder(int bitrateKbps) {
		Encoder known = encoder;
		if (known == null) {
			Path ffmpeg = binary();
			if (ffmpeg == null) {
				return null;
			}
			List<String[]> candidates = new ArrayList<>();
			if (mac()) {
				candidates.add(new String[] {"h264_videotoolbox", "Apple VideoToolbox"});
			} else {
				candidates.add(new String[] {"h264_nvenc", "NVIDIA NVENC"});
				candidates.add(new String[] {"h264_amf", "AMD AMF"});
				candidates.add(new String[] {"h264_qsv", "Intel Quick Sync"});
			}
			candidates.add(new String[] {"libx264", "x264 (CPU)"});
			for (String[] candidate : candidates) {
				if (encodes(ffmpeg, candidate[0])) {
					known = new Encoder(candidate[0], candidate[1], List.of());
					break;
				}
			}
			if (known == null) {
				problem = "None of FFmpeg's H.264 encoders works on this PC";
				return null;
			}
			encoder = known;
			VeloClient.LOGGER.info("Clips Pro: encoding with {}", known.label());
		}
		String rate = bitrateKbps + "k";
		List<String> args = new ArrayList<>(List.of("-c:v", known.name()));
		switch (known.name()) {
			case "h264_nvenc" -> args.addAll(List.of("-preset", "p4", "-tune", "ll", "-rc", "cbr", "-b:v", rate));
			case "h264_amf" -> args.addAll(List.of("-quality", "speed", "-rc", "cbr", "-b:v", rate));
			case "h264_qsv" -> args.addAll(List.of("-preset", "veryfast", "-b:v", rate));
			case "h264_videotoolbox" -> args.addAll(List.of("-realtime", "1", "-b:v", rate));
			default -> args.addAll(List.of("-preset", "ultrafast", "-tune", "zerolatency", "-b:v", rate, "-maxrate", rate, "-bufsize", bitrateKbps * 2 + "k"));
		}
		return new Encoder(known.name(), known.label(), args);
	}

	private static boolean encodes(Path ffmpeg, String name) {
		try {
			Process process = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
					"color=c=black:s=1280x720:r=60", "-frames:v", "12", "-pix_fmt", "yuv420p", "-c:v", name, "-f", "null", "-")
					.redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();
			return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}
}
