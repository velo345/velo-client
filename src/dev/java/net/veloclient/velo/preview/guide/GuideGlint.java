package net.veloclient.velo.preview.guide;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Enchanted items for the guide's Glint preview, the way the game shows them: the item texture with the glint's diagonal
 * shimmer sliding over it - only on the item's own pixels, added on top (like the glint layer), in the picked colour.
 * Made per frame into a small texture of our own, since the guide can't make real item stacks on the title screen.
 */
final class GuideGlint {

	private static final Map<String, Entry> ENTRIES = new HashMap<>();

	private static final class Entry {
		int[] base;
		int width;
		int height;
		NativeImage image;
		NativeImageBackedTexture texture;
		Identifier id;
		long paintedAt;
	}

	private GuideGlint() {
	}

	/** The texture to draw for {@code item} ("item/enchanted_book") with the glint in {@code color} at time {@code t}; null if not ready. */
	static Identifier texture(String item, int color, float t) {
		Entry e = ENTRIES.get(item);
		try {
			if (e == null) {
				e = load(item);
				ENTRIES.put(item, e);
			}
			if (e == null) {
				return null;
			}
			long now = System.nanoTime();
			if (now - e.paintedAt > 30_000_000L) { // ~30 fps is plenty for the shimmer
				e.paintedAt = now;
				paint(e, color, t);
			}
			return e.id;
		} catch (Exception | LinkageError ex) {
			return null;
		}
	}

	private static void paint(Entry e, int color, float t) {
		float cr = ((color >> 16) & 0xFF) / 255f;
		float cg = ((color >> 8) & 0xFF) / 255f;
		float cb = (color & 0xFF) / 255f;
		for (int y = 0; y < e.height; y++) {
			for (int x = 0; x < e.width; x++) {
				int argb = e.base[y * e.width + x];
				int a = argb >>> 24;
				if (a == 0) {
					e.image.setColorArgb(x, y, 0);
					continue;
				}
				// two glint bands crossing at an angle and drifting, like the scrolling glint texture
				float u = (x + y * 0.55f) / e.width;
				float v = (x * 0.35f - y) / e.height;
				float s1 = (float) Math.pow(0.5 + 0.5 * Math.sin(2 * Math.PI * (u * 1.4f - t * 0.32f)), 3);
				float s2 = (float) Math.pow(0.5 + 0.5 * Math.sin(2 * Math.PI * (v * 1.1f + t * 0.21f)), 5) * 0.55f;
				float g = Math.min(1f, (s1 + s2) * 0.8f + 0.12f);
				int r = Math.min(255, ((argb >> 16) & 0xFF) + Math.round(cr * g * 200));
				int gg = Math.min(255, ((argb >> 8) & 0xFF) + Math.round(cg * g * 200));
				int b = Math.min(255, (argb & 0xFF) + Math.round(cb * g * 200));
				e.image.setColorArgb(x, y, a << 24 | r << 16 | gg << 8 | b);
			}
		}
		e.texture.upload();
	}

	private static Entry load(String item) throws Exception {
		MinecraftClient client = MinecraftClient.getInstance();
		var resource = client.getResourceManager().getResource(Identifier.of("minecraft", "textures/" + item + ".png"));
		if (resource.isEmpty()) {
			return null;
		}
		NativeImage original;
		//? if <26.1 {
		try (InputStream in = resource.get().getInputStream()) {
		//?} else {
		/*try (InputStream in = resource.get().open()) {
		*///?}
			original = NativeImage.read(in);
		}
		Entry e = new Entry();
		e.width = original.getWidth();
		e.height = Math.min(original.getHeight(), original.getWidth()); // first frame of an animated strip
		e.base = new int[e.width * e.height];
		for (int y = 0; y < e.height; y++) {
			for (int x = 0; x < e.width; x++) {
				e.base[y * e.width + x] = original.getColorArgb(x, y);
			}
		}
		e.image = new NativeImage(e.width, e.height, true);
		original.close();
		e.texture = new NativeImageBackedTexture(() -> "velo guide glint", e.image);
		e.id = Identifier.of("velo-client", "guide/glint/" + item.replace('/', '_'));
		client.getTextureManager().registerTexture(e.id, e.texture);
		return e;
	}
}
