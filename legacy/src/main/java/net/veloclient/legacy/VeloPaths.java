package net.veloclient.legacy;

import java.nio.file.Files;
import java.nio.file.Path;

/** The shared Velo folder (same as the modern client and the launcher): {@code ~/.velo-client} or {@code %APPDATA%/VeloClient}. */
public final class VeloPaths {

	private static final Path ROOT = resolveRoot();

	private VeloPaths() {
	}

	private static Path resolveRoot() {
		String home = System.getProperty("velo.home");
		if (home != null && !home.isBlank()) {
			return Path.of(home);
		}
		if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
			String appData = System.getenv("APPDATA");
			return (appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"))).resolve("VeloClient");
		}
		return Path.of(System.getProperty("user.home"), ".velo-client");
	}

	public static Path root() {
		return ROOT;
	}

	public static Path config() {
		return ROOT.resolve("config");
	}

	public static Path capes() {
		return ROOT.resolve("capes");
	}

	public static Path cache(String name) {
		return ROOT.resolve("cache").resolve(name);
	}

	/** A per-account config file (what this account wears), falling back to the old global one - read only here. */
	public static Path accountConfig(String uuid, String fileName) {
		if (uuid != null) {
			Path file = config().resolve("accounts").resolve(uuid).resolve(fileName);
			if (Files.exists(file)) {
				return file;
			}
		}
		return config().resolve(fileName);
	}
}
