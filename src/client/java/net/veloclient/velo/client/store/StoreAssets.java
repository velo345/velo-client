package net.veloclient.velo.client.store;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.cosmetics.AnimatedCapeAsset;

import java.io.IOException;
import java.io.InputStream;

/** Shared helpers for reading a {@link StoreItem}'s bundled art - used both for the catalog grid's thumbnails and to hand the purchased GIF bytes to {@link net.veloclient.velo.client.cosmetics.CapeManager#importAnimatedGif}. */
public final class StoreAssets {

	private StoreAssets() {
	}

	private static final String MOD_VERSION = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("velo-client")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	private static final java.util.Set<String> LOADING = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final java.util.concurrent.ExecutorService LOADER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Velo store cloak loader");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * The item's animated texture, previewable before it's ever been bought - registered under the catalog id, separate
	 * from whatever id it gets once actually imported into the cape library on purchase. Null until it's decoded: that
	 * happens off the render thread (a 2048 px, 40-frame GIF took the game down for a second per tile). Render thread.
	 */
	public static AnimatedCapeAsset preview(StoreItem item) {
		AnimatedCapeAsset existing = AnimatedCapeAsset.existing(item.id());
		if (existing != null || !ready(item)) {
			return existing;
		}
		if (LOADING.add(item.id())) {
			LOADER.submit(() -> {
				try {
					net.veloclient.velo.client.cosmetics.GifDecoder.Result decoded;
					try (InputStream in = openGif(item)) {
						decoded = net.veloclient.velo.client.cosmetics.GifDecoder.decode(in);
					}
					MinecraftClient.getInstance().execute(() -> {
						try {
							AnimatedCapeAsset.registerDecoded(item.id(), decoded, true);
						} catch (IOException ignored) {
							// Shown as its still thumbnail.
						}
						LOADING.remove(item.id());
					});
				} catch (Exception e) {
					LOADING.remove(item.id());
				}
			});
		}
		return null;
	}

	/** A still first-frame picture of the item for grids (see {@link net.veloclient.velo.client.cosmetics.CapeThumbnails}); null while it's made. */
	public static net.veloclient.velo.client.cosmetics.CapeThumbnails.Thumb thumbnail(StoreItem item) {
		// A small "card" version of the art (768 px instead of the full 2048 px GIF, ~1 MB instead of ~8 MB) when the
		// server has one: a grid needs only that, the full GIF is fetched for the one hovered, opened or worn.
		String card = item.gifResource() == null ? cardSha(item.id()) : null;
		if (card != null) {
			java.nio.file.Path file = net.veloclient.velo.client.cosmetics.CosmeticsCatalog.file(card);
			if (file == null) {
				return null; // downloading
			}
			return net.veloclient.velo.client.cosmetics.CapeThumbnails.get(item.id() + "-card-" + card.substring(0, Math.min(12, card.length())), () -> {
				try {
					return java.nio.file.Files.newInputStream(file);
				} catch (IOException e) {
					throw new RuntimeException("Failed to open the card art of " + item.id(), e);
				}
			});
		}
		if (!ready(item)) {
			return null;
		}
		// Keyed by the art's version: the server file's hash, or the bundled file's path (changes with new art).
		String version = item.gifSha() != null ? item.gifSha() : String.valueOf(item.gifResource()) + "-" + MOD_VERSION;
		return net.veloclient.velo.client.cosmetics.CapeThumbnails.get(item.id() + "-" + Integer.toHexString(version.hashCode()), () -> openGif(item));
	}

	/** The sha256 of the item's small card art on the server, or null when it has none. */
	private static String cardSha(String id) {
		for (var c : net.veloclient.velo.client.cosmetics.CosmeticsCatalog.items()) {
			if (c.id().equals(id)) {
				return c.files() == null ? null : c.files().get("card");
			}
		}
		return null;
	}

	/** Whether the art is there: bundled, or a server-only cape's GIF downloaded (starts the download if not). */
	public static boolean ready(StoreItem item) {
		return item.gifResource() != null || net.veloclient.velo.client.cosmetics.CosmeticsCatalog.file(item.gifSha()) != null;
	}

	/** The item's GIF: bundled, or the downloaded file of a server-only cape. Check {@link #ready} first. */
	public static InputStream openGif(StoreItem item) {
		if (item.gifResource() == null) {
			java.nio.file.Path file = net.veloclient.velo.client.cosmetics.CosmeticsCatalog.file(item.gifSha());
			try {
				if (file == null) {
					throw new IOException("Still downloading " + item.id());
				}
				return java.nio.file.Files.newInputStream(file);
			} catch (IOException e) {
				throw new RuntimeException("Failed to open store item " + item.id(), e);
			}
		}
		try {
			var resource = MinecraftClient.getInstance().getResourceManager().getResource(item.gifResource())
					.orElseThrow(() -> new IOException("Missing bundled resource " + item.gifResource()));
			//? if <26.1 {
			return resource.getInputStream();
			//?} else {
			/*return resource.open();
			*///?}
		} catch (IOException e) {
			throw new RuntimeException("Failed to open store item resource " + item.gifResource(), e);
		}
	}
}
