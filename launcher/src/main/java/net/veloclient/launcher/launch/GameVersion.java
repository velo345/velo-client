package net.veloclient.launcher.launch;

/**
 * The Minecraft versions the launcher plays, oldest first (the last one is the default for new profiles), each with the
 * Fabric Loader build and the Fabric API build to install alongside it. 1.8.9 and 1.9.4 run on Legacy Fabric (its own
 * meta, intermediary, LWJGL 2 and Legacy Fabric API); the rest on Fabric. A version without a bundled velo-client jar
 * yet still launches - with the player's own mods, just without Velo's.
 */
public enum GameVersion {

	MC_1_8_9("1.8.9", "0.19.5", "1.13.5+1.8.9", true),
	MC_1_9_4("1.9.4", "0.19.5", "1.13.5+1.9.4", true),
	MC_1_21_1("1.21.1", "0.19.5", "0.116.17+1.21.1", false),
	MC_1_21_10("1.21.10", "0.19.5", "0.138.4+1.21.10", false),
	MC_1_21_11("1.21.11", "0.19.5", "0.141.6+1.21.11", false),
	MC_26_1("26.1", "0.19.5", "0.145.1+26.1", false),
	MC_26_2("26.2", "0.19.5", "0.161.0+26.2", false),
	MC_26_3("26.3", "0.19.5", "0.165.2+26.3", false);

	private final String id;
	private final String fabricLoaderVersion;
	private final String fabricApiVersion;
	private final boolean legacy;

	GameVersion(String id, String fabricLoaderVersion, String fabricApiVersion, boolean legacy) {
		this.id = id;
		this.fabricLoaderVersion = fabricLoaderVersion;
		this.fabricApiVersion = fabricApiVersion;
		this.legacy = legacy;
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

	/** Runs on Legacy Fabric (meta.legacyfabric.net, Legacy Fabric API) instead of Fabric. */
	public boolean legacy() {
		return legacy;
	}

	/** Fabric's or Legacy Fabric's meta API base. */
	public String metaBase() {
		return legacy ? "https://meta.legacyfabric.net" : "https://meta.fabricmc.net";
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
