package net.veloclient.launcher.instance;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.launch.MemoryPlanner;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Linux safety net for PCs with little swap, where running out of RAM doesn't just slow the game
 * down but freezes or hard-crashes the whole computer:
 * <ul>
 * <li>the game process is marked as the first thing the kernel's OOM killer should pick, so the
 * kernel ends Minecraft rather than the desktop when memory truly runs out;</li>
 * <li>while it runs, free memory is checked twice a second - if it stays critically low (swap
 * nearly exhausted too), the game is stopped before the system starts thrashing, and the
 * player is told why.</li>
 * </ul>
 */
public final class MemoryGuard {

	private static final long CRITICAL_AVAILABLE_MB = 220;
	private static final long CRITICAL_SWAP_FREE_MB = 160;
	private static final int STRIKES_TO_STOP = 4;

	private MemoryGuard() {
	}

	public static void watch(Instance instance, Process process) {
		if (!MemoryPlanner.isLinux()) {
			return;
		}
		try {
			Files.writeString(Path.of("/proc", String.valueOf(process.pid()), "oom_score_adj"), "900");
		} catch (Exception ignored) {
			// Not fatal - the watchdog below still applies.
		}
		Thread.ofVirtual().name("velo-memory-guard").start(() -> {
			int strikes = 0;
			while (process.isAlive()) {
				long[] mem = readMemInfo();
				boolean critical = mem[0] >= 0 && mem[0] < CRITICAL_AVAILABLE_MB && (mem[1] < 0 || mem[1] < CRITICAL_SWAP_FREE_MB);
				strikes = critical ? strikes + 1 : 0;
				if (strikes >= STRIKES_TO_STOP) {
					process.destroyForcibly();
					long available = mem[0];
					Platform.runLater(() -> {
						Alert alert = new Alert(Alert.AlertType.WARNING);
						alert.setTitle("Game stopped to protect your PC");
						alert.setHeaderText("Your PC ran out of memory");
						alert.setContentText("Only " + available + " MB of RAM was left, so " + instance.name()
								+ " was stopped before your computer could freeze. Close other programs (browser, Discord...) "
								+ "or give this profile less RAM, then start it again.");
						net.veloclient.launcher.ui.DialogStyling.apply(alert);
						alert.show();
					});
					return;
				}
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					return;
				}
			}
		});
	}

	/** {MemAvailable, SwapFree} in MB from /proc/meminfo; -1 when unknown (SwapFree -1 = no swap info). */
	private static long[] readMemInfo() {
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
