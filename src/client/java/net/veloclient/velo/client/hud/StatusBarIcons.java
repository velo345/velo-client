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

	private StatusBarIcons() {
	}

	private static Path dir(String kind) {
		return VeloPaths.root().resolve("icons").resolve(kind);
	}

	private static Path file(String kind, State state) {
		return dir(kind).resolve(state.file + ".png");
	}

	public static boolean hasDrawing(String kind) {
		migrateOldImport(kind);
		return Files.isRegularFile(file(kind, State.FULL));
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
		for (String key : new java.util.ArrayList<>(CACHE.keySet())) {
			destroy(key);
		}
	}

	private static void invalidate(String kind) {
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
