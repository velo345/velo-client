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
	public static final int MAX_TEXTURE_WIDTH = 2048;
	public static final int MAX_TEXTURE_HEIGHT = 1024;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String EQUIP_STATE_FILE = "cosmetics-cape.json";

	private CapeLibrary() {
	}

	/** Store item id per bundle (null for the player's own pictures), filled while reading metadata. */
	private static final java.util.Map<Path, String> SOURCE_ITEMS = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * The cloaks the signed-in account may wear. Bought Store cloaks are files in the one shared library - without
	 * this a second account listed the first one's purchases as unlocked, and the server refused them.
	 */
	public static List<CapeEntry> listAll() {
		String equipped = equippedCapeId().orElse(null);
		return dedupeStoreItems(listAllBundles(), equipped).stream().filter(entry -> {
			String item = SOURCE_ITEMS.get(entry.bundleFile());
			if (item == null || item.isEmpty()) {
				return true;
			}
			if (!net.veloclient.launcher.social.StoreApi.walletLoaded()) {
				return entry.id().equals(equipped);
			}
			return net.veloclient.launcher.social.StoreApi.owns(item) || net.veloclient.launcher.social.StoreApi.isOwner();
		}).toList();
	}

	/** The Store item a bundle was imported for, or null for the player's own pictures. */
	public static String sourceItemId(CapeEntry entry) {
		String item = SOURCE_ITEMS.get(entry.bundleFile());
		return item == null || item.isEmpty() ? null : item;
	}

	/**
	 * One cloak per Store item: two refreshes racing (or a renamed catalog entry) once imported the same cloak twice, and
	 * the Locker listed it twice (1.5.1.1, LuckySMP cloak). The worn copy wins; the spare files stay on disk (another
	 * account may wear that one) but aren't listed.
	 */
	private static List<CapeEntry> dedupeStoreItems(List<CapeEntry> all, String equipped) {
		java.util.Map<String, CapeEntry> byItem = new java.util.LinkedHashMap<>();
		List<CapeEntry> out = new ArrayList<>();
		for (CapeEntry entry : all) {
			String item = sourceItemId(entry);
			if (item == null) {
				out.add(entry);
				continue;
			}
			CapeEntry kept = byItem.get(item);
			if (kept == null || entry.id().equals(equipped)) {
				byItem.put(item, entry);
			}
		}
		out.addAll(byItem.values());
		return out;
	}

	/** Every bundle on disk, whoever bought it - for restoring purchases. */
	public static List<CapeEntry> listAllBundles() {
		VeloPaths.ensureDirectories();
		List<CapeEntry> capes = new ArrayList<>();
		try (var files = Files.list(VeloPaths.capes())) {
			for (Path file : files.filter(p -> p.toString().endsWith(".velocape")).toList()) {
				cachedMetadata(file).ifPresent(capes::add);
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
			throw new IOException("Cloak textures must keep the 2:1 cloak ratio (e.g. 64x32, 128x64, ... up to "
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
			zip.write(GSON.toJson(new Meta(id, name, null)).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return new CapeEntry(id, name, bundleFile, preset, false);
	}

	/**
	 * Imports a cloak texture already in the 64 x 32 layout (from the upload dialog, after {@code CapeArt} detected or
	 * converted it): a square texture is a cloak on top and its elytra below, written as texture.png + elytra.png like
	 * the game does.
	 */
	public static CapeEntry importTexture(String name, BufferedImage texture, CapePhysicsPresetData preset) throws IOException {
		int width = texture.getWidth();
		boolean combined = texture.getHeight() == width;
		if (width < TEXTURE_WIDTH || width > MAX_TEXTURE_WIDTH || width % TEXTURE_WIDTH != 0 || (!combined && texture.getHeight() * 2 != width)) {
			throw new IOException("Not a cloak texture (" + width + "x" + texture.getHeight() + ").");
		}
		VeloPaths.ensureDirectories();
		String id = UUID.randomUUID().toString();
		Path bundleFile = VeloPaths.capes().resolve(sanitize(name) + "-" + id.substring(0, 8) + ".velocape");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundleFile))) {
			zip.putNextEntry(new ZipEntry("texture.png"));
			ImageIO.write(texture.getSubimage(0, 0, width, width / 2), "png", zip);
			zip.closeEntry();
			if (combined) {
				zip.putNextEntry(new ZipEntry("elytra.png"));
				ImageIO.write(texture.getSubimage(0, width / 2, width, width / 2), "png", zip);
				zip.closeEntry();
			}

			zip.putNextEntry(new ZipEntry("physics.json"));
			zip.write(GSON.toJson(preset).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();

			zip.putNextEntry(new ZipEntry("meta.json"));
			zip.write(GSON.toJson(new Meta(id, name, null)).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return new CapeEntry(id, name, bundleFile, preset, false);
	}

	/** Imports an animated GIF (a Store cape's bundled art, or a Velo+ member's own GIF when {@code storeItemId} is null) as a new library cape - the animated counterpart of {@link #importPng}. */
	public static CapeEntry importAnimatedGif(String name, InputStream gifBytes, CapePhysicsPresetData preset, String storeItemId)
			throws IOException {
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
			// sourceItemId marks a Store cape; without one it's a Velo+ member's own GIF ("custom").
			Meta meta = new Meta(id, name, storeItemId);
			meta.custom = storeItemId == null;
			zip.write(GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return new CapeEntry(id, name, bundleFile, preset, true);
	}

	/** SHA-256 of an animated bundle's frames.gif, remembered per file + modification time (hashed once). */
	private static final java.util.Map<String, String> GIF_SHA = new java.util.concurrent.ConcurrentHashMap<>();

	public static String gifSha(CapeEntry entry) throws IOException {
		Path file = entry.bundleFile();
		String key = file + "@" + Files.getLastModifiedTime(file).toMillis();
		String known = GIF_SHA.get(key);
		if (known != null) {
			return known;
		}
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
			ZipEntry gif = zip.getEntry("frames.gif");
			if (gif == null) {
				return null;
			}
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			try (var in = zip.getInputStream(gif)) {
				byte[] buffer = new byte[1 << 16];
				for (int n; (n = in.read(buffer)) > 0; ) {
					digest.update(buffer, 0, n);
				}
			}
			String sha = java.util.HexFormat.of().formatHex(digest.digest());
			GIF_SHA.put(key, sha);
			return sha;
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	/**
	 * Swaps a Store cloak's art in place (the server changed it): same bundle, id, physics and meta - only frames.gif is
	 * new, so equips and settings stay.
	 */
	public static void replaceGif(CapeEntry entry, InputStream gifBytes) throws IOException {
		Path file = entry.bundleFile();
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (java.util.zip.ZipFile old = new java.util.zip.ZipFile(file.toFile());
				ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
			zip.putNextEntry(new ZipEntry("frames.gif"));
			gifBytes.transferTo(zip);
			zip.closeEntry();
			var entries = old.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				if (e.getName().equals("frames.gif")) {
					continue;
				}
				zip.putNextEntry(new ZipEntry(e.getName()));
				try (var in = old.getInputStream(e)) {
					in.transferTo(zip);
				}
				zip.closeEntry();
			}
		} catch (IOException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	public static void exportBundle(CapeEntry entry, Path destination) throws IOException {
		if (entry.animated()) {
			throw new IOException("Animated cloaks are Velo Store items and can't be exported");
		}
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
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(entry.bundleFile().toFile())) {
			ZipEntry zipEntry = zip.getEntry(entryName);
			if (zipEntry != null) {
				try (var in = zip.getInputStream(zipEntry)) {
					return in.readAllBytes();
				}
			}
		}
		throw new IOException("Cloak bundle is missing its " + entryName + ": " + entry.bundleFile());
	}

	public static void delete(CapeEntry entry) throws IOException {
		Files.deleteIfExists(entry.bundleFile());
		if (entry.id().equals(equippedCapeId().orElse(null))) {
			unequip();
		}
	}

	public static Optional<String> equippedCapeId() {
		Path file = VeloPaths.accountConfig(EQUIP_STATE_FILE);
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

	/** Runs after the equipped cape changed (the Locker keeps the worn loadout in step with it). */
	public static final java.util.List<Runnable> EQUIP_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

	public static void equip(String capeId) {
		saveEquipState(capeId);
	}

	public static void unequip() {
		saveEquipState(null);
	}

	private static void saveEquipState(String capeId) {
		VeloPaths.ensureDirectories();
		Path file = VeloPaths.accountConfig(EQUIP_STATE_FILE);
		try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(new EquipState(capeId), writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save cloak equip state", e);
		}
		EQUIP_LISTENERS.forEach(Runnable::run);
	}

	/** A bundle's metadata as of its size + modified time - every page rebuild (equip, select...) used to unzip every cape again. */
	private record CachedMeta(long modified, long size, Optional<CapeEntry> entry) {
	}

	private static final java.util.Map<Path, CachedMeta> META_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	private static Optional<CapeEntry> cachedMetadata(Path bundleFile) {
		try {
			long modified = Files.getLastModifiedTime(bundleFile).toMillis();
			long size = Files.size(bundleFile);
			CachedMeta cached = META_CACHE.get(bundleFile);
			if (cached != null && cached.modified() == modified && cached.size() == size) {
				return cached.entry();
			}
			Optional<CapeEntry> entry = readMetadata(bundleFile);
			META_CACHE.put(bundleFile, new CachedMeta(modified, size, entry));
			return entry;
		} catch (IOException e) {
			return readMetadata(bundleFile);
		}
	}

	private static Optional<CapeEntry> readMetadata(Path bundleFile) {
		Meta meta = null;
		CapePhysicsPresetData preset = CapePhysicsPresetData.defaults();
		boolean animated = false;
		// ZipFile reads the central directory at the end and then only the two small entries. ZipInputStream streamed
		// through the whole bundle (textures, GIF frames) to find them: 320 MB for 53 cloaks = the Locker's 1.6 s first open.
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(bundleFile.toFile())) {
			ZipEntry metaEntry = zip.getEntry("meta.json");
			if (metaEntry != null) {
				try (var in = new InputStreamReader(zip.getInputStream(metaEntry), StandardCharsets.UTF_8)) {
					meta = GSON.fromJson(in, Meta.class);
				}
			}
			ZipEntry physicsEntry = zip.getEntry("physics.json");
			if (physicsEntry != null) {
				try (var in = new InputStreamReader(zip.getInputStream(physicsEntry), StandardCharsets.UTF_8)) {
					CapePhysicsPresetData read = GSON.fromJson(in, CapePhysicsPresetData.class);
					if (read != null) {
						preset = read;
					}
				}
			}
			animated = zip.getEntry("frames.gif") != null;
		} catch (IOException e) {
			return Optional.empty();
		}
		if (meta == null || (animated && meta.sourceItemId == null && !meta.custom)) {
			// Animated capes come from the Velo Store (it records the item id) or a Velo+ member's own GIF import.
			return Optional.empty();
		}
		SOURCE_ITEMS.put(bundleFile, meta.sourceItemId == null ? "" : meta.sourceItemId);
		return Optional.of(new CapeEntry(meta.id, meta.name, bundleFile, preset, animated));
	}

	private static String sanitize(String name) {
		return name.toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
	}

	private static final class Meta {
		String id;
		String name;
		String sourceItemId;
		boolean custom;

		Meta(String id, String name, String sourceItemId) {
			this.id = id;
			this.name = name;
			this.sourceItemId = sourceItemId;
		}
	}

	private static final class EquipState {
		String equippedCapeId;

		EquipState(String equippedCapeId) {
			this.equippedCapeId = equippedCapeId;
		}
	}
}
