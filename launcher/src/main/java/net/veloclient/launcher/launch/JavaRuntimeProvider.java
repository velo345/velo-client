package net.veloclient.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Picks the Java runtime a Minecraft version actually needs.
 *
 * <p>The game normally runs on the launcher's own runtime. But the launcher can be started on an
 * older Java (from source, or a system Java on PATH) - and 26.x refuses to run below Java 25: its
 * version JSON even adds {@code --sun-misc-unsafe-memory-access=allow}, which a Java 21 JVM
 * rejects before Minecraft starts ("Unrecognized option ... Could not create the Java Virtual
 * Machine", a real report). So whenever the launcher's runtime is older than the version's
 * {@code javaVersion.majorVersion}, Mojang's own runtime for that version (the exact one the
 * official launcher uses) is downloaded once into {@code runtimes/<component>/}, every file
 * checksum-verified, and the game runs on that instead.
 */
public final class JavaRuntimeProvider {

	private static final String RUNTIME_INDEX = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
	private static final String COMPLETE_MARKER = ".velo-complete";

	/** The {@code java} binary to start the game with and its feature version (21, 25...). */
	public record JavaRuntime(Path binary, int feature, boolean bundled) {
	}

	private JavaRuntimeProvider() {
	}

	public static int requiredFeature(JsonObject versionJson) {
		try {
			return versionJson.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
		} catch (Exception e) {
			return 17;
		}
	}

	public static synchronized JavaRuntime forVersion(JsonObject versionJson, LaunchProgressListener listener) throws IOException {
		int required = requiredFeature(versionJson);
		int own = Runtime.version().feature();
		if (own >= required) {
			return new JavaRuntime(ownBinary(), own, true);
		}
		String component = "java-runtime-delta";
		try {
			component = versionJson.getAsJsonObject("javaVersion").get("component").getAsString();
		} catch (Exception ignored) {
			// Older JSONs without a component name: the newest generic one.
		}
		LauncherLog.info("Launcher runs on Java " + own + " but this version needs Java " + required + " - using Mojang's " + component);
		Path dir = VeloPaths.root().resolve("runtimes").resolve(component);
		Path binary = runtimeBinary(dir);
		if (Files.exists(dir.resolve(COMPLETE_MARKER)) && Files.exists(binary)) {
			return new JavaRuntime(binary, required, false);
		}
		listener.onPhase("Downloading Java " + required + "...");
		install(component, dir, listener);
		binary = runtimeBinary(dir);
		if (!Files.exists(binary)) {
			throw new IOException("Downloaded Java " + required + " but no java binary was found in " + dir);
		}
		return new JavaRuntime(binary, required, false);
	}

	private static void install(String component, Path dir, LaunchProgressListener listener) throws IOException {
		JsonObject index = MojangVersionService.getJson(RUNTIME_INDEX);
		String platform = platformKey();
		if (!index.has(platform) || !index.getAsJsonObject(platform).has(component)
				|| index.getAsJsonObject(platform).getAsJsonArray(component).isEmpty()) {
			throw new IOException("Mojang has no " + component + " Java runtime for " + platform
					+ ". Install Java " + component + " yourself and start the launcher with it.");
		}
		JsonObject entry = index.getAsJsonObject(platform).getAsJsonArray(component).get(0).getAsJsonObject();
		JsonObject manifest = MojangVersionService.getJson(entry.getAsJsonObject("manifest").get("url").getAsString());
		Files.deleteIfExists(dir.resolve(COMPLETE_MARKER));
		Files.createDirectories(dir);

		record FileJob(Path path, String url, String sha1, long size, boolean executable) {
		}
		List<FileJob> jobs = new ArrayList<>();
		List<Map.Entry<Path, String>> links = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : manifest.getAsJsonObject("files").entrySet()) {
			Path path = dir.resolve(e.getKey()).normalize();
			if (!path.startsWith(dir)) {
				continue;
			}
			JsonObject file = e.getValue().getAsJsonObject();
			switch (file.get("type").getAsString()) {
				case "directory" -> Files.createDirectories(path);
				case "link" -> links.add(Map.entry(path, file.get("target").getAsString()));
				case "file" -> {
					JsonObject raw = file.getAsJsonObject("downloads").getAsJsonObject("raw");
					jobs.add(new FileJob(path, raw.get("url").getAsString(), raw.get("sha1").getAsString(), raw.get("size").getAsLong(),
							file.has("executable") && file.get("executable").getAsBoolean()));
				}
				default -> {
				}
			}
		}
		long total = jobs.stream().mapToLong(FileJob::size).sum();
		AtomicLong done = new AtomicLong();
		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (FileJob job : jobs) {
				futures.add(executor.submit((Callable<Void>) () -> {
					Downloader.ensure(URI.create(job.url()), job.path(), job.sha1(), job.size(),
							n -> listener.onProgress(Math.min(1.0, done.addAndGet(n) / (double) Math.max(1, total))));
					if (job.executable()) {
						job.path().toFile().setExecutable(true);
					}
					return null;
				}));
			}
			for (Future<?> future : futures) {
				future.get();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Java download interrupted", e);
		} catch (ExecutionException e) {
			throw e.getCause() instanceof IOException io ? io : new IOException("Java download failed", e.getCause());
		} finally {
			executor.shutdown();
		}
		for (Map.Entry<Path, String> link : links) {
			try {
				Files.deleteIfExists(link.getKey());
				Files.createDirectories(link.getKey().getParent());
				Files.createSymbolicLink(link.getKey(), Path.of(link.getValue()));
			} catch (Exception ignored) {
				// Symlinks are only conveniences inside macOS/Linux runtimes (legal notices etc.).
			}
		}
		Files.writeString(dir.resolve(COMPLETE_MARKER), entry.getAsJsonObject("version").get("name").getAsString(), StandardCharsets.UTF_8);
	}

	private static Path runtimeBinary(Path dir) {
		return isMac() ? dir.resolve("jre.bundle/Contents/Home/bin/java") : dir.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
	}

	private static Path ownBinary() throws IOException {
		Path path = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
		if (!Files.exists(path)) {
			// A jpackage-installed build's bundled runtime doesn't reliably keep this binary.
			return JavaRuntimeFallback.ensureAvailable();
		}
		return path;
	}

	private static String platformKey() {
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm");
		if (isWindows()) {
			return arm ? "windows-arm64" : arch.contains("64") ? "windows-x64" : "windows-x86";
		}
		if (isMac()) {
			return arm ? "mac-os-arm64" : "mac-os";
		}
		return arch.contains("64") ? "linux" : "linux-i386";
	}

	private static boolean isWindows() {
		return OsRules.currentOsName().equals("windows");
	}

	private static boolean isMac() {
		return OsRules.currentOsName().equals("osx");
	}
}
