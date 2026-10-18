package net.veloclient.launcher.ui;

import javafx.application.Platform;
import net.veloclient.launcher.LauncherLog;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Dev-only ({@code -Dvelo.fxWatchdog}): finds what freezes the launcher. A background thread pings the JavaFX thread every 40ms;
 * when a ping waits longer than 100ms the FX thread's stack is logged (our frames first), so blocking file/network work and slow
 * page builds show up by name instead of as "the launcher hangs for a second".
 */
public final class FxStallWatchdog {

	private static volatile long answered = System.nanoTime();
	private static volatile Thread fxThread;

	private FxStallWatchdog() {
	}

	public static void start() {
		Platform.runLater(() -> fxThread = Thread.currentThread());
		Thread watcher = new Thread(() -> {
			long reportedAt = 0;
			while (true) {
				Platform.runLater(() -> answered = System.nanoTime());
				try {
					Thread.sleep(40);
				} catch (InterruptedException e) {
					return;
				}
				long now = System.nanoTime();
				// Pings go out every 40ms, so the FX thread answered within ~40ms unless it is busy.
				long waited = (now - answered) / 1_000_000;
				if (waited >= 100 && fxThread != null && now - reportedAt > 90_000_000L) {
					reportedAt = now;
					StackTraceElement[] stack = fxThread.getStackTrace();
					String ours = Arrays.stream(stack).filter(f -> f.getClassName().startsWith("net.veloclient"))
							.limit(6).map(f -> f.getClassName().replace("net.veloclient.launcher.", "") + "." + f.getMethodName() + ":" + f.getLineNumber())
							.collect(Collectors.joining(" <- "));
					String top = Arrays.stream(stack).limit(4).map(f -> f.getClassName() + "." + f.getMethodName()).collect(Collectors.joining(" <- "));
					LauncherLog.info("FX STALL " + waited + "ms  ours: " + ours + "  | top: " + top);
				}
			}
		}, "velo-fx-watchdog");
		watcher.setDaemon(true);
		watcher.start();
	}
}
