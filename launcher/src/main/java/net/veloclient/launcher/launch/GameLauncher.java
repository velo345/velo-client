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

		// Minecraft, its assets and maybe a Java runtime: ~1.5 GB on a first start. Say so up front instead of failing half-way.
		LaunchErrors.requireFreeSpace(GameDataPaths.assetsRoot(), 1536L * 1024 * 1024);
		listener.onPhase("Resolving version info...");
		JsonObject vanilla = version.ornithe() ? MojangVersionService.fetchOrnitheVersionDetail(version.id())
				: MojangVersionService.fetchVersionDetail(version.id());
		listener.onProgress(1.0);

		Path modsDir = InstancePaths.modsDir(instance.id());
		// The same profile may already be running (e.g. a second account on it). Its mods and
		// natives are in use then - and on Windows locked - so leave them exactly as they are.
		boolean alreadyRunning = net.veloclient.launcher.instance.RunningInstanceManager.countRunning(instance.id()) > 0;
		if (!alreadyRunning) {
			GameJars.installInto(modsDir, version);
			FabricApiInstaller.installInto(modsDir, version);
			listener.onPhase("Checking recommended mods...");
			PerformanceModsInstaller.installInto(instance.id(), modsDir, version);
			ForeignClientJars.disableIn(modsDir);
			ImmediatelyFastConfig.fixIn(InstancePaths.gameDir(instance.id()));
		}
		String loader = LoaderVersions.resolveFor(instance.id(), version, modsDir);
		JsonObject fabric = FabricMetaService.fetchLoaderProfile(version, loader);

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
				n -> {
					long now = clientDone.addAndGet(n);
					listener.onProgress(Math.min(1.0, now / (double) Math.max(1, clientSize)));
					listener.onBytes(now, clientSize);
				});

		listener.onPhase("Downloading assets...");
		var indexInfo = AssetInstaller.readAssetIndexInfo(vanilla);
		List<AssetInstaller.AssetObject> assets = AssetInstaller.loadIndex(indexInfo);
		long totalAssetBytes = assets.stream().mapToLong(AssetInstaller.AssetObject::size).sum();
		AtomicLong assetsDone = new AtomicLong();
		AssetInstaller.downloadAll(assets, n -> {
			long now = assetsDone.addAndGet(n);
			listener.onProgress(Math.min(1.0, now / (double) Math.max(1, totalAssetBytes)));
			listener.onBytes(now, totalAssetBytes);
		});

		listener.onPhase("Preparing natives...");
		Path nativesDir = InstancePaths.dir(instance.id()).resolve("natives");
		if (!alreadyRunning || !Files.isDirectory(nativesDir)) {
			NativesExtractor.prepare(nativesDir, vanillaLibraries, fabricLibraries);
		}
		listener.onProgress(1.0);

		// The launcher's own (newer) Java runs every version - unless a mod only accepts the version's own Java.
		boolean ownJavaOk = net.veloclient.launcher.doctor.ModRequirements.acceptsJava(modsDir, Runtime.version().feature());
		// 1.8.9 / 1.9.4 run on Ornithe with the launcher's Java 25 (Argentum is built for it; Pylon brings LWJGL 3, so the
		// Java 8-only LWJGL 2 never loads) - like OneClient does. Without Ornithe they'd need Mojang's Java 8.
		boolean exact = !ownJavaOk || JavaRuntimeProvider.requiredFeature(vanilla) < 17 && !version.ornithe();
		JavaRuntimeProvider.JavaRuntime runtime = JavaRuntimeProvider.forVersion(vanilla, listener, exact);

		listener.onPhase("Starting Minecraft...");
		return startProcess(instance, version, vanilla, fabric, libraries, clientJar, nativesDir, session, quickPlayServerAddress, runId, runtime);
	}

	private static void downloadLibraries(List<LibraryResolver.ResolvedLibrary> libraries, LaunchProgressListener listener) throws IOException {
		long totalBytes = libraries.stream().mapToLong(l -> Math.max(l.size(), 0)).sum();
		AtomicLong done = new AtomicLong();
		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		try {
			List<Future<?>> futures = libraries.stream()
					.<Future<?>>map(lib -> executor.submit((Callable<Void>) () -> {
						Downloader.ensure(lib.url(), lib.path(), lib.sha1(), lib.size(), n -> {
							long now = done.addAndGet(n);
							listener.onProgress(Math.min(1.0, now / (double) Math.max(1, totalBytes)));
							listener.onBytes(now, totalBytes);
						});
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
			String quickPlayServerAddress, String runId, JavaRuntimeProvider.JavaRuntime runtime) throws IOException {
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
		// Pre-1.13 version JSONs: one "minecraftArguments" string with these extra placeholders.
		values.put("user_properties", "{}");
		values.put("auth_session", session.minecraftAccessToken());
		values.put("game_assets", GameDataPaths.assetsRoot().toAbsolutePath().toString());
		Set<String> activeFeatures = Set.of();
		if (quickPlayServerAddress != null && !quickPlayServerAddress.isBlank()) {
			values.put("quickPlayMultiplayer", quickPlayServerAddress);
			activeFeatures = Set.of("is_quick_play_multiplayer");
		}

		// Memory size and garbage collector come from MemoryPlanner: automatic by default (sized
		// from this PC, this profile's mods/settings, and what the game really used last time),
		// or the fixed/per-profile amount when one is set. The heap starts small and grows only
		// as needed (see MemoryPlanner's class doc for why it's no longer claimed up front).
		MemoryPlanner.Plan memoryPlan = MemoryPlanner.plan(instance);
		net.veloclient.launcher.LauncherLog.info("Launching " + instance.name() + " with " + memoryPlan.summary() + " (" + String.join("; ", memoryPlan.reasons()) + ")");

		List<String> jvmArgs = new ArrayList<>(MemoryPlanner.jvmArgs(memoryPlan, runtime.feature()));
		// Stops the JVM memory-mapping its hsperfdata stats file - on a busy or slow disk, the
		// periodic writes to it are a known source of random multi-ms stalls, and nothing in a
		// game session reads those stats.
		jvmArgs.add("-XX:+PerfDisableSharedMem");
		// Compact object headers (JDK 25+, JEP 519): every Java object gets 4 bytes smaller, which
		// for Minecraft's millions of small objects means noticeably less heap churn and GC work.
		// Only where the game's runtime actually understands the flag - an unknown -XX flag would
		// stop the JVM starting.
		if (runtime.feature() >= 25) {
			jvmArgs.add("-XX:+UseCompactObjectHeaders");
		}
		if (runtime.feature() >= 17) {
			// JIT code cache: the default 240 MB fills up with Fabric + Velo's modules + a modpack's mixins, and then
			// HotSpot switches the JIT compiler off for the rest of the session ("CodeCache is full. Compiler has been
			// disabled") - the game keeps running, but on slower code, and FPS sinks over the session. Reserving more only
			// costs address space; the cache only commits what it really uses.
			jvmArgs.add("-XX:ReservedCodeCacheSize=512m");
			// Big CPUs with several memory nodes (Threadripper, EPYC, dual-socket Xeon): the heap is allocated next to
			// the core that uses it instead of across the slower interconnect. A no-op on ordinary single-node desktops.
			jvmArgs.add("-XX:+UseNUMA");
			if (MemoryPlanner.isLinux() && memoryPlan.maxMb() >= 4096) {
				// Linux: 2 MB pages for the heap (madvise, the kernel's default THP mode) - far fewer TLB misses walking
				// Minecraft's large heap. Where THP is switched off the JVM only prints a warning and uses normal pages.
				jvmArgs.add("-XX:+UseTransparentHugePages");
			}
		}
		// Class Data Sharing: the classes the game loaded last time come from a memory-mapped archive
		// instead of being parsed and verified again (see CdsArchive).
		jvmArgs.addAll(CdsArchive.jvmArgs(runtime, instance, runtime.feature() >= 25));
		// Windows' dual-stack connection ordering can spend the whole
		// connect timeout on a broken/unreachable IPv6 route before ever
		// falling back to IPv4 for a specific host, timing the connection
		// out entirely even though the server is reachable and up - Linux's
		// stack doesn't exhibit this the same way, which is why this only
		// shows up for some players/some servers on Windows. Forcing the
		// JVM to resolve IPv4-only sidesteps it; it's the standard fix for
		// this exact class of intermittent Minecraft connection timeout.
		// preferIPv4ADDRESSES, not preferIPv4Stack (2026-11-08): the stack flag turned IPv6 off completely, so players on
		// IPv6-only / NAT64 networks got "Network is unreachable: getsockopt", slow or missing server list pings, and some
		// hosts only worked under another name (eu.hypixel.net) - things no other launcher has. This keeps IPv4 first
		// (the Windows timeout fix above) and still connects over IPv6 where a host or network has nothing else.
		jvmArgs.add("-Djava.net.preferIPv4Addresses=true");
		FirstRunOptions.apply(instance.id()); // first: the two below can create options.txt
		GraphicsApi.applyPending(instance.id());
		LegacyOptions.apply(instance.id(), version, InstancePaths.modsDir(instance.id()));
		// The Doctor explains and fixes mod problems itself - Fabric's own error window would only stand in its way.
		jvmArgs.add("-Dfabric.noGui=true");
		// Which account's cosmetics the game wears (config/accounts/<uuid>/) - the same files the launcher's Locker edits.
		jvmArgs.add("-Dvelo.account=" + session.uuid().replace("-", ""));
		// AI flags only for a launch an AI asked for (--launch <profile> --ai): a normal Play click never gets them, so a
		// hidden AI copy and a normal copy can run side by side (the global toggle used to hide every game and stop it
		// taking the mouse).
		AiLaunch ai = PENDING_AI.remove(instance.id());
		int aiPort = 0;
		if (ai != null) {
			aiPort = freePort(ai.port() > 0 ? ai.port() : AI_PORT);
			jvmArgs.add("-Dvelo.aiApi=" + aiPort);
			jvmArgs.add("-Dvelo.aiToken=" + aiToken());
		}
		if (net.veloclient.launcher.AppVersion.DEV) {   // the dev game shares the dev launcher's folder (pets, loadouts, Locker)
			jvmArgs.add("-Dvelo.home=" + net.veloclient.launcher.data.VeloPaths.root());
		}
		if (instance.extraJvmArgs() != null && !instance.extraJvmArgs().isBlank()) {
			for (String arg : instance.extraJvmArgs().trim().split("\\s+")) {
				jvmArgs.add(arg);
			}
		}
		boolean modernArgs = vanilla.has("arguments");
		if (modernArgs) {
			jvmArgs.addAll(resolveArguments(vanilla.getAsJsonObject("arguments").getAsJsonArray("jvm"), values, activeFeatures));
		} else {
			// What launchers pass to old versions themselves (their JSON has no "jvm" list).
			jvmArgs.add("-Djava.library.path=" + values.get("natives_directory"));
			jvmArgs.add("-Dminecraft.launcher.brand=" + LAUNCHER_NAME);
			jvmArgs.add("-Dminecraft.launcher.version=" + AppVersion.VERSION);
			jvmArgs.add("-cp");
			jvmArgs.add(classpath);
		}
		if (fabric.has("arguments") && fabric.getAsJsonObject("arguments").has("jvm")) {
			jvmArgs.addAll(resolveArguments(fabric.getAsJsonObject("arguments").getAsJsonArray("jvm"), values, activeFeatures));
		}

		String devJvmArgs = System.getProperty("velo.gameJvmArgs");
		if (devJvmArgs != null && !devJvmArgs.isBlank()) {
			jvmArgs.addAll(List.of(devJvmArgs.split("\\|")));
		}

		List<String> gameArgs = new ArrayList<>();
		if (modernArgs) {
			gameArgs.addAll(resolveArguments(vanilla.getAsJsonObject("arguments").getAsJsonArray("game"), values, activeFeatures));
		} else if (vanilla.has("minecraftArguments")) {
			for (String arg : vanilla.get("minecraftArguments").getAsString().trim().split("\\s+")) {
				gameArgs.add(substitute(arg, values));
			}
		}
		// No Quick Play before 1.20: the old --server / --port arguments do the same (Ornithe's 1.8.9 JSON uses the
		// modern "arguments" format, so this goes by the version, not by the JSON).
		if (version.legacy() || !modernArgs) {
			if (quickPlayServerAddress != null && !quickPlayServerAddress.isBlank()) {
				String host = quickPlayServerAddress;
				String port = "25565";
				int colon = host.lastIndexOf(':');
				if (colon > 0 && host.indexOf(':') == colon) {
					port = host.substring(colon + 1);
					host = host.substring(0, colon);
				}
				gameArgs.addAll(List.of("--server", host, "--port", port));
			}
		}
		if (fabric.has("arguments") && fabric.getAsJsonObject("arguments").has("game")) {
			gameArgs.addAll(resolveArguments(fabric.getAsJsonObject("arguments").getAsJsonArray("game"), values, activeFeatures));
		}

		String devGameArgs = System.getProperty("velo.gameArgs");
		if (devGameArgs != null && !devGameArgs.isBlank()) {
			gameArgs.addAll(List.of(devGameArgs.split("\\|")));
		}

		Downloader.saveVerifiedIndex();
		String java = runtime.binary().toString();
		if (java.regionMatches(true, java.length() - 8, "java.exe", 0, 8) && Files.isRegularFile(Path.of(java.substring(0, java.length() - 8) + "javaw.exe"))) {
			// Discord only recognises Minecraft as a game when it runs as javaw.exe (with Fabric's -DFabricMcEmu argument, which the
			// Fabric profile adds) - started as java.exe it never gets the game overlay or "Playing Minecraft". Same as Mojang's launcher.
			java = java.substring(0, java.length() - 8) + "javaw.exe";
		}
		WindowsGpuPreference.preferHighPerformance(Path.of(java));
		List<String> command = new ArrayList<>();
		command.add(java);
		command.addAll(jvmArgs);
		command.add(mainClass);
		command.addAll(gameArgs);

		if (ai != null) {
			writeAiLaunch(instance, aiPort, ai.hidden());
		}
		if (ai != null && ai.hidden()) {
			if (MemoryPlanner.isLinux() && (onPath("kwin_wayland") || onPath("xvfb-run"))) {
				command = hiddenDisplay(command, InstancePaths.logsDir(instance.id()).resolve("hidden-start.sh"));
			} else {
				// Windows / macOS (and Linux without a virtual display): the game hides its own window once it's up
				command.add(command.indexOf(mainClass), "-Dvelo.hiddenWindow=true");
			}
		}
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
		if (MemoryPlanner.isLinux() && GraphicsApi.isOpenGl(instance.id(), version.id())) {
			// Mesa (AMD / Intel on Linux), OpenGL: glthread moves the driver's own work for every GL call off the render
			// thread onto a second core. Minecraft's render thread is the usual bottleneck on fast GPUs, so this turns
			// idle GPU time into frames. Ignored by NVIDIA's driver and by Vulkan; a player's own setting wins.
			builder.environment().putIfAbsent("mesa_glthread", "true");
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

	public static final int AI_PORT = 25620;

	/** An AI start: open the local API (on {@code port}, 0 = the first free one from 25620) and maybe hide the window. */
	public record AiLaunch(boolean hidden, int port) {
	}

	private static final java.util.Map<String, AiLaunch> PENDING_AI = new java.util.concurrent.ConcurrentHashMap<>();

	/** The next launch of this profile is an AI launch (taken by {@link #launch} once). */
	public static void queueAiLaunch(String instanceId, AiLaunch ai) {
		PENDING_AI.put(instanceId, ai);
	}

	// A second AI copy can't share the first one's port.
	private static int freePort(int from) {
		for (int port = from; port < from + 40; port++) {
			try (java.net.ServerSocket probe = new java.net.ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress())) {
				return port;
			} catch (IOException busy) {
				// next
			}
		}
		return from;
	}

	// ~/.velo-client/ai-launch.json: where the AI finds the API of the copy it just started (port can differ from 25620).
	private static void writeAiLaunch(Instance instance, int port, boolean hidden) {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("profile", instance.name());
			o.addProperty("profileId", instance.id());
			o.addProperty("url", "http://127.0.0.1:" + port);
			o.addProperty("port", port);
			o.addProperty("hidden", hidden);
			o.addProperty("startedAt", System.currentTimeMillis());
			Path file = net.veloclient.launcher.data.VeloPaths.root().resolve("ai-launch.json");
			Path tmp = file.resolveSibling("ai-launch.json.tmp");
			Files.writeString(tmp, o.toString());
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			net.veloclient.launcher.LauncherLog.warn("Couldn't write ai-launch.json", e);
		}
	}

	/** The AI API token (made once, kept in the launcher's settings). */
	public static synchronized String aiToken() {
		var data = net.veloclient.launcher.data.LauncherSettings.get();
		if (data.aiToken == null || data.aiToken.length() < 32) {
			byte[] b = new byte[24];
			new java.security.SecureRandom().nextBytes(b);
			data.aiToken = java.util.HexFormat.of().formatHex(b);
			net.veloclient.launcher.data.LauncherSettings.save(data);
		}
		return data.aiToken;
	}

	/** Every system can start the game hidden: Linux in a virtual display where there is one, otherwise the game hides its window. */
	public static boolean hiddenDisplayAvailable() {
		return true;
	}

	// The game inside an invisible display: KWin's virtual Wayland session (real GPU through Xwayland) or Xvfb.
	private static List<String> hiddenDisplay(List<String> command, Path script) throws IOException {
		if (onPath("kwin_wayland")) {
			StringBuilder sh = new StringBuilder("#!/bin/sh\nexec");
			for (String c : command) {
				sh.append(" '").append(c.replace("'", "'\\''")).append('\'');
			}
			sh.append('\n');
			Files.createDirectories(script.getParent());
			Files.writeString(script, sh.toString());
			script.toFile().setExecutable(true, true);
			return List.of("kwin_wayland", "--virtual", "--xwayland", "--no-lockscreen", "--width", "1600", "--height", "900",
					"--exit-with-session", script.toString());
		}
		if (onPath("xvfb-run")) {
			List<String> out = new ArrayList<>(List.of("xvfb-run", "-a", "-s", "-screen 0 1600x900x24"));
			out.addAll(command);
			return out;
		}
		return command;
	}

	private static boolean onPath(String tool) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(java.io.File.pathSeparator)) {
			if (Files.isExecutable(Path.of(dir, tool))) {
				return true;
			}
		}
		return false;
	}
}
