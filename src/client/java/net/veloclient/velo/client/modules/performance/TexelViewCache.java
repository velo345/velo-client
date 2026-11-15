package net.veloclient.velo.client.modules.performance;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Velo Turbo texel view cache (Vulkan 26.2): vanilla binds a uniform texel buffer by creating a fresh VkBufferView in
 * every descriptor push and queueing it for destruction right away - a driver create + destroy per draw that uses one
 * (profiled 2026-11-11: ~3% of the render thread's native time). Vanilla only allows views over a whole buffer, so a
 * view is fully described by (buffer, format, offset, range): it's made once, reused for every later push and
 * destroyed together with its buffer, from the same destruction queue that frees the buffer after the GPU is done
 * with it. {@code -Dvelo.texelViewCache=false}
 * restores vanilla. Render thread only (descriptor pushes and the destruction queue both run there).
 */
public final class TexelViewCache {

	public static final boolean ON = !"false".equals(System.getProperty("velo.texelViewCache"));
	/** buffer handle -> {format, offset, range, view, format, offset, range, view, ...} */
	private static final Long2ObjectOpenHashMap<long[]> VIEWS = new Long2ObjectOpenHashMap<>();
	/** The view handed out last came from (or went into) the cache: the caller must not queue it for destruction. */
	public static boolean lastCached;

	private TexelViewCache() {
	}

	/** The cached view for this exact description, or 0. */
	public static long find(long buffer, int format, long offset, long range) {
		long[] entries = VIEWS.get(buffer);
		if (entries != null) {
			for (int i = 0; i + 3 < entries.length; i += 4) {
				if (entries[i] == format && entries[i + 1] == offset && entries[i + 2] == range) {
					return entries[i + 3];
				}
			}
		}
		return 0L;
	}

	public static void put(long buffer, int format, long offset, long range, long view) {
		long[] old = VIEWS.get(buffer);
		long[] entries = old == null ? new long[4] : java.util.Arrays.copyOf(old, old.length + 4);
		int i = entries.length - 4;
		entries[i] = format;
		entries[i + 1] = offset;
		entries[i + 2] = range;
		entries[i + 3] = view;
		VIEWS.put(buffer, entries);
	}

	/**
	 * The buffer is being destroyed (GPU done with it): its views go to {@code destroyView} first. (The Vulkan call itself
	 * lives in the 26.2 mixin - this class is compiled for every version, older ones have no Vulkan bindings.)
	 */
	public static void drop(long buffer, java.util.function.LongConsumer destroyView) {
		long[] entries = VIEWS.remove(buffer);
		if (entries != null) {
			for (int i = 3; i < entries.length; i += 4) {
				destroyView.accept(entries[i]);
			}
		}
	}
}
