package net.veloclient.velo.client.cosmetics;

import net.veloclient.velo.client.store.StoreAssets;
import net.veloclient.velo.client.store.StoreCatalog;
import net.veloclient.velo.client.store.StoreItem;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a Store cape catalog id (what {@link
 * net.veloclient.velo.client.network.VeloUserRegistry} publishes for a
 * remote player - see {@link CapeManager#equippedSourceItemId}) into a
 * renderable {@link CapeDefinition}, for {@link
 * net.veloclient.velo.client.cosmetics.render.CapeFeatureRenderer}'s
 * other-player render path.
 *
 * <p>No network/texture transfer involved: every client already ships the
 * same built-in Store cape art as a bundled resource ({@link
 * StoreItem#gifResource()}), so a bare catalog id is enough - this just
 * loads that bundled GIF once per id and caches the result, the same way
 * the Store's own "preview before you buy" flow does ({@link
 * CapeManager#previewDefinitionFor}). A custom cape a player imported
 * themselves has no catalog id and can never resolve here - see
 * server/README.md's "known limitations".
 */
public final class RemoteCapeCache {

	// Optional.empty() is cached too (not just successes) so an unknown/bogus
	// capeId a server sent doesn't retry a resource-manager lookup every
	// single frame it's asked about.
	private static final Map<String, Optional<CapeDefinition>> RESOLVED = new ConcurrentHashMap<>();

	private RemoteCapeCache() {
	}

	public static Optional<CapeDefinition> resolve(String capeCatalogId) {
		if (capeCatalogId == null || capeCatalogId.isBlank()) {
			return Optional.empty();
		}
		return RESOLVED.computeIfAbsent(capeCatalogId, RemoteCapeCache::load);
	}

	private static Optional<CapeDefinition> load(String capeCatalogId) {
		Optional<StoreItem> item = StoreCatalog.byId(capeCatalogId);
		if (item.isEmpty()) {
			return Optional.empty();
		}
		try (InputStream in = StoreAssets.openGif(item.get())) {
			CapeDefinition definition = CapeManager.previewDefinitionFor(
					capeCatalogId, item.get().name(), in, CapePhysicsPreset.defaults());
			// Registers the texture now rather than lazily on first render,
			// so a malformed bundle fails (and gets cached as empty) here
			// instead of throwing mid-frame.
			CapeManager.textureIdentifier(definition);
			return Optional.of(definition);
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}
}
