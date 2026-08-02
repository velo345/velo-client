package net.veloclient.velo.client.cosmetics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.config.VeloPaths;

import java.io.IOException;
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
 * {@code texture.png} + {@code physics.json} + {@code meta.json}), texture
 * registration, and which cape (if any) is currently equipped (design spec
 * section 6.5). The cape texture template is 64x32, matching vanilla's own
 * cape UV layout, since rendering reuses vanilla's {@code PlayerCapeModel}
 * mesh/UV mapping with a swapped-in texture.
 */
public final class CapeManager {

	public static final int TEXTURE_WIDTH = 64;
	public static final int TEXTURE_HEIGHT = 32;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, CapeDefinition> LIBRARY = new LinkedHashMap<>();
	private static final Map<String, Identifier> TEXTURE_CACHE = new LinkedHashMap<>();
	private static String equippedCapeId;
	private static boolean loaded;

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

	public static void equip(String capeId) {
		equippedCapeId = capeId;
		net.veloclient.velo.config.ConfigManager.save("cosmetics-cape", new EquipState(capeId));
	}

	public static void unequip() {
		equip(null);
	}

	/** Imports a PNG texture (must be {@value #TEXTURE_WIDTH}x{@value #TEXTURE_HEIGHT}) as a new library cape. */
	public static CapeDefinition importCape(String name, Path pngFile, CapePhysicsPreset preset) throws IOException {
		VeloPaths.ensureDirectories();
		String id = UUID.randomUUID().toString();
		Path bundleFile = VeloPaths.capes().resolve(sanitize(name) + "-" + id.substring(0, 8) + ".velocape");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundleFile))) {
			zip.putNextEntry(new ZipEntry("texture.png"));
			Files.copy(pngFile, zip);
			zip.closeEntry();

			zip.putNextEntry(new ZipEntry("physics.json"));
			zip.write(GSON.toJson(preset).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();

			zip.putNextEntry(new ZipEntry("meta.json"));
			zip.write(GSON.toJson(new Meta(id, name)).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		CapeDefinition definition = new CapeDefinition(id, name, bundleFile, preset);
		LIBRARY.put(id, definition);
		return definition;
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

	/** Lazily loads and registers the cape's texture with Minecraft's texture manager, returning its {@link Identifier}. */
	public static Identifier textureIdentifier(CapeDefinition definition) {
		return TEXTURE_CACHE.computeIfAbsent(definition.id(), id -> {
			Identifier identifier = Identifier.of("velo-client", "cape_" + id.replace('-', '_'));
			try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(definition.bundleFile()))) {
				ZipEntry entry;
				while ((entry = zip.getNextEntry()) != null) {
					if (entry.getName().equals("texture.png")) {
						NativeImage image = NativeImage.read(zip);
						MinecraftClient.getInstance().getTextureManager()
								.registerTexture(identifier, new NativeImageBackedTexture(() -> definition.name(), image));
						break;
					}
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed to load cape texture for " + definition.id(), e);
			}
			return identifier;
		});
	}

	private static Optional<CapeDefinition> readBundleMetadata(Path bundleFile) {
		Meta meta = null;
		CapePhysicsPreset preset = CapePhysicsPreset.defaults();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(bundleFile))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().equals("meta.json")) {
					meta = GSON.fromJson(new java.io.InputStreamReader(zip, StandardCharsets.UTF_8), Meta.class);
				} else if (entry.getName().equals("physics.json")) {
					preset = GSON.fromJson(new java.io.InputStreamReader(zip, StandardCharsets.UTF_8), CapePhysicsPreset.class);
				}
			}
		} catch (IOException e) {
			return Optional.empty();
		}
		if (meta == null) {
			return Optional.empty();
		}
		return Optional.of(new CapeDefinition(meta.id(), meta.name(), bundleFile, preset));
	}

	private static String sanitize(String name) {
		return name.toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
	}

	private record Meta(String id, String name) {
	}

	private record EquipState(String equippedCapeId) {
	}
}
