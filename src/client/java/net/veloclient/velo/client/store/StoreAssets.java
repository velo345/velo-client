package net.veloclient.velo.client.store;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.cosmetics.AnimatedCapeAsset;

import java.io.IOException;
import java.io.InputStream;

/** Shared helpers for reading a {@link StoreItem}'s bundled art - used both for the catalog grid's thumbnails and to hand the purchased GIF bytes to {@link net.veloclient.velo.client.cosmetics.CapeManager#importAnimatedGif}. */
public final class StoreAssets {

	private StoreAssets() {
	}

	/** The item's animated texture, previewable before it's ever been bought - registered under the catalog id, separate from whatever id it gets once actually imported into the cape library on purchase. */
	public static AnimatedCapeAsset preview(StoreItem item) {
		return AnimatedCapeAsset.getOrRegister(item.id(), () -> openGif(item));
	}

	public static InputStream openGif(StoreItem item) {
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
