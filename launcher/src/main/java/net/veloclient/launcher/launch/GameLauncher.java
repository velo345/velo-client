package net.veloclient.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.veloclient.launcher.AppVersion;
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
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

/**
 * Resolves and downloads everything a profile needs, then launches Minecraft
 * directly via {@link ProcessBuilder} - this app *is* the launcher, it never
 * hands off to or installs into the official Minecraft Launcher/{@code ~/.minecraft}.
 * Call from a background thread; reports progress via {@link LaunchProgressListener}.
 */
public final class GameLauncher {

	private static final String LAUNCHER_NAME = "velo-client-launcher";

	/**
	 * @param process the running game process
	 * @param logFile this run's own launcher log ({@code logs/launcher_<runId>.log}) - not shared with any other
	 *                concurrent run of the same (or another) instance, so multiple instances/runs can be live-tailed independently
	 * @param runId   unique per launch attempt, even for two concurrent launches of the same {@link Instance}
	 */
	public record LaunchResult(Process process, Path logFile, String runId) {
	}

	private GameLauncher() {
	}

	public static LaunchResult launch(Instance instance, MinecraftSession session, LaunchProgressListener listener) throws IOException {
		return launch(instance, session, listener, null);
	}

	/** @param quickPlayServerAddress nullable "host:port" - when present, launches straight into that server via Mojang's Quick Play Multiplayer feature instead of to the title screen. */
	public static LaunchResult launch(Instance instance, MinecraftSession session, LaunchProgressListener listener, String quickPlayServerAddress) throws IOException {
		String runId = UUID.randomUUID().toString();
		GameVersion version = GameVersion.byId(instance.mcVersion());
		GameDataPaths.ensureDirectories(version.id());
		InstancePaths.ensureDirectories(instance.id());

		listener.onPhase("Resolving version info...");
		JsonObject vanilla = MojangVersionService.fetchVersionDetail(version.id());
		JsonObject fabric = FabricMetaService.fetchLoaderProfile(version);
		listener.onProgress(1.0);

		Path modsDir = InstancePaths.modsDir(instance.id());
		// The same profile may already be running (e.g. a second account on it). Its mods and
		// natives are in use then - and on Windows locked - so leave them exactly as they are.
		boolean alreadyRunning = net.veloclient.launcher.instance.RunningInstanceManager.countRunning(instance.id()) > 0;
		if (!alreadyRunning) {
			GameJars.installInto(modsDir, version);
			FabricApiInstaller.installInto(modsDir, version);
			listener.onPhase("Checking performance mods...");
			PerformanceModsInstaller.installInto(instance.id(), modsDir, version);
		}

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
		if (!alreadyRunning || !Files.isDirectory(nativesDir)) {
			NativesExtractor.prepare(nativesDir, vanillaLibraries);
		}
		listener.onProgress(1.0);

		listener.onPhase("Starting Minecraft...");
		return startProcess(instance, version, vanilla, fabric, libraries, clientJar, nativesDir, session, quickPlayServerAddress, runId);
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

	private static LaunchResult startProcess(Instance instance, GameVersion version, JsonObject vanilla, JsonObject fabric,
			List<LibraryResolver.ResolvedLibrary> libraries, Path clientJar, Path nativesDir, MinecraftSession session,
			String quickPlayServerAddress, String runId) throws IOException {
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
		values.put("launcher_version", AppVersion.VERSION);
		values.put("classpath", classpath);
		Set<String> activeFeatures = Set.of();
		if (quickPlayServerAddress != null && !quickPlayServerAddress.isBlank()) {
			values.put("quickPlayMultiplayer", quickPlayServerAddress);
			activeFeatures = Set.of("is_quick_play_multiplayer");
		}

		// Memory size and garbage collector come from MemoryPlanner: automatic by default (sized
		// from this PC, this profile's mods/settings, and what the game really used last time),
		// or the fixed/per-profile amount when one is set. -Xms matches -Xmx for G1 (no heap
		// resizing stutter); ZGC starts lower and grows/shrinks on its own without pauses.
		MemoryPlanner.Plan memoryPlan = MemoryPlanner.plan(instance);
		net.veloclient.launcher.LauncherLog.info("Launching " + instance.name() + " with " + memoryPlan.summary() + " (" + String.join("; ", memoryPlan.reasons()) + ")");

		List<String> jvmArgs = new ArrayList<>(MemoryPlanner.jvmArgs(memoryPlan));
		// Stops the JVM memory-mapping its hsperfdata stats file - on a busy or slow disk, the
		// periodic writes to it are a known source of random multi-ms stalls, and nothing in a
		// game session reads those stats.
		jvmArgs.add("-XX:+PerfDisableSharedMem");
		// Compact object headers (JDK 25+, JEP 519): every Java object gets 4 bytes smaller, which
		// for Minecraft's millions of small objects means noticeably less heap churn and GC work.
		// The game runs on this same runtime (see javaBinary()), so this only goes on where that
		// runtime actually understands the flag - an unknown -XX flag would stop the JVM starting.
		if (Runtime.version().feature() >= 25) {
			jvmArgs.add("-XX:+UseCompactObjectHeaders");
		}
		// Windows' dual-stack connection ordering can spend the whole
		// connect timeout on a broken/unreachable IPv6 route before ever
		// falling back to IPv4 for a specific host, timing the connection
		// out entirely even though the server is reachable and up - Linux's
		// stack doesn't exhibit this the same way, which is why this only
		// shows up for some players/some servers on Windows. Forcing the
		// JVM to resolve IPv4-only sidesteps it; it's the standard fix for
		// this exact class of intermittent Minecraft connection timeout.
		jvmArgs.add("-Djava.net.preferIPv4Stack=true");
		if (instance.extraJvmArgs() != null && !instance.extraJvmArgs().isBlank()) {
			for (String arg : instance.extraJvmArgs().trim().split("\\s+")) {
				jvmArgs.add(arg);
			}
		}
		jvmArgs.addAll(resolveArguments(vanilla.getAsJsonObject("arguments").getAsJsonArray("jvm"), values, activeFeatures));
		if (fabric.has("arguments") && fabric.getAsJsonObject("arguments").has("jvm")) {
			jvmArgs.addAll(resolveArguments(fabric.getAsJsonObject("arguments").getAsJsonArray("jvm"), values, activeFeatures));
		}

		List<String> gameArgs = new ArrayList<>();
		gameArgs.addAll(resolveArguments(vanilla.getAsJsonObject("arguments").getAsJsonArray("game"), values, activeFeatures));
		if (fabric.has("arguments") && fabric.getAsJsonObject("arguments").has("game")) {
			gameArgs.addAll(resolveArguments(fabric.getAsJsonObject("arguments").getAsJsonArray("game"), values, activeFeatures));
		}

		Downloader.saveVerifiedIndex();
		String java = javaBinary();
		WindowsGpuPreference.preferHighPerformance(Path.of(java));
		List<String> command = new ArrayList<>();
		command.add(java);
		command.addAll(jvmArgs);
		command.add(mainClass);
		command.addAll(gameArgs);

		Files.createDirectories(gameDir);
		Path logFile = InstancePaths.logsDir(instance.id()).resolve("launcher_" + runId + ".log");
		ProcessBuilder builder = new ProcessBuilder(command)
				.directory(gameDir.toFile())
				.redirectOutput(logFile.toFile())
				.redirectErrorStream(true);
		if (MemoryPlanner.isLinux() && net.veloclient.launcher.data.LauncherSettings.get().driverShaderCache) {
			// GPU drivers keep their own compiled-shader caches (OpenGL and Vulkan alike), but
			// NVIDIA's Linux driver purges its cache down to 128 MB and Mesa's default can be
			// small - with a modpack plus shader packs that evicts the game's own shaders, which
			// then recompile (stutter) next launch. Only set where the user hasn't chosen already.
			var env = builder.environment();
			env.putIfAbsent("__GL_SHADER_DISK_CACHE", "1");
			env.putIfAbsent("__GL_SHADER_DISK_CACHE_SKIP_CLEANUP", "1");
			env.putIfAbsent("MESA_SHADER_CACHE_MAX_SIZE", "2G");
		}
		return new LaunchResult(builder.start(), logFile, runId);
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
			// A jpackage-installed build's bundled runtime doesn't reliably
			// keep this binary (see JavaRuntimeFallback's own doc comment for
			// why) - self-heal into a cache directory instead of failing.
			return JavaRuntimeFallback.ensureAvailable().toString();
		}
		return path.toString();
	}

	private static List<String> resolveArguments(JsonArray argsArray, Map<String, String> values, Set<String> activeFeatures) {
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
			if (conditional.has("rules") && !OsRules.isAllowed(conditional.getAsJsonArray("rules"), activeFeatures)) {
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
