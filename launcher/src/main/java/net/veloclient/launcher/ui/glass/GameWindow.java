package net.veloclient.launcher.ui.glass;

import net.veloclient.launcher.LauncherLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Brings a running game's window to the front (Home's Running card, 2026-11-25) - best effort per OS: AppActivate on
 * Windows, System Events on macOS, xdotool or wmctrl on Linux/X11. Completes with false when nothing could do it (the
 * caller then shows the game's log page instead).
 */
public final class GameWindow {

	private GameWindow() {
	}

	public static CompletableFuture<Boolean> focus(Process process) {
		if (process == null || !process.isAlive()) {
			return CompletableFuture.completedFuture(false);
		}
		long pid = process.pid();
		return CompletableFuture.supplyAsync(() -> {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			try {
				if (os.contains("win")) {
					return run("powershell", "-NoProfile", "-NonInteractive", "-Command",
							"$s = New-Object -ComObject WScript.Shell; if ($s.AppActivate(" + pid + ")) { 'ok' }").contains("ok");
				}
				if (os.contains("mac")) {
					run("osascript", "-e", "tell application \"System Events\" to set frontmost of (first process whose unix id is " + pid + ") to true");
					return true;
				}
				String found = run("xdotool", "search", "--pid", String.valueOf(pid));
				String[] ids = found.isBlank() ? new String[0] : found.strip().split("\\s+");
				if (ids.length > 0) {
					run("xdotool", "windowactivate", ids[ids.length - 1]);
					return true;
				}
				for (String line : run("wmctrl", "-lp").split("\n")) {
					String[] cols = line.trim().split("\\s+");
					if (cols.length > 2 && cols[2].equals(String.valueOf(pid))) {
						run("wmctrl", "-ia", cols[0]);
						return true;
					}
				}
			} catch (IOException | InterruptedException | RuntimeException e) {
				LauncherLog.info("Couldn't bring the game window to the front: " + e.getMessage());
			}
			return false;
		});
	}

	private static String run(String... command) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
		byte[] out = p.getInputStream().readAllBytes();
		if (!p.waitFor(5, TimeUnit.SECONDS)) {
			p.destroyForcibly();
		}
		return new String(out, StandardCharsets.UTF_8);
	}
}
