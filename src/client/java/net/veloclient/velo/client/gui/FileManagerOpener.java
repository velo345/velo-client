package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Util;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Opens a folder in the host OS's file manager, shared by every in-game
 * screen with an "Open folder" button (originally {@code ModMenuScreen}'s
 * mods-folder button, now also {@link SchematicsScreen}'s).
 *
 * <p>Tries an OS-specific command first (see the javadoc on {@link #open}
 * for why), then falls back to vanilla's own opener and finally AWT Desktop
 * if that specific command isn't present on this machine.
 */
public final class FileManagerOpener {

	private FileManagerOpener() {
	}

	/**
	 * The previous version tried {@code Util.getOperatingSystem().open(dir)}
	 * first, which on this KDE/Plasma setup (and likely others) shells out to
	 * {@code xdg-open} with a bare filesystem path rather than a {@code
	 * file://} URI - confirmed by hand that {@code xdg-open /some/path} exits
	 * 0 and opens nothing (KDE's mime lookup fails on the missing URI
	 * scheme), while {@code xdg-open file:///some/path} reliably opens
	 * Dolphin. Since that "succeeds" with no exception and no visible effect,
	 * every fallback after it (AWT Desktop, raw xdg-open) was unreachable
	 * dead code - the button looked broken because the very first attempt
	 * silently no-oped. This now uses the verified-working, OS-specific
	 * command first and only falls back if the process itself fails to
	 * *start* (tool missing), not based on exit code - several of these
	 * tools (explorer.exe in particular) return unreliable exit codes even
	 * on success.
	 *
	 * @param onStatus called back on the render thread with a short status
	 *                 line ("Opening...", then the result) - screens show this
	 *                 the same way {@code ModMenuScreen} always has.
	 */
	public static void open(File dir, Consumer<String> onStatus) {
		onStatus.accept("Opening " + dir.getName() + "...");
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			StringBuilder attempts = new StringBuilder();
			String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			String[][] candidates;
			if (osName.contains("win")) {
				candidates = new String[][] {{"explorer.exe", dir.getAbsolutePath()}};
			} else if (osName.contains("mac") || osName.contains("darwin")) {
				candidates = new String[][] {{"open", dir.getAbsolutePath()}};
			} else {
				candidates = new String[][] {{"xdg-open", dir.toURI().toString()}};
			}
			for (String[] command : candidates) {
				try {
					new ProcessBuilder(command).start();
					MinecraftClient.getInstance().execute(() -> onStatus.accept("Opened " + dir.getName() + " - check your taskbar if it's not on top"));
					return;
				} catch (Throwable t) {
					attempts.append(String.join(" ", command)).append(": ").append(t).append("; ");
				}
			}
			try {
				Util.getOperatingSystem().open(dir);
				MinecraftClient.getInstance().execute(() -> onStatus.accept("Opened " + dir.getName() + " - check your taskbar if it's not on top"));
				return;
			} catch (Throwable t) {
				attempts.append("vanilla open: ").append(t).append("; ");
			}
			try {
				java.awt.Desktop.getDesktop().open(dir);
				MinecraftClient.getInstance().execute(() -> onStatus.accept("Opened " + dir.getName() + " - check your taskbar if it's not on top"));
				return;
			} catch (Throwable t) {
				attempts.append("AWT Desktop: ").append(t);
			}
			net.veloclient.velo.VeloClient.LOGGER.error("Could not open {} in a file manager - all methods failed: {}", dir, attempts);
			MinecraftClient.getInstance().execute(() -> onStatus.accept("Couldn't open a file manager - see the log"));
		});
	}
}
