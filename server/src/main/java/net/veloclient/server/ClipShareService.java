package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Clips Pro share links: Velo+ members upload a clip (H.264 MP4 made by the game) and get a link,
 * {@code https://velo-client.com/c/<id>}, that plays in any browser and embeds in Discord.
 *
 * <p>Files live in {@code <data>/clips/<id>.mp4} (+ {@code .jpg} thumbnail), the list in {@code clips.json}. Uploads
 * stream straight to disk (never held in memory). The game compresses them hard first (AV1, ~1.5 Mbit/s, at most 2
 * minutes), so a clip is usually a few MB. Limits per member: {@link #MAX_CLIP_BYTES} per clip, {@link #MAX_USER_BYTES}
 * and {@link #MAX_USER_CLIPS} in total. For the whole server: at most {@code maxTotalBytes} ({@code VELO_CLIPS_MAX_GB},
 * default 40 GB) and never below {@link #MIN_FREE_DISK} free - when a new clip needs room, the clips nobody has watched
 * for the longest go first (never one younger than a day). Clips of players who stopped being members more than
 * {@link #KEEP_AFTER_PLUS_MILLIS} ago are deleted by the sweep, and every clip is deleted once it's older than the
 * retention ({@code VELO_CLIPS_RETENTION_DAYS}, default 60 days) - the game and the launcher say so when sharing.
 *
 * <p>{@code clips-stats.json} counts every upload ever (and per day), for the owner panel - the list itself only has
 * the clips still kept.
 */
final class ClipShareService {

	static final long MAX_CLIP_BYTES = 40L * 1024 * 1024;
	static final long MAX_USER_BYTES = 500L * 1024 * 1024;
	static final long MIN_FREE_DISK = 10L * 1024 * 1024 * 1024;
	private final long minFreeDisk;
	private static final long PROTECT_NEW_MILLIS = 86_400_000L;
	static final int MAX_USER_CLIPS = 100;
	static final int MAX_THUMB_BYTES = 1024 * 1024;
	static final long KEEP_AFTER_PLUS_MILLIS = 30L * 86_400_000L;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String ALPHABET = "0123456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";

	/** A shared clip. {@code server} only when the uploader left it in. */
	record Clip(String id, String uuid, String username, String title, long createdAt, double seconds, int width, int height, int fps,
			long size, String trigger, String server, long views, boolean thumb, long lastViewedAt) {

		Clip withViews(long count) {
			return new Clip(id, uuid, username, title, createdAt, seconds, width, height, fps, size, trigger, server, count, thumb,
					System.currentTimeMillis());
		}

		Clip withThumb() {
			return new Clip(id, uuid, username, title, createdAt, seconds, width, height, fps, size, trigger, server, views, true, lastViewedAt);
		}

		Clip withTitle(String newTitle) {
			return new Clip(id, uuid, username, newTitle, createdAt, seconds, width, height, fps, size, trigger, server, views, thumb, lastViewedAt);
		}

		/** When it was last watched (or made, if never). */
		long lastSeen() {
			return Math.max(createdAt, lastViewedAt);
		}
	}

	/** What the uploader tells us about the video. */
	record Meta(String title, double seconds, int width, int height, int fps, String trigger, String server) {
	}

	private final Path dir;
	private final Path indexFile;
	private final Map<String, Clip> clips = new ConcurrentHashMap<>();
	private final Map<String, Boolean> uploading = new ConcurrentHashMap<>();
	private volatile boolean dirty;
	private final long maxTotalBytes;
	static final int DEFAULT_RETENTION_DAYS = 60;
	private final long retentionMillis;
	private final Path statsFile;

	/** Uploads ever: total and per UTC day ("2026-10-22" -> {clips, bytes}). */
	static final class Stats {
		long uploads;
		long uploadedBytes;
		long expired;
		long evicted;
		long removedByStaff;
		java.util.TreeMap<String, long[]> byDay = new java.util.TreeMap<>();
	}

	private Stats stats = new Stats();

	ClipShareService(Path dataDir) throws IOException {
		this(dataDir, 40L * 1024 * 1024 * 1024, MIN_FREE_DISK);
	}

	ClipShareService(Path dataDir, long maxTotalBytes, long minFreeDisk) throws IOException {
		this(dataDir, maxTotalBytes, minFreeDisk, DEFAULT_RETENTION_DAYS);
	}

	ClipShareService(Path dataDir, long maxTotalBytes, long minFreeDisk, int retentionDays) throws IOException {
		this.maxTotalBytes = maxTotalBytes;
		this.minFreeDisk = minFreeDisk;
		this.retentionMillis = Math.max(1, retentionDays) * 86_400_000L;
		this.dir = dataDir.resolve("clips");
		this.indexFile = dataDir.resolve("clips.json");
		this.statsFile = dataDir.resolve("clips-stats.json");
		if (Files.exists(statsFile)) {
			Stats loaded = GSON.fromJson(Files.readString(statsFile, StandardCharsets.UTF_8), Stats.class);
			if (loaded != null) {
				stats = loaded;
				if (stats.byDay == null) {
					stats.byDay = new java.util.TreeMap<>();
				}
			}
		}
		Files.createDirectories(dir);
		if (Files.exists(indexFile)) {
			Map<String, Clip> loaded = GSON.fromJson(Files.readString(indexFile, StandardCharsets.UTF_8), new TypeToken<Map<String, Clip>>() { }.getType());
			if (loaded != null) {
				clips.putAll(loaded);
			}
		}
	}

	Clip get(String id) {
		return id == null ? null : clips.get(id);
	}

	/** A member's clips, newest first. */
	List<Clip> of(String uuid) {
		List<Clip> out = new ArrayList<>(clips.values().stream().filter(c -> c.uuid().equals(uuid)).toList());
		out.sort(Comparator.comparingLong(Clip::createdAt).reversed());
		return out;
	}

	long usedBytes(String uuid) {
		return clips.values().stream().filter(c -> c.uuid().equals(uuid)).mapToLong(Clip::size).sum();
	}

	/** Streams the upload ({@code length} bytes, or -1 if unknown) to disk and lists it. */
	Clip upload(String uuid, String username, Meta meta, InputStream body, long length) throws ApiError, IOException {
		if (length > MAX_CLIP_BYTES) {
			throw new ApiError(413, "A shared clip can be at most " + MAX_CLIP_BYTES / 1024 / 1024 + " MB - trim it first");
		}
		if (of(uuid).size() >= MAX_USER_CLIPS) {
			throw new ApiError(413, "You have " + MAX_USER_CLIPS + " shared clips - delete some first");
		}
		long room = MAX_USER_BYTES - usedBytes(uuid);
		if (length > room) {
			throw new ApiError(413, "Not enough space left for shared clips - delete some first");
		}
		makeRoom(length > 0 ? length : MAX_CLIP_BYTES);
		if (uploading.putIfAbsent(uuid, Boolean.TRUE) != null) {
			throw new ApiError(429, "Another clip is still uploading");
		}
		Path temp = Files.createTempFile(dir, "upload-", ".tmp");
		try {
			long size = copyLimited(body, temp, Math.min(MAX_CLIP_BYTES, room));
			if (!isMp4(temp)) {
				throw new ApiError(400, "Not an MP4 video");
			}
			String id = newId();
			Files.move(temp, dir.resolve(id + ".mp4"), StandardCopyOption.ATOMIC_MOVE);
			String title = CloudBackupService.clean(meta.title(), 80);
			Clip clip = new Clip(id, uuid, username, title == null ? "Clip by " + username : title, System.currentTimeMillis(),
					Math.max(0, Math.min(3600, meta.seconds())), clamp(meta.width(), 0, 7680), clamp(meta.height(), 0, 4320), clamp(meta.fps(), 0, 240),
					size, CloudBackupService.clean(meta.trigger(), 16), CloudBackupService.clean(meta.server(), 80), 0, false, 0);
			clips.put(id, clip);
			countUpload(size);
			save();
			return clip;
		} finally {
			uploading.remove(uuid);
			Files.deleteIfExists(temp);
		}
	}

	long totalBytes() {
		return clips.values().stream().mapToLong(Clip::size).sum();
	}

	/**
	 * Frees room for {@code needed} more bytes: under the server-wide cap and above the free-disk floor, by deleting the
	 * least recently watched clips (never one less than a day old). Throws when it can't.
	 */
	synchronized void makeRoom(long needed) throws ApiError, IOException {
		List<Clip> byAge = new ArrayList<>(clips.values());
		byAge.sort(Comparator.comparingLong(Clip::lastSeen));
		long now = System.currentTimeMillis();
		int next = 0;
		while (totalBytes() + needed > maxTotalBytes || Files.getFileStore(dir).getUsableSpace() - needed < minFreeDisk) {
			while (next < byAge.size() && now - byAge.get(next).createdAt() < PROTECT_NEW_MILLIS) {
				next++;
			}
			if (next >= byAge.size()) {
				throw new ApiError(507, "Clip storage is full right now - try again later");
			}
			Clip victim = byAge.get(next++);
			clips.remove(victim.id());
			Files.deleteIfExists(dir.resolve(victim.id() + ".mp4"));
			Files.deleteIfExists(dir.resolve(victim.id() + ".jpg"));
			System.out.println("[clips] Made room: removed " + victim.id() + " (" + victim.size() / 1024 + " KB, last watched " + victim.lastSeen() + ")");
			synchronized (this) {
				stats.evicted++;
			}
		}
		save();
	}

	Clip thumbnail(String uuid, String id, byte[] jpeg) throws ApiError, IOException {
		Clip clip = owned(uuid, id, false);
		if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
			throw new ApiError(400, "The thumbnail must be a JPEG");
		}
		Files.write(dir.resolve(id + ".jpg"), jpeg);
		Clip changed = clip.withThumb();
		clips.put(id, changed);
		save();
		return changed;
	}

	Clip rename(String uuid, String id, String title) throws ApiError, IOException {
		Clip clip = owned(uuid, id, false);
		String clean = CloudBackupService.clean(title, 80);
		Clip changed = clip.withTitle(clean == null ? clip.title() : clean);
		clips.put(id, changed);
		save();
		return changed;
	}

	/** {@code asOwner}: the server owner may delete anyone's clip. */
	void delete(String uuid, String id, boolean asOwner) throws ApiError, IOException {
		owned(uuid, id, asOwner);
		clips.remove(id);
		Files.deleteIfExists(dir.resolve(id + ".mp4"));
		Files.deleteIfExists(dir.resolve(id + ".jpg"));
		save();
	}

	Path video(String id) {
		Clip clip = get(id);
		return clip == null ? null : dir.resolve(clip.id() + ".mp4");
	}

	Path thumb(String id) {
		Clip clip = get(id);
		return clip == null || !clip.thumb() ? null : dir.resolve(clip.id() + ".jpg");
	}

	void countView(String id) {
		clips.computeIfPresent(id, (k, c) -> c.withViews(c.views() + 1));
		dirty = true;
	}

	/** Saves view counts now and then, and deletes expired clips and the clips of players who are no longer kept. */
	int sweep(Predicate<String> stillKept) throws IOException {
		int removed = 0;
		long now = System.currentTimeMillis();
		for (Clip clip : List.copyOf(clips.values())) {
			boolean expired = now >= expiresAt(clip);
			if (expired || !stillKept.test(clip.uuid())) {
				if (expired) {
					synchronized (this) {
						stats.expired++;
					}
				}
				clips.remove(clip.id());
				Files.deleteIfExists(dir.resolve(clip.id() + ".mp4"));
				Files.deleteIfExists(dir.resolve(clip.id() + ".jpg"));
				removed++;
			}
		}
		if (removed > 0 || dirty) {
			save();
			saveStats();
		}
		return removed;
	}

	/** When the clip gets deleted on its own. */
	long expiresAt(Clip clip) {
		return clip.createdAt() + retentionMillis;
	}

	int retentionDays() {
		return (int) (retentionMillis / 86_400_000L);
	}

	long maxTotalBytes() {
		return maxTotalBytes;
	}

	long freeDisk() {
		try {
			return Files.getFileStore(dir).getUsableSpace();
		} catch (IOException e) {
			return -1;
		}
	}

	/** Every kept clip, newest first. */
	List<Clip> all() {
		List<Clip> out = new ArrayList<>(clips.values());
		out.sort(Comparator.comparingLong(Clip::createdAt).reversed());
		return out;
	}

	/** Staff removed it (reported, or found in the clip list). */
	Clip removeByStaff(String id) throws ApiError, IOException {
		Clip clip = owned(null, id, true);
		delete(null, id, true);
		synchronized (this) {
			stats.removedByStaff++;
		}
		saveStats();
		return clip;
	}

	synchronized Stats stats() {
		Stats copy = GSON.fromJson(GSON.toJson(stats), Stats.class);
		return copy;
	}

	private synchronized void countUpload(long size) {
		stats.uploads++;
		stats.uploadedBytes += size;
		String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
		long[] d = stats.byDay.computeIfAbsent(day, k -> new long[2]);
		d[0]++;
		d[1] += size;
		while (stats.byDay.size() > 400) {
			stats.byDay.remove(stats.byDay.firstKey());
		}
		saveStats();
	}

	private synchronized void saveStats() {
		try {
			Path temp = Files.createTempFile(statsFile.getParent(), "clips-stats-", ".json.tmp");
			Files.writeString(temp, GSON.toJson(stats), StandardCharsets.UTF_8);
			Files.move(temp, statsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[clips] Couldn't save clips-stats.json: " + e.getMessage());
		}
	}

	private Clip owned(String uuid, String id, boolean asOwner) throws ApiError {
		Clip clip = get(id);
		if (clip == null || !asOwner && !clip.uuid().equals(uuid)) {
			throw new ApiError(404, "No such clip");
		}
		return clip;
	}

	private synchronized void save() throws IOException {
		dirty = false;
		Path temp = Files.createTempFile(indexFile.getParent(), "clips-", ".json.tmp");
		Files.writeString(temp, GSON.toJson(clips), StandardCharsets.UTF_8);
		Files.move(temp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static long copyLimited(InputStream in, Path target, long max) throws ApiError, IOException {
		long total = 0;
		byte[] buffer = new byte[256 * 1024];
		try (OutputStream out = Files.newOutputStream(target)) {
			int read;
			while ((read = in.read(buffer)) > 0) {
				total += read;
				if (total > max) {
					throw new ApiError(413, "Clip too big - trim it first");
				}
				out.write(buffer, 0, read);
			}
		}
		if (total == 0) {
			throw new ApiError(400, "Empty upload");
		}
		return total;
	}

	/** ISO base media file: a box named {@code ftyp} right at the start. */
	private static boolean isMp4(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			byte[] head = in.readNBytes(12);
			return head.length == 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private String newId() {
		String id;
		do {
			StringBuilder sb = new StringBuilder(10);
			for (int i = 0; i < 10; i++) {
				sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
			}
			id = sb.toString();
		} while (clips.containsKey(id));
		return id;
	}
}
