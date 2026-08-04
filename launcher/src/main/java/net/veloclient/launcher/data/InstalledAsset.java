package net.veloclient.launcher.data;

/**
 * Metadata recorded for one file installed into an instance's mods/
 * resourcepacks/shaderpacks folder via Modrinth, so the UI can render a rich
 * card (icon/title/version/description) instead of a bare filename. Manually
 * dropped-in files that never got recorded here just fall back to a plain
 * filename row - this is enrichment, not the source of truth for what's
 * actually installed (the folder's file listing is).
 */
public record InstalledAsset(String projectId, String versionId, String filename, String title, String description,
		String iconUrl, String versionNumber, String projectType) {
}
