package net.veloclient.launcher.launch;

import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The download cache shared across every instance of a given Minecraft
 * version (libraries, assets, the vanilla client jar) - never duplicated per
 * profile, MultiMC/Prism-style. Contrast with {@link net.veloclient.launcher.instance.InstancePaths},
 * which is per-profile and isolated.
 */
public final class GameDataPaths {

	private GameDataPaths() {
	}

	private static Path root() {
		return VeloPaths.root().resolve("gamedata");
	}

	public static Path versionDir(String mcVersion) {
		return root().resolve("versions").resolve(mcVersion);
	}

	public static Path versionProfileFile(String mcVersion) {
		return versionDir(mcVersion).resolve("version.json");
	}

	public static Path clientJar(String mcVersion) {
		return versionDir(mcVersion).resolve("client.jar");
	}

	public static Path libraries() {
		return root().resolve("libraries");
	}

	public static Path assetIndexes() {
		return root().resolve("assets").resolve("indexes");
	}

	public static Path assetObjects() {
		return root().resolve("assets").resolve("objects");
	}

	public static Path assetsRoot() {
		return root().resolve("assets");
	}

	public static void ensureDirectories(String mcVersion) {
		for (Path dir : new Path[] {versionDir(mcVersion), libraries(), assetIndexes(), assetObjects()}) {
			try {
				Files.createDirectories(dir);
			} catch (IOException e) {
				throw new RuntimeException("Failed to create game data directory: " + dir, e);
			}
		}
	}
}
