package net.veloclient.launcher.launch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.modrinth.ModrinthClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs a curated set of widely-used Fabric optimization mods (plus Wavey Capes, for nicer capes, and Mod Menu) into a profile, from
 * their official Modrinth releases - the single biggest FPS lever there is. Most of the frame
 * rate gap between vanilla and "PvP clients" comes from exactly this kind of engine-level work
 * (Sodium's rewritten chunk renderer alone is typically 2-3x or more), which no in-game
 * settings toggle can match. Downloaded at install time rather than bundled, same as Fabric API.
 *
 * <p>Rules, so this never fights the player:
 * <ul>
 *   <li>Only a mod Modrinth has a Fabric build of for this exact Minecraft version is installed -
 *       a brand-new version with no compatible release yet simply gets fewer (or no) mods.</li>
 *   <li>Mods with required dependencies other than Fabric API are skipped rather than guessed at.</li>
 *   <li>A mod already present under any filename (detected by its {@code fabric.mod.json} id) is
 *       left alone - including the player's own copy of it.</li>
 *   <li>A mod this installed and the player later deleted or disabled is never re-added.</li>
 *   <li>The whole thing can be switched off per profile (profile settings, "Install performance
 *       mods"), and any failure is logged and skipped - it never blocks a launch.</li>
 * </ul>
 * State lives in {@code velo-performance-mods.json} in the profile's game directory.
 */
public final class PerformanceModsInstaller {

	/** Modrinth project slug -> the Fabric mod id its jar declares. */
	private static final Map<String, String> MODS = new LinkedHashMap<>();

	static {
		MODS.put("sodium", "sodium");
		MODS.put("lithium", "lithium");
		MODS.put("ferrite-core", "ferritecore");
		MODS.put("entityculling", "entityculling");
		MODS.put("immediatelyfast", "immediatelyfast");
		MODS.put("modernfix", "modernfix");
		// Added 2026-11-05 after an A/B on 26.2 (same world, Velo + the mods above): Gnetum spreads HUD drawing over
		// frames (Vulkan +24%, OpenGL +9%), Ixeris polls window/input events off the render thread (with Gnetum:
		// Vulkan +29%, OpenGL +19%). ScalableLux (-4%), Optimized Block Entities and AsyncLogger showed no gain there.
		MODS.put("gnetum", "gnetum");
		MODS.put("ixeris", "ixeris");
		// Culls leaf faces hidden by other leaves (fancy leaves keep their look): +19% in a forest world. Needs Sodium.
		MODS.put("cull-fewer-leaves", "cull-fewer-leaves");
		// Registers the render thread with Windows' "Games" scheduler class (MMCSS); does nothing elsewhere.
		MODS.put("windows-priority-boost", "windows-priority-boost");
		// Not performance: physics for capes (Velo Client detects it and hands it Velo's capes). Fetched from Modrinth like the rest, never bundled.
		MODS.put("wavey-capes", "waveycapes");
		// Mod Menu isn't installed any more: Velo's own Mods page lists every mod and opens their settings (2026-11-05).
	}

	/**
	 * 1.8.9 / 1.9.4 on Ornithe (Java 25) - the stack OneClient and Dolphin run, each a Modrinth "ornithe" build:
	 * Pylon (LWJGL 3 + SDL 3 instead of LWJGL 2: needed on Java 25, fixes stuck keys and high-polling-rate mouse lag),
	 * Argentum (Sodium for 1.8.9) + its Extras, Cera (OptiFine resource-pack features on Argentum: CTM, custom items/GUIs),
	 * Sarcio (memory leaks, allocations, faster server pings), Exploint (exploit patches). (AsyncLogger needs a newer log4j than 1.8.9 has.) Most are 1.8.9-only;
	 * 1.9.4 gets what exists for it.
	 */
	private static final Map<String, String> LEGACY_MODS = new LinkedHashMap<>();

	static {
		LEGACY_MODS.put("pylon", "pylon");
		LEGACY_MODS.put("argentum", "argentum");
		LEGACY_MODS.put("argentum-extras", "argentum-extras");
		LEGACY_MODS.put("cera", "cera");
		LEGACY_MODS.put("sarcio", "sarcio");
		LEGACY_MODS.put("exploint", "exploint");
		LEGACY_MODS.put("windows-priority-boost", "windows-priority-boost");
	}

	private static Map<String, String> modsFor(GameVersion version) {
		return version.ornithe() ? LEGACY_MODS : MODS;
	}

	/** Mods only installed on one OS ({@link OsRules#currentOsName()}): slug -> os name. */
	private static final Map<String, String> ONLY_ON = Map.of("windows-priority-boost", "windows");

	/** Modrinth project ids of mods this list installs itself, so another one may require them: project id -> mod id. */
	private static final Map<String, String> OWN_DEPENDENCIES = Map.of("AANobbMI", "sodium",
			// Ornithe Standard Libraries (installed like Fabric API) and Argentum (for its Extras and Cera)
			"EHGtwpJv", "osl", "QRMV06RW", "argentum");

	/** Ours that need another one of ours (slug -> required mod id): taken out again when the player removes that one. */
	private static final Map<String, String> NEEDS = Map.of("cull-fewer-leaves", "sodium", "argentum-extras", "argentum", "cera", "argentum");

	/** Ours that refuse to load next to another mod ("breaks" in their fabric.mod.json): slug -> that mod's id. */
	private static final Map<String, String> CLASHES = Map.of("cull-fewer-leaves", "moreculling", "sarcio", "zirconium");

	/** Installed by older launchers, now part of Velo: removed again unless another mod needs them. */
	private static final Map<String, String> RETIRED = Map.of("modmenu", "modmenu", "placeholder-api", "placeholder-api");

	/** Modrinth's project id for Fabric API - always satisfied here. */
	private static final String FABRIC_API_PROJECT_ID = "P7dR8mSH";
	/** Text Placeholder API - installed by this list itself, ahead of Mod Menu. */
	private static final String PLACEHOLDER_API_PROJECT_ID = "eXts2L7r";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String STATE_FILE = "velo-performance-mods.json";

	/** {@code installed}: slug -> filename we put in the mods folder. */
	public static final class State {
		public boolean enabled = true;
		public String gameVersion;
		public Map<String, String> installed = new LinkedHashMap<>();
	}

	private PerformanceModsInstaller() {
	}

	public static State readState(String instanceId) {
		Path file = InstancePaths.gameDir(instanceId).resolve(STATE_FILE);
		try {
			if (Files.exists(file)) {
				State state = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
				if (state != null) {
					if (state.installed == null) {
						state.installed = new LinkedHashMap<>();
					}
					return state;
				}
			}
		} catch (IOException | RuntimeException e) {
			LauncherLog.warn("Couldn't read " + STATE_FILE + " - starting fresh", e);
		}
		return new State();
	}

	public static void writeState(String instanceId, State state) {
		Path file = InstancePaths.gameDir(instanceId).resolve(STATE_FILE);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(state), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LauncherLog.warn("Couldn't save " + STATE_FILE, e);
		}
	}

	/** Best-effort; never throws. */
	public static void installInto(String instanceId, Path modsDir, GameVersion version) {
		try {
			State state = readState(instanceId);
			if (!state.enabled) {
				return;
			}
			// A profile moved to another Minecraft version needs builds for that version - drop our
			// old files (only the ones we installed) and start over for the new one.
			if (state.gameVersion != null && !state.gameVersion.equals(version.id())) {
				for (String fileName : state.installed.values()) {
					Files.deleteIfExists(modsDir.resolve(fileName));
					Files.deleteIfExists(modsDir.resolve(fileName + ".disabled"));
				}
				state.installed.clear();
			}
			state.gameVersion = version.id();

			Files.createDirectories(modsDir);
			// One we installed that another mod no longer accepts (Nvidium wants exactly Sodium 0.9.2...): swap it below.
			for (Map.Entry<String, String> mod : modsFor(version).entrySet()) {
				String fileName = state.installed.get(mod.getKey());
				Path file = fileName == null ? null : modsDir.resolve(fileName);
				if (file != null && Files.exists(file)) {
					String installed = net.veloclient.launcher.doctor.ModRequirements.versionOf(file);
					if (installed != null && !net.veloclient.launcher.doctor.ModRequirements.acceptsMod(modsDir, mod.getValue(), installed)) {
						LauncherLog.info("Another mod needs a different " + mod.getKey() + " than " + installed + " - picking one that fits");
						Files.delete(file);
						state.installed.remove(mod.getKey());
					}
				}
			}
			for (Map.Entry<String, String> retired : RETIRED.entrySet()) {
				String fileName = state.installed.get(retired.getKey());
				if (fileName != null && !net.veloclient.launcher.doctor.ModRequirements.someoneDepends(modsDir, retired.getValue(), fileName)) {
					Files.deleteIfExists(modsDir.resolve(fileName));
					Files.deleteIfExists(modsDir.resolve(fileName + ".disabled"));
					state.installed.remove(retired.getKey());
					LauncherLog.info("Removed " + fileName + " - Velo's Mods page does what it did");
				}
			}
			Set<String> presentModIds = installedModIds(modsDir);
			Set<String> loading = enabledModIds(modsDir);
			for (Map.Entry<String, String> clash : CLASHES.entrySet()) {
				String fileName = state.installed.get(clash.getKey());
				if (fileName != null && Files.exists(modsDir.resolve(fileName)) && loading.contains(clash.getValue())) {
					// the player added the other one (MoreCulling): theirs wins, ours would stop the game from starting
					Files.deleteIfExists(modsDir.resolve(fileName));
					LauncherLog.info("Removed " + fileName + " - it can't run next to " + clash.getValue());
				}
			}
			for (Map.Entry<String, String> need : NEEDS.entrySet()) {
				String fileName = state.installed.get(need.getKey());
				if (fileName != null && Files.exists(modsDir.resolve(fileName)) && !loading.contains(need.getValue())) {
					// e.g. Cull Fewer Leaves without Sodium wouldn't even start the game
					Files.deleteIfExists(modsDir.resolve(fileName));
					state.installed.remove(need.getKey());
					LauncherLog.info("Removed " + fileName + " - it needs " + need.getValue() + ", which isn't installed any more");
				}
			}
			for (Map.Entry<String, String> mod : modsFor(version).entrySet()) {
				String slug = mod.getKey();
				// Installed by us before: either still there, or the player removed/disabled it on
				// purpose - either way, nothing to do.
				if (state.installed.containsKey(slug) || presentModIds.contains(mod.getValue())) {
					continue;
				}
				String clash = CLASHES.get(slug);
				if (clash != null && presentModIds.contains(clash)) {
					continue;
				}
				String os = ONLY_ON.get(slug);
				if (os != null && !os.equals(OsRules.currentOsName())) {
					continue;
				}
				try {
					installOne(slug, version, modsDir, presentModIds).ifPresent(fileName -> {
						state.installed.put(slug, fileName);
						writeDefaultConfig(slug, modsDir.getParent());
					});
				} catch (IOException | RuntimeException e) {
					LauncherLog.warn("Skipping performance mod " + slug + " for " + version.id(), e);
				}
			}
			writeState(instanceId, state);
		} catch (IOException | RuntimeException e) {
			LauncherLog.warn("Performance mod install failed - launching without changes", e);
		}
	}

	private static Optional<String> installOne(String slug, GameVersion version, Path modsDir, Set<String> presentModIds) throws IOException {
		List<ModrinthClient.ProjectVersion> candidates = ModrinthClient.versions(slug, version.id(), "mod", version.modrinthLoader());
		// Modrinth lists newest first.
		List<ModrinthClient.ProjectVersion> fitting = candidates.stream()
				.filter(v -> v.gameVersions() != null && v.gameVersions().contains(version.id()))
				.filter(v -> v.loaders() != null && v.loaders().contains(version.modrinthLoader()))
				.toList();
		Optional<ModrinthClient.ProjectVersion> chosen = fitting.stream().findFirst();
		String modId = modsFor(version).get(slug);
		if (chosen.isPresent() && net.veloclient.launcher.doctor.ModRequirements.constrains(modsDir, modId)) {
			// Another mod wants a certain version of it: the newest build every mod accepts (read from the jar itself -
			// Modrinth's version names don't match fabric.mod.json's).
			chosen = Optional.empty();
			for (ModrinthClient.ProjectVersion candidate : fitting.stream().limit(12).toList()) {
				ModrinthClient.VersionFile f = candidate.primaryFile().orElse(null);
				if (f == null || f.filename() == null || !f.filename().endsWith(".jar") || f.filename().contains("/")) {
					continue;
				}
				Path cached = GameDataPaths.libraries().resolve("modrinth").resolve(slug).resolve(f.filename());
				Downloader.ensure(URI.create(f.url()), cached, f.sha1(), f.size(), b -> { });
				String v = net.veloclient.launcher.doctor.ModRequirements.versionOf(cached);
				if (v != null && net.veloclient.launcher.doctor.ModRequirements.acceptsMod(modsDir, modId, v)) {
					chosen = Optional.of(candidate);
					break;
				}
			}
		}
		if (chosen.isEmpty()) {
			return Optional.empty();
		}
		ModrinthClient.ProjectVersion projectVersion = chosen.get();
		Set<String> loading = enabledModIds(modsDir);
		boolean unmetDependency = projectVersion.requiredDependencies().stream()
				.anyMatch(d -> !FABRIC_API_PROJECT_ID.equals(d.projectId()) && !PLACEHOLDER_API_PROJECT_ID.equals(d.projectId())
						// one of ours (Sodium for Cull Fewer Leaves) - fine while it's really in the folder
						&& !loading.contains(OWN_DEPENDENCIES.getOrDefault(d.projectId(), "\0")));
		if (unmetDependency) {
			// Fabric API is always installed alongside velo-client (FabricApiInstaller); anything
			// else would need resolving, which this installer doesn't guess at - skip that mod.
			LauncherLog.info("Skipping " + slug + " - it has required dependencies");
			return Optional.empty();
		}
		ModrinthClient.VersionFile file = projectVersion.primaryFile().orElse(null);
		if (file == null || file.filename() == null || !file.filename().endsWith(".jar") || file.filename().contains("/")) {
			return Optional.empty();
		}
		Path cached = GameDataPaths.libraries().resolve("modrinth").resolve(slug).resolve(file.filename());
		Downloader.ensure(URI.create(file.url()), cached, file.sha1(), file.size(), b -> { });
		Path dest = modsDir.resolve(file.filename());
		Files.copy(cached, dest, StandardCopyOption.REPLACE_EXISTING);
		presentModIds.add(modsFor(version).get(slug));
		LauncherLog.info("Installed performance mod " + file.filename() + " for " + version.id());
		return Optional.of(file.filename());
	}

	/**
	 * Velo's starting config for a mod it just installed, only when the player has none yet. Gnetum: no "HUD FPS"
	 * counter of its own on screen (Velo has its FPS display).
	 */
	private static void writeDefaultConfig(String slug, Path gameDir) {
		// A full file (Gnetum's own defaults, one value changed): a partial one could leave its other settings unset.
		String file = switch (slug) {
			case "gnetum" -> "gnetum.json";
			default -> null;
		};
		if (file == null) {
			return;
		}
		Path target = gameDir.resolve("config").resolve(file);
		try (InputStream in = PerformanceModsInstaller.class.getResourceAsStream("/net/veloclient/launcher/modconfig/" + file)) {
			if (in != null && !Files.exists(target)) {
				Files.createDirectories(target.getParent());
				Files.copy(in, target);
			}
		} catch (IOException e) {
			LauncherLog.warn("Couldn't write the default config for " + slug, e);
		}
	}

	/** Mod ids (plus "provides" aliases) of every jar in {@code modsDir}, enabled or disabled. */
	private static Set<String> installedModIds(Path modsDir) throws IOException {
		return modIds(modsDir, true);
	}

	/** Mod ids of the jars that will really load (not .disabled). */
	private static Set<String> enabledModIds(Path modsDir) throws IOException {
		return modIds(modsDir, false);
	}

	private static Set<String> modIds(Path modsDir, boolean withDisabled) throws IOException {
		Set<String> ids = new HashSet<>();
		try (Stream<Path> files = Files.list(modsDir)) {
			for (Path jar : files.filter(p -> {
				String name = p.getFileName().toString();
				return name.endsWith(".jar") || withDisabled && name.endsWith(".jar.disabled");
			}).toList()) {
				try (ZipFile zip = new ZipFile(jar.toFile())) {
					ZipEntry entry = zip.getEntry("fabric.mod.json");
					if (entry == null) {
						continue;
					}
					try (InputStream in = zip.getInputStream(entry)) {
						JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
						if (json.has("id")) {
							ids.add(json.get("id").getAsString());
						}
						if (json.has("provides") && json.get("provides").isJsonArray()) {
							json.getAsJsonArray("provides").forEach(e -> ids.add(e.getAsString()));
						}
					}
				} catch (IOException | RuntimeException ignored) {
					// Not a readable mod jar - irrelevant here.
				}
			}
		}
		return ids;
	}
}
