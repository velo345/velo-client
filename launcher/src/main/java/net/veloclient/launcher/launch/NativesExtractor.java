package net.veloclient.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Prepares the per-launch natives directory. Minecraft versions this
 * launcher supports (1.21+/26.x) ship LWJGL natives as ordinary per-platform
 * library jars that get put straight on the classpath and self-extract at
 * runtime, so this normally only needs to create an empty directory - the
 * legacy "natives"+"downloads.classifiers" library shape (pre-1.19) is
 * handled defensively in case it's ever encountered.
 */
public final class NativesExtractor {

	private NativesExtractor() {
	}

	public static void prepare(Path nativesDir, JsonArray vanillaLibraries) throws IOException {
		if (Files.exists(nativesDir)) {
			try (var stream = Files.walk(nativesDir)) {
				for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
		Files.createDirectories(nativesDir);
		if (vanillaLibraries == null) {
			return;
		}
		for (var element : vanillaLibraries) {
			JsonObject library = element.getAsJsonObject();
			if (!library.has("natives") || !library.has("downloads")) {
				continue;
			}
			JsonObject downloads = library.getAsJsonObject("downloads");
			if (!downloads.has("classifiers")) {
				continue;
			}
			if (library.has("rules") && !OsRules.isAllowed(library.getAsJsonArray("rules"), OsRules.NO_FEATURES)) {
				continue;
			}
			JsonObject natives = library.getAsJsonObject("natives");
			String osKey = OsRules.currentOsName();
			if (!natives.has(osKey)) {
				continue;
			}
			String classifier = natives.get(osKey).getAsString();
			JsonObject classifiers = downloads.getAsJsonObject("classifiers");
			if (!classifiers.has(classifier)) {
				continue;
			}
			JsonObject artifact = classifiers.getAsJsonObject(classifier);
			Path jarPath = GameDataPaths.libraries().resolve(artifact.get("path").getAsString());
			Downloader.ensure(java.net.URI.create(artifact.get("url").getAsString()), jarPath,
					artifact.has("sha1") ? artifact.get("sha1").getAsString() : null,
					artifact.has("size") ? artifact.get("size").getAsLong() : -1, b -> { });
			extractJar(jarPath, nativesDir);
		}
	}

	private static void extractJar(Path jar, Path destDir) throws IOException {
		try (JarFile jarFile = new JarFile(jar.toFile())) {
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) {
					continue;
				}
				Path dest = destDir.resolve(entry.getName()).normalize();
				if (!dest.startsWith(destDir)) {
					continue;
				}
				Files.createDirectories(dest.getParent());
				try (InputStream in = jarFile.getInputStream(entry)) {
					Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}
}
