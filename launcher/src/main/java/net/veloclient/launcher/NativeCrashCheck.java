package net.veloclient.launcher;

import com.google.gson.JsonObject;
import net.veloclient.launcher.data.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A hard crash of the launcher's JVM (a native fault - graphics drivers, GTK, ...) leaves no report behind: the process
 * is simply gone. The JVM does write an {@code hs_err_pid*.log} into the folder it ran in, though. On the next start
 * this finds the newest one that's ours and hasn't been reported, and once the window is up offers to send it.
 */
final class NativeCrashCheck {

	private static final long MAX_AGE_MILLIS = 14L * 24 * 3600 * 1000;
	private static final int MAX_CHARS = 60_000;
	private static Path pending;

	private NativeCrashCheck() {
	}

	private static Path seenFile() {
		return VeloPaths.logs().resolve("reported-native-crashes.txt");
	}

	/** Looks for an unreported crash log (startup, before any window - must never throw). */
	static void scan() {
		try {
			List<String> seen = Files.exists(seenFile()) ? Files.readAllLines(seenFile()) : List.of();
			List<Path> folders = new ArrayList<>(List.of(Path.of(System.getProperty("user.home")), Path.of("").toAbsolutePath(),
					Path.of(System.getProperty("java.io.tmpdir")), VeloPaths.logs()));
			long now = System.currentTimeMillis();
			Path newest = null;
			long newestTime = 0;
			for (Path folder : folders) {
				if (!Files.isDirectory(folder)) {
					continue;
				}
				try (Stream<Path> files = Files.list(folder)) {
					for (Path file : (Iterable<Path>) files.filter(f -> f.getFileName().toString().matches("hs_err_pid\\d+\\.log"))::iterator) {
						long time = Files.getLastModifiedTime(file).toMillis();
						if (now - time > MAX_AGE_MILLIS || seen.contains(file.toAbsolutePath().toString()) || time <= newestTime) {
							continue;
						}
						// Only ours: the game (a separate JVM) writes its own into the profile folder, and other Java apps aren't our business.
						String head = Files.readString(file, StandardCharsets.UTF_8).substring(0, (int) Math.min(Files.size(file), 200_000));
						if (head.contains("net.veloclient.launcher") || head.contains("Velo Client Launcher") || head.contains("velo-client-launcher")) {
							newest = file;
							newestTime = time;
						}
					}
				} catch (Exception ignored) {
					// unreadable folder
				}
			}
			pending = newest;
			if (newest != null) {
				LauncherLog.info("Found a native crash log from a previous run: " + newest);
				String text = Files.readString(newest, StandardCharsets.UTF_8);
				for (String lib : GRAPHICS_LIBS) {
					if (text.contains(lib)) {
						// It died in the graphics stack: next starts draw in software and without 3D, until the next launcher version.
						Files.createDirectories(safeFlag().getParent());
						Files.writeString(safeFlag(), AppVersion.VERSION + " " + lib, StandardCharsets.UTF_8);
						LauncherLog.info("The crash was in " + lib + " - starting in safe graphics mode (software rendering, no 3D)");
						break;
					}
				}
			}
			applySafeGraphics();
		} catch (Throwable ignored) {
			// never in the way of starting
		}
	}

	/** Native libraries that mean "the graphics stack crashed" in an hs_err log. */
	private static final String[] GRAPHICS_LIBS = {"libprism_es2", "libprism_sw", "libglass", "libGL", "libEGL", "libGLX", "libnvidia", "nvidia_dri",
			"iris_dri", "radeonsi_dri", "swrast_dri", "libgallium", "libvulkan", "libgdk-3", "libgtk-3"};

	private static Path safeFlag() {
		return VeloPaths.logs().resolve("safe-graphics.txt");
	}

	/** Safe graphics mode (after a graphics crash, for this launcher version): software rendering, no 3D scenes. */
	private static void applySafeGraphics() {
		try {
			if (!Files.exists(safeFlag())) {
				return;
			}
			String flag = Files.readString(safeFlag(), StandardCharsets.UTF_8);
			if (!flag.startsWith(AppVersion.VERSION + " ")) {
				Files.deleteIfExists(safeFlag());   // a new launcher version gets a fresh try
				return;
			}
			if (System.getProperty("prism.order") == null) {
				System.setProperty("prism.order", "sw");
			}
			System.setProperty("velo.no3d", "true");
			LauncherLog.info("Safe graphics mode is on (" + flag.strip() + ")");
		} catch (Throwable ignored) {
			// normal graphics then
		}
	}

	/** After the window is up: offers to report the crash found by {@link #scan()} (each one only once). */
	static void offer(javafx.stage.Window owner) {
		Path file = pending;
		pending = null;
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(seenFile().getParent());
			Files.writeString(seenFile(), file.toAbsolutePath() + System.lineSeparator(), StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			String text = Files.readString(file, StandardCharsets.UTF_8);
			JsonObject context = new JsonObject();
			context.addProperty("area", "launcher-native-crash");
			context.addProperty("hs_err", text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text);
			context.addProperty("file", file.getFileName().toString());
			// What the launcher was doing before it died: the previous run's log (kept as launcher.log.1).
			Path previous = VeloPaths.logs().resolve("launcher.log.1");
			if (Files.exists(previous)) {
				List<String> lines = Files.readAllLines(previous, StandardCharsets.UTF_8);
				context.addProperty("previousLog", String.join("\n", lines.subList(Math.max(0, lines.size() - 300), lines.size())));
			}
			net.veloclient.launcher.ui.BugReportDialog.show(owner, "The launcher crashed last time", context);
		} catch (Throwable e) {
			LauncherLog.warn("Couldn't offer the native crash report", e);
		}
	}
}
