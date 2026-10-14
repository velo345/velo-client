package net.veloclient.velo.client.store;

import net.veloclient.velo.client.cosmetics.CapeDefinition;
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.cosmetics.CapePhysicsPreset;
import net.veloclient.velo.client.network.StoreClient;

import java.io.InputStream;
import java.util.Objects;

/**
 * Makes the local cape library match what the server says you own: anything bought in the
 * launcher, on another PC, or granted by the owner shows up here without re-buying.
 */
public final class StoreRestore {

	private StoreRestore() {
	}

	private static final java.util.concurrent.atomic.AtomicBoolean PENDING = new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * Runs {@link #restoreMissing} on the client thread soon (coalesced) - called when a downloaded cosmetic
	 * file arrives, since an owned server-only cape can't be imported before its art is there (an owned
	 * cape used to stay out of the library, so it couldn't be equipped, until the next wallet refresh).
	 */
	public static void scheduleRestore() {
		if (PENDING.compareAndSet(false, true)) {
			net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
				PENDING.set(false);
				restoreMissing();
			});
		}
	}

	public static void restoreMissing() {
		for (String itemId : StoreClient.owned()) {
			boolean present = CapeManager.library().values().stream()
					.map(CapeDefinition::sourceItemId).anyMatch(id -> Objects.equals(id, itemId));
			if (present) {
				continue;
			}
			StoreCatalog.byId(itemId).filter(StoreAssets::ready).ifPresent(item -> {
				try (InputStream in = StoreAssets.openGif(item)) {
					CapeManager.importAnimatedGif(item.name(), in, CapePhysicsPreset.defaults(), item.id());
				} catch (Exception e) {
					net.veloclient.velo.VeloClient.LOGGER.warn("Couldn't restore owned store item {}", itemId, e);
				}
			});
		}
	}
}
