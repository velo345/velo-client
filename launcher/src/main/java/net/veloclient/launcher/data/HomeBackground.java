package net.veloclient.launcher.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Velo+: your own picture or animated GIF behind the Home screen's big card, instead of the 3D island. The file is
 * copied into {@code <velo root>/backgrounds/home.<ext>} (so moving or deleting the original doesn't break it).
 * Members only - without Velo+ the island comes back, the file stays for when they rejoin.
 */
public final class HomeBackground {

	public static final long MAX_BYTES = 40L * 1024 * 1024;
	private static final String[] TYPES = {"png", "jpg", "jpeg", "gif", "webp", "bmp"};

	private HomeBackground() {
	}

	private static Path dir() {
		return VeloPaths.root().resolve("backgrounds");
	}

	/** The chosen file, or null for none. */
	public static Path file() {
		for (String type : TYPES) {
			Path p = dir().resolve("home." + type);
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		return null;
	}

	/** Copies {@code source} in as the background (replacing the old one); throws for unsupported or huge files. */
	public static Path set(Path source) throws IOException {
		String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
		String type = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
		if (!java.util.Arrays.asList(TYPES).contains(type)) {
			throw new IOException("Pick a PNG, JPG or GIF");
		}
		if (Files.size(source) > MAX_BYTES) {
			throw new IOException("That file is over " + MAX_BYTES / 1024 / 1024 + " MB");
		}
		Files.createDirectories(dir());
		clear();
		Path target = dir().resolve("home." + (type.equals("jpeg") ? "jpg" : type));
		Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		return target;
	}

	public static void clear() throws IOException {
		for (String type : TYPES) {
			Files.deleteIfExists(dir().resolve("home." + type));
		}
	}
}
