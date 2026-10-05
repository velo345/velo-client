package net.veloclient.launcher.instance;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import net.veloclient.launcher.data.LauncherSettings;
import net.veloclient.launcher.launch.MemoryPlanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux safety net for PCs with little swap, where running out of RAM doesn't just slow the game
 * down but freezes or hard-crashes the whole computer:
 * <ul>
 * <li>each game process is marked as the first thing the kernel's OOM killer should pick (the most
 * recently started one first), so the kernel ends Minecraft rather than the desktop;</li>
 * <li>one shared watchdog checks free memory twice a second - if it stays critically low (swap
 * nearly exhausted too) for a moment, it stops <b>only the most recently started game</b>, then
 * gives memory a few seconds to recover before judging again. With two instances open, the one
 * you just started goes first and the one you were already playing survives.</li>
 * </ul>
 * Thresholds and the on/off switch are in launcher Settings (defaults: 220 MB RAM, 160 MB swap, 2 s).
 */
public final class MemoryGuard {

	private static final long TICK_MS = 500;
	private static final long RECOVERY_MS = 4000;

	private record Watched(Instance instance, Process process, long startedAt) {
	}

	private static final List<Watched> WATCHED = new ArrayList<>();
	private static Thread thread;

	private MemoryGuard() {
	}

	public static synchronized void watch(Instance instance, Process process) {
		if (!MemoryPlanner.isLinux()) {
			return;
		}
		WATCHED.removeIf(w -> !w.process().isAlive());
		WATCHED.add(new Watched(instance, process, System.nanoTime()));
		// Older games get a slightly lower OOM priority than the newest one.
		for (Watched w : WATCHED) {
			setOomScore(w.process(), w.process() == process ? 950 : 850);
		}
		if (thread == null || !thread.isAlive()) {
			thread = Thread.ofVirtual().name("velo-memory-guard").start(MemoryGuard::run);
		}
	}

	private static void run() {
		int strikes = 0;
		while (true) {
			Watched newest;
			synchronized (MemoryGuard.class) {
				WATCHED.removeIf(w -> !w.process().isAlive());
				if (WATCHED.isEmpty()) {
					thread = null;
					return;
				}
				newest = WATCHED.stream().max((a, b) -> Long.compare(a.startedAt(), b.startedAt())).orElseThrow();
			}
			LauncherSettings.Data settings = LauncherSettings.get();
			long[] mem = readMemInfo();
			boolean critical = settings.memoryGuard && mem[0] >= 0 && mem[0] < settings.guardAvailableMb
					&& (mem[1] < 0 || mem[1] < settings.guardSwapMb);
			strikes = critical ? strikes + 1 : 0;
			int needed = Math.max(1, (int) Math.round(settings.guardSeconds * 1000 / TICK_MS));
			if (strikes >= needed) {
				strikes = 0;
				newest.process().destroyForcibly();
				int stillRunning;
				synchronized (MemoryGuard.class) {
					stillRunning = (int) WATCHED.stream().filter(w -> w != newest && w.process().isAlive()).count();
				}
				warn(newest.instance(), mem[0], stillRunning);
				sleep(RECOVERY_MS);
				continue;
			}
			sleep(TICK_MS);
		}
	}

	private static void warn(Instance instance, long available, int stillRunning) {
		Platform.runLater(() -> {
			Alert alert = new Alert(Alert.AlertType.WARNING);
			alert.setTitle("Game stopped to protect your PC");
			alert.setHeaderText("Your PC ran out of memory");
			alert.setContentText("Only " + available + " MB of RAM was left, so " + instance.name()
					+ " was stopped before your computer could freeze."
					+ (stillRunning > 0 ? " Your other running game" + (stillRunning > 1 ? "s were" : " was") + " kept open." : "")
					+ " Close other programs (browser, Discord...) or give this profile less RAM, then start it again."
					+ " (Settings > Memory guard)");
			net.veloclient.launcher.ui.DialogStyling.apply(alert);
			alert.show();
		});
	}

	private static void setOomScore(Process process, int score) {
		try {
			Files.writeString(Path.of("/proc", String.valueOf(process.pid()), "oom_score_adj"), String.valueOf(score));
		} catch (Exception ignored) {
			// Not fatal - the watchdog still applies.
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}

	/** {MemAvailable, SwapFree} in MB from /proc/meminfo; -1 when unknown (SwapFree -1 = no swap info). */
	static long[] readMemInfo() {
		long available = -1;
		long swapFree = -1;
		try {
			for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
				if (line.startsWith("MemAvailable:")) {
					available = Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
				} else if (line.startsWith("SwapFree:")) {
					swapFree = Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
				}
			}
		} catch (Exception ignored) {
			// Unknown - treated as not critical.
		}
		return new long[] {available, swapFree};
	}
}
