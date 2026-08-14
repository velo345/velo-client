package net.veloclient.velo.client.cosmetics;

/**
 * Metadata for one cape in the local library. {@code bundleFile} points at the
 * {@code .velocape} bundle (a zip containing either a static {@code texture.png}
 * or, for a store-bought animated cape, a {@code frames.gif} - never both -
 * plus {@code physics.json} and, optionally, {@code elytra.png}) it was
 * loaded from, so re-exporting is just a file copy. {@code hasElytraTexture}
 * is cached here (rather than re-scanning the zip every time it's asked)
 * since it's checked every frame a cape is equipped, to decide whether the
 * player's real elytra should get this cape's matching texture. {@code
 * animated} likewise decides whether {@link CapeManager#textureIdentifier}
 * resolves through {@link AnimatedCapeAsset} instead of the plain static
 * texture cache. Animated capes never have an elytra texture (they're
 * store-only cosmetics, not user PNG imports).
 *
 * <p>{@code sourceItemId}, if non-null, is the {@code StoreItem#id()} this
 * cape was bought as (see {@code StorePurchase}) - null for anything
 * user-imported. This is what {@code VeloServerClient} publishes as the
 * equipped cape when syncing with a Velo Client server: other clients
 * already have the same built-in Store art bundled, so broadcasting this
 * catalog id (rather than {@code id}, which is a random UUID assigned at
 * import time and means nothing on anyone else's client) is enough for them
 * to render it - no texture transfer needed.
 */
public record CapeDefinition(String id, String name, java.nio.file.Path bundleFile, CapePhysicsPreset physicsPreset,
		boolean hasElytraTexture, boolean animated, String sourceItemId) {
}
