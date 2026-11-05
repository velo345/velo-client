package net.veloclient.legacy.cape;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.texture.DynamicTexture;
import net.minecraft.resource.Identifier;
import net.veloclient.legacy.VeloLegacy;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cloak textures by key ({@code local:<id>}, a store id, {@code custom:<hash>}). The art is fetched and decoded on a
 * background thread; {@link #get} (render thread) returns null until then, creates the texture once and moves an
 * animated cloak to its current frame - one upload per frame change, not per draw.
 */
public final class CapeTextures {

	private static final long RETRY_MILLIS = 20_000;

	private static final class Slot {
		volatile CapeArt.Decoded art;
		volatile long failedAt;
		volatile boolean loading;
		DynamicTexture texture;
		Identifier id;
		int shownFrame = -1;
		long totalMillis;
	}

	private static final Map<String, Slot> SLOTS = new ConcurrentHashMap<>();
	private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "velo-cloaks");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	private CapeTextures() {
	}

	/** The texture for {@code key}, loading the bytes from {@code source} the first time (null = nothing to show). */
	public static Identifier get(String key, Callable<byte[]> source) {
		Slot slot = SLOTS.computeIfAbsent(key, k -> new Slot());
		CapeArt.Decoded art = slot.art;
		if (art == null) {
			if (!slot.loading && System.currentTimeMillis() - slot.failedAt > RETRY_MILLIS) {
				slot.loading = true;
				LOADER.execute(() -> {
					try {
						byte[] bytes = source.call();
						if (bytes != null) {
							slot.art = CapeArt.decode(bytes);
						} else {
							slot.failedAt = System.currentTimeMillis() - RETRY_MILLIS + 1500; // not there yet: ask again soon
						}
					} catch (Exception e) {
						slot.failedAt = System.currentTimeMillis();
						VeloLegacy.LOGGER.debug("Couldn't load cloak {}", key, e);
					} finally {
						slot.loading = false;
					}
				});
			}
			return null;
		}
		if (slot.texture == null) {
			slot.texture = new DynamicTexture(art.width(), art.height());
			slot.id = Minecraft.getInstance().getTextureManager().register("velo_cloak", slot.texture);
			for (int d : art.delays()) {
				slot.totalMillis += d;
			}
		}
		int frame = 0;
		if (art.frames().size() > 1) {
			long t = System.currentTimeMillis() % Math.max(1, slot.totalMillis);
			int[] delays = art.delays();
			while (frame < delays.length - 1 && t >= delays[frame]) {
				t -= delays[frame];
				frame++;
			}
		}
		if (frame != slot.shownFrame) {
			slot.shownFrame = frame;
			int[] src = art.frames().get(frame);
			int[] dst = slot.texture.getPixels();
			int rw = art.regionWidth();
			int w = art.width();
			for (int y = 0; y < art.regionHeight(); y++) {
				System.arraycopy(src, y * rw, dst, y * w, rw);
			}
			slot.texture.upload();
		}
		return slot.id;
	}

	/** Forget one key (the equipped cloak changed): its texture is freed and loaded again when asked. */
	public static void drop(String key) {
		Slot slot = SLOTS.remove(key);
		if (slot != null && slot.id != null) {
			Minecraft.getInstance().getTextureManager().close(slot.id);
		}
	}
}
