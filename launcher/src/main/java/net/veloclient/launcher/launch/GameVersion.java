package net.veloclient.launcher.launch;

/**
 * The 3 Minecraft versions this project supports (see
 * {@code stonecutter.properties.toml}), each with the Fabric Loader build
 * and matching Fabric API build to install alongside it - velo-client depends
 * on Fabric API at runtime (it's {@code modImplementation}, not jar-in-jar
 * included, per the root {@code build.gradle.kts}), so every profile needs
 * it installed too, not just velo-client's own jar.
 */
public enum GameVersion {

	MC_1_21_11("1.21.11", "0.19.3", "0.141.4+1.21.11"),
	MC_26_1("26.1", "0.19.3", "0.145.1+26.1"),
	MC_26_2("26.2", "0.19.3", "0.156.0+26.2");

	private final String id;
	private final String fabricLoaderVersion;
	private final String fabricApiVersion;

	GameVersion(String id, String fabricLoaderVersion, String fabricApiVersion) {
		this.id = id;
		this.fabricLoaderVersion = fabricLoaderVersion;
		this.fabricApiVersion = fabricApiVersion;
	}

	public String id() {
		return id;
	}

	public String fabricLoaderVersion() {
		return fabricLoaderVersion;
	}

	public String fabricApiVersion() {
		return fabricApiVersion;
	}

	public static GameVersion byId(String id) {
		for (GameVersion version : values()) {
			if (version.id.equals(id)) {
				return version;
			}
		}
		throw new IllegalArgumentException("Unsupported Minecraft version: " + id);
	}
}
