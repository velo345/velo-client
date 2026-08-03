package net.veloclient.velo.client.cosmetics;

/**
 * Metadata for one cape in the local library. {@code bundleFile} points at the
 * {@code .velocape} bundle (a zip containing {@code texture.png} + {@code physics.json}
 * and, optionally, {@code elytra.png}) it was loaded from, so re-exporting is
 * just a file copy. {@code hasElytraTexture} is cached here (rather than
 * re-scanning the zip every time it's asked) since it's checked every frame
 * a cape is equipped, to decide whether the player's real elytra should get
 * this cape's matching texture.
 */
public record CapeDefinition(String id, String name, java.nio.file.Path bundleFile, CapePhysicsPreset physicsPreset,
		boolean hasElytraTexture) {
}
