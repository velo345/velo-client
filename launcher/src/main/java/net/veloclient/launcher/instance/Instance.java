package net.veloclient.launcher.instance;

/**
 * One "mod profile": a name, a pinned Minecraft version, and an icon. Each
 * instance owns an isolated game directory (its own mods/saves/config) under
 * {@link InstancePaths#dir(String)} - distinct from the mod's in-game
 * {@code ServerProfile} (server-address safe-mode presets, see
 * {@code net.veloclient.launcher.data.ProfileData}), which this is unrelated to.
 */
public record Instance(String id, String name, String mcVersion, InstanceIcon icon, long createdAtEpochMillis) {
}
