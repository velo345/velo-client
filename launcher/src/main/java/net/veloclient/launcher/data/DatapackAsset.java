package net.veloclient.launcher.data;

/** One Modrinth-sourced datapack installed into a specific world's {@code datapacks/} folder - see {@link DatapackRegistry}. */
public record DatapackAsset(String projectId, String versionId, String filename, String title, String description, String iconUrl, String versionNumber) {
}
