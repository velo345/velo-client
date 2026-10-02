package net.veloclient.launcher.launch;

import net.veloclient.launcher.data.VeloPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which game files (libraries, client jar, assets) were already verified against their
 * SHA-1, together with the size and modification time they had then. A file whose size and mtime
 * are unchanged doesn't get re-hashed on the next launch - previously every launch re-read and
 * hashed every one of them (~20,000 files, 1+ GB), several seconds of pure disk I/O.
 * Any change to a file (size or mtime) means it's hashed again, so a damaged or replaced file
 * is still caught.
 */
final class VerifiedFileIndex {

	private static final Map<String, String> ENTRIES = new ConcurrentHashMap<>();
	private static volatile boolean loaded;
	private static volatile boolean dirty;

	private VerifiedFileIndex() {
	}

	private static Path file() {
		return VeloPaths.root().resolve("cache").resolve("verified-files.tsv");
	}

	private static void loadOnce() {
		if (loaded) {
			return;
		}
		synchronized (VerifiedFileIndex.class) {
			if (loaded) {
				return;
			}
			try {
				if (Files.exists(file())) {
					for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
						int tab = line.indexOf('\t');
						if (tab > 0) {
							ENTRIES.put(line.substring(0, tab), line.substring(tab + 1));
						}
					}
				}
			} catch (IOException ignored) {
				// Start fresh - files just get hashed once more.
			}
			loaded = true;
		}
	}

	private static String stamp(long size, long mtimeMillis, String sha1) {
		return size + "|" + mtimeMillis + "|" + sha1.toLowerCase();
	}

	static boolean isVerified(Path path, long size, long mtimeMillis, String sha1) {
		loadOnce();
		return stamp(size, mtimeMillis, sha1).equals(ENTRIES.get(path.toAbsolutePath().toString()));
	}

	static void markVerified(Path path, String sha1) {
		loadOnce();
		try {
			ENTRIES.put(path.toAbsolutePath().toString(),
					stamp(Files.size(path), Files.getLastModifiedTime(path).toMillis(), sha1));
			dirty = true;
		} catch (IOException ignored) {
			// Not recorded; it's verified again next time.
		}
	}

	static synchronized void save() {
		if (!dirty) {
			return;
		}
		dirty = false;
		try {
			Files.createDirectories(file().getParent());
			Path temp = file().resolveSibling("verified-files.tsv.tmp");
			try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				for (Map.Entry<String, String> entry : ENTRIES.entrySet()) {
					writer.write(entry.getKey());
					writer.write('\t');
					writer.write(entry.getValue());
					writer.newLine();
				}
			}
			Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
			dirty = true;
		}
	}
}
