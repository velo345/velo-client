package net.veloclient.launcher.ui;

import javafx.scene.image.Image;
import net.veloclient.launcher.data.WingsCatalog;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decoded pictures the pages show again and again (the logo at a few sizes, wing previews, profile icons),
 * kept once decoded: building a page used to decode and scale the same PNGs on every visit, which made
 * switching tabs stutter. Small LRU; FX thread or any thread.
 */
public final class UiImages {

	private static final int MAX = 160;
	private static final Map<String, Image> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
			return size() > MAX;
		}
	};

	private UiImages() {
	}

	/** A resource from the launcher jar at {@code width} x {@code height} (0 = its own size). */
	public static Image resource(String path, double width, double height) {
		return cached("res:" + path + "@" + width + "x" + height, () -> {
			InputStream in = UiImages.class.getResourceAsStream(path);
			return in == null ? null : new Image(in, width, height, true, true);
		});
	}

	/** A file on disk (a profile's custom icon); a changed file is decoded again. */
	public static Image file(Path file, double width, double height) {
		long modified;
		try {
			modified = Files.getLastModifiedTime(file).toMillis();
		} catch (Exception e) {
			modified = 0;
		}
		return cached("file:" + file + "#" + modified + "@" + width + "x" + height, () -> new Image(file.toUri().toString(), width, height, true, true));
	}

	/** A pair of wings' preview picture (full size; ImageViews scale it). */
	public static Image wingPreview(String wingsId) {
		return cached("wings:" + wingsId, () -> {
			InputStream in = WingsCatalog.openPreview(wingsId);
			return in == null ? null : new Image(in);
		});
	}

	/** Forgets a wing preview (after the catalog downloaded new art). */
	public static synchronized void forgetWings() {
		CACHE.keySet().removeIf(k -> k.startsWith("wings:"));
	}

	private static Image cached(String key, java.util.function.Supplier<Image> load) {
		synchronized (UiImages.class) {
			Image image = CACHE.get(key);
			if (image != null) {
				return image;
			}
		}
		Image image = load.get();
		if (image != null && !image.isError()) {
			synchronized (UiImages.class) {
				CACHE.put(key, image);
			}
		}
		return image;
	}
}
