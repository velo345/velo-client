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
		// The dev launcher points its games at its own folder, so dev and release settings never mix.
		String home = System.getProperty("velo.home");
		if (home != null && !home.isBlank()) {
			return Path.of(home);
		}
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

	/** The Minecraft account (dashless uuid) the per-account files belong to; null = none known yet. */
	private static volatile String account = normalize(System.getProperty("velo.account"));

	/** The Velo launcher passes {@code -Dvelo.account}; other launchers get it from the game session at start. */
	public static void setAccount(String uuid) {
		account = normalize(uuid);
	}

	private static String normalize(String uuid) {
		return uuid == null || uuid.isBlank() ? null : uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
	}

	public static String account() {
		return account;
	}

	/**
	 * A config file that belongs to one Minecraft account - what it wears (cloak, wings, aura, ...), its name style,
	 * its active look. These used to be global, so a second account showed the first one's cosmetics locally while
	 * everyone else saw nothing (the server only accepts what the account owns). The old global file goes once to
	 * the first account that asks for it ({@code accounts/legacy-owner.txt}), never to a second one.
	 */
	public static Path accountConfig(String fileName) {
		String uuid = account;
		if (uuid == null) {
			return config().resolve(fileName);
		}
		Path dir = config().resolve("accounts").resolve(uuid);
		Path file = dir.resolve(fileName);
		Path legacy = config().resolve(fileName);
		if (!Files.isDirectory(dir)) {
			try {
				Files.createDirectories(dir);
			} catch (IOException ignored) {
				// The write after this reports it.
			}
		}
		if (!Files.exists(file) && Files.exists(legacy)) {
			try {
				Path owner = config().resolve("accounts").resolve("legacy-owner.txt");
				if (!Files.exists(owner)) {
					Files.writeString(owner, uuid);
				}
				if (uuid.equals(Files.readString(owner).strip())) {
					Files.copy(legacy, file);
				}
			} catch (IOException ignored) {
				// Starts empty for this account then.
			}
		}
		return file;
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
