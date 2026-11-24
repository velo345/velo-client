package net.veloclient.launcher.theme;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The theme's "Corners" setting. JavaFX CSS can look up colours but not sizes, so the theme's corner radius can't be a
 * {@code -velo-*} token like the colours - which is why the setting used to do nothing at all. Instead every radius in
 * launcher.css is scaled by {@code radius / 10} (10 = the stock look) into a small extra stylesheet that sits after
 * launcher.css in every launcher window, dialogs opened later included. 0 stays square, pills and circles (50+) stay
 * round.
 */
public final class ThemeRadii {

	public static final int STOCK = 10;
	private static final String BASE = "/net/veloclient/launcher/launcher.css";
	private static final Pattern BLOCK = Pattern.compile("([^{}]+)\\{([^{}]*)\\}");
	private static final Pattern DECL = Pattern.compile("(-fx-(?:background|border)-radius)\\s*:\\s*([^;]+);");
	private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)(px)?");

	private static String rules;
	private static String sheet;
	private static boolean watching;

	private ThemeRadii() {
	}

	/** Applies {@code radius} to every launcher window now and to the ones opened later. FX thread. */
	public static void apply(int radius) {
		String next = radius == STOCK || radius <= 0 ? null : sheetFor(radius);
		if (java.util.Objects.equals(next, sheet)) {
			return;
		}
		String previous = sheet;
		sheet = next;
		for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
			update(window.getScene(), previous);
		}
		if (!watching) {
			watching = true;
			javafx.stage.Window.getWindows().addListener((javafx.collections.ListChangeListener<javafx.stage.Window>) change -> {
				while (change.next()) {
					for (javafx.stage.Window window : change.getAddedSubList()) {
						update(window.getScene(), null);
					}
				}
			});
		}
	}

	private static void update(javafx.scene.Scene scene, String previous) {
		if (scene == null) {
			return;
		}
		var sheets = scene.getStylesheets();
		if (previous != null) {
			sheets.remove(previous);
		}
		boolean launcherScene = sheets.stream().anyMatch(s -> s.endsWith("launcher.css"));
		if (sheet != null && launcherScene && !sheets.contains(sheet)) {
			sheets.add(sheet);
		}
	}

	/** The stylesheet URL for a radius (written once per radius into the temp folder). */
	private static String sheetFor(int radius) {
		try {
			Path file = Path.of(System.getProperty("java.io.tmpdir"), "velo-launcher-corners-" + radius + ".css");
			String css = scaled(radius / (double) STOCK);
			if (!Files.exists(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(css)) {
				Files.writeString(file, css, StandardCharsets.UTF_8);
			}
			return file.toUri().toString();
		} catch (Exception e) {
			return null;
		}
	}

	/** Only the radius declarations of launcher.css, scaled. */
	static String scaled(double factor) throws java.io.IOException {
		if (rules == null) {
			try (var in = ThemeRadii.class.getResourceAsStream(BASE)) {
				rules = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
			}
		}
		StringBuilder out = new StringBuilder();
		Matcher block = BLOCK.matcher(rules);
		while (block.find()) {
			String selector = block.group(1).trim();
			if (selector.startsWith("@")) {
				continue;
			}
			StringBuilder decls = new StringBuilder();
			Matcher decl = DECL.matcher(block.group(2));
			while (decl.find()) {
				decls.append("\t").append(decl.group(1)).append(": ").append(scale(decl.group(2).trim(), factor)).append(";\n");
			}
			if (!decls.isEmpty()) {
				out.append(selector).append(" {\n").append(decls).append("}\n");
			}
		}
		return out.toString();
	}

	private static String scale(String value, double factor) {
		Matcher m = NUMBER.matcher(value);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			double n = Double.parseDouble(m.group(1));
			double scaled = n == 0 || n >= 50 ? n : Math.max(1, Math.round(n * factor));
			m.appendReplacement(out, String.format(Locale.ROOT, "%s%s", (long) scaled == scaled ? String.valueOf((long) scaled) : String.valueOf(scaled),
					m.group(2) == null ? "" : m.group(2)));
		}
		m.appendTail(out);
		return out.toString();
	}
}
