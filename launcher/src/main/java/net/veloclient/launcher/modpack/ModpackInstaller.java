package net.veloclient.launcher.modpack;

import com.google.gson.Gson;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.launch.Downloader;
import net.veloclient.launcher.modrinth.ModrinthClient;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Applies a Modrinth {@code .mrpack} modpack version into a profile's game
 * directory: every listed file (mods/config/resourcepacks/etc, whatever
 * paths the manifest specifies) plus the {@code overrides/}/{@code
 * client-overrides/} folder contents, copied straight over whatever's
 * already there. This is destructive to a profile's existing setup by
 * design (a modpack defines a complete loadout) - {@code ModpacksTabView}
 * warns and confirms before calling this.
 */
public final class ModpackInstaller {

	private static final Gson GSON = new Gson();

	private ModpackInstaller() {
	}

	public record Result(String packName, String minecraftVersion, int filesInstalled) {
	}

	/** @param onProgress fraction 0..1 across the whole apply (mrpack download + every listed file) */
	public static Result apply(String instanceId, ModrinthClient.ProjectVersion version, DoubleConsumer onProgress) throws IOException {
		Optional<ModrinthClient.VersionFile> primary = version.primaryFile();
		if (primary.isEmpty()) {
			throw new IOException("This modpack version has no downloadable file.");
		}
		ModrinthClient.VersionFile file = primary.get();
		Path cacheDir = VeloPaths.root().resolve("cache").resolve("mrpacks");
		Files.createDirectories(cacheDir);
		Path mrpackFile = cacheDir.resolve(file.filename());
		Downloader.ensure(URI.create(file.url()), mrpackFile, file.sha1(), file.size(), n -> { });
		onProgress.accept(0.05);

		MrpackIndex index = readIndex(mrpackFile);
		Path gameDir = InstancePaths.gameDir(instanceId);

		List<MrpackIndex.MrpackFile> clientFiles = index.files().stream().filter(MrpackIndex.MrpackFile::clientSupported).toList();
		int total = clientFiles.size();
		int done = 0;
		for (MrpackIndex.MrpackFile entry : clientFiles) {
			if (entry.downloads() == null || entry.downloads().isEmpty()) {
				continue;
			}
			Path dest = gameDir.resolve(entry.path()).normalize();
			if (!dest.startsWith(gameDir)) {
				// A malicious/malformed manifest could try to write outside the
				// profile's own folder via "../" path segments - refuse those
				// entries outright rather than following them.
				continue;
			}
			String sha1 = entry.hashes() != null ? entry.hashes().get("sha1") : null;
			Downloader.ensure(URI.create(entry.downloads().get(0)), dest, sha1, entry.fileSize(), n -> { });
			done++;
			double fraction = 0.05 + 0.85 * (total == 0 ? 1.0 : done / (double) total);
			onProgress.accept(fraction);
		}

		extractOverrides(mrpackFile, gameDir);
		onProgress.accept(1.0);

		return new Result(index.name(), index.minecraftVersion(), done);
	}

	private static MrpackIndex readIndex(Path mrpackFile) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(mrpackFile))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().equals("modrinth.index.json")) {
					String json = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
					return GSON.fromJson(json, MrpackIndex.class);
				}
			}
		}
		throw new IOException("Not a valid .mrpack file - missing modrinth.index.json");
	}

	/** Copies every entry under {@code overrides/} and {@code client-overrides/} (both optional, client-overrides taking priority when both set the same file) straight into {@code gameDir}, overwriting. */
	private static void extractOverrides(Path mrpackFile, Path gameDir) throws IOException {
		extractPrefixed(mrpackFile, gameDir, "overrides/");
		extractPrefixed(mrpackFile, gameDir, "client-overrides/");
	}

	private static void extractPrefixed(Path mrpackFile, Path gameDir, String prefix) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(mrpackFile))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
					continue;
				}
				String relative = entry.getName().substring(prefix.length());
				if (relative.isBlank()) {
					continue;
				}
				Path dest = gameDir.resolve(relative).normalize();
				if (!dest.startsWith(gameDir)) {
					continue;
				}
				Files.createDirectories(dest.getParent());
				// Deliberately not wrapped in its own try-with-resources: the
				// current entry's data is read directly off the shared
				// ZipInputStream (advanced by the next getNextEntry() call),
				// closing it here would end the whole zip, not just this entry.
				Files.copy(zip, dest, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}
}
