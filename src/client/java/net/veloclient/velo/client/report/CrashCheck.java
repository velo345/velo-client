package net.veloclient.velo.client.report;

import net.veloclient.velo.config.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "Minecraft crashed last time" detection: on the first title screen of a session, looks for a
 * crash report written since the last check that nobody has dealt with yet (the launcher's crash
 * dialog marks the ones it already offered in {@code crash-reports/.velo-handled}).
 */
public final class CrashCheck {

	private static boolean checked;

	private CrashCheck() {
	}

	/** The newest unhandled crash report since the last start, or null. Only answers once per session. */
	public static Path pendingCrash() {
		if (checked) {
			return null;
		}
		checked = true;
		Path state = VeloPaths.root().resolve("crash-check.txt");
		long last;
		try {
			last = Files.exists(state) ? Long.parseLong(Files.readString(state).trim()) : -1;
		} catch (Exception e) {
			last = -1;
		}
		try {
			Files.createDirectories(state.getParent());
			Files.writeString(state, String.valueOf(System.currentTimeMillis()));
		} catch (Exception ignored) {
			// Worst case we ask again next time.
		}
		if (last < 0) {
			// First run with this feature: don't bring up old crashes.
			return null;
		}
		Path dir = BugReporter.gameDir().resolve("crash-reports");
		if (!Files.isDirectory(dir)) {
			return null;
		}
		List<String> handled = handled(dir);
		long since = last;
		try (var files = Files.list(dir)) {
			return files.filter(p -> p.getFileName().toString().endsWith(".txt"))
					.filter(p -> !handled.contains(p.getFileName().toString()))
					.filter(p -> modified(p) > since)
					.max(Comparator.comparingLong(CrashCheck::modified)).orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	private static long modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (Exception e) {
			return 0;
		}
	}

	private static List<String> handled(Path dir) {
		try {
			Path marker = dir.resolve(".velo-handled");
			return Files.exists(marker) ? new ArrayList<>(Files.readAllLines(marker, StandardCharsets.UTF_8)) : new ArrayList<>();
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	public static void markHandled(Path crashFile) {
		try {
			Path marker = crashFile.getParent().resolve(".velo-handled");
			List<String> lines = handled(crashFile.getParent());
			String name = crashFile.getFileName().toString();
			if (!lines.contains(name)) {
				lines.add(name);
				Files.write(marker, lines, StandardCharsets.UTF_8);
			}
		} catch (Exception ignored) {
			// Asked once more at worst.
		}
	}

	/** First line that says what broke ("Description: ..." plus the exception under it). */
	public static String cause(Path crashFile) {
		try {
			List<String> lines = Files.readAllLines(crashFile, StandardCharsets.UTF_8);
			String description = null;
			for (String line : lines) {
				if (description == null && line.startsWith("Description: ")) {
					description = line.substring("Description: ".length()).trim();
				} else if (description != null && !line.isBlank()) {
					String text = description + ": " + line.trim();
					return text.length() > 200 ? text.substring(0, 200) + "..." : text;
				}
			}
			return description;
		} catch (Exception e) {
			return null;
		}
	}
}
