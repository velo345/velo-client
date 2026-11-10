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
		} else {
			// macOS: a game started from the Dock doesn't get the shell's PATH, so Homebrew's FFmpeg (Apple Silicon:
			// /opt/homebrew, Intel: /usr/local) isn't found by name - and the downloaded build is Intel-only.
			for (String candidate : new String[] {"/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg"}) {
				if (Files.isExecutable(Path.of(candidate)) && works(candidate)) {
					binary = Path.of(candidate);
					break;
				}
			}
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

	/** Hardware encoders that failed while recording this session (the next probe skips them). */
	private static final java.util.Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** All probes failed: don't probe again (each probe is up to four ffmpeg runs) until Clips Pro is switched on again. */
	private static volatile boolean noneWorks;

	/** The hardware encoder keeps failing while recording: drop it so the next start uses the next one (or the CPU). */
	public static boolean demoteHardwareEncoder() {
		Encoder known = encoder;
		if (known == null || known.name().equals("libx264")) {
			return false;
		}
		FAILED.add(known.name());
		encoder = null;
		return true;
	}

	/** Called when Clips Pro is switched on: try every encoder again. */
	public static void resetProbe() {
		noneWorks = false;
		FAILED.clear();
	}

	/**
	 * Windows: a crashing ffmpeg.exe (a broken GPU driver encoder, a missing DLL) shows an error dialog and stays alive
	 * until someone clicks it away - several of those piled up on a player's PC. Child processes inherit the error
	 * mode, so setting it once here keeps every ffmpeg quiet (it just exits, and we retry with a delay).
	 */
	public static void quietCrashDialogs() {
		if (quieted || !System.getProperty("os.name", "").toLowerCase().contains("win")) {
			return;
		}
		quieted = true;
		try {
			// via reflection: Minecraft 1.21.x compiles against Java 21, where this API is still a preview
			Class<?> linkerC = Class.forName("java.lang.foreign.Linker");
			Class<?> arenaC = Class.forName("java.lang.foreign.Arena");
			Class<?> lookupC = Class.forName("java.lang.foreign.SymbolLookup");
			Class<?> layoutC = Class.forName("java.lang.foreign.MemoryLayout");
			Class<?> descC = Class.forName("java.lang.foreign.FunctionDescriptor");
			Class<?> segmentC = Class.forName("java.lang.foreign.MemorySegment");
			Class<?> optionC = Class.forName("java.lang.foreign.Linker$Option");
			Object linker = linkerC.getMethod("nativeLinker").invoke(null);
			Object kernel32 = lookupC.getMethod("libraryLookup", String.class, arenaC).invoke(null, "kernel32", arenaC.getMethod("global").invoke(null));
			java.util.Optional<?> symbol = (java.util.Optional<?>) lookupC.getMethod("find", String.class).invoke(kernel32, "SetErrorMode");
			if (symbol.isEmpty()) {
				return;
			}
			Object javaInt = Class.forName("java.lang.foreign.ValueLayout").getField("JAVA_INT").get(null);
			Object args = java.lang.reflect.Array.newInstance(layoutC, 1);
			java.lang.reflect.Array.set(args, 0, javaInt);
			Object descriptor = descC.getMethod("of", layoutC, args.getClass()).invoke(null, javaInt, args);
			java.lang.invoke.MethodHandle setErrorMode = (java.lang.invoke.MethodHandle) linkerC
					.getMethod("downcallHandle", segmentC, descC, java.lang.reflect.Array.newInstance(optionC, 0).getClass())
					.invoke(linker, symbol.get(), descriptor, java.lang.reflect.Array.newInstance(optionC, 0));
			// SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX
			setErrorMode.invoke(0x0001 | 0x0002 | 0x8000);
		} catch (Throwable ignored) {
			// older Java / FFM unavailable: keep the default error mode
		}
	}

	private static volatile boolean quieted;

	/** The best H.264 encoder that actually encodes here (tested once with a short clip of nothing). */
	public static Encoder encoder(int bitrateKbps) {
		Encoder known = encoder;
		if (known == null && noneWorks) {
			return null;
		}
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
				if (FAILED.contains(candidate[0])) {
					continue;
				}
				if (encodes(ffmpeg, candidate[0])) {
					known = new Encoder(candidate[0], candidate[1], List.of());
					break;
				}
			}
			if (known == null) {
				problem = "None of FFmpeg's H.264 encoders works on this PC";
				noneWorks = true;
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
			// the CPU fallback: at most half the cores, so the game (and the PC) stay usable
			default -> args.addAll(List.of("-preset", "ultrafast", "-tune", "zerolatency", "-b:v", rate, "-maxrate", rate, "-bufsize", bitrateKbps * 2 + "k",
					"-threads", String.valueOf(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)))));
		}
		return new Encoder(known.name(), known.label(), args);
	}

	private static boolean encodes(Path ffmpeg, String name) {
		quietCrashDialogs();
		Process process = null;
		try {
			// output discarded (reading it blocked forever on an ffmpeg stuck behind a crash dialog), hard timeout, then killed
			process = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
					"color=c=black:s=1280x720:r=60", "-frames:v", "12", "-pix_fmt", "yuv420p", "-c:v", name, "-f", "null", "-")
					.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			if (!process.waitFor(15, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return false;
			}
			return process.exitValue() == 0;
		} catch (Exception e) {
			if (process != null) {
				process.destroyForcibly();
			}
			return false;
		}
	}
}
