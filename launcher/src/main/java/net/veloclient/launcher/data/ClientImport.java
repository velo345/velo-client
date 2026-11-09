package net.veloclient.launcher.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.launch.Downloader;
import net.veloclient.launcher.launch.GameDataPaths;
import net.veloclient.launcher.modrinth.ModrinthClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * "Import from another client": finds the Minecraft setups other launchers and clients left on this computer (the vanilla
 * launcher's .minecraft - which Lunar, Badlion and Dawn play from too -, Lunar's and Dawn's (formerly Feather) own mod folders, NoRisk,
 * the Modrinth App, Prism / MultiMC and CurseForge instances) and copies what's useful into a Velo profile: keybinds and video
 * settings (options.txt), the server list, Fabric mods - looked up on Modrinth by their checksum and fetched in the build for the
 * profile's Minecraft version, so mods from an older version still work -, mod configs, resource packs and shader packs, and
 * optionally worlds. Nothing at the source is changed.
 */
public final class ClientImport {

	/**
	 * One place to import from. {@code mcVersion} and {@code loader} ("fabric", "forge", "neoforge", "quilt") are best guesses
	 * (null when unknown). Resource and shader packs can live apart from the game directory (Lunar keeps them per profile).
	 */
	public record Source(String client, String name, Path gameDir, Path modsDir, String mcVersion, String loader, Path resourcePacksDir,
			Path shaderPacksDir) {

		public boolean hasOptions() {
			return gameDir != null && Files.isRegularFile(gameDir.resolve("options.txt"));
		}

		public boolean hasServers() {
			return gameDir != null && Files.isRegularFile(gameDir.resolve("servers.dat"));
		}

		public int modCount() {
			return cachedCount("mods:" + modsDir, () -> {
				if (modsDir != null && modsDir.getFileName() != null && modsDir.getFileName().toString().startsWith(NoRiskMods.MARKER)) {
					return NoRiskMods.count(modsDir);
				}
				return modJars(modsDir).size();
			});
		}

		public int resourcePackCount() {
			return cachedCount("packs:" + resourcePacksDir, () -> packCount(resourcePacksDir, "pack.mcmeta"));
		}

		public int shaderPackCount() {
			return cachedCount("shaders:" + shaderPacksDir, () -> packCount(shaderPacksDir, "shaders"));
		}

		public int worldCount() {
			return cachedCount("worlds:" + gameDir, this::countWorlds);
		}

		private int countWorlds() {
			Path saves = savesDir();
			if (saves == null || !Files.isDirectory(saves)) {
				return 0;
			}
			try (Stream<Path> s = Files.list(saves)) {
				return (int) s.filter(p -> Files.isRegularFile(p.resolve("level.dat"))).count();
			} catch (IOException e) {
				return 0;
			}
		}

		public boolean hasConfig() {
			Path config = configDir();
			return config != null && Files.isDirectory(config);
		}

		/** The worlds folder: the game directory's, or for a Lunar profile the shared .minecraft's (see {@link #shared}). */
		public Path savesDir() {
			return shared("saves");
		}

		public Path configDir() {
			return shared("config");
		}

		/**
		 * Lunar profiles (.lunarclient/profiles/&lt;name&gt;) keep their own options.txt and server list, but worlds and mod
		 * configs stay in the shared .minecraft next to .lunarclient - imports from Lunar used to find no worlds at all.
		 */
		private Path shared(String name) {
			if (gameDir == null) {
				return null;
			}
			Path own = gameDir.resolve(name);
			if (hasEntries(own)) {
				return own;
			}
			for (Path p = gameDir; p != null; p = p.getParent()) {
				if (p.getFileName() != null && p.getFileName().toString().equals(".lunarclient") && p.getParent() != null) {
					Path common = p.getParent().resolve(".minecraft").resolve(name);
					if (hasEntries(common)) {
						return common;
					}
				}
			}
			return own;
		}

		private static boolean hasEntries(Path dir) {
			if (!Files.isDirectory(dir)) {
				return false;
			}
			try (Stream<Path> s = Files.list(dir)) {
				return s.findAny().isPresent();
			} catch (IOException e) {
				return false;
			}
		}

		/** Anything worth importing at all. */
		public boolean useful() {
			return hasOptions() || hasServers() || modCount() > 0 || resourcePackCount() > 0 || shaderPackCount() > 0;
		}
	}

	/** What to bring over. */
	public record Options(boolean settings, boolean servers, boolean mods, boolean configs, boolean resourcePacks, boolean shaderPacks, boolean worlds) {
	}

	/** The clients we know where to look for - the first ones are shown even when not found, so you see what's supported. */
	public static final List<String> CLIENTS = List.of("Minecraft Launcher", "Lunar Client", "OneClient", "Dawn Client", "NoRisk Client", "Modrinth App",
			"Prism / MultiMC", "CurseForge", "LabyMod", "ATLauncher", "GDLauncher", "FTB App", "Technic", "Other launchers");

	private static final Pattern FABRIC_API_VERSION = Pattern.compile("fabric-api-[^+]+\\+([0-9][0-9a-z.\\-]*)\\.jar", Pattern.CASE_INSENSITIVE);
	/** Minecraft versions as they show up in jar and folder names: 1.21.11, 1.20, 26.2, 26.1.2 (not 0.x mod versions). */
	private static final Pattern MC_VERSION = Pattern.compile("(?<![0-9.])(1\\.\\d{1,2}(?:\\.\\d{1,2})?|2\\d\\.\\d{1,2}(?:\\.\\d{1,2})?)(?![0-9])");

	private ClientImport() {
	}

	// ---- Finding sources ----

	/**
	 * Every launcher setup on this computer. Each "home" is searched: the real one, and on Linux every Flatpak sandbox under
	 * ~/.var/app (Lunar, Modrinth, Prism, the Minecraft launcher ... keep their files there when installed from Flathub).
	 */
	public static List<Source> detect() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		Path home = Path.of(System.getProperty("user.home"));
		boolean windows = os.contains("win");
		boolean mac = os.contains("mac");
		List<Source> sources = new ArrayList<>();

		// Base folders, each like a home directory: {home, data dir, config dir}.
		List<Path[]> bases = new ArrayList<>();
		if (windows) {
			Path appData = env("APPDATA", home.resolve("AppData/Roaming"));
			Path local = env("LOCALAPPDATA", home.resolve("AppData/Local"));
			bases.add(new Path[] {home, appData, appData});
			bases.add(new Path[] {appData, local, local});
		} else if (mac) {
			Path support = home.resolve("Library/Application Support");
			bases.add(new Path[] {home, support, support});
			bases.add(new Path[] {support, support, home.resolve("Library/Preferences")});
		} else {
			Path data = System.getenv("XDG_DATA_HOME") != null ? Path.of(System.getenv("XDG_DATA_HOME")) : home.resolve(".local/share");
			Path config = System.getenv("XDG_CONFIG_HOME") != null ? Path.of(System.getenv("XDG_CONFIG_HOME")) : home.resolve(".config");
			bases.add(new Path[] {home, data, config});
			for (Path sandbox : subdirs(home.resolve(".var/app"), 1)) {
				bases.add(new Path[] {sandbox, sandbox.resolve("data"), sandbox.resolve("config")});
			}
		}
		List<Path> documents = documentsDirs(home);

		for (Path[] base : bases) {
			Path b = base[0];
			Path data = base[1];
			Path config = base[2];
			String where = b.toString().contains("/.var/app/") ? " (Flatpak)" : "";
			// A Flatpak client's sandbox .minecraft is that client's, not the vanilla launcher's.
			String sandboxClient = b.getFileName().toString().toLowerCase(Locale.ROOT).contains("lunar") ? "Lunar Client"
					: b.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*(dawn|feather).*") ? "Dawn Client" : "Minecraft Launcher";
			probe(".minecraft", () -> {
			// The vanilla launcher's .minecraft (Badlion, TLauncher, SKLauncher play from it too).
			for (Path dotMinecraft : distinct(b.resolve(".minecraft"), b.resolve("minecraft"), mac ? b.resolve("minecraft") : null)) {
				if (!Files.isDirectory(dotMinecraft) || dotMinecraft.getFileName().toString().equals("minecraft") && !mac
						&& !Files.isRegularFile(dotMinecraft.resolve("launcher_profiles.json"))) {
					continue;
				}
				addVersioned(sources, sandboxClient, (sandboxClient.equals("Minecraft Launcher") ? "" : sandboxClient.split(" ")[0] + " ") + ".minecraft" + where,
						dotMinecraft, dotMinecraft.resolve("mods"), null);
				// HMCL / TLauncher "version isolation": versions/<name>/ is a game directory of its own.
				for (Path version : subdirs(dotMinecraft.resolve("versions"), 1)) {
					if (Files.isDirectory(version.resolve("mods")) || Files.isRegularFile(version.resolve("options.txt"))) {
						addVersioned(sources, "Other launchers", version.getFileName() + " (.minecraft/versions)" + where, version,
								version.resolve("mods"), versionFromName(version.getFileName().toString()));
					}
				}
				// LabyMod 4 loads Fabric mods from labymod-neo/fabric/<version>/mods.
				for (Path version : subdirs(dotMinecraft.resolve("labymod-neo/fabric"), 1)) {
					add(sources, "LabyMod", "LabyMod " + version.getFileName() + where, dotMinecraft, version.resolve("mods"),
							versionFromName(version.getFileName().toString()), "fabric", null, null);
				}
			}
			});
			Path gameForClients = Files.isDirectory(b.resolve(".minecraft")) ? b.resolve(".minecraft") : null;

			probe("Lunar Client", () -> {
			// Lunar Client: .lunarclient/profiles/<profile>/mods/fabric-<version>/ (older: profiles/lunar/<version>/mods/...).
			// Settings and worlds are in .minecraft; resource packs and shaders per profile.
			Path lunar = b.resolve(".lunarclient");
			for (Path mods : dirsNamed(lunar.resolve("profiles"), "mods", 3)) {
				Path profile = mods.getParent();
				String label = "Lunar " + lunar.resolve("profiles").relativize(profile).toString().replace('\\', '/').replace("lunar/", "");
				addVersioned(sources, "Lunar Client", label + where, gameForClients != null ? gameForClients : profile, mods, null,
						profile.resolve("resourcepacks"), profile.resolve("shaders"));
			}
			for (Path dir : subdirs(lunar.resolve("offline/multiver/mods"), 1)) {
				add(sources, "Lunar Client", "Lunar mods " + dir.getFileName() + where, gameForClients, dir, versionFromName(dir.getFileName().toString()),
						"fabric", null, null);
			}
			});
			probe("Dawn Client", () -> {
			// Dawn (formerly Feather Client, Flatpak gg.dawn.Launcher): user-mods/<version>/ in .dawn - or the old .feather
			// folder an install from before the rename still uses - plus per-profile mods folders.
			for (Path dawn : distinct(b.resolve(".dawn"), data.resolve("dawn"), data.resolve(".dawn"), data.resolve("Dawn"),
					data.resolve("gg.dawn.Launcher"), b.resolve(".feather"), data.resolve("feather"), data.resolve(".feather"))) {
				for (Path dir : subdirs(dawn.resolve("user-mods"), 1)) {
					add(sources, "Dawn Client", "Dawn " + dir.getFileName() + where, gameForClients, dir, versionFromName(dir.getFileName().toString()),
							"fabric", null, null);
				}
				for (Path mods : dirsNamed(dawn.resolve("profiles"), "mods", 3)) {
					Path profile = mods.getParent();
					addVersioned(sources, "Dawn Client", "Dawn " + dawn.resolve("profiles").relativize(profile).toString().replace('\\', '/') + where,
							Files.isRegularFile(profile.resolve("options.txt")) ? profile : gameForClients != null ? gameForClients : profile, mods, null,
							profile.resolve("resourcepacks"), profile.resolve("shaderpacks"));
				}
			}
			});
			probe("NoRisk Client", () -> {
			// NoRisk Client: profiles with their own game directories (launcher v3 keeps them in noriskclientv3/profiles).
			for (Path norisk : distinct(b.resolve(".norisk"), data.resolve("norisk"), data.resolve("NoRiskClient"), data.resolve("noriskclient"),
					data.resolve("noriskclientv3"), data.resolve("gg.norisk.app"), b.resolve(".noriskclient"))) {
				// One source per profile from NoRisk's database (ten "NRC <version>" profiles share one folder); by folder only
				// when it can't be read.
				List<NoRiskMods.Profile> profiles = Files.isRegularFile(norisk.resolve("meta").resolve("app.db")) ? NoRiskMods.profiles(norisk) : List.of();
				for (NoRiskMods.Profile profile : profiles) {
					add(sources, "NoRisk Client", "NoRisk " + profile.name() + where, profile.dir(), NoRiskMods.sourceModsDir(profile),
							profile.mcVersion(), profile.loader(), null, null);
				}
				if (profiles.isEmpty()) {
					for (Path dir : gameDirsBelow(norisk, 5)) {
						add(sources, "NoRisk Client", "NoRisk " + dir.getFileName() + where, dir, dir.resolve("mods"), NoRiskMods.lastPlayedVersion(dir),
								null, null, null);
					}
				}
			}
			});
			probe("Modrinth App", () -> {
			// Modrinth App profiles (the Flatpak keeps them in its sandbox's data folder).
			for (Path app : distinct(data.resolve("ModrinthApp"), data.resolve("com.modrinth.theseus"), b.resolve("ModrinthApp"))) {
				for (Path dir : subdirs(app.resolve("profiles"), 1)) {
					add(sources, "Modrinth App", dir.getFileName() + where, dir, dir.resolve("mods"), modrinthVersion(dir), modrinthLoader(dir), null, null);
				}
			}
			});
			probe("OneClient", () -> {
			// OneClient (Polyfrost): one shared .minecraft (settings, worlds, servers, configs) and a mods folder per Minecraft
			// version under clusters/<version> <loader>/ - one source per cluster that has mods.
			Path oneClient = data.resolve("Polyfrost").resolve("OneClient");
			if (Files.isDirectory(oneClient.resolve("clusters"))) {
				Path shared = oneClient.resolve(".minecraft");
				for (Path cluster : subdirs(oneClient.resolve("clusters"), 1)) {
					if (modJars(cluster.resolve("mods")).isEmpty()) {
						continue;
					}
					String label = cluster.getFileName().toString();
					String version = label.split(" ")[0];
					String loader = label.toLowerCase(Locale.ROOT).contains("forge") ? "forge" : "fabric";
					add(sources, "OneClient", label + where, Files.isDirectory(shared) ? shared : cluster, cluster.resolve("mods"), version, loader,
							cluster.resolve("resourcepacks"), cluster.resolve("shaderpacks"));
				}
			}
			});
			probe("Prism / MultiMC", () -> {
			// Prism Launcher / PolyMC / MultiMC instances.
			for (Path prism : distinct(data.resolve("PrismLauncher"), data.resolve("PolyMC"), data.resolve("multimc"), data.resolve("MultiMC"),
					b.resolve("PrismLauncher"), b.resolve("MultiMC"), b.resolve(".local/share/multimc"))) {
				for (Path dir : subdirs(prism.resolve("instances"), 1)) {
					Path game = Files.isDirectory(dir.resolve(".minecraft")) ? dir.resolve(".minecraft") : dir.resolve("minecraft");
					String[] info = prismInfo(dir);
					add(sources, "Prism / MultiMC", instanceName(dir) + where, game, game.resolve("mods"), info[0], info[1], null, null);
				}
			}
			});
			probe("ATLauncher", () -> {
			// ATLauncher.
			for (Path at : distinct(data.resolve("ATLauncher"), b.resolve("ATLauncher"), b.resolve(".atlauncher"))) {
				for (Path dir : subdirs(at.resolve("instances"), 1)) {
					add(sources, "ATLauncher", dir.getFileName() + where, dir, dir.resolve("mods"));
				}
			}
			});
			probe("GDLauncher", () -> {
			// GDLauncher (Carbon: data/instances/<name>/instance, legacy "next": instances/<name>).
			for (Path gd : distinct(data.resolve("gdlauncher_carbon"), config.resolve("gdlauncher_carbon"), data.resolve("gdlauncher_next"),
					config.resolve("gdlauncher_next"), b.resolve("gdlauncher_next"))) {
				for (Path dir : subdirs(gd.resolve("data/instances"), 1)) {
					Path game = Files.isDirectory(dir.resolve("instance")) ? dir.resolve("instance") : dir;
					add(sources, "GDLauncher", dir.getFileName() + where, game, game.resolve("mods"));
				}
				for (Path dir : subdirs(gd.resolve("instances"), 1)) {
					add(sources, "GDLauncher", dir.getFileName() + where, dir, dir.resolve("mods"));
				}
			}
			});
			probe("FTB App", () -> {
			// FTB App.
			for (Path ftb : distinct(b.resolve(".ftba"), data.resolve(".ftba"), data.resolve("ftba"))) {
				for (Path dir : subdirs(ftb.resolve("instances"), 1)) {
					add(sources, "FTB App", instanceName(dir) + where, dir, dir.resolve("mods"));
				}
			}
			});
			probe("Technic", () -> {
			// Technic.
			for (Path dir : subdirs(b.resolve(".technic/modpacks"), 1)) {
				add(sources, "Technic", dir.getFileName() + where, dir, dir.resolve("mods"));
			}
			});
		}
		// CurseForge: <home or Documents>/curseforge/minecraft/Instances/<name> (Documents may be localised, e.g. ~/Dokumente).
		List<Path> curseRoots = new ArrayList<>();
		curseRoots.add(home.resolve("curseforge/minecraft/Instances"));
		for (Path docs : documents) {
			curseRoots.add(docs.resolve("curseforge/minecraft/Instances"));
		}
		// CurseForge lets players move its folder (Settings > Minecraft), usually to another drive: "CurseForge is
		// downloaded, Velo doesn't recognize it" (bug report, 2026-11-07). Windows paths ignore case.
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			for (Path root : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
				// a disconnected network drive or an empty card reader can block a file check for half a minute
				if (!answersQuickly(root)) {
					LauncherLog.info("Client import: skipped drive " + root + " (didn't answer within 1.5 s)");
					continue;
				}
				curseRoots.add(root.resolve("curseforge/minecraft/Instances"));
				curseRoots.add(root.resolve("Games/curseforge/minecraft/Instances"));
			}
		}
		probe("CurseForge", () -> {
		for (Path cf : distinct(curseRoots.toArray(Path[]::new))) {
			for (Path dir : subdirs(cf, 1)) {
				String[] info = curseInfo(dir);
				add(sources, "CurseForge", dir.getFileName().toString(), dir, dir.resolve("mods"), info[0], info[1], null, null);
			}
		}
		});
		// The page shows every source's counts: work them out here, in the background (they list folders and open
		// zips - on the UI thread, with many profiles or a slow drive, that froze the import window).
		long warm = System.nanoTime();
		try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
			for (Source source : sources) {
				jobs.add(pool.submit(() -> {
					source.modCount();
					source.resourcePackCount();
					source.shaderPackCount();
					source.worldCount();
				}));
			}
			for (var job : jobs) {
				try {
					job.get(10, java.util.concurrent.TimeUnit.SECONDS);
				} catch (Exception | Error e) {
					job.cancel(true);
				}
			}
		}
		LauncherLog.info("Client import: found " + sources.size() + " sources, counted in " + (System.nanoTime() - warm) / 1_000_000 + " ms");
		return sources;
	}

	/** A mod in a source, as the import page lists it. {@code title}/{@code iconUrl}: from Modrinth once known. */
	public record ModInfo(Path jar, String name, String title, String iconUrl) {
	}

	/** The source's mods with the names their fabric.mod.json gives (quick, local). */
	public static List<ModInfo> listMods(Source source) {
		List<ModInfo> out = new ArrayList<>();
		for (Path jar : modJars(source.modsDir())) {
			String name = null;
			try {
				JsonObject json = fabricJson(jar);
				if (json != null && json.has("name")) {
					name = json.get("name").getAsString();
				}
			} catch (RuntimeException ignored) {
				// unreadable jar: the file name
			}
			out.add(new ModInfo(jar, name != null ? name : jar.getFileName().toString().replaceFirst("\\.jar$", ""), null, null));
		}
		return out;
	}

	/**
	 * Modrinth's project (title + icon) for as many of these mods as it knows: by checksum first, then by mod id as the
	 * project slug. Missing ones aren't in the map. Network - never on the UI thread.
	 */
	public static java.util.Map<Path, ModrinthClient.Project> identify(List<Path> jars) {
		java.util.Map<Path, ModrinthClient.Project> out = new java.util.HashMap<>();
		try {
			java.util.Map<Path, String> sha1 = new java.util.HashMap<>();
			for (Path jar : jars) {
				try {
					sha1.put(jar, Downloader.sha1Of(jar));
				} catch (IOException | RuntimeException ignored) {
					// unreadable
				}
			}
			java.util.Map<String, ModrinthClient.ProjectVersion> owner = ModrinthClient.versionsByFileSha1(sha1.values());
			java.util.Map<Path, String> projectOf = new java.util.HashMap<>();
			java.util.Set<String> slugs = new java.util.LinkedHashSet<>();
			for (Path jar : jars) {
				ModrinthClient.ProjectVersion v = sha1.containsKey(jar) ? owner.get(sha1.get(jar)) : null;
				if (v != null) {
					projectOf.put(jar, v.projectId());
				} else {
					slugs.addAll(slugCandidates(jar, fabricModId(jar)));
				}
			}
			java.util.Set<String> wanted = new java.util.LinkedHashSet<>(projectOf.values());
			wanted.addAll(slugs);
			java.util.Map<String, ModrinthClient.Project> byKey = new java.util.HashMap<>();
			List<String> all = new ArrayList<>(wanted);
			for (int i = 0; i < all.size(); i += 200) {
				for (ModrinthClient.Project project : ModrinthClient.projects(all.subList(i, Math.min(all.size(), i + 200)))) {
					byKey.put(project.id(), project);
					if (project.slug() != null) {
						byKey.put(project.slug(), project);
					}
				}
			}
			for (Path jar : jars) {
				ModrinthClient.Project project = projectOf.containsKey(jar) ? byKey.get(projectOf.get(jar)) : null;
				if (project == null) {
					for (String slug : slugCandidates(jar, fabricModId(jar))) {
						project = byKey.get(slug);
						if (project != null) {
							break;
						}
					}
				}
				if (project != null) {
					out.put(jar, project);
				}
			}
			LauncherLog.info("Client import: Modrinth knows " + out.size() + " of " + jars.size() + " mods");
		} catch (IOException | RuntimeException e) {
			LauncherLog.info("Client import: Modrinth lookup for the mod list failed (" + e.getMessage() + ")");
		}
		return out;
	}

	private interface Probe {
		void run() throws Exception;
	}

	/**
	 * One launcher's detection: whatever goes wrong in it (an unreadable folder, a database our reader can't parse - even a
	 * StackOverflowError) only leaves that launcher out; the import window used to crash or load forever instead.
	 */
	private static void probe(String client, Probe probe) {
		long start = System.nanoTime();
		try {
			probe.run();
		} catch (Exception | StackOverflowError | OutOfMemoryError | LinkageError e) {
			LauncherLog.warn("Client import: looking for " + client + " failed - skipped", e instanceof Exception ex ? ex : new RuntimeException(e));
		}
		long ms = (System.nanoTime() - start) / 1_000_000;
		if (ms > 500) {
			LauncherLog.info("Client import: " + client + " took " + ms + " ms");
		}
	}

	/** Whether a drive root answers a file check within 1.5 s. */
	private static boolean answersQuickly(Path root) {
		var task = java.util.concurrent.CompletableFuture.supplyAsync(() -> Files.isDirectory(root),
				java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		try {
			return task.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			task.cancel(true);
			return false;
		}
	}

	/** Counts per folder, worked out once (see the end of detect). */
	private static final java.util.Map<String, Integer> COUNTS = new java.util.concurrent.ConcurrentHashMap<>();

	private static int cachedCount(String key, java.util.function.IntSupplier count) {
		Integer known = COUNTS.get(key);
		if (known != null) {
			return known;
		}
		int n;
		try {
			n = count.getAsInt();
		} catch (RuntimeException | StackOverflowError e) {
			n = 0;
		}
		COUNTS.put(key, n);
		return n;
	}

	private static Path env(String name, Path fallback) {
		String value = System.getenv(name);
		return value != null && !value.isBlank() ? Path.of(value) : fallback;
	}

	/** ~/Documents plus the localised XDG documents folder (~/Dokumente, ~/Documenti ...). */
	private static List<Path> documentsDirs(Path home) {
		List<Path> out = new ArrayList<>();
		out.add(home.resolve("Documents"));
		Path userDirs = home.resolve(".config/user-dirs.dirs");
		try {
			if (Files.isRegularFile(userDirs)) {
				for (String line : Files.readAllLines(userDirs, StandardCharsets.UTF_8)) {
					if (line.startsWith("XDG_DOCUMENTS_DIR=")) {
						String value = line.substring(line.indexOf('=') + 1).replace("\"", "").replace("$HOME", home.toString());
						out.add(Path.of(value));
					}
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// just ~/Documents
		}
		String profile = System.getenv("USERPROFILE");
		if (profile != null) {
			out.add(Path.of(profile, "Documents"));
		}
		return out;
	}

	private static List<Path> distinct(Path... paths) {
		List<Path> out = new ArrayList<>();
		for (Path p : paths) {
			if (p != null && !out.contains(p)) {
				out.add(p);
			}
		}
		return out;
	}

	private static void add(List<Source> sources, String client, String name, Path gameDir, Path modsDir) {
		add(sources, client, name, gameDir, modsDir, null, null, null, null);
	}

	/**
	 * A mods folder that holds its jars in per-version subfolders (Lunar's fabric-26.2/, some people's mods/1.21.11/) becomes one
	 * source per subfolder; otherwise one source.
	 */
	private static void addVersioned(List<Source> sources, String client, String name, Path gameDir, Path modsDir, String version) {
		addVersioned(sources, client, name, gameDir, modsDir, version, null, null);
	}

	private static void addVersioned(List<Source> sources, String client, String name, Path gameDir, Path modsDir, String version,
			Path packs, Path shaders) {
		List<Path> versioned = new ArrayList<>();
		for (Path sub : subdirs(modsDir, 1)) {
			String folder = sub.getFileName().toString();
			if (versionFromName(folder) != null && !modJars(sub).isEmpty()) {
				versioned.add(sub);
			}
		}
		if (versioned.isEmpty() || !modJars(modsDir).isEmpty()) {
			add(sources, client, name, gameDir, modsDir, version, null, packs, shaders);
		}
		for (Path sub : versioned) {
			String folder = sub.getFileName().toString();
			String loader = folder.toLowerCase(Locale.ROOT).startsWith("forge") ? "forge" : folder.toLowerCase(Locale.ROOT).startsWith("neoforge")
					? "neoforge" : "fabric";
			add(sources, client, name + "  ·  " + folder, gameDir, sub, versionFromName(folder), loader, packs, shaders);
		}
	}

	private static void add(List<Source> sources, String client, String name, Path gameDir, Path modsDir, String version, String loader,
			Path packs, Path shaders) {
		if ((gameDir == null || !Files.isDirectory(gameDir)) && (modsDir == null || !Files.isDirectory(modsDir))) {
			return;
		}
		Path mods = real(modsDir);
		for (Source s : sources) {
			if (s.modsDir() != null && mods != null && real(s.modsDir()).equals(mods) && s.client().equals(client)) {
				return;
			}
		}
		// Packs of their own (Lunar's per profile) when there are any, else the game directory's.
		if ((packs == null || packCount(packs, "pack.mcmeta") == 0) && gameDir != null) {
			packs = gameDir.resolve("resourcepacks");
		}
		if ((shaders == null || packCount(shaders, "shaders") == 0) && gameDir != null) {
			shaders = gameDir.resolve("shaderpacks");
		}
		String guessed = version != null ? version : versionFromMods(modsDir);
		String guessedLoader = loader != null ? loader : loaderFromMods(modsDir);
		Source source = new Source(client, name, gameDir, modsDir, guessed, guessedLoader, packs, shaders);
		if (source.useful()) {
			sources.add(source);
		}
	}

	private static Path real(Path path) {
		if (path == null) {
			return null;
		}
		try {
			return path.toRealPath();
		} catch (IOException e) {
			return path.toAbsolutePath().normalize();
		}
	}

	private static List<Path> subdirs(Path dir, int depth) {
		if (dir == null || !Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> s = Files.walk(dir, depth)) {
			return s.filter(p -> !p.equals(dir) && Files.isDirectory(p)).filter(p -> dir.relativize(p).getNameCount() == depth).sorted().toList();
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
	}

	/** Every folder called {@code name} up to {@code depth} levels below {@code dir}. */
	private static List<Path> dirsNamed(Path dir, String name, int depth) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> s = Files.walk(dir, depth)) {
			return s.filter(Files::isDirectory).filter(p -> p.getFileName().toString().equals(name)).sorted().toList();
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
	}

	/** Folders under {@code dir} that look like a game directory (an options.txt or a mods folder next to config). */
	private static List<Path> gameDirsBelow(Path dir, int depth) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> s = Files.walk(dir, depth)) {
			return s.filter(Files::isDirectory)
					.filter(p -> Files.isRegularFile(p.resolve("options.txt")) || Files.isDirectory(p.resolve("mods")) && Files.isDirectory(p.resolve("config")))
					.sorted().toList();
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
	}

	private static String versionFromName(String name) {
		Matcher m = MC_VERSION.matcher(name);
		return m.find() ? m.group(1) : null;
	}

	/**
	 * The Minecraft version a mods folder is for: Fabric API's jar name says it exactly ("fabric-api-0.156.0+26.2.jar"); otherwise
	 * the version most jar names mention ("sodium-fabric-0.8.9+mc26.1.1.jar", "appleskin-fabric-mc1.21.11-3.0.8.jar").
	 */
	private static String versionFromMods(Path modsDir) {
		List<Path> jars = modJars(modsDir);
		Map<String, Integer> votes = new LinkedHashMap<>();
		for (Path jar : jars) {
			String file = jar.getFileName().toString();
			Matcher api = FABRIC_API_VERSION.matcher(file);
			if (api.matches()) {
				return api.group(1);
			}
			Matcher m = MC_VERSION.matcher(file.replaceAll("(?i)\\.jar$", ""));
			java.util.Set<String> seen = new java.util.HashSet<>();
			while (m.find()) {
				if (seen.add(m.group(1))) {
					votes.merge(m.group(1), 1, Integer::sum);
				}
			}
		}
		return votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

	/** "fabric", "forge", "neoforge" or "quilt" - whichever the most jars are made for. */
	private static String loaderFromMods(Path modsDir) {
		int fabric = 0;
		int forge = 0;
		int neo = 0;
		int quilt = 0;
		for (Path jar : modJars(modsDir)) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				if (zip.getEntry("fabric.mod.json") != null) {
					fabric++;
				} else if (zip.getEntry("quilt.mod.json") != null) {
					quilt++;
				} else if (zip.getEntry("META-INF/neoforge.mods.toml") != null) {
					neo++;
				} else if (zip.getEntry("META-INF/mods.toml") != null) {
					forge++;
				}
			} catch (IOException | RuntimeException ignored) {
				// not a zip
			}
		}
		int best = Math.max(Math.max(fabric, forge), Math.max(neo, quilt));
		if (best == 0) {
			return null;
		}
		return best == fabric ? "fabric" : best == neo ? "neoforge" : best == forge ? "forge" : "quilt";
	}

	/** {mc version, loader} from a Prism/MultiMC instance's mmc-pack.json. */
	private static String[] prismInfo(Path instanceDir) {
		String[] out = {null, null};
		Path pack = instanceDir.resolve("mmc-pack.json");
		try {
			if (Files.isRegularFile(pack)) {
				var components = JsonParser.parseString(Files.readString(pack)).getAsJsonObject().getAsJsonArray("components");
				for (var c : components) {
					JsonObject o = c.getAsJsonObject();
					String uid = o.has("uid") ? o.get("uid").getAsString() : "";
					switch (uid) {
						case "net.minecraft" -> out[0] = o.has("version") ? o.get("version").getAsString() : null;
						case "net.fabricmc.fabric-loader" -> out[1] = "fabric";
						case "org.quiltmc.quilt-loader" -> out[1] = "quilt";
						case "net.minecraftforge" -> out[1] = "forge";
						case "net.neoforged" -> out[1] = "neoforge";
						default -> { }
					}
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// unknown
		}
		return out;
	}

	private static String instanceName(Path instanceDir) {
		for (String file : List.of("instance.cfg", "instance.json")) {
			Path cfg = instanceDir.resolve(file);
			try {
				if (Files.isRegularFile(cfg)) {
					String text = Files.readString(cfg);
					if (file.endsWith(".json")) {
						JsonObject json = JsonParser.parseString(text).getAsJsonObject();
						if (json.has("name")) {
							return json.get("name").getAsString();
						}
					} else {
						for (String line : text.split("\\R")) {
							if (line.startsWith("name=")) {
								return line.substring(5).strip();
							}
						}
					}
				}
			} catch (IOException | RuntimeException ignored) {
				// folder name
			}
		}
		return instanceDir.getFileName().toString();
	}

	/** {mc version, loader} from CurseForge's minecraftinstance.json. */
	private static String[] curseInfo(Path instanceDir) {
		String[] out = {null, null};
		try {
			Path file = instanceDir.resolve("minecraftinstance.json");
			if (Files.isRegularFile(file)) {
				JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				if (json.has("gameVersion") && !json.get("gameVersion").isJsonNull()) {
					out[0] = json.get("gameVersion").getAsString();
				}
				if (json.has("baseModLoader") && json.get("baseModLoader").isJsonObject()) {
					String name = json.getAsJsonObject("baseModLoader").get("name").getAsString().toLowerCase(Locale.ROOT);
					out[1] = name.startsWith("neoforge") ? "neoforge" : name.startsWith("forge") ? "forge" : name.startsWith("quilt") ? "quilt"
							: name.startsWith("fabric") ? "fabric" : null;
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// unknown
		}
		return out;
	}

	/** Older Modrinth App profiles have a profile.json; newer ones keep it in a database, so the mods decide. */
	private static String modrinthVersion(Path profile) {
		try {
			Path file = profile.resolve("profile.json");
			if (Files.isRegularFile(file)) {
				JsonObject meta = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("metadata");
				if (meta != null && meta.has("game_version")) {
					return meta.get("game_version").getAsString();
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// from the mods
		}
		return null;
	}

	private static String modrinthLoader(Path profile) {
		try {
			Path file = profile.resolve("profile.json");
			if (Files.isRegularFile(file)) {
				JsonObject meta = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("metadata");
				if (meta != null && meta.has("loader")) {
					return meta.get("loader").getAsString().toLowerCase(Locale.ROOT);
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// from the mods
		}
		return null;
	}

	/**
	 * A library another client unpacked from its mods, not a mod of its own: Lunar's dependencies/ holds the jars nested
	 * in mods, named with a hash ({@code fabric-entity-events-v1-3.0.5+4ebb5c083e-c57b74e2adb457ee.jar}). Fabric Loader
	 * unpacks those from the mods itself; copied in as loose jars they win over newer ones - MixinExtras 0.2.2 from a Lunar
	 * import broke Fabric API's injections and the game never started (6 crash reports, 2026-11-05). Fabric API modules and
	 * MixinExtras are never imported either: Fabric API and the loader bring their own.
	 */
	static boolean bundledLibrary(Path jar, String modId) {
		String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
		Path parent = jar.getParent();
		boolean inDependencies = parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("dependencies");
		if (inDependencies && name.matches(".*-[0-9a-f]{15,16}\\.jar")) {
			return true;
		}
		if (modId == null) {
			return false;
		}
		return modId.equals("mixinextras") || modId.matches("fabric-[a-z0-9-]+-(v\\d+|base)") || modId.equals("fabric-api-lookup-api-v1");
	}

	/**
	 * Mods that are part of another client rather than something the player chose: OneClient's OneConfig and PolyPlus (with
	 * Polydium inside - its license doesn't allow passing it on). Velo has its own version of what they do.
	 */
	static boolean otherClientsOwnMod(String modId) {
		if (modId == null) {
			return false;
		}
		String id = modId.toLowerCase(Locale.ROOT);
		return id.startsWith("oneconfig") || id.startsWith("polyplus") || id.startsWith("polydium") || id.startsWith("oneclient");
	}

	/** The mod jars of a folder: its own *.jar plus a "dependencies" subfolder (Lunar puts libraries there). */
	static List<Path> modJars(Path dir) {
		if (dir != null && dir.getFileName() != null && dir.getFileName().toString().startsWith(NoRiskMods.MARKER)) {
			return NoRiskMods.jars(dir);
		}
		if (dir == null || !Files.isDirectory(dir)) {
			return List.of();
		}
		List<Path> out = new ArrayList<>();
		for (Path folder : List.of(dir, dir.resolve("dependencies"))) {
			if (!Files.isDirectory(folder)) {
				continue;
			}
			try (Stream<Path> s = Files.list(folder)) {
				s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") && Files.isRegularFile(p)).sorted().forEach(out::add);
			} catch (IOException ignored) {
				// unreadable
			}
		}
		// NoRisk v3 plays from its mod cache, not this folder (that's why imports came out short).
		for (Path jar : NoRiskMods.jars(dir)) {
			if (!out.contains(jar)) {
				out.add(jar);
			}
		}
		return out;
	}

	/** Zips plus unpacked pack folders (a folder with {@code marker} inside). */
	private static int packCount(Path dir, String marker) {
		if (dir == null || !Files.isDirectory(dir)) {
			return 0;
		}
		try (Stream<Path> s = Files.list(dir)) {
			return (int) s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")
					|| Files.isDirectory(p) && Files.exists(p.resolve(marker))).count();
		} catch (IOException e) {
			return 0;
		}
	}

	// ---- Importing ----

	/** Copies the chosen parts of {@code source} into the profile {@code instanceId} (on Minecraft {@code mcVersion}). Returns a summary. */
	public static String importInto(Source source, Options options, String instanceId, String mcVersion, Consumer<String> progress) {
		return importInto(source, options, instanceId, mcVersion, progress, f -> { });
	}

	/**
	 * {@code fraction}: 0..1 of the whole import (the parts that run, weighted: mods by count, folders by bytes). A file or
	 * folder that can't be read is skipped and the rest goes on; nothing here may end without a summary (an unreadable
	 * folder used to throw out of the background task and leave the page loading forever).
	 */
	public static String importInto(Source source, Options options, String instanceId, String mcVersion, Consumer<String> progress,
			java.util.function.DoubleConsumer fraction) {
		Path game = InstancePaths.gameDir(instanceId);
		List<String> done = new ArrayList<>();
		// Parts that run, each a slice of the bar.
		int parts = (options.settings() && source.hasOptions() ? 1 : 0) + (options.servers() && source.hasServers() ? 1 : 0)
				+ (options.mods() && source.modCount() > 0 ? 3 : 0) + (options.configs() && source.hasConfig() ? 1 : 0)
				+ (options.resourcePacks() && source.resourcePackCount() > 0 ? 1 : 0) + (options.shaderPacks() && source.shaderPackCount() > 0 ? 1 : 0)
				+ (options.worlds() && source.worldCount() > 0 ? 3 : 0);
		double[] base = {0};
		double total = Math.max(1, parts);
		java.util.function.BiConsumer<Integer, Double> part = (weight, f) -> fraction.accept(Math.min(1, (base[0] + weight * Math.max(0, Math.min(1, f))) / total));
		try {
			Files.createDirectories(game);
			if (options.settings() && source.hasOptions()) {
				progress.accept("Keybinds & settings...");
				mergeOptions(source.gameDir().resolve("options.txt"), game.resolve("options.txt"));
				done.add("keybinds & settings");
				base[0] += 1;
				fraction.accept(base[0] / total);
			}
			if (options.servers() && source.hasServers()) {
				Path target = game.resolve("servers.dat");
				if (Files.exists(target)) {
					Files.copy(target, game.resolve("servers.dat.before-import"), StandardCopyOption.REPLACE_EXISTING);
				}
				Files.copy(source.gameDir().resolve("servers.dat"), target, StandardCopyOption.REPLACE_EXISTING);
				done.add("server list");
				base[0] += 1;
				fraction.accept(base[0] / total);
			}
			if (options.mods() && source.modCount() > 0) {
				int[] result = importMods(source.modsDir(), InstancePaths.modsDir(instanceId), mcVersion, progress, f -> part.accept(3, f));
				base[0] += 3;
				done.add(result[0] + " mods" + (result[1] > 0 ? " (" + result[1] + " skipped - no Fabric build for " + mcVersion + ")" : ""));
			}
			if (options.configs() && source.hasConfig()) {
				progress.accept("Mod configs...");
				copyTree(source.configDir(), game.resolve("config"), false, f -> part.accept(1, f));
				done.add("mod configs");
				base[0] += 1;
			}
			if (options.resourcePacks() && source.resourcePackCount() > 0) {
				progress.accept("Resource packs...");
				copyTree(source.resourcePacksDir(), InstancePaths.resourcePacksDir(instanceId), false, f -> part.accept(1, f));
				done.add(source.resourcePackCount() + " resource packs");
				base[0] += 1;
			}
			if (options.shaderPacks() && source.shaderPackCount() > 0) {
				progress.accept("Shader packs...");
				copyTree(source.shaderPacksDir(), InstancePaths.shaderPacksDir(instanceId), false, f -> part.accept(1, f));
				done.add(source.shaderPackCount() + " shader packs");
				base[0] += 1;
			}
			if (options.worlds() && source.worldCount() > 0) {
				progress.accept("Worlds (this can take a while)...");
				copyTree(source.savesDir(), InstancePaths.savesDir(instanceId), false, f -> {
					part.accept(3, f);
					progress.accept("Worlds  ·  " + Math.round(f * 100) + "%");
				});
				done.add(source.worldCount() + " worlds");
				base[0] += 3;
			}
		} catch (IOException | RuntimeException e) {
			LauncherLog.warn("Import from " + source.name() + " failed part-way", e);
			done.add("stopped: " + e.getMessage());
		}
		fraction.accept(1);
		return done.isEmpty() ? "Nothing to import" : "Imported " + String.join(", ", done) + ".";
	}

	/** Every "key:value" line of the source wins; lines only the profile has stay. Keybinds are just such lines. */
	private static void mergeOptions(Path source, Path target) throws IOException {
		Map<String, String> merged = new LinkedHashMap<>();
		if (Files.exists(target)) {
			readOptions(target, merged);
			Files.copy(target, target.resolveSibling("options.txt.before-import"), StandardCopyOption.REPLACE_EXISTING);
		}
		readOptions(source, merged);
		StringBuilder out = new StringBuilder();
		merged.forEach((k, v) -> out.append(k).append(':').append(v).append('\n'));
		Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
	}

	private static void readOptions(Path file, Map<String, String> into) throws IOException {
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			int colon = line.indexOf(':');
			if (colon > 0) {
				into.put(line.substring(0, colon), line.substring(colon + 1));
			}
		}
	}

	/**
	 * Mods: each jar - Fabric, Quilt, Forge or NeoForge - is looked up on Modrinth (by checksum, else by its mod id as the project
	 * slug) and the Fabric build for {@code mcVersion} is downloaded, so mods from other loaders and older versions come along too.
	 * A Fabric jar Modrinth doesn't know is copied when it says it runs on that version. Velo Client, Fabric API and other clients'
	 * own jars are left out. Returns {imported, skipped}.
	 */
	private static int[] importMods(Path from, Path to, String mcVersion, Consumer<String> progress, java.util.function.DoubleConsumer fraction)
			throws IOException {
		Files.createDirectories(to);
		NoRiskMods.fetchMissing(from, progress);
		List<Path> jars = modJars(from);
		java.util.Set<String> projects = java.util.concurrent.ConcurrentHashMap.newKeySet();
		java.util.Set<String> present = new java.util.HashSet<>();
		try (Stream<Path> s = Files.list(to)) {
			for (Path jar : s.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
				String id = fabricModId(jar);
				if (id != null) {
					present.add(id);
				}
			}
		}
		int imported = 0;
		int skipped = 0;
		progress.accept("Looking up " + jars.size() + " mods on Modrinth...");
		fraction.accept(0.05);
		Lookup lookup = bulkLookup(jars, mcVersion);
		fraction.accept(0.15);
		// 1) what each jar needs - local checks only
		List<Path> fetch = new ArrayList<>();
		java.util.Map<Path, String> ids = new java.util.HashMap<>();
		for (Path jar : jars) {
			String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
			if (name.startsWith("velo-client") || name.startsWith("velo-addons") || name.startsWith("fabric-api") || name.contains("lunar")
					|| name.contains("feather") || name.contains("dawn-") || name.contains("norisk") || name.startsWith("nrc-")
					|| net.veloclient.launcher.launch.ForeignClientJars.isForeignClient(jar)) {
				continue;
			}
			String modId = fabricModId(jar);
			ids.put(jar, modId);
			if (modId != null && present.contains(modId)) {
				continue;
			}
			if (bundledLibrary(jar, modId) || otherClientsOwnMod(modId)) {
				skipped++;
				continue;
			}
			// Already built for this Minecraft version: take exactly this jar. Swapping it for Modrinth's newest broke
			// working setups - Sodium 0.8.12 became 0.8.15-beta and Voxy (which needs exactly <= 0.8.12) crashed the game.
			if (modId != null && madeFor(jar, mcVersion, lookup)) {
				String hash = lookup.sha1().get(jar);
				ModrinthClient.ProjectVersion owner = hash == null ? null : lookup.owner().get(hash);
				if (owner != null) {
					projects.add(owner.projectId());
				}
				try {
					Files.copy(jar, to.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
					present.add(modId);
					imported++;
				} catch (IOException e) {
					skipped++;
					LauncherLog.info("Import: couldn't copy " + jar.getFileName() + ": " + e.getMessage());
				}
				continue;
			}
			fetch.add(jar);
		}
		// 2) the Modrinth downloads, six at a time (one after another took minutes for a big mod list)
		java.util.Map<Path, Object> fetched = new java.util.concurrent.ConcurrentHashMap<>();
		java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(6);
		try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			for (Path jar : fetch) {
				pool.submit(() -> {
					slots.acquireUninterruptibly();
					try {
						Path got = fetchForVersion(jar, ids.get(jar), mcVersion, projects, lookup);
						fetched.put(jar, got == null ? Boolean.FALSE : got);
					} catch (IOException | RuntimeException e) {
						LauncherLog.info("Modrinth lookup failed for " + jar.getFileName() + ": " + e.getMessage());
						fetched.put(jar, Boolean.FALSE);
					} finally {
						slots.release();
						int n = finished.incrementAndGet();
						progress.accept("Mods " + n + " / " + fetch.size() + "  ·  " + jar.getFileName());
						fraction.accept(0.15 + 0.8 * n / Math.max(1, fetch.size()));
					}
				});
			}
		}
		// 3) put them in place, in the original order (so which of two jars for one mod wins stays the same)
		for (Path jar : fetch) {
			Object got = fetched.get(jar);
			String modId = ids.get(jar);
			if (got == FOUND_ALREADY) {
				continue;
			}
			try {
				if (got instanceof Path path) {
					String id = fabricModId(path);
					if (id == null || present.add(id)) {
						Files.copy(path, to.resolve(path.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
						imported++;
					}
					continue;
				}
				if (modId != null && runsOn(jar, mcVersion)) {
					Files.copy(jar, to.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
					present.add(modId);
					imported++;
				} else {
					skipped++;
					LauncherLog.info("Import: no Fabric build of " + jar.getFileName() + " for " + mcVersion);
				}
			} catch (IOException e) {
				skipped++;
				LauncherLog.info("Import: couldn't copy " + jar.getFileName() + ": " + e.getMessage());
			}
		}
		fraction.accept(1);
		return new int[] {imported, skipped};
	}

	/** Marker: the jar's Modrinth project was already brought in by another jar in this import. */
	private static final Path FOUND_ALREADY = Path.of("");

	/** What a few bulk Modrinth requests already found for the whole import (instead of several requests per mod). */
	private record Lookup(java.util.Map<Path, String> sha1, java.util.Map<String, ModrinthClient.ProjectVersion> latest,
			java.util.Map<String, ModrinthClient.ProjectVersion> owner, java.util.Map<String, String> slugToId) {
	}

	private static Lookup bulkLookup(List<Path> jars, String mcVersion) {
		java.util.Map<Path, String> sha1 = new java.util.HashMap<>();
		for (Path jar : jars) {
			try {
				sha1.put(jar, Downloader.sha1Of(jar));
			} catch (IOException | RuntimeException ignored) {
				// unreadable jar: per-mod fallback
			}
		}
		java.util.Map<String, ModrinthClient.ProjectVersion> latest = java.util.Map.of();
		java.util.Map<String, ModrinthClient.ProjectVersion> owner = java.util.Map.of();
		java.util.Map<String, String> slugToId = new java.util.HashMap<>();
		try {
			latest = ModrinthClient.latestForFiles(sha1.values(), "fabric", mcVersion);
			owner = ModrinthClient.versionsByFileSha1(sha1.values());
			// Jars Modrinth doesn't know by checksum: their mod ids are usually the project slug - ask for all at once.
			java.util.Set<String> slugs = new java.util.LinkedHashSet<>();
			for (Path jar : jars) {
				String hash = sha1.get(jar);
				if (hash == null || !owner.containsKey(hash)) {
					slugs.addAll(slugCandidates(jar, fabricModId(jar)));
				}
			}
			for (ModrinthClient.Project project : ModrinthClient.projects(slugs)) {
				if (project.slug() != null) {
					slugToId.put(project.slug(), project.id());
				}
				slugToId.put(project.id(), project.id());
			}
		} catch (IOException | RuntimeException e) {
			LauncherLog.info("Modrinth bulk lookup failed, looking mods up one by one: " + e.getMessage());
		}
		return new Lookup(sha1, latest, owner, slugToId);
	}

	/** The cached Fabric build for {@code mcVersion} of the jar's Modrinth project, or null when there's none. */
	private static Path fetchForVersion(Path jar, String fabricId, String mcVersion, java.util.Set<String> projects, Lookup lookup)
			throws IOException {
		String hash = lookup.sha1().get(jar);
		ModrinthClient.ProjectVersion direct = hash == null ? null : lookup.latest().get(hash);
		String projectId = direct != null ? direct.projectId()
				: hash != null && lookup.owner().containsKey(hash) ? lookup.owner().get(hash).projectId() : null;
		if (projectId == null) {
			// Not on Modrinth by checksum (a CurseForge download, a re-packed jar): most mods' Modrinth slug is their mod id.
			for (String slug : slugCandidates(jar, fabricId)) {
				String id = lookup.slugToId().get(slug);
				if (id == null && lookup.slugToId().isEmpty()) {
					id = ModrinthClient.getProject(slug).map(ModrinthClient.Project::id).orElse(null);
				}
				if (id != null) {
					projectId = id;
					break;
				}
			}
		}
		if (projectId == null && hash != null && lookup.owner().isEmpty()) {
			projectId = ModrinthClient.versionByFileSha1(hash).map(ModrinthClient.ProjectVersion::projectId).orElse(null);
		}
		if (projectId == null) {
			return null;
		}
		if (!projects.add(projectId)) {
			return FOUND_ALREADY;
		}
		Optional<ModrinthClient.ProjectVersion> build = direct != null && direct.gameVersions() != null && direct.gameVersions().contains(mcVersion)
				? Optional.of(direct)
				: ModrinthClient.versions(projectId, mcVersion).stream()
						.filter(v -> v.gameVersions() != null && v.gameVersions().contains(mcVersion))
						.filter(v -> v.loaders() != null && v.loaders().contains("fabric"))
						.findFirst();
		if (build.isEmpty()) {
			return null;
		}
		ModrinthClient.VersionFile file = build.get().primaryFile().orElse(null);
		if (file == null || file.filename() == null || file.filename().contains("/") || !file.filename().endsWith(".jar")) {
			return null;
		}
		Path cached = GameDataPaths.libraries().resolve("modrinth").resolve(projectId).resolve(file.filename());
		Downloader.ensure(URI.create(file.url()), cached, file.sha1(), file.size(), b -> { });
		return cached;
	}

	/** Mod ids a jar declares (Fabric, Quilt, Forge/NeoForge mods.toml), each a likely Modrinth slug. */
	private static List<String> slugCandidates(Path jar, String fabricId) {
		List<String> out = new ArrayList<>();
		if (fabricId != null) {
			out.add(fabricId);
		}
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (String toml : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
				ZipEntry entry = zip.getEntry(toml);
				if (entry != null) {
					try (InputStream in = zip.getInputStream(entry)) {
						Matcher m = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
						while (m.find()) {
							String id = m.group(1);
							if (!id.equals("minecraft") && !id.equals("forge") && !id.equals("neoforge") && !out.contains(id)) {
								out.add(id);
							}
						}
					}
				}
			}
			ZipEntry quilt = zip.getEntry("quilt.mod.json");
			if (quilt != null) {
				try (InputStream in = zip.getInputStream(quilt)) {
					JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
					String id = json.getAsJsonObject("quilt_loader").get("id").getAsString();
					if (!out.contains(id)) {
						out.add(id);
					}
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// no more candidates
		}
		// Underscored ids are usually dashed slugs (entity_model_features -> entity-model-features).
		for (String id : List.copyOf(out)) {
			if (id.contains("_") && !out.contains(id.replace('_', '-'))) {
				out.add(id.replace('_', '-'));
			}
		}
		return out.size() > 4 ? out.subList(0, 4) : out;
	}

	private static JsonObject fabricJson(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) {
				return null;
			}
			try (InputStream in = zip.getInputStream(entry)) {
				return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static String fabricModId(Path jar) {
		JsonObject json = fabricJson(jar);
		return json != null && json.has("id") ? json.get("id").getAsString() : null;
	}

	/** A jar's declared Minecraft range, checked loosely: "*", the exact version, or a prefix like "~26.2" / ">=26.2". */
	/**
	 * Whether the jar is known to be built for {@code mcVersion}: Modrinth lists that version for this exact file, or
	 * its fabric.mod.json names it (a jar without any Minecraft requirement doesn't count - that's no proof).
	 */
	private static boolean madeFor(Path jar, String mcVersion, Lookup lookup) {
		String hash = lookup.sha1().get(jar);
		ModrinthClient.ProjectVersion owner = hash == null ? null : lookup.owner().get(hash);
		if (owner != null && owner.gameVersions() != null) {
			return owner.gameVersions().contains(mcVersion);
		}
		JsonObject json = fabricJson(jar);
		return json != null && json.has("depends") && json.getAsJsonObject("depends").has("minecraft") && runsOn(jar, mcVersion);
	}

	private static boolean runsOn(Path jar, String mcVersion) {
		JsonObject json = fabricJson(jar);
		if (json == null || !json.has("depends") || !json.getAsJsonObject("depends").has("minecraft")) {
			return true;
		}
		String range = json.getAsJsonObject("depends").get("minecraft").toString().replaceAll("[\\[\\]\"]", "");
		if (range.contains("*")) {
			return true;
		}
		for (String part : range.split("[,\\s]+")) {
			String v = part.replaceAll("^[~^>=<]+", "").replace(".x", "");
			if (!v.isEmpty() && (mcVersion.equals(v) || mcVersion.startsWith(v + ".") || v.startsWith(mcVersion) && part.startsWith("~"))) {
				return true;
			}
		}
		return false;
	}

	private static void copyTree(Path from, Path to, boolean overwrite) throws IOException {
		copyTree(from, to, overwrite, f -> { });
	}

	/**
	 * Copies a folder; files or folders that can't be read (in use, no permission, a broken link) are skipped and logged
	 * instead of stopping everything. {@code fraction}: 0..1 by bytes copied.
	 */
	private static void copyTree(Path from, Path to, boolean overwrite, java.util.function.DoubleConsumer fraction) throws IOException {
		if (!Files.isDirectory(from)) {
			return;
		}
		List<Path> files = new ArrayList<>();
		long[] totalBytes = {0};
		Files.walkFileTree(from, new java.nio.file.SimpleFileVisitor<>() {
			@Override
			public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
				if (attrs.isRegularFile()) {
					files.add(file);
					totalBytes[0] += attrs.size();
				}
				return java.nio.file.FileVisitResult.CONTINUE;
			}

			@Override
			public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException e) {
				LauncherLog.info("Import: skipped unreadable " + file + " (" + e.getMessage() + ")");
				return java.nio.file.FileVisitResult.CONTINUE;
			}
		});
		long copied = 0;
		int skipped = 0;
		long lastReport = 0;
		for (Path file : files) {
			Path dest = to.resolve(from.relativize(file).toString());
			long size = 0;
			try {
				size = Files.size(file);
				if (overwrite || !Files.exists(dest)) {
					Files.createDirectories(dest.getParent());
					Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException | RuntimeException e) {
				skipped++;
				LauncherLog.info("Import: couldn't copy " + file + " (" + e.getMessage() + ")");
			}
			copied += size;
			if (copied - lastReport > 4L * 1024 * 1024 || copied == totalBytes[0]) {
				lastReport = copied;
				fraction.accept(totalBytes[0] == 0 ? 1 : copied / (double) totalBytes[0]);
			}
		}
		fraction.accept(1);
		if (skipped > 0) {
			LauncherLog.info("Import: " + skipped + " file(s) from " + from + " couldn't be copied (in use or no permission)");
		}
	}
}
