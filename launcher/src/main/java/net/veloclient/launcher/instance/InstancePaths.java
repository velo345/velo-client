package net.veloclient.launcher.instance;

import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Per-instance directory layout, rooted at {@code ~/.velo-client/instances/<id>/}.
 * Each instance is a fully isolated Minecraft game directory (own mods/,
 * saves/, config/, logs/) - only the downloaded libraries/assets/client jars
 * are shared across instances, see {@link net.veloclient.launcher.launch.GameDataPaths}.
 */
public final class InstancePaths {

	private InstancePaths() {
	}

	public static Path root() {
		return VeloPaths.root().resolve("instances");
	}

	public static Path dir(String instanceId) {
		return root().resolve(instanceId);
	}

	public static Path metadataFile(String instanceId) {
		return dir(instanceId).resolve("instance.json");
	}

	public static Path iconFile(String instanceId) {
		return dir(instanceId).resolve("icon.png");
	}

	/** The instance's Minecraft game directory: mods/saves/config/logs all live directly under this. */
	public static Path gameDir(String instanceId) {
		return dir(instanceId);
	}

	public static Path modsDir(String instanceId) {
		return gameDir(instanceId).resolve("mods");
	}

	public static Path resourcePacksDir(String instanceId) {
		return gameDir(instanceId).resolve("resourcepacks");
	}

	public static Path shaderPacksDir(String instanceId) {
		return gameDir(instanceId).resolve("shaderpacks");
	}

	public static Path logsDir(String instanceId) {
		return gameDir(instanceId).resolve("logs");
	}

	public static void ensureRoot() {
		try {
			Files.createDirectories(root());
		} catch (IOException e) {
			throw new RuntimeException("Failed to create instances directory: " + root(), e);
		}
	}

	public static void ensureDirectories(String instanceId) {
		for (Path dir : new Path[] {root(), dir(instanceId), modsDir(instanceId), resourcePacksDir(instanceId),
				shaderPacksDir(instanceId), logsDir(instanceId)}) {
			try {
				Files.createDirectories(dir);
			} catch (IOException e) {
				throw new RuntimeException("Failed to create instance directory: " + dir, e);
			}
		}
	}
}
