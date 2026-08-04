package net.veloclient.launcher.instance;

/**
 * One "mod profile": a name, a pinned Minecraft version, and an icon. Each
 * instance owns an isolated game directory (its own mods/saves/config) under
 * {@link InstancePaths#dir(String)} - distinct from the mod's in-game
 * {@code ServerProfile} (server-address safe-mode presets, see
 * {@code net.veloclient.launcher.data.ProfileData}), which this is unrelated to.
 *
 * <p>{@code ramMinMb}/{@code ramMaxMb}/{@code extraJvmArgs} are nullable -
 * null means "use the launcher's own defaults" ({@code GameLauncher}'s
 * {@code DEFAULT_MIN/MAX_MEMORY_MB}) rather than duplicating those defaults
 * here. Older {@code instance.json} files without these fields deserialize
 * with them null automatically.
 */
public record Instance(String id, String name, String mcVersion, InstanceIcon icon, long createdAtEpochMillis,
		Integer ramMinMb, Integer ramMaxMb, String extraJvmArgs) {

	/** Convenience constructor for call sites that don't care about RAM/JVM overrides yet. */
	public Instance(String id, String name, String mcVersion, InstanceIcon icon, long createdAtEpochMillis) {
		this(id, name, mcVersion, icon, createdAtEpochMillis, null, null, null);
	}

	public Instance withSettings(Integer ramMinMb, Integer ramMaxMb, String extraJvmArgs) {
		return new Instance(id, name, mcVersion, icon, createdAtEpochMillis, ramMinMb, ramMaxMb, extraJvmArgs);
	}
}
