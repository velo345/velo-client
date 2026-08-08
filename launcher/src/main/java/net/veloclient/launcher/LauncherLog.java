package net.veloclient.launcher;

import net.veloclient.launcher.data.VeloPaths;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tees {@link System#out}/{@link System#err} into a persistent
 * {@code ~/.velo-client/logs/launcher.log} in addition to the console, so
 * whatever actually went wrong on a real user's machine survives past the
 * session - a jpackage-installed GUI app has no visible console at all on
 * Windows, so every {@code e.printStackTrace()} anywhere in this codebase
 * used to be permanently invisible to anyone but a developer running from
 * source. Every line already gets a timestamp prefix here rather than
 * relying on call sites to add their own, so this alone is enough to
 * diagnose "worked yesterday, broken today" and "works for me, not for them"
 * reports without needing to modify whatever actually failed.
 *
 * <p>The previous run's log is kept as {@code launcher.log.1} (one level of
 * rotation, not a growing pile) so a crash-on-startup report still has the
 * prior session's context to compare against.
 */
public final class LauncherLog {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private static boolean installed;

	private LauncherLog() {
	}

	/** Idempotent - safe to call more than once (only the first call actually installs the tee). */
	public static synchronized void install() {
		if (installed) {
			return;
		}
		installed = true;
		try {
			VeloPaths.ensureDirectories();
			Path logFile = VeloPaths.logs().resolve("launcher.log");
			Path previous = VeloPaths.logs().resolve("launcher.log.1");
			if (Files.exists(logFile)) {
				Files.move(logFile, previous, StandardCopyOption.REPLACE_EXISTING);
			}
			OutputStream fileOut = new FileOutputStream(logFile.toFile());
			System.setOut(new TeePrintStream(System.out, fileOut));
			System.setErr(new TeePrintStream(System.err, fileOut));
			Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
				System.err.println("Uncaught exception on thread " + thread.getName() + ":");
				throwable.printStackTrace();
			});
			System.out.println("==== Velo Client Launcher " + AppVersion.VERSION + " starting - "
					+ LocalDateTime.now() + " - " + System.getProperty("os.name") + " " + System.getProperty("os.version")
					+ " - Java " + System.getProperty("java.version") + " ====");
		} catch (IOException e) {
			// Logging to disk is best-effort - a launcher that can't write its
			// own log file should still run normally rather than failing to
			// start over it.
			e.printStackTrace();
		}
	}

	/**
	 * Logs a non-fatal problem worth keeping a record of - something that was
	 * recovered from (so it shouldn't interrupt whatever the user's doing
	 * with a dialog/crash) but that a developer would want in hand if the
	 * user reports something looking off afterward. Routes through
	 * {@code System.err} so it's captured by the tee installed above
	 * regardless of whether {@link #install()} has run yet.
	 */
	public static void warn(String message, Throwable cause) {
		System.err.println("[WARN] " + message + ": " + cause);
	}

	/** Writes every line to both the original stream and the log file, timestamped, without needing call sites to change. */
	private static final class TeePrintStream extends PrintStream {
		private final PrintStream original;

		TeePrintStream(PrintStream original, OutputStream fileOut) {
			super(fileOut, true);
			this.original = original;
		}

		@Override
		public void println(String line) {
			String timestamped = "[" + LocalDateTime.now().format(TIMESTAMP) + "] " + line;
			original.println(line);
			super.println(timestamped);
		}

		@Override
		public void print(String s) {
			original.print(s);
			super.print(s);
		}
	}
}
