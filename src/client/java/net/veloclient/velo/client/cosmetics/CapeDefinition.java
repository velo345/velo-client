package net.veloclient.velo.client.cosmetics;

/**
 * Metadata for one cape in the local library. {@code bundleFile} points at the
 * {@code .velocape} bundle (a zip containing {@code texture.png} + {@code physics.json})
 * it was loaded from, so re-exporting is just a file copy.
 */
public record CapeDefinition(String id, String name, java.nio.file.Path bundleFile, CapePhysicsPreset physicsPreset) {
}
