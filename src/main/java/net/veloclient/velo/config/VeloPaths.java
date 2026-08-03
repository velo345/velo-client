package net.veloclient.velo.config;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

/**
 * Resolves the {@code ~/.velo-client/} (Linux/macOS) or {@code %APPDATA%/VeloClient/}
 * (Windows) directory tree described in design spec section 8. Kept separate
 * from Minecraft's own per-instance config dir so the launcher and multiple
 * game instances can share one profile/cosmetics library.
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

	/**
	 * Separate from {@link #profiles()}, which holds the older per-server
	 * auto-load {@code ServerProfile} snapshots (address patterns + which
	 * modules to enable) - these are the manually create/choose/rename/delete
	 * {@code VeloProfile} snapshots (every module's full settings + HUD
	 * layout). Different JSON shape entirely, so they can't share a
	 * directory without one profile loader misreading the other's files.
	 */
	public static Path savedProfiles() {
		return ROOT.resolve("saved-profiles");
	}

	public static Path capes() {
		return ROOT.resolve("capes");
	}

	public static Path crosshairs() {
		return ROOT.resolve("crosshairs");
	}

	public static Path logs() {
		return ROOT.resolve("logs");
	}

	public static Path manifestFile() {
		return ROOT.resolve("manifest.json");
	}

	/** Creates the whole directory tree if it doesn't exist yet. Safe to call repeatedly. */
	public static void ensureDirectories() {
		for (Path dir : new Path[] {root(), config(), profiles(), savedProfiles(), capes(), crosshairs(), logs()}) {
			try {
				Files.createDirectories(dir);
			} catch (IOException e) {
				throw new RuntimeException("Failed to create Velo Client directory: " + dir, e);
			}
		}
	}

	/** Falls back to the mod's own Fabric config dir; used only if the shared root is unwritable. */
	public static Path fabricConfigFallback() {
		return FabricLoader.getInstance().getConfigDir().resolve("velo-client");
	}
}
