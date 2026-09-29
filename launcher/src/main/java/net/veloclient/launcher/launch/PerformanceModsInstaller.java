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
 * Installs a small, curated set of widely-used Fabric optimization mods into a profile, from
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
	}

	/** Modrinth's project id for Fabric API - the one dependency always satisfied here. */
	private static final String FABRIC_API_PROJECT_ID = "P7dR8mSH";

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
			Set<String> presentModIds = installedModIds(modsDir);
			for (Map.Entry<String, String> mod : MODS.entrySet()) {
				String slug = mod.getKey();
				// Installed by us before: either still there, or the player removed/disabled it on
				// purpose - either way, nothing to do.
				if (state.installed.containsKey(slug) || presentModIds.contains(mod.getValue())) {
					continue;
				}
				try {
					installOne(slug, version, modsDir, presentModIds).ifPresent(fileName -> state.installed.put(slug, fileName));
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
		List<ModrinthClient.ProjectVersion> candidates = ModrinthClient.versions(slug, version.id());
		// Modrinth lists newest first.
		Optional<ModrinthClient.ProjectVersion> chosen = candidates.stream()
				.filter(v -> v.gameVersions() != null && v.gameVersions().contains(version.id()))
				.filter(v -> v.loaders() != null && v.loaders().contains("fabric"))
				.findFirst();
		if (chosen.isEmpty()) {
			return Optional.empty();
		}
		ModrinthClient.ProjectVersion projectVersion = chosen.get();
		boolean unmetDependency = projectVersion.requiredDependencies().stream()
				.anyMatch(d -> !FABRIC_API_PROJECT_ID.equals(d.projectId()));
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
		presentModIds.add(MODS.get(slug));
		LauncherLog.info("Installed performance mod " + file.filename() + " for " + version.id());
		return Optional.of(file.filename());
	}

	/** Mod ids (plus "provides" aliases) of every jar in {@code modsDir}, enabled or disabled. */
	private static Set<String> installedModIds(Path modsDir) throws IOException {
		Set<String> ids = new HashSet<>();
		try (Stream<Path> files = Files.list(modsDir)) {
			for (Path jar : files.filter(p -> {
				String name = p.getFileName().toString();
				return name.endsWith(".jar") || name.endsWith(".jar.disabled");
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
