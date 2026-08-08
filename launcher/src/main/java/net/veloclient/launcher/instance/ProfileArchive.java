package net.veloclient.launcher.instance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Exports/imports a profile as a plain {@code .zip} of its mods/config/
 * resource pack/shader pack folders plus a small metadata file - not a
 * bespoke binary format, deliberately just those folders as-is, so the
 * result is already useful to (or importable from) any other Fabric
 * launcher that also just uses a mods folder, not only Velo Client's own.
 *
 * <p>{@link #importFrom} recognizes two shapes: a zip this class itself
 * produced (has {@code velo-profile.json} at the root - its Minecraft
 * version/RAM/JVM args are restored exactly), or any other zip (treated as
 * a generic "mods folder export" from elsewhere - every {@code .jar} found
 * at any depth is collected into the new profile's mods folder, and a
 * top-level {@code config/} folder if present, best-effort since there's no
 * metadata to read the Minecraft version from).
 */
public final class ProfileArchive {

	private static final String METADATA_ENTRY = "velo-profile.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private record ExportedMetadata(String mcVersion, Integer ramMinMb, Integer ramMaxMb, String extraJvmArgs) {
	}

	private ProfileArchive() {
	}

	public static void export(Instance instance, Path targetZip) throws IOException {
		try (OutputStream fileOut = Files.newOutputStream(targetZip); ZipOutputStream zip = new ZipOutputStream(fileOut)) {
			ExportedMetadata metadata = new ExportedMetadata(
					instance.mcVersion(), instance.ramMinMb(), instance.ramMaxMb(), instance.extraJvmArgs());
			zip.putNextEntry(new ZipEntry(METADATA_ENTRY));
			zip.write(GSON.toJson(metadata).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();

			addDirectoryIfPresent(zip, InstancePaths.modsDir(instance.id()), "mods/");
			addDirectoryIfPresent(zip, InstancePaths.resourcePacksDir(instance.id()), "resourcepacks/");
			addDirectoryIfPresent(zip, InstancePaths.shaderPacksDir(instance.id()), "shaderpacks/");
			Path configDir = InstancePaths.gameDir(instance.id()).resolve("config");
			addDirectoryIfPresent(zip, configDir, "config/");
		}
	}

	private static void addDirectoryIfPresent(ZipOutputStream zip, Path dir, String entryPrefix) throws IOException {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				String relative = dir.relativize(file).toString().replace('\\', '/');
				zip.putNextEntry(new ZipEntry(entryPrefix + relative));
				Files.copy(file, zip);
				zip.closeEntry();
			}
		}
	}

	/** @return the newly created profile, named {@code name} and pointed at whatever Minecraft version the archive specifies (or {@code fallbackVersion} if it doesn't). */
	public static Instance importFrom(Path zipFile, String name, String fallbackVersion) throws IOException {
		ExportedMetadata metadata = readMetadata(zipFile);
		String mcVersion = metadata != null && metadata.mcVersion() != null ? metadata.mcVersion() : fallbackVersion;
		String id = UUID.randomUUID().toString();
		InstancePaths.ensureDirectories(id);

		try (InputStream fileIn = Files.newInputStream(zipFile); ZipInputStream zip = new ZipInputStream(fileIn)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				extractEntry(zip, entry, id);
			}
		}

		Instance instance = new Instance(id, name, mcVersion, InstanceIcon.builtin(BuiltinIcons.DEFAULT),
				System.currentTimeMillis());
		if (metadata != null) {
			instance = instance.withSettings(metadata.ramMinMb(), metadata.ramMaxMb(), metadata.extraJvmArgs());
		}
		InstanceStore.save(instance);
		return instance;
	}

	private static void extractEntry(ZipInputStream zip, ZipEntry entry, String instanceId) throws IOException {
		if (entry.isDirectory() || entry.getName().equals(METADATA_ENTRY)) {
			return;
		}
		String name = entry.getName().replace('\\', '/');
		Path destination = destinationFor(name, instanceId);
		if (destination == null) {
			return;
		}
		Files.createDirectories(destination.getParent());
		Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Maps a zip entry path to where it belongs in the new instance: our own
	 * {@code mods/}, {@code config/}, {@code resourcepacks/}, {@code
	 * shaderpacks/} prefixes go straight to their matching folder; anything
	 * else falls back to "is it a .jar anywhere, or a config/ file at any
	 * depth" so a plain third-party mods-folder zip still imports sensibly.
	 */
	private static Path destinationFor(String entryName, String instanceId) {
		Path gameDir = InstancePaths.gameDir(instanceId);
		for (String prefix : new String[] {"mods/", "resourcepacks/", "shaderpacks/", "config/"}) {
			if (entryName.startsWith(prefix)) {
				return gameDir.resolve(entryName);
			}
		}
		if (entryName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
			String fileName = entryName.substring(entryName.lastIndexOf('/') + 1);
			return InstancePaths.modsDir(instanceId).resolve(fileName);
		}
		if (entryName.contains("config/")) {
			String relative = entryName.substring(entryName.indexOf("config/"));
			return gameDir.resolve(relative);
		}
		return null;
	}

	private static ExportedMetadata readMetadata(Path zipFile) throws IOException {
		try (InputStream fileIn = Files.newInputStream(zipFile); ZipInputStream zip = new ZipInputStream(fileIn)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().equals(METADATA_ENTRY)) {
					String json = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
					try {
						return GSON.fromJson(json, ExportedMetadata.class);
					} catch (JsonSyntaxException e) {
						return null;
					}
				}
			}
		}
		return null;
	}
}
