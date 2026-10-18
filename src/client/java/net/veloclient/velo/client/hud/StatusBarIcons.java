package net.veloclient.velo.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.config.VeloPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * The player's own drawings for the Hearts, Hunger and Armor Bar modules: one small pixel image per
 * state (full / half / empty), drawn in the pixel editor or imported from a PNG, saved as
 * {@code ~/.velo-client/icons/<kind>/<state>.png}. A missing half or empty drawing is derived from
 * the full one (its left half / a dark silhouette), so drawing just "full" is enough. Also traces
 * outlines from an icon's shape - used for the saturation ring around the food icons (from vanilla's
 * own sprite by default, so it follows resource packs).
 */
public final class StatusBarIcons {

	public enum State {
		FULL("full", "Full"), HALF("half", "Half"), EMPTY("empty", "Empty");

		public final String file;
		public final String label;

		State(String file, String label) {
			this.file = file;
			this.label = label;
		}
	}

	/** Size imported PNGs are fitted to when there's no drawing yet (2x the 9px slot). */
	public static final int SIZE = 18;

	private static final Map<String, Identifier> CACHE = new HashMap<>();
	private static final Map<String, Integer> SIZES = new HashMap<>();
	private static final Map<String, Boolean> DRAWINGS = new HashMap<>();

	private StatusBarIcons() {
	}

	private static Path dir(String kind) {
		return VeloPaths.root().resolve("icons").resolve(kind);
	}

	private static Path file(String kind, State state) {
		return dir(kind).resolve(state.file + ".png");
	}

	public static boolean hasDrawing(String kind) {
		return DRAWINGS.computeIfAbsent(kind, key -> {
			migrateOldImport(key);
			return Files.isRegularFile(file(key, State.FULL));
		});
	}

	/** Older versions stored one imported PNG as icons/&lt;kind&gt;.png - it becomes the "full" drawing. */
	private static void migrateOldImport(String kind) {
		Path old = VeloPaths.root().resolve("icons").resolve(kind + ".png");
		if (!Files.isRegularFile(old) || Files.exists(file(kind, State.FULL))) {
			return;
		}
		try (InputStream in = Files.newInputStream(old)) {
			NativeImage fitted = fit(NativeImage.read(in), SIZE);
			Files.createDirectories(dir(kind));
			fitted.writeTo(file(kind, State.FULL));
			fitted.close();
			Files.deleteIfExists(old);
		} catch (Exception e) {
			VeloClient.LOGGER.warn("Velo: couldn't migrate old {} icon", kind, e);
		}
	}

	// ---- Drawing ----

	/**
	 * Draws one state of the custom icon into the 9x9 slot at (x, y), tinted by {@code color}
	 * (0xFFFFFFFF = as drawn). Returns false if there's no drawing.
	 */
	public static boolean draw(DrawContext context, String kind, State state, int x, int y, int color) {
		Identifier full = texture(kind, State.FULL);
		if (full == null) {
			return false;
		}
		Identifier own = state == State.FULL ? full : texture(kind, state);
		if (own != null) {
			drawSquare(context, own, SIZES.getOrDefault(kind + "/" + state.file, 9), x, y, false, color);
			return true;
		}
		int size = SIZES.getOrDefault(kind + "/full", 9);
		if (state == State.HALF) {
			drawSquare(context, full, size, x, y, true, color);
		} else {
			drawSquare(context, full, size, x, y, false, 0x59000000);
		}
		return true;
	}

	/** Draws {@code texture} (a square of {@code size} px) into the 9x9 slot; {@code half} = only its left 5/9. */
	public static void drawSquare(DrawContext context, Identifier texture, int size, int x, int y, boolean half, int color) {
		int w = half ? 5 : 9;
		int region = half ? Math.max(1, Math.round(size * 5 / 9f)) : size;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, w, 9, region, size, size, size, color);
	}

	public static Identifier texture(String kind, State state) {
		if (state == State.FULL && !hasDrawing(kind)) {
			return null;
		}
		return cached(kind + "/" + state.file, () -> {
			Path path = file(kind, state);
			if (!Files.isRegularFile(path)) {
				return null;
			}
			try (InputStream in = Files.newInputStream(path)) {
				return NativeImage.read(in);
			}
		});
	}

	/** Outline traced from the "full" drawing (null if none). */
	public static Identifier drawingOutline(String kind) {
		if (!hasDrawing(kind)) {
			return null;
		}
		return cached(kind + "/outline", () -> {
			try (InputStream in = Files.newInputStream(file(kind, State.FULL))) {
				NativeImage image = NativeImage.read(in);
				NativeImage outline = outline(image);
				image.close();
				return outline;
			}
		});
	}

	public static int drawingOutlineSize(String kind) {
		return SIZES.getOrDefault(kind + "/outline", 9);
	}

	/** Outline traced from a vanilla GUI sprite (e.g. {@code hud/food_full}), resource packs included. */
	public static Identifier vanillaOutline(String sprite) {
		return cached("vanilla/" + sprite.replace('/', '_') + "_outline", () -> {
			Identifier path = Identifier.of("minecraft", "textures/gui/sprites/" + sprite + ".png");
			var resource = MinecraftClient.getInstance().getResourceManager().getResource(path);
			if (resource.isEmpty()) {
				return null;
			}
			//? if <26.1 {
			try (InputStream in = resource.get().getInputStream()) {
			//?} else {
			/*try (InputStream in = resource.get().open()) {
			*///?}
				NativeImage image = NativeImage.read(in);
				NativeImage outline = outline(image);
				image.close();
				return outline;
			}
		});
	}

	/** Size of a vanilla sprite outline (the sprite's own resolution). */
	public static int vanillaSize(String sprite) {
		return SIZES.getOrDefault("vanilla/" + sprite.replace('/', '_') + "_outline", 9);
	}

	// ---- Vanilla sprites in your colour ----

	/** sprite -> cache key of the colour currently registered for it (only the latest per sprite is kept, so a colour picker doesn't leak textures). */
	private static final Map<String, String> RECOLORED = new HashMap<>();

	/**
	 * A vanilla GUI sprite (e.g. {@code hud/heart/full}, resource packs included) with its hue moved to
	 * {@code color}: shading, highlights and outline stay, so it still looks like the vanilla icon - just in
	 * another colour. A grey/white/black target gives a grey version. Null if the sprite doesn't exist.
	 */
	public static Identifier recoloredSprite(String sprite, int color) {
		String key = "vrc/" + sprite.replace('/', '_') + "/" + String.format("%08x", color);
		String previous = RECOLORED.put(sprite, key);
		if (previous != null && !previous.equals(key)) {
			destroy(previous);
		}
		return cached(key, () -> {
			Identifier path = Identifier.of("minecraft", "textures/gui/sprites/" + sprite + ".png");
			var resource = MinecraftClient.getInstance().getResourceManager().getResource(path);
			if (resource.isEmpty()) {
				return null;
			}
			//? if <26.1 {
			try (InputStream in = resource.get().getInputStream()) {
			//?} else {
			/*try (InputStream in = resource.get().open()) {
			*///?}
				NativeImage image = NativeImage.read(in);
				recolor(image, color);
				return image;
			}
		});
	}

	/** Pixel size of a recoloured sprite (the sprite's own resolution). */
	public static int recoloredSize(String sprite, int color) {
		return SIZES.getOrDefault("vrc/" + sprite.replace('/', '_') + "/" + String.format("%08x", color), 9);
	}

	/** In place: shifts every pixel's hue by (target - the sprite's own average colour), scaling saturation/lightness the same way. */
	private static void recolor(NativeImage image, int color) {
		int w = image.getWidth();
		int h = image.getHeight();
		// The sprite's own average colour (weighted by saturation, so the dark outline and white shine don't count).
		double sumX = 0, sumY = 0, sumS = 0, sumL = 0, weight = 0;
		float[] hsl = new float[3];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = image.getColorArgb(x, y);
				if ((argb >>> 24) < 128) {
					continue;
				}
				rgbToHsl(argb, hsl);
				double wt = hsl[1] * (1 - Math.abs(2 * hsl[2] - 1));
				sumX += Math.cos(hsl[0] * 2 * Math.PI) * wt;
				sumY += Math.sin(hsl[0] * 2 * Math.PI) * wt;
				sumS += hsl[1] * wt;
				sumL += hsl[2] * wt;
				weight += wt;
			}
		}
		if (weight <= 0.0001) {
			return;
		}
		double refHue = (Math.atan2(sumY, sumX) / (2 * Math.PI) + 1) % 1;
		double refSat = Math.max(0.05, sumS / weight);
		double refLight = Math.max(0.05, sumL / weight);
		float[] target = new float[3];
		rgbToHsl(color | 0xFF000000, target);
		double satScale = target[1] / refSat;
		double lightScale = target[2] / refLight;
		boolean grey = target[1] < 0.08f;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = image.getColorArgb(x, y);
				int alpha = argb >>> 24;
				if (alpha == 0) {
					continue;
				}
				rgbToHsl(argb, hsl);
				// Only the colourful part follows the target; near-grey pixels (outline, shine) keep their lightness.
				double colorful = Math.min(1, hsl[1] * 2);
				double hue = (hsl[0] + (target[0] - refHue) + 1) % 1;
				double sat = grey ? 0 : Math.min(1, hsl[1] * satScale);
				double light = hsl[2] * (1 + (lightScale - 1) * colorful);
				int rgb = hslToRgb((float) hue, (float) sat, (float) Math.max(0, Math.min(1, light)));
				image.setColorArgb(x, y, (alpha << 24) | (rgb & 0xFFFFFF));
			}
		}
	}

	private static void rgbToHsl(int argb, float[] out) {
		float r = ((argb >> 16) & 0xFF) / 255f;
		float g = ((argb >> 8) & 0xFF) / 255f;
		float b = (argb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float l = (max + min) / 2;
		float s = 0;
		float hue = 0;
		if (max != min) {
			float d = max - min;
			s = l > 0.5f ? d / (2 - max - min) : d / (max + min);
			if (max == r) {
				hue = (g - b) / d + (g < b ? 6 : 0);
			} else if (max == g) {
				hue = (b - r) / d + 2;
			} else {
				hue = (r - g) / d + 4;
			}
			hue /= 6;
		}
		out[0] = hue;
		out[1] = s;
		out[2] = l;
	}

	private static int hslToRgb(float h, float s, float l) {
		float r;
		float g;
		float b;
		if (s == 0) {
			r = g = b = l;
		} else {
			float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
			float p = 2 * l - q;
			r = hue(p, q, h + 1f / 3);
			g = hue(p, q, h);
			b = hue(p, q, h - 1f / 3);
		}
		return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
	}

	private static float hue(float p, float q, float t) {
		if (t < 0) {
			t += 1;
		}
		if (t > 1) {
			t -= 1;
		}
		if (t < 1f / 6) {
			return p + (q - p) * 6 * t;
		}
		if (t < 0.5f) {
			return q;
		}
		if (t < 2f / 3) {
			return p + (q - p) * (2f / 3 - t) * 6;
		}
		return p;
	}

	// ---- Editing ----

	/** Copies of the saved drawings (missing states absent), for the pixel editor. */
	public static Map<State, NativeImage> loadForEditing(String kind) {
		Map<State, NativeImage> images = new EnumMap<>(State.class);
		if (!hasDrawing(kind)) {
			return images;
		}
		for (State state : State.values()) {
			Path path = file(kind, state);
			if (Files.isRegularFile(path)) {
				try (InputStream in = Files.newInputStream(path)) {
					images.put(state, NativeImage.read(in));
				} catch (IOException e) {
					VeloClient.LOGGER.warn("Velo: couldn't read {} {}", kind, state, e);
				}
			}
		}
		return images;
	}

	/** Saves drawings ({@code null}/absent state = derive it from "full"). The images stay the caller's. */
	public static void save(String kind, Map<State, NativeImage> images) throws IOException {
		Files.createDirectories(dir(kind));
		for (State state : State.values()) {
			NativeImage image = images.get(state);
			if (image == null) {
				Files.deleteIfExists(file(kind, state));
			} else {
				image.writeTo(file(kind, state));
			}
		}
		invalidate(kind);
	}

	public static void delete(String kind) throws IOException {
		for (State state : State.values()) {
			Files.deleteIfExists(file(kind, state));
		}
		invalidate(kind);
	}

	/** Forgets everything (resource reloads change the vanilla sprites). */
	public static void invalidateAll() {
		RECOLORED.clear();
		for (String key : new java.util.ArrayList<>(CACHE.keySet())) {
			destroy(key);
		}
	}

	private static void invalidate(String kind) {
		DRAWINGS.remove(kind);
		for (String key : new java.util.ArrayList<>(CACHE.keySet())) {
			if (key.startsWith(kind + "/")) {
				destroy(key);
			}
		}
	}

	private static void destroy(String key) {
		Identifier id = CACHE.remove(key);
		SIZES.remove(key);
		if (id != null) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
		}
	}

	@FunctionalInterface
	private interface ImageSource {
		NativeImage load() throws IOException;
	}

	private static Identifier cached(String key, ImageSource source) {
		if (CACHE.containsKey(key)) {
			return CACHE.get(key);
		}
		Identifier id = null;
		try {
			NativeImage image = source.load();
			if (image != null) {
				SIZES.put(key, image.getWidth());
				id = Identifier.of("velo-client", "status_icon_" + key.replace('/', '_').replace('-', '_'));
				MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> key, image));
			}
		} catch (Exception e) {
			VeloClient.LOGGER.warn("Velo: couldn't load status bar icon {}", key, e);
		}
		CACHE.put(key, id);
		return id;
	}

	// ---- Image helpers (also used by the pixel editor's PNG import) ----

	/**
	 * Fits any image into a {@code size}-pixel square: trims transparent borders, keeps the aspect
	 * ratio, centres it and box-filters it down (alpha-weighted, so edges don't go dark). Closes
	 * {@code source}.
	 */
	public static NativeImage fit(NativeImage source, int size) {
		int w = source.getWidth();
		int h = source.getHeight();
		int minX = w, minY = h, maxX = -1, maxY = -1;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if ((source.getColorArgb(x, y) >>> 24) > 8) {
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
		}
		NativeImage out = new NativeImage(size, size, true);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				out.setColorArgb(x, y, 0);
			}
		}
		if (maxX < 0) {
			source.close();
			return out;
		}
		int cw = maxX - minX + 1;
		int ch = maxY - minY + 1;
		double scale = Math.min((double) size / cw, (double) size / ch);
		double offX = (size - cw * scale) / 2;
		double offY = (size - ch * scale) / 2;
		for (int oy = 0; oy < size; oy++) {
			for (int ox = 0; ox < size; ox++) {
				double sx0 = minX + (ox - offX) / scale;
				double sy0 = minY + (oy - offY) / scale;
				double sx1 = sx0 + 1 / scale;
				double sy1 = sy0 + 1 / scale;
				double a = 0, r = 0, g = 0, b = 0, weight = 0;
				for (int sy = (int) Math.floor(sy0); sy < Math.ceil(sy1); sy++) {
					for (int sx = (int) Math.floor(sx0); sx < Math.ceil(sx1); sx++) {
						double coverX = Math.min(sx + 1, sx1) - Math.max(sx, sx0);
						double coverY = Math.min(sy + 1, sy1) - Math.max(sy, sy0);
						if (coverX <= 0 || coverY <= 0) {
							continue;
						}
						double cover = coverX * coverY;
						weight += cover;
						if (sx < minX || sy < minY || sx > maxX || sy > maxY) {
							continue;
						}
						int argb = source.getColorArgb(sx, sy);
						double pa = (argb >>> 24) / 255.0 * cover;
						a += pa;
						r += ((argb >> 16) & 0xFF) * pa;
						g += ((argb >> 8) & 0xFF) * pa;
						b += (argb & 0xFF) * pa;
					}
				}
				if (a <= 0 || weight <= 0) {
					continue;
				}
				int alpha = (int) Math.round(Math.min(1, a / weight) * 255);
				out.setColorArgb(ox, oy, (alpha << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a));
			}
		}
		source.close();
		return out;
	}

	/**
	 * White where the icon's edge is: opaque pixels touching transparency (or the image border) -
	 * for vanilla-style art that's exactly the dark outline, so tinting this recolors the outline.
	 */
	public static NativeImage outline(NativeImage image) {
		int w = image.getWidth();
		int h = image.getHeight();
		NativeImage out = new NativeImage(w, h, true);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				boolean opaque = (image.getColorArgb(x, y) >>> 24) > 100;
				boolean edge = opaque && (transparent(image, x - 1, y) || transparent(image, x + 1, y)
						|| transparent(image, x, y - 1) || transparent(image, x, y + 1));
				out.setColorArgb(x, y, edge ? 0xFFFFFFFF : 0);
			}
		}
		return out;
	}

	private static boolean transparent(NativeImage image, int x, int y) {
		if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
			return true;
		}
		return (image.getColorArgb(x, y) >>> 24) <= 100;
	}
}
