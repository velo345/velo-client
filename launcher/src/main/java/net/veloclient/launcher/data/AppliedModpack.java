package net.veloclient.launcher.data;

/** The modpack currently applied to a profile, if any - see {@link AppliedModpackStore}. */
public record AppliedModpack(String projectId, String versionId, String title, String versionNumber, String iconUrl) {
}
