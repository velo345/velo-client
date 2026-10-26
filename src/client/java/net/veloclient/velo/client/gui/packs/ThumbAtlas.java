package net.veloclient.velo.client.gui.packs;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Texture thumbnails for the pack browser: decoded on a worker thread, fitted into 32 px cells and packed into a
 * few 512 px GUI textures (256 per page) instead of one texture per file. Each thumbnail also remembers whether
 * the source was animated and its real size. {@link #release()} frees everything.
 */
public final class ThumbAtlas {

	private static final int CELL = 32;
	private static final int PAGE = 512;
	private static final int PER_ROW = PAGE / CELL;
	private static final int PER_PAGE = PER_ROW * PER_ROW;

	/** Where a loaded thumbnail sits: page, cell, and the fitted image size inside the cell. */
	public record Thumb(int page, int cell, int width, int height, int sourceWidth, int sourceHeight) {
	}

	private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "velo-pack-thumbs");
		thread.setDaemon(true);
		return thread;
	});
	private final List<GpuImage> pages = new ArrayList<>();
	private final boolean[] dirtyPages = new boolean[64];
	private final Map<String, Thumb> thumbs = new HashMap<>();
	private final Map<String, Boolean> pending = new HashMap<>();
	private int next;
	private boolean released;

	/**
	 * The thumbnail of {@code key}, or null while it's loading (it's queued on the first ask). {@code reader} returns
	 * the PNG bytes (called on a worker thread).
	 */
	public Thumb get(String key, Function<String, byte[]> reader) {
		Thumb thumb = thumbs.get(key);
		if (thumb != null || pending.containsKey(key) || released) {
			return thumb;
		}
		pending.put(key, true);
		worker.execute(() -> {
			int[] cell = new int[CELL * CELL];
			int fw = 0;
			int fh = 0;
			int sw = 0;
			int sh = 0;
			try {
				byte[] png = reader.apply(key);
				if (png != null) {
					TexDoc doc = TexDoc.read(png, reader.apply(key + ".mcmeta"));
					int[] src = doc.frames.get(0);
					sw = doc.width;
					sh = doc.height;
					float scale = Math.min(CELL / (float) sw, CELL / (float) sh);
					if (scale >= 1f) {
						scale = (float) Math.floor(scale);
					}
					fw = Math.max(1, Math.round(sw * scale));
					fh = Math.max(1, Math.round(sh * scale));
					for (int y = 0; y < fh; y++) {
						for (int x = 0; x < fw; x++) {
							cell[y * CELL + x] = src[Math.min(sh - 1, (int) (y / scale)) * sw + Math.min(sw - 1, (int) (x / scale))];
						}
					}
				}
			} catch (Exception ignored) {
				// Unreadable: an empty tile.
			}
			int w = fw;
			int h = fh;
			int sourceW = sw;
			int sourceH = sh;
			MinecraftClient.getInstance().execute(() -> place(key, cell, w, h, sourceW, sourceH));
		});
		return null;
	}

	private void place(String key, int[] cell, int w, int h, int sourceW, int sourceH) {
		if (released) {
			return;
		}
		int index = next++;
		int page = index / PER_PAGE;
		if (page >= dirtyPages.length) {
			return;
		}
		while (pages.size() <= page) {
			pages.add(new GpuImage("pack thumbnails", PAGE, PAGE));
		}
		int c = index % PER_PAGE;
		pages.get(page).put(cell, (c % PER_ROW) * CELL, (c / PER_ROW) * CELL, CELL, CELL);
		dirtyPages[page] = true;
		thumbs.put(key, new Thumb(page, c, w, h, sourceW, sourceH));
	}

	/** Uploads pages that got new thumbnails (once per frame). */
	public void flush() {
		for (int i = 0; i < pages.size(); i++) {
			if (dirtyPages[i]) {
				dirtyPages[i] = false;
				pages.get(i).flush();
			}
		}
	}

	/** Draws a thumbnail centred in the square (x, y, size), scaled up crisp. */
	public void draw(DrawContext context, Thumb thumb, int x, int y, int size) {
		if (thumb == null || thumb.width() == 0 || thumb.page() >= pages.size()) {
			return;
		}
		float scale = Math.min(size / (float) thumb.width(), size / (float) thumb.height());
		int w = Math.max(1, Math.round(thumb.width() * scale));
		int h = Math.max(1, Math.round(thumb.height() * scale));
		int u = (thumb.cell() % PER_ROW) * CELL;
		int v = (thumb.cell() / PER_ROW) * CELL;
		pages.get(thumb.page()).draw(context, x + (size - w) / 2, y + (size - h) / 2, w, h, u, v, thumb.width(), thumb.height());
	}

	/** Forgets one thumbnail (after the file changed) - it's loaded again on the next ask. */
	public void invalidate(String key) {
		thumbs.remove(key);
		pending.remove(key);
	}

	public void release() {
		released = true;
		worker.shutdownNow();
		for (GpuImage page : pages) {
			page.release();
		}
		pages.clear();
		thumbs.clear();
	}
}
