package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Full read/write cape library for the launcher, byte-for-byte compatible
 * with the mod's {@code CapeManager} - same {@code .velocape} bundle format
 * (texture.png + physics.json + meta.json) and the same
 * {@code config/cosmetics-cape.json} equip-state file, so importing/equipping
 * a cape here is immediately visible in-game and vice versa (design spec
 * section 6.5 / section 8's single-source-of-truth config).
 */
public final class CapeLibrary {

	/** Base vanilla cape template size - still the minimum accepted, and what preview/UV crop math is proportioned against. */
	public static final int TEXTURE_WIDTH = 64;
	public static final int TEXTURE_HEIGHT = 32;
	/** HD cape textures are accepted at any multiple of the base 2:1 template up to this size (matches vanilla's UV normalization, which is resolution-independent). */
	public static final int MAX_TEXTURE_WIDTH = 512;
	public static final int MAX_TEXTURE_HEIGHT = 256;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String EQUIP_STATE_FILE = "cosmetics-cape.json";

	private CapeLibrary() {
	}

	public static List<CapeEntry> listAll() {
		VeloPaths.ensureDirectories();
		List<CapeEntry> capes = new ArrayList<>();
		try (var files = Files.list(VeloPaths.capes())) {
			for (Path file : files.filter(p -> p.toString().endsWith(".velocape")).toList()) {
				readMetadata(file).ifPresent(capes::add);
			}
		} catch (IOException ignored) {
			// Empty/missing directory - no capes yet.
		}
		return capes;
	}

	/** Validates the PNG is exactly {@value #TEXTURE_WIDTH}x{@value #TEXTURE_HEIGHT} before importing. */
	public static CapeEntry importPng(String name, Path pngFile, CapePhysicsPresetData preset) throws IOException {
		BufferedImage image = ImageIO.read(pngFile.toFile());
		if (image == null) {
			throw new IOException("Not a readable PNG file.");
		}
		int width = image.getWidth();
		int height = image.getHeight();
		// Vanilla's cape model UV is baked as fractions of a 64x32 template, so
		// the GPU samples proportionally regardless of the bound texture's real
		// resolution - any multiple of that 2:1 ratio "just works" for HD capes,
		// not only the exact base size.
		boolean validRatio = width == height * 2;
		boolean withinBounds = width >= TEXTURE_WIDTH && height >= TEXTURE_HEIGHT
				&& width <= MAX_TEXTURE_WIDTH && height <= MAX_TEXTURE_HEIGHT;
		if (!validRatio || !withinBounds) {
			throw new IOException("Cape textures must keep the 2:1 cape ratio (e.g. 64x32, 128x64, ... up to "
					+ MAX_TEXTURE_WIDTH + "x" + MAX_TEXTURE_HEIGHT + ") - got " + width + "x" + height + ".");
		}

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
		return new CapeEntry(id, name, bundleFile, preset, false);
	}

	/** Imports an animated GIF (a Store cape's bundled art) as a new library cape - the animated counterpart of {@link #importPng}. */
	public static CapeEntry importAnimatedGif(String name, InputStream gifBytes, CapePhysicsPresetData preset) throws IOException {
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
			zip.write(GSON.toJson(new Meta(id, name)).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return new CapeEntry(id, name, bundleFile, preset, true);
	}

	public static void exportBundle(CapeEntry entry, Path destination) throws IOException {
		Files.copy(entry.bundleFile(), destination, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Raw {@code texture.png} bytes from the bundle, for rendering a live preview (e.g. as a JavaFX {@code Image}). */
	public static byte[] textureBytes(CapeEntry entry) throws IOException {
		return bundleEntryBytes(entry, "texture.png");
	}

	/** Raw {@code frames.gif} bytes from an animated bundle - {@link CapeEntry#animated()} must be true. */
	public static byte[] gifBytes(CapeEntry entry) throws IOException {
		return bundleEntryBytes(entry, "frames.gif");
	}

	private static byte[] bundleEntryBytes(CapeEntry entry, String entryName) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(entry.bundleFile()))) {
			ZipEntry zipEntry;
			while ((zipEntry = zip.getNextEntry()) != null) {
				if (zipEntry.getName().equals(entryName)) {
					return zip.readAllBytes();
				}
			}
		}
		throw new IOException("Cape bundle is missing its " + entryName + ": " + entry.bundleFile());
	}

	public static void delete(CapeEntry entry) throws IOException {
		Files.deleteIfExists(entry.bundleFile());
		if (entry.id().equals(equippedCapeId().orElse(null))) {
			unequip();
		}
	}

	public static Optional<String> equippedCapeId() {
		Path file = VeloPaths.config().resolve(EQUIP_STATE_FILE);
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			EquipState state = GSON.fromJson(reader, EquipState.class);
			return state == null ? Optional.empty() : Optional.ofNullable(state.equippedCapeId);
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	public static void equip(String capeId) {
		saveEquipState(capeId);
	}

	public static void unequip() {
		saveEquipState(null);
	}

	private static void saveEquipState(String capeId) {
		VeloPaths.ensureDirectories();
		Path file = VeloPaths.config().resolve(EQUIP_STATE_FILE);
		try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(new EquipState(capeId), writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save cape equip state", e);
		}
	}

	private static Optional<CapeEntry> readMetadata(Path bundleFile) {
		Meta meta = null;
		CapePhysicsPresetData preset = CapePhysicsPresetData.defaults();
		boolean animated = false;
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(bundleFile))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().equals("meta.json")) {
					meta = GSON.fromJson(new InputStreamReader(zip, StandardCharsets.UTF_8), Meta.class);
				} else if (entry.getName().equals("physics.json")) {
					preset = GSON.fromJson(new InputStreamReader(zip, StandardCharsets.UTF_8), CapePhysicsPresetData.class);
				} else if (entry.getName().equals("frames.gif")) {
					animated = true;
				}
			}
		} catch (IOException e) {
			return Optional.empty();
		}
		return meta == null ? Optional.empty() : Optional.of(new CapeEntry(meta.id, meta.name, bundleFile, preset, animated));
	}

	private static String sanitize(String name) {
		return name.toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
	}

	private static final class Meta {
		String id;
		String name;

		Meta(String id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	private static final class EquipState {
		String equippedCapeId;

		EquipState(String equippedCapeId) {
			this.equippedCapeId = equippedCapeId;
		}
	}
}
