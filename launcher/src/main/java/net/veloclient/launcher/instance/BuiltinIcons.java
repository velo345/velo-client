package net.veloclient.launcher.instance;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A small fixed set of profile icons - Lucide-derived PNGs (see NOTICE.md),
 * white strokes on transparent so they read cleanly against the tile's own
 * accent-gradient background, replacing the previous hand-drawn Canvas
 * glyphs (flagged as looking rough compared to a real icon set).
 */
public final class BuiltinIcons {

	public static final String DEFAULT = "diamond";

	private static final Set<String> IDS = new LinkedHashSet<>(Set.of(
			"diamond", "sword", "shield", "star", "potion", "compass", "book", "flame", "anvil", "skull"));

	private BuiltinIcons() {
	}

	public static Iterable<String> ids() {
		return IDS;
	}

	/** Renders a builtin icon into a rounded tile sized {@code size}x{@code size}. */
	private static final java.util.Map<String, Image> ICONS = new java.util.concurrent.ConcurrentHashMap<>();

	public static StackPane render(String id, double size, Color accentStart, Color accentEnd) {
		StackPane tile = new StackPane();
		tile.setPrefSize(size, size);
		tile.setMinSize(size, size);
		tile.setMaxSize(size, size);
		tile.setPadding(new Insets(size * 0.16));
		// A (nearly) white accent - the Monochrome theme - made the white glyph vanish on its own tile: those get a plain
		// white tile with a black glyph. Only Velo's own icons; a profile's custom picture is shown as it is.
		boolean lightAccent = isLight(accentStart);
		tile.setStyle("-fx-background-radius: " + (size * 0.22) + "; -fx-background-color: "
				+ (lightAccent ? "#FFFFFF;" : "linear-gradient(to bottom right, " + toRgba(accentStart) + ", " + toRgba(accentEnd) + ");"));

		String resolvedId = IDS.contains(id) ? id : DEFAULT;
		// decoded + smooth-scaled once per icon and size (every Library visit used to decode every profile's PNG again)
		Image glyph = ICONS.computeIfAbsent(resolvedId + "@" + Math.round(size), k -> new Image(
				BuiltinIcons.class.getResourceAsStream("/net/veloclient/launcher/images/icons/" + resolvedId + ".png"),
				size * 0.68, size * 0.68, true, true));
		if (lightAccent) {
			glyph = DARK.computeIfAbsent(resolvedId + "@" + Math.round(size), k -> blackened(ICONS.get(resolvedId + "@" + Math.round(size))));
		}
		ImageView view = new ImageView(glyph);
		StackPane.setAlignment(view, Pos.CENTER);
		tile.getChildren().add(view);
		return tile;
	}

	/** Decodes the icons for {@code sizes} off the UI thread (pages with many profiles opened slowly the first time). */
	public static void warm(double... sizes) {
		for (double size : sizes) {
			for (String id : IDS) {
				ICONS.computeIfAbsent(id + "@" + Math.round(size), k -> new Image(
						BuiltinIcons.class.getResourceAsStream("/net/veloclient/launcher/images/icons/" + id + ".png"),
						size * 0.68, size * 0.68, true, true));
			}
		}
	}

	private static final java.util.Map<String, Image> DARK = new java.util.concurrent.ConcurrentHashMap<>();

	private static boolean isLight(Color c) {
		int rgb = ((int) Math.round(c.getRed() * 255) << 16) | ((int) Math.round(c.getGreen() * 255) << 8) | (int) Math.round(c.getBlue() * 255);
		return net.veloclient.launcher.theme.LauncherTheme.luminance(rgb) > 0.65;
	}

	/** The same glyph in black (its alpha kept). */
	private static Image blackened(Image source) {
		int w = (int) source.getWidth();
		int h = (int) source.getHeight();
		javafx.scene.image.WritableImage out = new javafx.scene.image.WritableImage(w, h);
		javafx.scene.image.PixelReader in = source.getPixelReader();
		javafx.scene.image.PixelWriter writer = out.getPixelWriter();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int alpha = in.getArgb(x, y) >>> 24;
				writer.setArgb(x, y, (alpha << 24) | 0x111111);
			}
		}
		return out;
	}

	private static String toRgba(Color c) {
		return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
				(int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255), c.getOpacity());
	}
}
