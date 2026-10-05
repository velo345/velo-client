package net.veloclient.launcher.world;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Copies a Minecraft world into a profile's {@code saves} folder - from a world folder or from a
 * {@code .zip} (as worlds are usually shared/downloaded). Finds the actual world inside: the folder
 * that holds {@code level.dat}, whether that's the picked folder itself or one level down (zips
 * almost always wrap the world in a folder). Never overwrites an existing world: a name that's taken
 * gets " (2)", " (3)"...
 */
public final class WorldImporter {

	/** Zips bigger than this (uncompressed) are refused - a world download, not a disk filler. */
	private static final long MAX_UNZIPPED_BYTES = 16L * 1024 * 1024 * 1024;

	private WorldImporter() {
	}

	/** Imports a world folder or {@code .zip}; returns the new world's folder name in {@code savesDir}. */
	public static String importWorld(Path source, Path savesDir) throws IOException {
		Files.createDirectories(savesDir);
		if (Files.isDirectory(source)) {
			Path world = findWorldRoot(source);
			if (world == null) {
				throw new IOException("That folder isn't a Minecraft world (no level.dat found)");
			}
			Path target = freeName(savesDir, world.getFileName().toString());
			copyTree(world, target);
			return target.getFileName().toString();
		}
		String name = source.getFileName().toString();
		if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
			throw new IOException("Pick a world folder or a .zip file");
		}
		Path temp = Files.createTempDirectory(savesDir, ".import-");
		try {
			unzip(source, temp);
			Path world = findWorldRoot(temp);
			if (world == null) {
				throw new IOException("That zip doesn't contain a Minecraft world (no level.dat found)");
			}
			String worldName = world.equals(temp) ? name.substring(0, name.length() - 4) : world.getFileName().toString();
			Path target = freeName(savesDir, worldName);
			try {
				Files.move(world, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				copyTree(world, target);
			}
			Files.deleteIfExists(target.resolve("session.lock"));
			return target.getFileName().toString();
		} finally {
			deleteTree(temp);
		}
	}

	/** The folder holding level.dat: {@code dir} itself, or a sub-folder up to two levels down. */
	private static Path findWorldRoot(Path dir) throws IOException {
		if (Files.isRegularFile(dir.resolve("level.dat"))) {
			return dir;
		}
		try (Stream<Path> walk = Files.walk(dir, 3)) {
			return walk.filter(p -> p.getFileName().toString().equals("level.dat") && Files.isRegularFile(p))
					.map(Path::getParent)
					.filter(p -> !p.getFileName().toString().startsWith("__MACOSX"))
					.min(Comparator.comparingInt(Path::getNameCount))
					.orElse(null);
		}
	}

	private static Path freeName(Path savesDir, String wanted) {
		String base = wanted.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
		if (base.isEmpty()) {
			base = "Imported World";
		}
		Path candidate = savesDir.resolve(base);
		for (int n = 2; Files.exists(candidate); n++) {
			candidate = savesDir.resolve(base + " (" + n + ")");
		}
		return candidate;
	}

	private static void unzip(Path zip, Path into) throws IOException {
		long total = 0;
		Path root = into.toAbsolutePath().normalize();
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry entry;
			byte[] buffer = new byte[64 * 1024];
			while ((entry = in.getNextEntry()) != null) {
				Path target = root.resolve(entry.getName()).normalize();
				if (!target.startsWith(root)) {
					throw new IOException("Unsafe path in zip: " + entry.getName());
				}
				if (entry.isDirectory()) {
					Files.createDirectories(target);
					continue;
				}
				Files.createDirectories(target.getParent());
				try (var out = Files.newOutputStream(target)) {
					int read;
					while ((read = in.read(buffer)) > 0) {
						total += read;
						if (total > MAX_UNZIPPED_BYTES) {
							throw new IOException("That zip is too big to be a world");
						}
						out.write(buffer, 0, read);
					}
				}
			}
		}
	}

	private static void copyTree(Path from, Path to) throws IOException {
		Files.walkFileTree(from, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(to.resolve(from.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				// session.lock belongs to whatever game had the world open - never copy it.
				if (!file.getFileName().toString().equals("session.lock")) {
					try (InputStream in = Files.newInputStream(file)) {
						Files.copy(in, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
					}
				}
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Deletes a world folder (or the importer's temp folder). */
	public static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}
}
