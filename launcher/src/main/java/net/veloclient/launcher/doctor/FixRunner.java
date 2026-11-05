package net.veloclient.launcher.doctor;

import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.data.InstalledAsset;
import net.veloclient.launcher.data.InstalledAssetStore;
import net.veloclient.launcher.doctor.Diagnosis.Action;
import net.veloclient.launcher.doctor.Diagnosis.Fix;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.launch.Downloader;
import net.veloclient.launcher.launch.GraphicsApi;
import net.veloclient.launcher.modrinth.ModrinthClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Carries out fixes: turns files on / off (renaming to {@code .disabled}, nothing is deleted), moves files to the
 * profile's backups folder, installs and updates from Modrinth (with the dependencies the new version needs), and
 * changes the graphics API, memory or Iris' shader switch. One failing action never stops the others.
 */
final class FixRunner {

	record Result(List<Fix> applied, List<String> failures) {
	}

	private FixRunner() {
	}

	static Result run(Instance instance, List<Fix> fixes, BiConsumer<String, Double> progress) {
		List<Fix> applied = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		int total = fixes.stream().mapToInt(f -> f.actions().size()).sum();
		int done = 0;
		Instance current = instance;
		for (Fix fix : fixes) {
			boolean ok = true;
			for (Action action : fix.actions()) {
				progress.accept(fix.label() + "...", done / (double) Math.max(1, total));
				try {
					current = apply(current, action);
				} catch (Exception e) {
					ok = false;
					failures.add(fix.label() + ": " + e.getMessage());
					LauncherLog.info("Doctor: " + fix.label() + " failed: " + e);
				}
				done++;
			}
			if (ok) {
				applied.add(fix);
			}
		}
		progress.accept("Done", 1.0);
		return new Result(applied, failures);
	}

	private static Instance apply(Instance instance, Action action) throws IOException {
		switch (action) {
			case Action.Disable d -> setEnabled(d.file(), false);
			case Action.Enable e -> setEnabled(e.file(), true);
			case Action.Remove r -> {
				Path file = existing(r.file());
				if (file != null) {
					Path backups = InstancePaths.dir(instance.id()).resolve("backups").resolve(file.getParent().getFileName().toString());
					Files.createDirectories(backups);
					Files.move(file, backups.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			case Action.Install i -> install(instance, i.project(), i.name(), i.requirement(), 0);
			case Action.Update u -> update(instance, u);
			case Action.SetGraphicsApi g -> GraphicsApi.write(instance.id(), g.value());
			case Action.SetMemory m -> {
				Instance changed = m.maxMb() == 0 ? instance.withSettings(null, null, instance.extraJvmArgs())
						: instance.withSettings(null, m.maxMb(), instance.extraJvmArgs());
				InstanceStore.save(changed);
				return changed;
			}
			case Action.DisableShaders ignored -> disableShaders(instance);
			case Action.ClearFabricCache ignored -> clearFabricCache(instance);
			case Action.LowerRenderDistance r -> lowerRenderDistance(instance, r.max());
			case Action.SetLoader l -> {
				net.veloclient.launcher.launch.GameVersion version = net.veloclient.launcher.launch.GameVersion.byId(instance.mcVersion());
				if (version == null) {
					throw new IOException("unknown Minecraft version " + instance.mcVersion());
				}
				net.veloclient.launcher.launch.LoaderVersions.set(instance.id(), version, l.version());
			}
		}
		return instance;
	}

	/** Fabric's remapped game jars and processed mods - all rebuilt on the next start. */
	private static void clearFabricCache(Instance instance) throws IOException {
		Path cache = InstancePaths.gameDir(instance.id()).resolve(".fabric");
		if (!Files.exists(cache)) {
			return;
		}
		try (var walk = Files.walk(cache)) {
			for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	/** The file as it is on disk now, enabled or disabled. */
	private static Path existing(Path file) {
		if (Files.exists(file)) {
			return file;
		}
		String name = file.getFileName().toString();
		Path other = name.endsWith(".disabled") ? file.resolveSibling(name.substring(0, name.length() - ".disabled".length()))
				: file.resolveSibling(name + ".disabled");
		return Files.exists(other) ? other : null;
	}

	private static void setEnabled(Path file, boolean on) throws IOException {
		Path now = existing(file);
		if (now == null) {
			throw new IOException("the file is gone");
		}
		String name = now.getFileName().toString();
		boolean disabled = name.endsWith(".disabled");
		if (on && disabled) {
			Files.move(now, now.resolveSibling(name.substring(0, name.length() - ".disabled".length())), StandardCopyOption.REPLACE_EXISTING);
		} else if (!on && !disabled) {
			Files.move(now, now.resolveSibling(name + ".disabled"), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void install(Instance instance, String project, String name, String requirement, int depth) throws IOException {
		Optional<ModrinthClient.Project> found = ModrinthClient.getProject(project);
		if (found.isEmpty()) {
			// Mod ids aren't always Modrinth slugs ("yet_another_config_lib_v3"): search by name and take a close hit.
			var hits = ModrinthClient.search(name, "mod", instance.mcVersion(), "relevance", 0, 5).hits();
			String want = norm(name);
			for (var hit : hits) {
				if (norm(hit.title()).equals(want) || norm(hit.slug()).equals(norm(project))) {
					found = ModrinthClient.getProject(hit.projectId());
					break;
				}
			}
			if (found.isEmpty() && !hits.isEmpty() && norm(hits.get(0).title()).contains(want)) {
				found = ModrinthClient.getProject(hits.get(0).projectId());
			}
		}
		if (found.isEmpty()) {
			throw new IOException(name + " isn't on Modrinth");
		}
		String projectId = found.get().id();
		ModrinthClient.ProjectVersion version = fitting(projectId, instance.mcVersion(), requirement)
				.or(() -> {
					try {
						return newest(projectId, instance.mcVersion());
					} catch (IOException e) {
						return Optional.empty();
					}
				})
				.orElseThrow(() -> new IOException(name + " has no version for Minecraft " + instance.mcVersion() + " yet"));
		download(instance, version, null);
		if (depth < 2) {
			Snapshot after = Snapshot.take(instance);
			var provided = after.provided();
			for (ModrinthClient.Dependency dep : version.requiredDependencies()) {
				Optional<ModrinthClient.Project> depProject = ModrinthClient.getProject(dep.projectId());
				if (depProject.isPresent() && !provided.containsKey(depProject.get().slug()) && !"P7dR8mSH".equals(dep.projectId())
						&& InstalledAssetStore.loadAll(instance.id(), InstalledAssetStore.Kind.MOD).stream().noneMatch(a -> a.projectId().equals(dep.projectId()))) {
					install(instance, dep.projectId(), depProject.get().title(), null, depth + 1);
				}
			}
		}
	}

	private static void update(Instance instance, Action.Update u) throws IOException {
		Path file = existing(u.file());
		if (file == null) {
			throw new IOException("the file is gone");
		}
		String projectId = null;
		String currentVersion = null;
		InstalledAsset asset = InstalledAssetStore.asMap(InstalledAssetStore.loadAll(instance.id(), InstalledAssetStore.Kind.MOD))
				.get(file.getFileName().toString().replace(".disabled", ""));
		if (asset != null) {
			projectId = asset.projectId();
			currentVersion = asset.versionId();
		} else {
			Optional<ModrinthClient.ProjectVersion> byHash = ModrinthClient.versionByFileSha1(Downloader.sha1Of(file));
			if (byHash.isPresent()) {
				projectId = byHash.get().projectId();
				currentVersion = byHash.get().id();
			}
		}
		Optional<ModrinthClient.ProjectVersion> newest = projectId == null ? Optional.empty() : newest(projectId, instance.mcVersion());
		if (newest.isEmpty() || newest.get().id().equals(currentVersion)) {
			if (u.orElseDisable()) {
				setEnabled(file, false);
				return;
			}
			throw new IOException(projectId == null ? u.name() + " isn't on Modrinth" : "no newer " + u.name() + " for Minecraft " + instance.mcVersion());
		}
		download(instance, newest.get(), file);
	}

	/**
	 * The newest version for this Minecraft version whose number fits the range a mod asked for. Modrinth version
	 * numbers are sometimes prefixed ("26.2-0.29.5", "mc26.2-0.29.5"), so the part after the last dash is tried too.
	 */
	private static Optional<ModrinthClient.ProjectVersion> fitting(String projectId, String mcVersion, String requirement) throws IOException {
		if (requirement == null) {
			return Optional.empty();
		}
		return ModrinthClient.versions(projectId, mcVersion, "mod").stream()
				.filter(v -> VersionPredicate.matches(requirement, v.versionNumber())
						|| v.versionNumber().contains("-") && VersionPredicate.matches(requirement, v.versionNumber().substring(v.versionNumber().lastIndexOf('-') + 1)))
				.max(Comparator.comparing(ModrinthClient.ProjectVersion::datePublished));
	}

	private static Optional<ModrinthClient.ProjectVersion> newest(String projectId, String mcVersion) throws IOException {
		return ModrinthClient.versions(projectId, mcVersion, "mod").stream()
				.filter(v -> v.versionType() == null || "release".equals(v.versionType()) || "beta".equals(v.versionType()))
				.max(Comparator.comparing(ModrinthClient.ProjectVersion::datePublished))
				.or(() -> {
					try {
						return ModrinthClient.versions(projectId, mcVersion, "mod").stream().max(Comparator.comparing(ModrinthClient.ProjectVersion::datePublished));
					} catch (IOException e) {
						return Optional.empty();
					}
				});
	}

	/** Downloads {@code version} into the mods folder; {@code replaces} (nullable) goes to backups afterwards. */
	private static void download(Instance instance, ModrinthClient.ProjectVersion version, Path replaces) throws IOException {
		ModrinthClient.VersionFile file = version.primaryFile().orElseThrow(() -> new IOException("the version has no file"));
		Path mods = InstancePaths.modsDir(instance.id());
		Files.createDirectories(mods);
		Path dest = mods.resolve(file.filename());
		Downloader.ensure(URI.create(file.url()), dest, file.sha1(), file.size(), n -> { });
		ModrinthClient.getProject(version.projectId()).ifPresent(project -> InstalledAssetStore.record(instance.id(), InstalledAssetStore.Kind.MOD,
				new InstalledAsset(project.id(), version.id(), file.filename(), project.title(), project.description(), project.iconUrl(),
						version.versionNumber(), "mod")));
		if (replaces != null && !replaces.getFileName().toString().equals(file.filename())) {
			Path backups = InstancePaths.dir(instance.id()).resolve("backups").resolve("mods");
			Files.createDirectories(backups);
			Files.move(replaces, backups.resolve(replaces.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			InstalledAssetStore.forget(instance.id(), InstalledAssetStore.Kind.MOD, replaces.getFileName().toString().replace(".disabled", ""));
		}
	}

	private static void lowerRenderDistance(Instance instance, int max) throws IOException {
		Path options = InstancePaths.gameDir(instance.id()).resolve("options.txt");
		if (!Files.exists(options)) {
			Files.writeString(options, "renderDistance:" + max + "\nsimulationDistance:" + Math.min(max, 8) + "\n", StandardCharsets.UTF_8);
			return;
		}
		List<String> lines = new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8));
		boolean found = false;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			for (String key : new String[] {"renderDistance:", "simulationDistance:"}) {
				if (line.startsWith(key)) {
					found |= key.equals("renderDistance:");
					try {
						int value = Integer.parseInt(line.substring(key.length()).strip());
						if (value > max) {
							lines.set(i, key + max);
						}
					} catch (NumberFormatException e) {
						lines.set(i, key + max);
					}
				}
			}
		}
		if (!found) {
			lines.add("renderDistance:" + max);
		}
		Files.write(options, lines, StandardCharsets.UTF_8);
	}

	private static void disableShaders(Instance instance) throws IOException {
		Path props = InstancePaths.gameDir(instance.id()).resolve("config").resolve("iris.properties");
		Files.createDirectories(props.getParent());
		List<String> lines = Files.exists(props) ? new ArrayList<>(Files.readAllLines(props, StandardCharsets.UTF_8)) : new ArrayList<>();
		lines.removeIf(l -> l.startsWith("enableShaders="));
		lines.add("enableShaders=false");
		Files.write(props, lines, StandardCharsets.UTF_8);
	}

	private static String norm(String s) {
		return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}
}
