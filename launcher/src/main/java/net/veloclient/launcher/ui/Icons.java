package net.veloclient.launcher.ui;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.Map;

/**
 * Line icons for the launcher (24-unit grid, round caps), drawn as vector paths so they stay sharp at any size and
 * take any color - the same family everywhere instead of mixed PNGs.
 */
public final class Icons {

	private static final Map<String, String> PATHS = Map.ofEntries(
			Map.entry("home", "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z"),
			Map.entry("library", "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"),
			Map.entry("server", "M3 4h18v7H3zM3 13h18v7H3zM7 7.5h.01M7 16.5h.01"),
			Map.entry("store", "M5 7h14l1 13H4zM9 7a3 3 0 0 1 6 0"),
			Map.entry("locker", "M20.4 7.6 16 4l-1.5 2a3 3 0 0 1-5 0L8 4 3.6 7.6 6 11h2v9h8v-9h2z"),
			Map.entry("news", "M4 5h13v14a2 2 0 0 0 2 2H6a2 2 0 0 1-2-2zM17 9h3v10a2 2 0 0 1-2 2M8 9h5M8 13h5M8 17h3"),
			Map.entry("clips", "M3 6h13v12H3zM16 10l5-3v10l-5-3"),
			Map.entry("search", "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-3.5-3.5"),
			Map.entry("friends", "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 21c0-3.9 3.1-7 7-7s7 3.1 7 7M16 3.5a4 4 0 0 1 0 7.5M22 21c0-3-1.8-5.6-4.5-6.6"),
			Map.entry("bell", "M6 9a6 6 0 1 1 12 0c0 7 3 8 3 8H3s3-1 3-8M10.3 21a1.9 1.9 0 0 0 3.4 0"),
			Map.entry("settings", "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"),
			Map.entry("chevron-down", "M6 9l6 6 6-6"),
			Map.entry("chevron-up", "M6 15l6-6 6 6"),
			Map.entry("chevron-left", "M15 6l-6 6 6 6"),
			Map.entry("chevron-right", "M9 6l6 6-6 6"),
			Map.entry("chevrons-updown", "M7 15l5 5 5-5M7 9l5-5 5 5"),
			Map.entry("arrow-left", "M19 12H5M11 18l-6-6 6-6"),
			Map.entry("plus", "M12 5v14M5 12h14"),
			Map.entry("close", "M6 6l12 12M18 6 6 18"),
			Map.entry("check", "M5 12l5 5L20 7"),
			Map.entry("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"),
			Map.entry("globe", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"),
			Map.entry("sliders", "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M16 4v4M10 10v4M18 16v4"),
			Map.entry("more", "M5 12h.01M12 12h.01M19 12h.01"),
			Map.entry("download", "M12 3v12M7 10l5 5 5-5M5 21h14"),
			Map.entry("upload", "M12 15V3M7 8l5-5 5 5M5 21h14"),
			Map.entry("trash", "M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14M10 11v5M14 11v5"),
			Map.entry("edit", "M12 20h9M16.5 3.5a2.1 2.1 0 1 1 3 3L7 19l-4 1 1-4z"),
			Map.entry("copy", "M9 9h11v11H9zM5 15H4V4h11v1"),
			Map.entry("star", "M12 3l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.4 6.8 19.1l1-5.8L3.5 9.2l5.9-.8z"),
			Map.entry("heart", "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21.2l8.8-8.8a5.5 5.5 0 0 0 0-7.8z"),
			Map.entry("gift", "M3 8h18v4H3zM5 12h14v9H5zM12 8v13M12 8S10 3 7.5 3a2.5 2.5 0 0 0 0 5M12 8s2-5 4.5-5a2.5 2.5 0 0 1 0 5"),
			Map.entry("eye", "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"),
			Map.entry("refresh", "M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5"),
			Map.entry("lock", "M5 11h14v10H5zM8 11V7a4 4 0 0 1 8 0v4"),
			Map.entry("external", "M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"),
			Map.entry("user", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21c0-4.4 3.6-8 8-8s8 3.6 8 8"),
			Map.entry("user-plus", "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 21c0-3.9 3.1-7 7-7 1.6 0 3 .5 4.2 1.4M19 14v6M16 17h6"),
			Map.entry("logout", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"),
			Map.entry("palette", "M12 3a9 9 0 1 0 0 18c1 0 1.5-.8 1.5-1.5 0-1.2-1-1.5-1-2.5 0-.8.7-1.5 1.5-1.5H16a5 5 0 0 0 5-5c0-4.1-4-7.5-9-7.5zM7.5 11h.01M10 7.5h.01M15 7.5h.01"),
			Map.entry("image", "M3 5h18v14H3zM3 16l5-5 4 4 3-3 6 6M15.5 9a1.5 1.5 0 1 0 0-.01"),
			Map.entry("grid", "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"),
			Map.entry("list", "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"),
			Map.entry("filter", "M3 5h18l-7 8v6l-4 2v-8z"),
			Map.entry("zap", "M13 2 4 14h7l-1 8 9-12h-7z"),
			Map.entry("shield", "M12 3 4 6v6c0 4.5 3.4 7.7 8 9 4.6-1.3 8-4.5 8-9V6z"),
			Map.entry("link", "M10 14a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1M14 10a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1"),
			Map.entry("share", "M18 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 22a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM8.6 13.5l6.8 4M15.4 6.5l-6.8 4"),
			Map.entry("box", "M21 16V8l-9-5-9 5v8l9 5zM3.3 7l8.7 5 8.7-5M12 22V12"),
			Map.entry("cpu", "M6 6h12v12H6zM9 9h6v6H9zM9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"),
			Map.entry("trophy", "M8 21h8M12 17v4M7 4h10v5a5 5 0 0 1-10 0zM17 5h3v2a3 3 0 0 1-3 3M7 5H4v2a3 3 0 0 0 3 3"),
			Map.entry("sparkles", "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z"),
			Map.entry("clock", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2"),
			Map.entry("message", "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"),
			Map.entry("send", "M22 2 11 13M22 2l-7 20-4-9-9-4z"),
			Map.entry("swap", "M7 7h13l-3-3M17 17H4l3 3"),
			Map.entry("info", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v6M12 7.5h.01"),
			Map.entry("warning", "M12 3 2 20h20zM12 10v4M12 17h.01"),
			Map.entry("play", "M7 4.5v15l12.5-7.5z"),
			Map.entry("stop", "M6 6h12v12H6z"),
			Map.entry("pause", "M6.5 4.5h3.5v15H6.5zM14 4.5h3.5v15H14z"),
			Map.entry("volume", "M4 9.5h4L13 5v14l-5-4.5H4zM16.5 9a4 4 0 0 1 0 6M19 6.5a7.5 7.5 0 0 1 0 11"),
			Map.entry("volume-off", "M4 9.5h4L13 5v14l-5-4.5H4zM16.5 9.5l5 5M21.5 9.5l-5 5"),
			Map.entry("flag", "M5 21V4M5 4h11l-2 4 2 4H5"),
			Map.entry("expand", "M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5"),
			Map.entry("wings", "M12 9C9 6 5 4.5 2 5C2.5 8 4 10 6 11.5C5 11.7 4 11.6 3 11.2C4.2 13.2 6 14.4 8 14.7C7.2 15.1 6.4 15.3 5.5 15.2C7.6 17 10 17 12 14"
					+ "M12 9C15 6 19 4.5 22 5C21.5 8 20 10 18 11.5C19 11.7 20 11.6 21 11.2C19.8 13.2 18 14.4 16 14.7C16.8 15.1 17.6 15.3 18.5 15.2C16.4 17 14 17 12 14"),
			Map.entry("aura", "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM4.9 4.9l2.1 2.1M17 17l2.1 2.1M3 12h2M19 12h2M4.9 19.1 7 17M17 7l2.1-2.1M12 2v2M12 20v2"),
			Map.entry("cape", "M8 4h8M8 4C7 9 5.5 14 4 20C6.5 21 9.5 19 12 20C14.5 21 17.5 19 20 20C18.5 14 17 9 16 4M10 4l2 2.5 2-2.5"),
			Map.entry("skin", "M8 3h8v6H8zM6 9h12v7H6zM8 16h3v5H8zM13 16h3v5h-3z"),
			Map.entry("badge", "M12 2l2.4 2.4H18v3.6L20.4 12 18 14.4V18h-3.6L12 20.4 9.6 18H6v-3.6L3.6 12 6 9.6V6h3.6z"),
			Map.entry("keyboard", "M3 6h18v12H3zM7 10h.01M11 10h.01M15 10h.01M7 14h10"),
			Map.entry("map", "M9 4 3 6v14l6-2 6 2 6-2V4l-6 2zM9 4v14M15 6v14"),
			Map.entry("ticket", "M3 8a2 2 0 0 0 0 4v4h18v-4a2 2 0 0 1 0-4V4H3zM9 4v12"),
			Map.entry("crown", "M3 8l4 4 5-7 5 7 4-4-2 11H5z"),
			Map.entry("scissors", "M6 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM20 4 8.1 15.9M14.5 14.5 20 20M8.1 8.1 12 12"),
			Map.entry("bug", "M8 9a4 4 0 0 1 8 0v6a4 4 0 0 1-8 0zM12 9v10M4 13h4M16 13h4M5 7l3 2M19 7l-3 2M5 19l3-2M19 19l-3-2"));

	private Icons() {
	}

	public static boolean has(String name) {
		return PATHS.containsKey(name);
	}

	/** A stroked line icon, {@code size} px square, in {@code color}. */
	public static Node icon(String name, double size, Paint color) {
		return icon(name, size, color, 1.9);
	}

	public static Node icon(String name, double size, Paint color, double strokeWidth) {
		SVGPath path = new SVGPath();
		path.setContent(PATHS.getOrDefault(name, PATHS.get("info")));
		boolean filled = name.equals("play") || name.equals("stop") || name.equals("pause");
		path.setFill(filled ? color : Color.TRANSPARENT);
		path.setStroke(color);
		path.setStrokeWidth(strokeWidth);
		path.setStrokeLineCap(StrokeLineCap.ROUND);
		path.setStrokeLineJoin(StrokeLineJoin.ROUND);
		double scale = size / 24.0;
		path.setScaleX(scale);
		path.setScaleY(scale);
		StackPane box = new StackPane(new Group(path));
		box.setAlignment(Pos.CENTER);
		box.setMinSize(size, size);
		box.setPrefSize(size, size);
		box.setMaxSize(size, size);
		box.setMouseTransparent(true);
		box.getStyleClass().add("vicon");
		return box;
	}

	/** The icon's path, for callers that want to recolor it later (e.g. hover states). */
	public static SVGPath pathOf(Node icon) {
		if (icon instanceof StackPane box && !box.getChildren().isEmpty() && box.getChildren().get(0) instanceof Group group
				&& !group.getChildren().isEmpty() && group.getChildren().get(0) instanceof SVGPath path) {
			return path;
		}
		return null;
	}
}
