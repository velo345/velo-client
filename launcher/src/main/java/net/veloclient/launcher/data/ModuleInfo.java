package net.veloclient.launcher.data;

/** Mirrors one entry from the mod's exported {@code manifest.json} (design spec section 8). */
public record ModuleInfo(String id, String displayName, String description, String category, String safetyTag, boolean defaultEnabled) {
}
