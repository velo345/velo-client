package net.veloclient.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resolves and downloads everything a profile needs, then launches Minecraft
 * directly via {@link ProcessBuilder} - this app *is* the launcher, it never
 * hands off to or installs into the official Minecraft Launcher/{@code ~/.minecraft}.
 * Call from a background thread; reports progress via {@link LaunchProgressListener}.
 */
public final class GameLauncher {

	private static final String LAUNCHER_NAME = "velo-client-launcher";
	private static final String LAUNCHER_VERSION = "0.1.0";
	private static final String DEFAULT_MAX_MEMORY_MB = "4096";
	private static final String DEFAULT_MIN_MEMORY_MB = "1024";

	private GameLauncher() {
	}

	public static Process launch(Instance instance, MinecraftSession session, LaunchProgressListener listener) throws IOException {
		GameVersion version = GameVersion.byId(instance.mcVersion());
		GameDataPaths.ensureDirectories(version.id());
		InstancePaths.ensureDirectories(instance.id());

		listener.onPhase("Resolving version info...");
		JsonObject vanilla = MojangVersionService.fetchVersionDetail(version.id());
		JsonObject fabric = FabricMetaService.fetchLoaderProfile(version);
		listener.onProgress(1.0);

		Path modsDir = InstancePaths.modsDir(instance.id());
		GameJars.installInto(modsDir, version);
		FabricApiInstaller.installInto(modsDir, version);

		JsonArray vanillaLibraries = vanilla.getAsJsonArray("libraries");
		JsonArray fabricLibraries = fabric.has("libraries") ? fabric.getAsJsonArray("libraries") : new JsonArray();
		List<LibraryResolver.ResolvedLibrary> libraries = LibraryResolver.resolve(fabricLibraries, vanillaLibraries);

		listener.onPhase("Downloading libraries...");
		downloadLibraries(libraries, listener);

		listener.onPhase("Downloading Minecraft client...");
		JsonObject clientDownload = vanilla.getAsJsonObject("downloads").getAsJsonObject("client");
		Path clientJar = GameDataPaths.clientJar(version.id());
		long clientSize = clientDownload.get("size").getAsLong();
		AtomicLong clientDone = new AtomicLong();
		Downloader.ensure(URI.create(clientDownload.get("url").getAsString()), clientJar,
				clientDownload.get("sha1").getAsString(), clientSize,
				n -> listener.onProgress(Math.min(1.0, clientDone.addAndGet(n) / (double) Math.max(1, clientSize))));

		listener.onPhase("Downloading assets...");
		var indexInfo = AssetInstaller.readAssetIndexInfo(vanilla);
		List<AssetInstaller.AssetObject> assets = AssetInstaller.loadIndex(indexInfo);
		long totalAssetBytes = assets.stream().mapToLong(AssetInstaller.AssetObject::size).sum();
		AtomicLong assetsDone = new AtomicLong();
		AssetInstaller.downloadAll(assets, n -> listener.onProgress(Math.min(1.0, assetsDone.addAndGet(n) / (double) Math.max(1, totalAssetBytes))));

		listener.onPhase("Preparing natives...");
		Path nativesDir = InstancePaths.dir(instance.id()).resolve("natives");
		NativesExtractor.prepare(nativesDir, vanillaLibraries);
		listener.onProgress(1.0);

		listener.onPhase("Starting Minecraft...");
		return startProcess(instance, version, vanilla, fabric, libraries, clientJar, nativesDir, session);
	}

	private static void downloadLibraries(List<LibraryResolver.ResolvedLibrary> libraries, LaunchProgressListener listener) throws IOException {
		long totalBytes = libraries.stream().mapToLong(l -> Math.max(l.size(), 0)).sum();
		AtomicLong done = new AtomicLong();
		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		try {
			List<Future<?>> futures = libraries.stream()
					.<Future<?>>map(lib -> executor.submit((Callable<Void>) () -> {
						Downloader.ensure(lib.url(), lib.path(), lib.sha1(), lib.size(),
								n -> listener.onProgress(Math.min(1.0, done.addAndGet(n) / (double) Math.max(1, totalBytes))));
						return null;
					}))
					.toList();
			for (Future<?> future : futures) {
				future.get();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Library download interrupted", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw cause instanceof IOException io ? io : new IOException("Failed to download libraries", cause);
		} finally {
			executor.shutdown();
		}
	}

	private static Process startProcess(Instance instance, GameVersion version, JsonObject vanilla, JsonObject fabric,
			List<LibraryResolver.ResolvedLibrary> libraries, Path clientJar, Path nativesDir, MinecraftSession session) throws IOException {
		String classpath = buildClasspath(libraries, clientJar);
		String mainClass = fabric.get("mainClass").getAsString();
		Path gameDir = InstancePaths.gameDir(instance.id());

		Map<String, String> values = new LinkedHashMap<>();
		values.put("auth_player_name", session.username());
		values.put("version_name", version.id());
		values.put("game_directory", gameDir.toAbsolutePath().toString());
		values.put("assets_root", GameDataPaths.assetsRoot().toAbsolutePath().toString());
		values.put("assets_index_name", vanilla.getAsJsonObject("assetIndex").get("id").getAsString());
		values.put("auth_uuid", session.uuid());
		values.put("auth_access_token", session.minecraftAccessToken());
		values.put("auth_xuid", "");
		values.put("clientid", "");
		values.put("user_type", "msa");
		values.put("version_type", vanilla.has("type") ? vanilla.get("type").getAsString() : "release");
		values.put("natives_directory", nativesDir.toAbsolutePath().toString());
		values.put("launcher_name", LAUNCHER_NAME);
		values.put("launcher_version", LAUNCHER_VERSION);
		values.put("classpath", classpath);

		List<String> jvmArgs = new ArrayList<>();
		jvmArgs.add("-Xmx" + DEFAULT_MAX_MEMORY_MB + "m");
		jvmArgs.add("-Xms" + DEFAULT_MIN_MEMORY_MB + "m");
		jvmArgs.addAll(resolveArguments(vanilla.getAsJsonObject("arguments").getAsJsonArray("jvm"), values));
		if (fabric.has("arguments") && fabric.getAsJsonObject("arguments").has("jvm")) {
			jvmArgs.addAll(resolveArguments(fabric.getAsJsonObject("arguments").getAsJsonArray("jvm"), values));
		}

		List<String> gameArgs = new ArrayList<>();
		gameArgs.addAll(resolveArguments(vanilla.getAsJsonObject("arguments").getAsJsonArray("game"), values));
		if (fabric.has("arguments") && fabric.getAsJsonObject("arguments").has("game")) {
			gameArgs.addAll(resolveArguments(fabric.getAsJsonObject("arguments").getAsJsonArray("game"), values));
		}

		List<String> command = new ArrayList<>();
		command.add(javaBinary());
		command.addAll(jvmArgs);
		command.add(mainClass);
		command.addAll(gameArgs);

		Files.createDirectories(gameDir);
		Path logFile = InstancePaths.logsDir(instance.id()).resolve("latest_launcher.log");
		ProcessBuilder builder = new ProcessBuilder(command)
				.directory(gameDir.toFile())
				.redirectOutput(logFile.toFile())
				.redirectErrorStream(true);
		return builder.start();
	}

	private static String buildClasspath(List<LibraryResolver.ResolvedLibrary> libraries, Path clientJar) {
		StringBuilder classpath = new StringBuilder();
		for (LibraryResolver.ResolvedLibrary library : libraries) {
			classpath.append(library.path().toAbsolutePath()).append(java.io.File.pathSeparatorChar);
		}
		classpath.append(clientJar.toAbsolutePath());
		return classpath.toString();
	}

	private static String javaBinary() throws IOException {
		String javaHome = System.getProperty("java.home");
		String exe = OsRules.currentOsName().equals("windows") ? "java.exe" : "java";
		Path path = Path.of(javaHome, "bin", exe);
		if (!Files.exists(path)) {
			// Bit us once already: a packaged (jpackage) build's bundled
			// runtime can be built without this binary (jpackage strips
			// native command-line launchers, including "java" itself, from
			// the runtime it bundles unless explicitly told to keep them -
			// see launcher/build.gradle's jpackageDist task) - ProcessBuilder's
			// own error for a missing executable is a bare, unhelpful native
			// CreateProcess/errno message, so fail with the actual path here.
			throw new IOException("No Java runtime found at " + path
					+ " - this build of the launcher is missing its own java binary, needed to start Minecraft.");
		}
		return path.toString();
	}

	private static List<String> resolveArguments(JsonArray argsArray, Map<String, String> values) {
		List<String> result = new ArrayList<>();
		if (argsArray == null) {
			return result;
		}
		for (JsonElement element : argsArray) {
			if (element.isJsonPrimitive()) {
				result.add(substitute(element.getAsString(), values));
				continue;
			}
			JsonObject conditional = element.getAsJsonObject();
			if (conditional.has("rules") && !OsRules.isAllowed(conditional.getAsJsonArray("rules"), OsRules.NO_FEATURES)) {
				continue;
			}
			JsonElement value = conditional.get("value");
			if (value.isJsonArray()) {
				for (JsonElement v : value.getAsJsonArray()) {
					result.add(substitute(v.getAsString(), values));
				}
			} else {
				result.add(substitute(value.getAsString(), values));
			}
		}
		return result;
	}

	private static String substitute(String template, Map<String, String> values) {
		String result = template;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			result = result.replace("${" + entry.getKey() + "}", entry.getValue());
		}
		return result;
	}
}
