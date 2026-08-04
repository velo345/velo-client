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
	public static StackPane render(String id, double size, Color accentStart, Color accentEnd) {
		StackPane tile = new StackPane();
		tile.setPrefSize(size, size);
		tile.setMinSize(size, size);
		tile.setMaxSize(size, size);
		tile.setPadding(new Insets(size * 0.16));
		tile.setStyle("-fx-background-radius: " + (size * 0.22) + "; -fx-background-color: linear-gradient(to bottom right, "
				+ toRgba(accentStart) + ", " + toRgba(accentEnd) + ");");

		String resolvedId = IDS.contains(id) ? id : DEFAULT;
		ImageView view = new ImageView(new Image(
				BuiltinIcons.class.getResourceAsStream("/net/veloclient/launcher/images/icons/" + resolvedId + ".png"),
				size * 0.68, size * 0.68, true, true));
		StackPane.setAlignment(view, Pos.CENTER);
		tile.getChildren().add(view);
		return tile;
	}

	private static String toRgba(Color c) {
		return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
				(int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255), c.getOpacity());
	}
}
