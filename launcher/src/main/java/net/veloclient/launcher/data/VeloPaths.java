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
		// The dev launcher keeps its own profiles, instances and caches, so it never touches the real ones.
		boolean dev = net.veloclient.launcher.AppVersion.DEV;
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			Path base = appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"));
			return base.resolve(dev ? "VeloClientDev" : "VeloClient");
		}
		Path root = Path.of(System.getProperty("user.home"), dev ? ".velo-client-dev" : ".velo-client");
		if (dev) {
			copyAccountOnce(root);
		}
		return root;
	}

	/** The dev launcher starts signed in: the first time, it takes a copy of the real launcher's account file. */
	private static void copyAccountOnce(Path devRoot) {
		Path account = devRoot.resolve("config").resolve("account.json");
		Path real = Path.of(System.getProperty("user.home"), ".velo-client", "config", "account.json");
		try {
			if (!Files.exists(account) && Files.exists(real)) {
				Files.createDirectories(account.getParent());
				Files.copy(real, account);
			}
		} catch (IOException ignored) {
			// sign in by hand then
		}
	}

	public static Path root() {
		return ROOT;
	}

	/**
	 * Where downloaded game files (Minecraft's libraries, assets and Java runtimes) live: shared with the real launcher
	 * even in a dev build - they're the same files, and a dev launcher shouldn't download Minecraft all over again.
	 */
	public static Path sharedRoot() {
		if (!net.veloclient.launcher.AppVersion.DEV) {
			return ROOT;
		}
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			return (appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"))).resolve("VeloClient");
		}
		return Path.of(System.getProperty("user.home"), ".velo-client");
	}

	public static Path config() {
		return ROOT.resolve("config");
	}

	/** The Minecraft account (dashless uuid) the per-account files belong to; null = none known yet. */
	private static volatile String account;

	public static void setAccount(String uuid) {
		account = uuid == null || uuid.isBlank() ? null : uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
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
