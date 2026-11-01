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
				refreshArtIfChanged(itemId);
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

	/** Library copies whose art was compared with the Store's (id + art version), so each is checked once a session. */
	private static final java.util.Set<String> CHECKED = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final java.util.concurrent.ExecutorService CHECKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Velo cloak art check");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	/**
	 * A cloak's art can be updated on the server after you bought it. The Store draws the new art, but your library copy
	 * (made at purchase) kept the old one - so the cloak looked right in the Store and old in-game. Compares the copy's
	 * GIF with the Store's off the render thread and swaps it in when they differ.
	 */
	private static void refreshArtIfChanged(String itemId) {
		StoreCatalog.byId(itemId).filter(StoreAssets::ready).ifPresent(item -> {
			String version = item.gifSha() != null ? item.gifSha() : String.valueOf(item.gifResource());
			CapeManager.library().values().stream()
					.filter(d -> Objects.equals(d.sourceItemId(), itemId) && d.animated() && d.bundleFile() != null)
					.forEach(definition -> {
						if (!CHECKED.add(definition.id() + "|" + version)) {
							return;
						}
						CHECKER.submit(() -> {
							try {
								byte[] current;
								try (InputStream in = StoreAssets.openGif(item)) {
									current = in.readAllBytes();
								}
								byte[] copy = CapeManager.readBundleEntryBytes(definition, "frames.gif");
								if (java.util.Arrays.equals(current, copy)) {
									return;
								}
								net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
									try {
										CapeManager.replaceAnimatedArt(definition, current);
										net.veloclient.velo.VeloClient.LOGGER.info("Updated cloak {} to its new Store art", item.name());
									} catch (Exception e) {
										CHECKED.remove(definition.id() + "|" + version);
										net.veloclient.velo.VeloClient.LOGGER.warn("Couldn't update cloak {} to its new art", itemId, e);
									}
								});
							} catch (Exception e) {
								CHECKED.remove(definition.id() + "|" + version);
							}
						});
					});
		});
	}
}
