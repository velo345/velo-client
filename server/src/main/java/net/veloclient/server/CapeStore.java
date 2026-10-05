package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-uploaded custom capes - the one piece of server state that IS persisted, since unlike
 * "who's online" an uploaded cape should survive a restart. Each account has at most one custom
 * cape; files are content-addressed ({@code <sha256>.png/.gif}) so clients can cache them forever
 * and verify what they downloaded, and identical uploads share one file.
 *
 * <p>Every upload is decoded with {@link ImageIO} before it's accepted - a PNG is then re-encoded
 * from the decoded pixels (so whatever else was in the original file, metadata chunks or anything
 * crafted, never reaches other players), a GIF is size/frame-count checked frame by frame. The
 * dimension limits match what the mod can render: vanilla's 64x32 cape layout scaled up to
 * 2048x1024 (2x height for the combined cape+elytra layout), and much smaller for animated capes
 * because every frame is held decoded in each viewer's memory.
 */
final class CapeStore {

	static final String CUSTOM_PREFIX = "custom:";
	static final int MAX_UPLOAD_BYTES = 6 * 1024 * 1024;

	private static final int MAX_STATIC_WIDTH = 2048;
	private static final int MAX_ANIMATED_WIDTH = 512;
	private static final int MAX_ANIMATED_FRAMES = 128;
	// Decoded pixel budget for one animated cape (width * height * frames) - ~64 MB of RGBA.
	private static final long MAX_ANIMATED_PIXELS = 16L * 1024 * 1024;
	private static final long MIN_UPLOAD_INTERVAL_MILLIS = 10_000;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	record StoredCape(String hash, String type) {
		String fileName() {
			return hash + "." + type;
		}

		String contentType() {
			return "gif".equals(type) ? "image/gif" : "image/png";
		}
	}

	private final Path directory;
	private final Path indexFile;
	private final Path bannedFile;
	private final java.util.Set<String> bannedHashes = ConcurrentHashMap.newKeySet();
	private final Map<String, StoredCape> byUuid = new ConcurrentHashMap<>();
	private final Map<String, Long> lastUploadMillis = new ConcurrentHashMap<>();

	CapeStore(Path dataDirectory) throws IOException {
		this.directory = dataDirectory.resolve("capes");
		this.indexFile = dataDirectory.resolve("capes.json");
		this.bannedFile = dataDirectory.resolve("banned-capes.json");
		Files.createDirectories(directory);
		if (Files.exists(bannedFile)) {
			java.util.List<String> banned = GSON.fromJson(Files.readString(bannedFile, StandardCharsets.UTF_8),
					new TypeToken<java.util.List<String>>() { }.getType());
			if (banned != null) {
				bannedHashes.addAll(banned);
			}
		}
		if (Files.exists(indexFile)) {
			Map<String, StoredCape> loaded = GSON.fromJson(Files.readString(indexFile, StandardCharsets.UTF_8),
					new TypeToken<Map<String, StoredCape>>() { }.getType());
			if (loaded != null) {
				byUuid.putAll(loaded);
			}
		}
	}

	/** Thrown for anything the uploader got wrong (bad format, too big, too frequent) - becomes a 400/429. */
	static final class RejectedUpload extends Exception {
		final int status;

		RejectedUpload(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	String hashFor(String uuid) {
		StoredCape cape = byUuid.get(uuid);
		// GIFs uploaded before animated capes became Store-only are no longer shown to anyone.
		return cape == null || "gif".equals(cape.type()) ? null : cape.hash();
	}

	/** Validates, normalizes, stores and assigns {@code bytes} as {@code uuid}'s custom cape; returns its hash. */
	synchronized String store(String uuid, byte[] bytes) throws RejectedUpload, IOException {
		long now = System.currentTimeMillis();
		Long last = lastUploadMillis.get(uuid);
		if (last != null && now - last < MIN_UPLOAD_INTERVAL_MILLIS) {
			throw new RejectedUpload(429, "Uploading too fast - wait a few seconds");
		}
		lastUploadMillis.put(uuid, now);

		StoredCape cape;
		byte[] normalized;
		if (isGif(bytes)) {
			// Animated capes are Velo Store items (shared by item id, ownership checked) - uploading
			// your own would make buying them pointless.
			throw new RejectedUpload(403, "Animated capes are only available from the Velo Store");
		} else if (isPng(bytes)) {
			normalized = reencodePng(bytes);
			cape = new StoredCape(sha256(normalized), "png");
		} else {
			throw new RejectedUpload(400, "Only PNG capes are supported");
		}

		if (bannedHashes.contains(cape.hash())) {
			throw new RejectedUpload(403, "This cape was removed by a server admin");
		}
		Path target = directory.resolve(cape.fileName());
		if (!Files.exists(target)) {
			Path temp = Files.createTempFile(directory, "upload-", ".tmp");
			Files.write(temp, normalized);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		StoredCape previous = byUuid.put(uuid, cape);
		saveIndex();
		if (previous != null && !previous.hash().equals(cape.hash())) {
			deleteIfUnreferenced(previous);
		}
		return cape.hash();
	}

	/** Moderation removal: also bans the exact image so the same file can't simply be uploaded again. */
	synchronized boolean removeAndBan(String uuid) throws IOException {
		StoredCape cape = byUuid.get(uuid);
		if (cape == null) {
			return false;
		}
		bannedHashes.add(cape.hash());
		Path temp = Files.createTempFile(bannedFile.getParent(), "banned-", ".json.tmp");
		Files.writeString(temp, GSON.toJson(bannedHashes), StandardCharsets.UTF_8);
		Files.move(temp, bannedFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		return remove(uuid);
	}

	synchronized boolean remove(String uuid) throws IOException {
		StoredCape removed = byUuid.remove(uuid);
		if (removed == null) {
			return false;
		}
		saveIndex();
		deleteIfUnreferenced(removed);
		return true;
	}

	/** Raw bytes + content type for a stored hash, or null if nobody currently has that cape. */
	StoredFile read(String hash) throws IOException {
		if (hash == null || !hash.matches("[0-9a-f]{64}")) {
			return null;
		}
		for (StoredCape cape : byUuid.values()) {
			if (cape.hash().equals(hash) && !"gif".equals(cape.type())) {
				Path file = directory.resolve(cape.fileName());
				return Files.exists(file) ? new StoredFile(Files.readAllBytes(file), cape.contentType()) : null;
			}
		}
		return null;
	}

	record StoredFile(byte[] bytes, String contentType) {
	}

	private void deleteIfUnreferenced(StoredCape cape) throws IOException {
		boolean stillUsed = byUuid.values().stream().anyMatch(c -> c.hash().equals(cape.hash()));
		if (!stillUsed) {
			Files.deleteIfExists(directory.resolve(cape.fileName()));
		}
	}

	private void saveIndex() throws IOException {
		Path temp = Files.createTempFile(indexFile.getParent(), "capes-", ".json.tmp");
		Files.writeString(temp, GSON.toJson(byUuid), StandardCharsets.UTF_8);
		Files.move(temp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Cape layout is 2:1 (cape only) or 1:1 (cape on top, elytra below), scaled from vanilla's 64x32. */
	private static void validateDimensions(int width, int height, int maxWidth) throws RejectedUpload {
		if (width < 64 || width > maxWidth || width % 64 != 0) {
			throw new RejectedUpload(400, "Cape width must be a multiple of 64 between 64 and " + maxWidth + " (got " + width + ")");
		}
		if (height != width / 2 && height != width) {
			throw new RejectedUpload(400, "Cape must be 2:1 (cape) or 1:1 (cape + elytra) - got " + width + "x" + height);
		}
	}

	private static byte[] reencodePng(byte[] bytes) throws RejectedUpload, IOException {
		BufferedImage image;
		try {
			image = ImageIO.read(new ByteArrayInputStream(bytes));
		} catch (IOException | RuntimeException e) {
			throw new RejectedUpload(400, "Not a readable PNG");
		}
		if (image == null) {
			throw new RejectedUpload(400, "Not a readable PNG");
		}
		validateDimensions(image.getWidth(), image.getHeight(), MAX_STATIC_WIDTH);
		BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
		argb.getGraphics().drawImage(image, 0, 0, null);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(argb, "png", out);
		return out.toByteArray();
	}

	private static boolean isPng(byte[] b) {
		return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
	}

	private static boolean isGif(byte[] b) {
		return b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
