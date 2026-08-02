package net.veloclient.launcher.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the same {@code ~/.velo-client/} tree the mod uses
 * ({@code net.veloclient.velo.config.VeloPaths}), so the launcher and the
 * in-game panel share one config/profile/manifest/cape source of truth
 * (design spec section 8). Deliberately duplicated rather than shared code,
 * since the launcher and the Fabric mod are separate applications/classpaths.
 */
public final class VeloPaths {

	private static final Path ROOT = resolveRoot();

	private VeloPaths() {
	}

	private static Path resolveRoot() {
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			Path base = appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"));
			return base.resolve("VeloClient");
		}
		return Path.of(System.getProperty("user.home"), ".velo-client");
	}

	public static Path root() {
		return ROOT;
	}

	public static Path config() {
		return ROOT.resolve("config");
	}

	public static Path profiles() {
		return ROOT.resolve("profiles");
	}

	public static Path capes() {
		return ROOT.resolve("capes");
	}

	public static Path logs() {
		return ROOT.resolve("logs");
	}

	public static Path manifestFile() {
		return ROOT.resolve("manifest.json");
	}

	public static void ensureDirectories() {
		for (Path dir : new Path[] {root(), config(), profiles(), capes(), logs()}) {
			try {
				Files.createDirectories(dir);
			} catch (IOException e) {
				throw new RuntimeException("Failed to create Velo Client directory: " + dir, e);
			}
		}
	}
}
