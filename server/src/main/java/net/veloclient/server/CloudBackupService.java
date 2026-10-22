package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Velo+ Cloud Sync: each member's restore points - zips of their Velo settings, HUD layouts, profiles, waypoints and
 * keybinds that the game makes (see the mod's {@code CloudSync}). Stored under {@code <data>/cloud/<uuid>/}: one
 * {@code <id>.zip} per restore point and an {@code index.json} with what's in it.
 *
 * <p>Limits: {@link #MAX_BACKUP_BYTES} per backup, {@link #MAX_COUNT} restore points and {@link #MAX_TOTAL_BYTES} per
 * member. A new backup first pushes out the oldest automatic ones, then the oldest unpinned; pinned ones are only ever
 * deleted by their owner. The server never looks inside beyond checking it's a sane zip (no paths that climb out, no zip
 * bombs). Backups of players whose membership ended more than {@link #KEEP_AFTER_PLUS_MILLIS} ago are deleted.
 */
final class CloudBackupService {

	static final int MAX_BACKUP_BYTES = 4 * 1024 * 1024;
	static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;
	static final int MAX_COUNT = 30;
	static final int MAX_PINNED = 10;
	static final long KEEP_AFTER_PLUS_MILLIS = 90L * 86_400_000L;
	private static final long MAX_UNPACKED_BYTES = 32L * 1024 * 1024;
	private static final int MAX_ENTRIES = 2000;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final SecureRandom RANDOM = new SecureRandom();

	/** One restore point. {@code auto}: made by the game on its own (not "Back up now"). */
	record Backup(String id, long createdAt, long size, String sha256, String device, String label, boolean auto, boolean pinned, int files) {
	}

	private final Path root;
	private final Map<String, Object> locks = new ConcurrentHashMap<>();

	CloudBackupService(Path dataDir) throws IOException {
		this.root = dataDir.resolve("cloud");
		Files.createDirectories(root);
	}

	private Object lock(String uuid) {
		return locks.computeIfAbsent(uuid, k -> new Object());
	}

	private Path dir(String uuid) throws ApiError {
		if (uuid == null || !uuid.matches("[0-9a-f]{32}")) {
			throw new ApiError(400, "Bad account");
		}
		return root.resolve(uuid);
	}

	/** Newest first. */
	List<Backup> list(String uuid) throws ApiError, IOException {
		synchronized (lock(uuid)) {
			List<Backup> all = new ArrayList<>(read(uuid));
			all.sort(Comparator.comparingLong(Backup::createdAt).reversed());
			return all;
		}
	}

	long usedBytes(String uuid) throws ApiError, IOException {
		return list(uuid).stream().mapToLong(Backup::size).sum();
	}

	/**
	 * Stores a new restore point. If it's byte-for-byte the newest one already there, that one is returned instead of
	 * keeping a copy.
	 */
	Backup add(String uuid, byte[] zip, String device, String label, boolean auto) throws ApiError, IOException {
		if (zip.length == 0 || zip.length > MAX_BACKUP_BYTES) {
			throw new ApiError(413, "A backup can be at most " + (MAX_BACKUP_BYTES / 1024 / 1024) + " MB");
		}
		int files = validate(zip);
		String sha = CapeStore.sha256(zip);
		synchronized (lock(uuid)) {
			List<Backup> all = new ArrayList<>(read(uuid));
			all.sort(Comparator.comparingLong(Backup::createdAt));
			if (!all.isEmpty() && all.getLast().sha256().equals(sha)) {
				return all.getLast();
			}
			// Make room: oldest automatic first, then oldest unpinned.
			while (all.size() >= MAX_COUNT || total(all) + zip.length > MAX_TOTAL_BYTES) {
				Backup victim = all.stream().filter(b -> b.auto() && !b.pinned()).findFirst()
						.orElse(all.stream().filter(b -> !b.pinned()).findFirst().orElse(null));
				if (victim == null) {
					throw new ApiError(413, "Your cloud is full of pinned restore points - unpin or delete some first");
				}
				all.remove(victim);
				Files.deleteIfExists(dir(uuid).resolve(victim.id() + ".zip"));
			}
			Backup backup = new Backup(newId(), System.currentTimeMillis(), zip.length, sha, clean(device, 40), clean(label, 60), auto, false, files);
			Files.createDirectories(dir(uuid));
			Path temp = Files.createTempFile(dir(uuid), "upload-", ".tmp");
			Files.write(temp, zip);
			Files.move(temp, dir(uuid).resolve(backup.id() + ".zip"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			all.add(backup);
			write(uuid, all);
			return backup;
		}
	}

	byte[] file(String uuid, String id) throws ApiError, IOException {
		synchronized (lock(uuid)) {
			find(uuid, id);
			return Files.readAllBytes(dir(uuid).resolve(id + ".zip"));
		}
	}

	void delete(String uuid, String id) throws ApiError, IOException {
		synchronized (lock(uuid)) {
			List<Backup> all = new ArrayList<>(read(uuid));
			Backup backup = find(uuid, id);
			all.removeIf(b -> b.id().equals(backup.id()));
			Files.deleteIfExists(dir(uuid).resolve(id + ".zip"));
			write(uuid, all);
		}
	}

	Backup pin(String uuid, String id, boolean pinned, String label) throws ApiError, IOException {
		synchronized (lock(uuid)) {
			List<Backup> all = new ArrayList<>(read(uuid));
			Backup backup = find(uuid, id);
			if (pinned && !backup.pinned() && all.stream().filter(Backup::pinned).count() >= MAX_PINNED) {
				throw new ApiError(400, "At most " + MAX_PINNED + " pinned restore points");
			}
			Backup changed = new Backup(backup.id(), backup.createdAt(), backup.size(), backup.sha256(), backup.device(),
					label == null ? backup.label() : clean(label, 60), backup.auto(), pinned, backup.files());
			all.replaceAll(b -> b.id().equals(id) ? changed : b);
			write(uuid, all);
			return changed;
		}
	}

	/** Deletes the backups of players who haven't been members for {@link #KEEP_AFTER_PLUS_MILLIS}. */
	int sweep(Predicate<String> stillKept) throws IOException {
		int removed = 0;
		try (Stream<Path> dirs = Files.list(root)) {
			for (Path dir : dirs.toList()) {
				String uuid = dir.getFileName().toString();
				if (uuid.matches("[0-9a-f]{32}") && !stillKept.test(uuid)) {
					synchronized (lock(uuid)) {
						try (Stream<Path> files = Files.walk(dir)) {
							for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
								Files.deleteIfExists(file);
							}
						}
					}
					removed++;
				}
			}
		}
		return removed;
	}

	private Backup find(String uuid, String id) throws ApiError, IOException {
		if (id == null || !id.matches("[0-9a-z]{12}")) {
			throw new ApiError(400, "Bad restore point id");
		}
		return read(uuid).stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow(() -> new ApiError(404, "No such restore point"));
	}

	private static long total(List<Backup> all) {
		return all.stream().mapToLong(Backup::size).sum();
	}

	/** A sane zip: readable, plain relative paths, not too many files, not a zip bomb. Returns how many files it has. */
	private static int validate(byte[] zip) throws ApiError {
		int files = 0;
		long unpacked = 0;
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry entry;
			byte[] buffer = new byte[16 * 1024];
			while ((entry = in.getNextEntry()) != null) {
				String name = entry.getName();
				if (name.startsWith("/") || name.startsWith("\\") || name.contains("..") || name.contains(":") || name.length() > 200) {
					throw new ApiError(400, "Backup has a bad file name");
				}
				if (++files > MAX_ENTRIES) {
					throw new ApiError(400, "Backup has too many files");
				}
				int read;
				while ((read = in.read(buffer)) > 0) {
					unpacked += read;
					if (unpacked > MAX_UNPACKED_BYTES) {
						throw new ApiError(400, "Backup unpacks too big");
					}
				}
			}
		} catch (IOException e) {
			throw new ApiError(400, "Not a readable backup");
		}
		if (files == 0) {
			throw new ApiError(400, "Empty backup");
		}
		return files;
	}

	private List<Backup> read(String uuid) throws ApiError, IOException {
		Path index = dir(uuid).resolve("index.json");
		if (!Files.exists(index)) {
			return List.of();
		}
		List<Backup> loaded = GSON.fromJson(Files.readString(index, StandardCharsets.UTF_8), new TypeToken<List<Backup>>() { }.getType());
		return loaded == null ? List.of() : loaded;
	}

	private void write(String uuid, List<Backup> all) throws ApiError, IOException {
		Files.createDirectories(dir(uuid));
		Path temp = Files.createTempFile(dir(uuid), "index-", ".tmp");
		Files.writeString(temp, GSON.toJson(all), StandardCharsets.UTF_8);
		Files.move(temp, dir(uuid).resolve("index.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static String newId() {
		String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
		StringBuilder sb = new StringBuilder(12);
		for (int i = 0; i < 12; i++) {
			sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
		}
		return sb.toString();
	}

	static String clean(String text, int max) {
		if (text == null) {
			return null;
		}
		String plain = text.replaceAll("[\\p{Cntrl}§]", "").strip();
		if (plain.isEmpty()) {
			return null;
		}
		return plain.length() > max ? plain.substring(0, max) : plain;
	}
}
