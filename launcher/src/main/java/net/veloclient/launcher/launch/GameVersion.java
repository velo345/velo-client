package net.veloclient.launcher.launch;

/**
 * The Minecraft versions the launcher plays, oldest first (the last one is the default for new profiles), each with the
 * Fabric Loader build and the Fabric API build to install alongside it. 1.8.9 and 1.9.4 run on Ornithe (Fabric Loader
 * with Ornithe's calamus intermediary, its Standard Libraries instead of Fabric API, on Java 25 with Pylon's LWJGL 3) - the
 * base the fast 1.8.9 mods (Argentum, Sarcio...) are made for; the rest on Fabric. A version without a bundled velo-client jar
 * yet still launches - with the player's own mods, just without Velo's.
 */
public enum GameVersion {

	// Ornithe: the "API" is Ornithe Standard Libraries (OSL), loader 0.20.1 (Sarcio needs 0.19.4+).
	MC_1_8_9("1.8.9", "0.20.1", "0.23.1", true),
	MC_1_9_4("1.9.4", "0.20.1", "0.23.1", true),
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

	/** An old (pre-1.13) version: runs on Ornithe (meta.ornithemc.net, OSL) instead of Fabric. */
	public boolean legacy() {
		return legacy;
	}

	/** Same as {@link #legacy()}, read where it's about the mod loader. */
	public boolean ornithe() {
		return legacy;
	}

	/** Fabric's or Ornithe's meta API base. */
	public String metaBase() {
		return legacy ? "https://meta.ornithemc.net" : "https://meta.fabricmc.net";
	}

	/** The loader builds for this version (Ornithe's v3 API names Fabric Loader "fabric-loader"; its v2 lists its old loader). */
	public String loaderListUrl() {
		return legacy ? metaBase() + "/v3/versions/fabric-loader/" + id : metaBase() + "/v2/versions/loader/" + id;
	}

	/** The launcher profile (main class, loader + intermediary libraries, JVM arguments) for one loader build. */
	public String loaderProfileUrl(String loader) {
		return loaderListUrl() + "/" + loader + "/profile/json";
	}

	/** Mod loader name on Modrinth. */
	public String modrinthLoader() {
		return legacy ? "ornithe" : "fabric";
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
