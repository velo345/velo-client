package net.veloclient.velo.client.gui.drawer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Icons at the exact size they're shown. GUI textures have no mipmaps, so a 112-128 px icon drawn at 30 screen
 * pixels skips texels and looks rough; this makes a box-filtered copy at that pixel size once (premultiplied
 * alpha, so edges stay clean) and the drawer draws it 1:1.
 */
public final class CrispIcons {

	private static final Map<String, Identifier> CACHE = new HashMap<>();
	private static final Set<String> FAILED = new HashSet<>();

	private CrispIcons() {
	}

	/** {@code src} scaled to {@code px} pixels wide (height keeps the aspect ratio), or null if it can't be read. */
	public static Identifier get(Identifier src, int px) {
		px = Math.max(4, Math.min(256, px));
		String key = src + "@" + px;
		Identifier id = CACHE.get(key);
		if (id != null || FAILED.contains(key)) {
			return id;
		}
		try {
			NativeImage image = read(src);
			if (image == null) {
				FAILED.add(key);
				return null;
			}
			NativeImage small = downsample(image, px);
			image.close();
			if (CACHE.size() > 600) {
				for (Identifier old : CACHE.values()) {
					MinecraftClient.getInstance().getTextureManager().destroyTexture(old);
				}
				CACHE.clear();
			}
			id = net.veloclient.velo.client.gui.media.MediaTextures.register(small, "velo icon").id();
			CACHE.put(key, id);
			return id;
		} catch (Exception | LinkageError e) {
			FAILED.add(key);
			return null;
		}
	}

	private static NativeImage read(Identifier src) throws java.io.IOException {
		var resource = MinecraftClient.getInstance().getResourceManager().getResource(src);
		if (resource.isEmpty()) {
			return null;
		}
		//? if <26.1 {
		try (InputStream in = resource.get().getInputStream()) {
		//?} else {
		/*try (InputStream in = resource.get().open()) {
		*///?}
			return NativeImage.read(in);
		}
	}

	/** Area-averaged downscale (each target pixel = the exact source area under it). */
	private static NativeImage downsample(NativeImage src, int px) {
		int sw = src.getWidth();
		int sh = src.getHeight();
		int dw = Math.min(px, sw);
		int dh = Math.max(1, Math.round(dw * sh / (float) sw));
		float fx = sw / (float) dw;
		float fy = sh / (float) dh;
		// premultiplied source
		float[] a = new float[sw * sh];
		float[] r = new float[sw * sh];
		float[] g = new float[sw * sh];
		float[] b = new float[sw * sh];
		for (int y = 0; y < sh; y++) {
			for (int x = 0; x < sw; x++) {
				int c = src.getColorArgb(x, y);
				float al = (c >>> 24) / 255f;
				int i = y * sw + x;
				a[i] = al;
				r[i] = ((c >> 16) & 0xFF) * al;
				g[i] = ((c >> 8) & 0xFF) * al;
				b[i] = (c & 0xFF) * al;
			}
		}
		NativeImage out = new NativeImage(dw, dh, false);
		for (int y = 0; y < dh; y++) {
			float y0 = y * fy;
			float y1 = y0 + fy;
			for (int x = 0; x < dw; x++) {
				float x0 = x * fx;
				float x1 = x0 + fx;
				float sa = 0, sr = 0, sg = 0, sb = 0, area = 0;
				for (int yy = (int) y0; yy < Math.min(sh, (int) Math.ceil(y1)); yy++) {
					float wy = Math.min(y1, yy + 1) - Math.max(y0, yy);
					for (int xx = (int) x0; xx < Math.min(sw, (int) Math.ceil(x1)); xx++) {
						float w = wy * (Math.min(x1, xx + 1) - Math.max(x0, xx));
						int i = yy * sw + xx;
						sa += a[i] * w;
						sr += r[i] * w;
						sg += g[i] * w;
						sb += b[i] * w;
						area += w;
					}
				}
				int color = 0;
				if (sa > 0.0001f) {
					int al = Math.round(sa / area * 255f);
					color = (Math.min(255, al) << 24) | (Math.min(255, Math.round(sr / sa)) << 16) | (Math.min(255, Math.round(sg / sa)) << 8)
							| Math.min(255, Math.round(sb / sa));
				}
				out.setColorArgb(x, y, color);
			}
		}
		return out;
	}
}
