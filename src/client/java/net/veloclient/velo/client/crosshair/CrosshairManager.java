package net.veloclient.velo.client.crosshair;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.config.ConfigManager;
import net.veloclient.velo.config.VeloPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Local crosshair library, mirroring {@link net.veloclient.velo.client.cosmetics.CapeManager}'s
 * pattern: each crosshair is a directory under {@code ~/.velo-client/crosshairs/<id>/}
 * containing {@code meta.json}, {@code idle.png}, and (only for {@link
 * CrosshairDefinition.HitMode#SEPARATE_IMAGE}) {@code hit.png}. Textures are
 * registered lazily and cached; {@link #invalidateTexture} must be called
 * after editing a crosshair still in the cache, or the old pixels keep
 * rendering until the image identifier happens to change.
 */
public final class CrosshairManager {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, CrosshairDefinition> LIBRARY = new LinkedHashMap<>();
	private static final Map<String, Identifier> TEXTURE_CACHE = new LinkedHashMap<>();
	private static String equippedId;
	private static boolean loaded;

	private CrosshairManager() {
	}

	public static synchronized void loadLibrary() {
		if (loaded) {
			return;
		}
		loaded = true;
		VeloPaths.ensureDirectories();
		LIBRARY.clear();
		try (var dirs = Files.list(VeloPaths.crosshairs())) {
			for (Path dir : dirs.filter(Files::isDirectory).toList()) {
				readMeta(dir).ifPresent(def -> LIBRARY.put(def.id(), def));
			}
		} catch (IOException ignored) {
			// No crosshairs directory content yet; library just stays empty.
		}
		boolean fresh = LIBRARY.isEmpty();
		if (fresh) {
			seedDefaults();
		}
		EquipState state = ConfigManager.load("crosshair-equip", EquipState.class, new EquipState(null));
		equippedId = fresh ? equippedId : state.equippedId();
		seedCenteredPresets(fresh);
	}

	public static Map<String, CrosshairDefinition> library() {
		return Map.copyOf(LIBRARY);
	}

	public static Optional<CrosshairDefinition> equipped() {
		return Optional.ofNullable(equippedId).map(LIBRARY::get);
	}

	public static void equip(String id) {
		equippedId = id;
		ConfigManager.save("crosshair-equip", new EquipState(id));
	}

	public static void unequip() {
		equip(null);
	}

	private static Path dirFor(String id) {
		return VeloPaths.crosshairs().resolve(id);
	}

	public static NativeImage loadIdleImage(String id) {
		return readImage(dirFor(id).resolve("idle.png"));
	}

	public static NativeImage loadHitImage(String id) {
		return readImage(dirFor(id).resolve("hit.png"));
	}

	private static NativeImage readImage(Path file) {
		try (var in = Files.newInputStream(file)) {
			return NativeImage.read(in);
		} catch (IOException e) {
			throw new RuntimeException("Failed to read crosshair image " + file, e);
		}
	}

	/** Creates a new, blank (fully transparent) crosshair and saves it immediately. */
	public static CrosshairDefinition createBlank(String name, int canvasSize) {
		NativeImage image = new NativeImage(canvasSize, canvasSize, false);
		image.fillRect(0, 0, canvasSize, canvasSize, 0);
		String id = UUID.randomUUID().toString();
		CrosshairDefinition definition = new CrosshairDefinition(id, name, canvasSize, CrosshairDefinition.HitMode.NONE, Map.of());
		save(definition, image, null);
		image.close();
		return definition;
	}

	/** Imports an existing (must be square) PNG as a new crosshair. */
	public static CrosshairDefinition importFromPng(String name, Path pngFile) throws IOException {
		NativeImage image;
		try (var in = Files.newInputStream(pngFile)) {
			image = NativeImage.read(in);
		}
		if (image.getWidth() != image.getHeight()) {
			image.close();
			throw new IOException("Crosshair images must be square (got " + image.getWidth() + "x" + image.getHeight() + ")");
		}
		String id = UUID.randomUUID().toString();
		CrosshairDefinition definition = new CrosshairDefinition(id, name, image.getWidth(), CrosshairDefinition.HitMode.NONE, Map.of());
		save(definition, image, null);
		image.close();
		return definition;
	}

	/** Persists the definition's metadata and pixel data. {@code hitImage} is only written when {@code hitMode == SEPARATE_IMAGE}; pass {@code null} otherwise. */
	public static void save(CrosshairDefinition definition, NativeImage idleImage, NativeImage hitImage) {
		VeloPaths.ensureDirectories();
		Path dir = dirFor(definition.id());
		try {
			Files.createDirectories(dir);
			idleImage.writeTo(dir.resolve("idle.png"));
			Path hitFile = dir.resolve("hit.png");
			if (definition.hitMode() == CrosshairDefinition.HitMode.SEPARATE_IMAGE && hitImage != null) {
				hitImage.writeTo(hitFile);
			} else {
				Files.deleteIfExists(hitFile);
			}
			Files.writeString(dir.resolve("meta.json"), GSON.toJson(new Meta(definition.id(), definition.name(),
					definition.canvasSize(), definition.hitMode().name(), definition.colorSwap())));
		} catch (IOException e) {
			throw new RuntimeException("Failed to save crosshair " + definition.id(), e);
		}
		LIBRARY.put(definition.id(), definition);
		invalidateTexture(definition.id());
	}

	public static void delete(String id) {
		if (id.equals(equippedId)) {
			unequip();
		}
		LIBRARY.remove(id);
		invalidateTexture(id);
		Path dir = dirFor(id);
		try (var files = Files.list(dir)) {
			for (Path file : files.toList()) {
				Files.deleteIfExists(file);
			}
			Files.deleteIfExists(dir);
		} catch (IOException ignored) {
			// Best-effort cleanup - a stray leftover directory isn't worth surfacing an error for.
		}
	}

	/** Lazily loads and registers a crosshair's idle or hit texture, returning its {@link Identifier}. */
	public static Identifier textureIdentifier(String id, boolean hit) {
		String key = id + (hit ? ":hit" : ":idle");
		return TEXTURE_CACHE.computeIfAbsent(key, k -> {
			NativeImage image = hit ? loadHitImage(id) : loadIdleImage(id);
			Identifier identifier = Identifier.of("velo-client", "crosshair_" + id.replace('-', '_') + (hit ? "_hit" : "_idle"));
			MinecraftClient.getInstance().getTextureManager().registerTexture(identifier, new NativeImageBackedTexture(() -> id, image));
			return identifier;
		});
	}

	/**
	 * Lazily generates and caches the color-swap hit variant as a real GPU
	 * texture, exactly like {@link #textureIdentifier} - the alternative
	 * (recomputing the swap pixel-by-pixel from a freshly re-read PNG every
	 * single frame the crosshair is drawn in its hit state) is real,
	 * needless per-frame disk I/O and CPU work for something that only
	 * changes when the crosshair itself is edited.
	 */
	public static Identifier colorSwapTextureIdentifier(CrosshairDefinition definition) {
		String key = definition.id() + ":swap";
		return TEXTURE_CACHE.computeIfAbsent(key, k -> {
			NativeImage source = loadIdleImage(definition.id());
			NativeImage swapped = new NativeImage(definition.canvasSize(), definition.canvasSize(), false);
			for (int x = 0; x < definition.canvasSize(); x++) {
				for (int y = 0; y < definition.canvasSize(); y++) {
					int argb = source.getColorArgb(x, y);
					if ((argb >>> 24) == 0) {
						swapped.setColorArgb(x, y, 0);
						continue;
					}
					Integer mapped = definition.colorSwap().get(argb | 0xFF000000);
					swapped.setColorArgb(x, y, mapped != null ? (mapped & 0x00FFFFFF) | (argb & 0xFF000000) : argb);
				}
			}
			source.close();
			Identifier identifier = Identifier.of("velo-client", "crosshair_" + definition.id().replace('-', '_') + "_swap");
			MinecraftClient.getInstance().getTextureManager().registerTexture(identifier, new NativeImageBackedTexture(() -> definition.id(), swapped));
			return identifier;
		});
	}

	/** Must be called after saving new pixel data for a crosshair that may already be texture-cached (i.e. edited, not newly created), or the old image keeps rendering. */
	public static void invalidateTexture(String id) {
		Identifier idle = TEXTURE_CACHE.remove(id + ":idle");
		Identifier hit = TEXTURE_CACHE.remove(id + ":hit");
		Identifier swap = TEXTURE_CACHE.remove(id + ":swap");
		var textureManager = MinecraftClient.getInstance().getTextureManager();
		if (idle != null) {
			textureManager.destroyTexture(idle);
		}
		if (hit != null) {
			textureManager.destroyTexture(hit);
		}
		if (swap != null) {
			textureManager.destroyTexture(swap);
		}
	}

	private static Optional<CrosshairDefinition> readMeta(Path dir) {
		Path metaFile = dir.resolve("meta.json");
		if (!Files.exists(metaFile)) {
			return Optional.empty();
		}
		try {
			Meta meta = GSON.fromJson(Files.readString(metaFile), Meta.class);
			if (meta == null) {
				return Optional.empty();
			}
			return Optional.of(new CrosshairDefinition(meta.id(), meta.name(), meta.canvasSize(),
					CrosshairDefinition.HitMode.valueOf(meta.hitMode()), meta.colorSwap() != null ? meta.colorSwap() : Map.of()));
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}

	/** Ships a couple of ready-to-use crosshairs on first run so the library isn't empty; these are ordinary (editable/deletable) entries, not protected presets. */
	private static void seedDefaults() {
		int size = 16;
		NativeImage cross = new NativeImage(size, size, false);
		cross.fillRect(0, 0, size, size, 0);
		int white = 0xFFFFFFFF;
		for (int i = 0; i < size; i++) {
			cross.setColorArgb(i, size / 2, white);
			cross.setColorArgb(i, size / 2 - 1, white);
			cross.setColorArgb(size / 2, i, white);
			cross.setColorArgb(size / 2 - 1, i, white);
		}
		// Small gap in the middle, classic FPS-style crosshair rather than a solid plus.
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				cross.setColorArgb(size / 2 + dx, size / 2 + dy, 0);
			}
		}
		CrosshairDefinition crossDef = new CrosshairDefinition(UUID.randomUUID().toString(), "Classic Cross", size, CrosshairDefinition.HitMode.COLOR_SWAP,
				Map.of(white, 0xFFFF4444));
		save(crossDef, cross, null);
		cross.close();

		NativeImage dot = new NativeImage(size, size, false);
		dot.fillRect(0, 0, size, size, 0);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				dot.setColorArgb(size / 2 + dx, size / 2 + dy, white);
			}
		}
		CrosshairDefinition dotDef = new CrosshairDefinition(UUID.randomUUID().toString(), "Dot", size, CrosshairDefinition.HitMode.COLOR_SWAP,
				Map.of(white, 0xFFFF4444));
		save(dotDef, dot, null);
		dot.close();

		equippedId = crossDef.id();
	}

	/**
	 * The centred preset set (2026-11-06): 15x15 canvases, so every shape has one real centre pixel and lines the same
	 * length on each side. Added once to every library (a marker file remembers it); they're ordinary editable entries.
	 */
	private static void seedCenteredPresets(boolean fresh) {
		Path marker = VeloPaths.crosshairs().resolve(".presets-v2");
		if (Files.exists(marker)) {
			return;
		}
		int n = 15, c = 7;
		String first = null;
		String[] names = {"Cross", "Cross + Dot", "Outlined Cross", "Dot", "Small Plus", "Circle", "X", "T", "Thick Cross", "Bracket Dot"};
		for (int k = 0; k < names.length; k++) {
			NativeImage img = new NativeImage(n, n, false);
			img.fillRect(0, 0, n, n, 0);
			int w = 0xFFFFFFFF, black = 0xC0000000;
			switch (k) {
				case 0, 1, 2 -> {
					for (int d = 2; d <= 6; d++) {
						img.setColorArgb(c + d, c, w); img.setColorArgb(c - d, c, w); img.setColorArgb(c, c + d, w); img.setColorArgb(c, c - d, w);
					}
					if (k >= 1) img.setColorArgb(c, c, w);
					if (k == 2) outline(img, black);
				}
				case 3 -> { for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) img.setColorArgb(c + dx, c + dy, w); outline(img, black); }
				case 4 -> { for (int d = -3; d <= 3; d++) { img.setColorArgb(c + d, c, w); img.setColorArgb(c, c + d, w); } }
				case 5 -> {
					for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
						double r = Math.hypot(x - c, y - c);
						if (Math.abs(r - 5.2) < 0.6) img.setColorArgb(x, y, w);
					}
					img.setColorArgb(c, c, w);
				}
				case 6 -> { for (int d = 2; d <= 5; d++) { img.setColorArgb(c + d, c + d, w); img.setColorArgb(c - d, c - d, w); img.setColorArgb(c + d, c - d, w); img.setColorArgb(c - d, c + d, w); } }
				case 7 -> { for (int d = 2; d <= 6; d++) { img.setColorArgb(c + d, c, w); img.setColorArgb(c - d, c, w); img.setColorArgb(c, c + d, w); } img.setColorArgb(c, c, w); }
				case 8 -> {
					for (int d = 2; d <= 6; d++) for (int t = -1; t <= 1; t++) {
						img.setColorArgb(c + d, c + t, w); img.setColorArgb(c - d, c + t, w); img.setColorArgb(c + t, c + d, w); img.setColorArgb(c + t, c - d, w);
					}
				}
				default -> {
					img.setColorArgb(c, c, w);
					for (int d = 0; d <= 2; d++) {
						img.setColorArgb(c - 5, c - 5 + d, w); img.setColorArgb(c - 5 + d, c - 5, w);
						img.setColorArgb(c + 5, c - 5 + d, w); img.setColorArgb(c + 5 - d, c - 5, w);
						img.setColorArgb(c - 5, c + 5 - d, w); img.setColorArgb(c - 5 + d, c + 5, w);
						img.setColorArgb(c + 5, c + 5 - d, w); img.setColorArgb(c + 5 - d, c + 5, w);
					}
				}
			}
			CrosshairDefinition def = new CrosshairDefinition(UUID.randomUUID().toString(), names[k], n, CrosshairDefinition.HitMode.COLOR_SWAP,
					Map.of(w, 0xFFFF4444));
			save(def, img, null);
			img.close();
			if (first == null) first = def.id();
		}
		if (fresh && first != null) {
			equippedId = first;
		}
		try {
			Files.writeString(marker, "presets v2\n");
		} catch (IOException ignored) {
			// worst case they get added again next start
		}
	}

	/** A dark pixel round every white pixel (on empty pixels only): readable on bright sky and snow. */
	private static void outline(NativeImage img, int color) {
		int n = img.getWidth();
		boolean[] on = new boolean[n * n];
		for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) on[y * n + x] = (img.getColorArgb(x, y) >>> 24) > 0;
		for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			if (on[y * n + x]) continue;
			boolean near = false;
			for (int dy = -1; dy <= 1 && !near; dy++) for (int dx = -1; dx <= 1; dx++) {
				int xx = x + dx, yy = y + dy;
				if (xx >= 0 && yy >= 0 && xx < n && yy < n && on[yy * n + xx]) { near = true; break; }
			}
			if (near) img.setColorArgb(x, y, color);
		}
	}

	private record Meta(String id, String name, int canvasSize, String hitMode, Map<Integer, Integer> colorSwap) {
	}

	private record EquipState(String equippedId) {
	}
}
