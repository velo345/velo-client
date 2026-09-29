package net.veloclient.launcher.launch;

import net.veloclient.launcher.LauncherLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * On Windows laptops with both an integrated and a dedicated GPU, Windows runs {@code java.exe}
 * on the power-saving integrated one by default - Minecraft then sits at a fraction of the
 * frame rate the machine can do (commonly "stuck around 60 FPS" on a gaming laptop), and no
 * in-game setting or mod can fix it because the wrong GPU was picked before the game started.
 *
 * <p>This sets the same per-app preference as Windows' own Settings > Display > Graphics >
 * "High performance" page ({@code HKCU\Software\Microsoft\DirectX\UserGpuPreferences}, a per-user
 * key, no admin needed) - only for the exact Java binary this launcher starts the game with, and
 * never overriding a choice the player already made there. Harmless on single-GPU machines.
 */
final class WindowsGpuPreference {

	private static final String KEY = "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences";

	private WindowsGpuPreference() {
	}

	static void preferHighPerformance(Path javaExecutable) {
		if (!OsRules.currentOsName().equals("windows")) {
			return;
		}
		String exe = javaExecutable.toAbsolutePath().toString();
		// javaw.exe sits next to java.exe and is what some setups actually run - cover both.
		for (String path : new String[] {exe, exe.replaceFirst("(?i)java\\.exe$", "javaw.exe")}) {
			try {
				if (run("reg", "query", KEY, "/v", path) == 0) {
					continue;
				}
				if (run("reg", "add", KEY, "/v", path, "/t", "REG_SZ", "/d", "GpuPreference=2;", "/f") == 0) {
					LauncherLog.info("Set Windows GPU preference to High performance for " + path);
				}
			} catch (Exception e) {
				LauncherLog.warn("Couldn't set the Windows GPU preference for " + path, e);
			}
		}
	}

	private static int run(String... command) throws Exception {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
		if (!process.waitFor(5, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			return -1;
		}
		return process.exitValue();
	}
}
