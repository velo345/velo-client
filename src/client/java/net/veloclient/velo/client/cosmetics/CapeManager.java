package net.veloclient.velo.client.cosmetics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.config.VeloPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Local cape library: import/export {@code .velocape} bundles (a zip of
 * {@code texture.png} + {@code physics.json} + {@code meta.json}, plus an
 * optional {@code elytra.png}), texture registration, and which cape (if
 * any) is currently equipped (design spec section 6.5). The cape texture
 * template is 64x32, matching vanilla's own cape UV layout, since rendering
 * reuses vanilla's {@code PlayerCapeModel} mesh/UV mapping with a
 * swapped-in texture.
 *
 * <p>A lot of real cape art ships with a matching elytra texture bundled
 * into the same image - vanilla's own elytra texture is also 64x32, so a
 * 64x64 imported PNG is treated as "cape on top, elytra on the bottom" and
 * split into two separate 64x32 textures at import time; a plain 64x32 PNG
 * just stays cape-only, no elytra override at all. Whichever one applies is
 * detected purely from image height, no extra UI step needed.
 */
public final class CapeManager {

	public static final int TEXTURE_WIDTH = 64;
	public static final int TEXTURE_HEIGHT = 32;
	private static final int COMBINED_TEXTURE_HEIGHT = TEXTURE_HEIGHT * 2;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, CapeDefinition> LIBRARY = new LinkedHashMap<>();
	private static final Map<String, Identifier> TEXTURE_CACHE = new LinkedHashMap<>();
	private static final Map<String, Identifier> ELYTRA_TEXTURE_CACHE = new LinkedHashMap<>();
	private static String equippedCapeId;
	private static boolean loaded;
	/** A Store preview cape, shown on the local player instead of the real equipped one while a preview screen is open - see {@link #renderCape()}. */
	private static CapeDefinition previewOverride;

	private CapeManager() {
	}

	public static synchronized void loadLibrary() {
		if (loaded) {
			return;
		}
		loaded = true;
		VeloPaths.ensureDirectories();
		LIBRARY.clear();
		try (var files = Files.list(VeloPaths.capes())) {
			for (Path file : files.filter(p -> p.toString().endsWith(".velocape")).toList()) {
				readBundleMetadata(file).ifPresent(def -> LIBRARY.put(def.id(), def));
			}
		} catch (IOException ignored) {
			// No capes directory content yet; library just stays empty.
		}
		EquipState state = net.veloclient.velo.config.ConfigManager.load("cosmetics-cape", EquipState.class, new EquipState(null));
		equippedCapeId = state.equippedCapeId();
	}

	public static Map<String, CapeDefinition> library() {
		return Map.copyOf(LIBRARY);
	}

	public static Optional<CapeDefinition> equipped() {
		return Optional.ofNullable(equippedCapeId).map(LIBRARY::get);
	}

	/** The Store catalog id of the equipped cape, if it's a purchased Store cape (see {@link CapeDefinition#sourceItemId()}) - what {@code VeloServerClient} publishes to a Velo Client server. */
	public static Optional<String> equippedSourceItemId() {
		return equipped().map(CapeDefinition::sourceItemId).filter(id -> id != null && !id.isBlank());
	}

	/** Sets/clears the Store's temporary preview cape - see {@link #renderCape()}. */
	public static void setPreviewOverride(CapeDefinition definition) {
		previewOverride = definition;
	}

	public static void clearPreviewOverride() {
		previewOverride = null;
	}

	/**
	 * Whichever cape should actually be drawn on the local player right now:
	 * a Store item being previewed, if one's active, otherwise the real
	 * equipped cape. {@link net.veloclient.velo.client.cosmetics.render.CapeFeatureRenderer}
	 * and the Wavey Capes compat mixin both render through this instead of
	 * {@link #equipped()} directly, so the Store's 3D preview can show a cape
	 * the player hasn't bought yet without touching their actual equip
	 * state.
	 */
	public static Optional<CapeDefinition> renderCape() {
		return previewOverride != null ? Optional.of(previewOverride) : equipped();
	}

	public static void equip(String capeId) {
		equippedCapeId = capeId;
		net.veloclient.velo.config.ConfigManager.save("cosmetics-cape", new EquipState(capeId));
	}

	public static void unequip() {
		equip(null);
	}

	public static void delete(String capeId) {
		CapeDefinition definition = LIBRARY.remove(capeId);
		if (definition == null) {
			return;
		}
		if (capeId.equals(equippedCapeId)) {
			unequip();
		}
		TEXTURE_CACHE.remove(capeId);
		ELYTRA_TEXTURE_CACHE.remove(capeId);
		try {
			Files.deleteIfExists(definition.bundleFile());
		} catch (IOException ignored) {
			// Best-effort cleanup - a stray leftover bundle isn't worth surfacing an error for.
		}
	}

	/** Renames a library cape and/or updates its physics preset in place, rewriting the {@code .velocape} bundle's meta.json/physics.json (texture.png/elytra.png are copied through unchanged). */
	public static void update(String capeId, String newName, CapePhysicsPreset newPreset) throws IOException {
		CapeDefinition existing = LIBRARY.get(capeId);
		if (existing == null) {
			throw new IOException("Unknown cape id: " + capeId);
		}
		Path tempFile = Files.createTempFile(existing.bundleFile().getParent(), "cape-update-", ".velocape");
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(existing.bundleFile()));
				ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tempFile))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				out.putNextEntry(new ZipEntry(entry.getName()));
				switch (entry.getName()) {
					case "meta.json" -> out.write(GSON.toJson(new Meta(capeId, newName, existing.sourceItemId())).getBytes(StandardCharsets.UTF_8));
					case "physics.json" -> out.write(GSON.toJson(newPreset).getBytes(StandardCharsets.UTF_8));
					default -> in.transferTo(out);
				}
				out.closeEntry();
			}
		}
		Files.move(tempFile, existing.bundleFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		LIBRARY.put(capeId, new CapeDefinition(capeId, newName, existing.bundleFile(), newPreset, existing.hasElytraTexture(), existing.animated(), existing.sourceItemId()));
	}

	/**
	 * Imports a PNG texture as a new library cape. A plain
	 * {@value #TEXTURE_WIDTH}x{@value #TEXTURE_HEIGHT} image is cape-only; a
	 * {@value #TEXTURE_WIDTH}x{@value #COMBINED_TEXTURE_HEIGHT} image is
	 * split into a cape texture (top half) and a matching elytra texture
	 * (bottom half) - see the class doc.
	 */
	public static CapeDefinition importCape(String name, Path pngFile, CapePhysicsPreset preset) throws IOException {
		VeloPaths.ensureDirectories();
		String id = UUID.randomUUID().toString();
		Path bundleFile = VeloPaths.capes().resolve(sanitize(name) + "-" + id.substring(0, 8) + ".velocape");

		NativeImage source = NativeImage.read(Files.newInputStream(pngFile));
		boolean combined = source.getWidth() == TEXTURE_WIDTH && source.getHeight() >= COMBINED_TEXTURE_HEIGHT;
		try {
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundleFile))) {
				zip.putNextEntry(new ZipEntry("texture.png"));
				writeRegion(zip, source, 0, combined ? TEXTURE_HEIGHT : source.getHeight());
				zip.closeEntry();

				if (combined) {
					zip.putNextEntry(new ZipEntry("elytra.png"));
					writeRegion(zip, source, TEXTURE_HEIGHT, TEXTURE_HEIGHT);
					zip.closeEntry();
				}

				zip.putNextEntry(new ZipEntry("physics.json"));
				zip.write(GSON.toJson(preset).getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();

				zip.putNextEntry(new ZipEntry("meta.json"));
				zip.write(GSON.toJson(new Meta(id, name, null)).getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		} finally {
			source.close();
		}
		CapeDefinition definition = new CapeDefinition(id, name, bundleFile, preset, combined, false, null);
		LIBRARY.put(id, definition);
		return definition;
	}

	/** Same as {@link #importAnimatedGif(String, InputStream, CapePhysicsPreset, String)} with no Store provenance - a plain shared {@code frames.gif}, not a purchase. */
	public static CapeDefinition importAnimatedGif(String name, InputStream gifBytes, CapePhysicsPreset preset) throws IOException {
		return importAnimatedGif(name, gifBytes, preset, null);
	}

	/**
	 * Imports an animated GIF (a store cape's bundled art, or a shared
	 * {@code frames.gif}) as a new library cape - the animated counterpart of
	 * {@link #importCape}. No elytra split: animated capes are store
	 * cosmetics, not user PNG imports, so there's no combined-texture
	 * convention to honor here. {@code sourceItemId}, if this came from
	 * {@code StorePurchase}, is the {@code StoreItem#id()} bought - see
	 * {@link CapeDefinition#sourceItemId()}.
	 */
	public static CapeDefinition importAnimatedGif(String name, InputStream gifBytes, CapePhysicsPreset preset, String sourceItemId) throws IOException {
		VeloPaths.ensureDirectories();
		String id = UUID.randomUUID().toString();
		Path bundleFile = VeloPaths.capes().resolve(sanitize(name) + "-" + id.substring(0, 8) + ".velocape");

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundleFile))) {
			zip.putNextEntry(new ZipEntry("frames.gif"));
			gifBytes.transferTo(zip);
			zip.closeEntry();

			zip.putNextEntry(new ZipEntry("physics.json"));
			zip.write(GSON.toJson(preset).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();

			zip.putNextEntry(new ZipEntry("meta.json"));
			zip.write(GSON.toJson(new Meta(id, name, sourceItemId)).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		CapeDefinition definition = new CapeDefinition(id, name, bundleFile, preset, false, true, sourceItemId);
		LIBRARY.put(id, definition);
		return definition;
	}

	/**
	 * Builds a throwaway, never-saved-to-the-library {@link CapeDefinition}
	 * for the Store's "try before you buy" preview - same bundle shape {@link
	 * #importAnimatedGif} writes, just to a temp file instead of {@link
	 * VeloPaths#capes()}, and never added to {@link #LIBRARY}. Passing the
	 * same {@code id} a Store item's grid tile already used to register its
	 * preview texture (see {@code StoreAssets.preview}) means {@link
	 * #textureIdentifier} finds that texture already cached and never
	 * actually needs to read this temp file back - it exists purely as a
	 * safety net for whichever code path asks first.
	 */
	public static CapeDefinition previewDefinitionFor(String id, String name, InputStream gifBytes, CapePhysicsPreset preset) throws IOException {
		Path tempFile = Files.createTempFile("velo-store-preview-", ".velocape");
		tempFile.toFile().deleteOnExit();
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tempFile))) {
			zip.putNextEntry(new ZipEntry("frames.gif"));
			gifBytes.transferTo(zip);
			zip.closeEntry();

			zip.putNextEntry(new ZipEntry("physics.json"));
			zip.write(GSON.toJson(preset).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();

			zip.putNextEntry(new ZipEntry("meta.json"));
			zip.write(GSON.toJson(new Meta(id, name, id)).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return new CapeDefinition(id, name, tempFile, preset, false, true, id);
	}

	/** Crops {@code source} to a {@value #TEXTURE_WIDTH}x{@code height} region starting at {@code startY} and writes it as a PNG into {@code out} (via a throwaway temp file - NativeImage only writes to a real path/file, not a stream). */
	private static void writeRegion(java.io.OutputStream out, NativeImage source, int startY, int height) throws IOException {
		NativeImage region = new NativeImage(TEXTURE_WIDTH, height, false);
		try {
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < TEXTURE_WIDTH; x++) {
					region.setColorArgb(x, y, source.getColorArgb(x, startY + y));
				}
			}
			Path tempFile = Files.createTempFile("velo-cape-region-", ".png");
			try {
				region.writeTo(tempFile);
				Files.copy(tempFile, out);
			} finally {
				Files.deleteIfExists(tempFile);
			}
		} finally {
			region.close();
		}
	}

	public static void exportCape(String capeId, Path destination) throws IOException {
		CapeDefinition definition = LIBRARY.get(capeId);
		if (definition == null) {
			throw new IOException("Unknown cape id: " + capeId);
		}
		Files.copy(definition.bundleFile(), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	/** Imports a {@code .velocape} bundle someone shared with you. */
	public static CapeDefinition importBundle(Path bundleFile) throws IOException {
		VeloPaths.ensureDirectories();
		Path copy = VeloPaths.capes().resolve(bundleFile.getFileName());
		Files.copy(bundleFile, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		return readBundleMetadata(copy).orElseThrow(() -> new IOException("Invalid .velocape bundle: " + bundleFile));
	}

	/**
	 * Lazily loads and registers the cape's texture with Minecraft's texture
	 * manager, returning its {@link Identifier} - for an animated cape, this
	 * is the one stable identifier {@link AnimatedCapeAsset} keeps updating
	 * in place every frame, not a per-frame identifier, so every caller
	 * (render layer, library tile, Wavey Capes compat) just keeps sampling
	 * whatever they already had.
	 */
	public static Identifier textureIdentifier(CapeDefinition definition) {
		if (definition.animated()) {
			return AnimatedCapeAsset.getOrRegister(definition.id(), () -> openBundleEntry(definition, "frames.gif")).identifier();
		}
		return TEXTURE_CACHE.computeIfAbsent(definition.id(), id -> registerFromBundle(definition, "texture.png", "cape_" + id.replace('-', '_'))
				.orElseThrow(() -> new RuntimeException("Failed to load cape texture for " + definition.id())));
	}

	/** Reads one zip entry's full bytes into memory - used for {@code frames.gif}, which {@link GifDecoder} needs as a plain {@link InputStream}, not a {@link net.minecraft.client.texture.NativeImage}. */
	private static InputStream openBundleEntry(CapeDefinition definition, String entryName) {
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(definition.bundleFile()))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().equals(entryName)) {
					return new java.io.ByteArrayInputStream(zip.readAllBytes());
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to read \"" + entryName + "\" from " + definition.bundleFile(), e);
		}
		throw new RuntimeException("Missing \"" + entryName + "\" in " + definition.bundleFile());
	}

	/** Lazily loads and registers the cape's bundled elytra texture, if it has one ({@link CapeDefinition#hasElytraTexture()}). */
	public static Optional<Identifier> elytraTextureIdentifier(CapeDefinition definition) {
		if (!definition.hasElytraTexture()) {
			return Optional.empty();
		}
		return Optional.of(ELYTRA_TEXTURE_CACHE.computeIfAbsent(definition.id(),
				id -> registerFromBundle(definition, "elytra.png", "cape_elytra_" + id.replace('-', '_'))
						.orElseThrow(() -> new RuntimeException("Failed to load elytra texture for " + definition.id()))));
	}

	private static Optional<Identifier> registerFromBundle(CapeDefinition definition, String entryName, String texturePath) {
		Identifier identifier = Identifier.of("velo-client", texturePath);
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(definition.bundleFile()))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().equals(entryName)) {
					NativeImage image = NativeImage.read(zip);
					MinecraftClient.getInstance().getTextureManager()
							.registerTexture(identifier, new NativeImageBackedTexture(() -> definition.name(), image));
					return Optional.of(identifier);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to load texture \"" + entryName + "\" for " + definition.id(), e);
		}
		return Optional.empty();
	}

	private static Optional<CapeDefinition> readBundleMetadata(Path bundleFile) {
		Meta meta = null;
		CapePhysicsPreset preset = CapePhysicsPreset.defaults();
		boolean hasElytra = false;
		boolean animated = false;
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(bundleFile))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().equals("meta.json")) {
					meta = GSON.fromJson(new java.io.InputStreamReader(zip, StandardCharsets.UTF_8), Meta.class);
				} else if (entry.getName().equals("physics.json")) {
					preset = GSON.fromJson(new java.io.InputStreamReader(zip, StandardCharsets.UTF_8), CapePhysicsPreset.class);
				} else if (entry.getName().equals("elytra.png")) {
					hasElytra = true;
				} else if (entry.getName().equals("frames.gif")) {
					animated = true;
				}
			}
		} catch (IOException e) {
			return Optional.empty();
		}
		if (meta == null) {
			return Optional.empty();
		}
		return Optional.of(new CapeDefinition(meta.id(), meta.name(), bundleFile, preset, hasElytra, animated, meta.sourceItemId()));
	}

	private static String sanitize(String name) {
		return name.toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
	}

	// sourceItemId is absent from meta.json written before this field existed
	// - Gson leaves it null rather than failing to parse, so old bundles just
	// come back as "not a Store cape" (accurate: they predate Store capes
	// tracking provenance at all).
	private record Meta(String id, String name, String sourceItemId) {
	}

	private record EquipState(String equippedCapeId) {
	}
}
