package net.veloclient.velo.client.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Runs a native OS file-picker dialog as a separate process instead of
 * Swing's {@code JFileChooser}. AWT genuinely doesn't work reliably inside
 * this JVM - something in Minecraft/LWJGL's own startup forces {@code
 * java.awt.headless} back to {@code true} even after this mod's own JVM
 * launch argument sets it false (confirmed: the flag is present on the
 * process's own command line, and {@code JFrame} construction still threw
 * {@code HeadlessException}). {@code GraphicsEnvironment} caches that flag
 * the first time anything asks, so nothing done later in Java code can undo
 * it for the rest of the process - a subprocess with its own native dialog
 * sidesteps AWT entirely instead of trying to out-race whatever sets it.
 *
 * <p>Shared by every screen that needs a PNG picker (originally written for
 * {@link CapeEquipScreen}, factored out here once the crosshair editor
 * needed the exact same thing).
 */
public final class NativeFileDialog {

	private NativeFileDialog() {
	}

	/** @return the chosen file, or {@code null} if the user cancelled. */
	public static Path pickPngFile(String promptTitle) throws IOException, InterruptedException {
		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (osName.contains("win")) {
			return pickFileWindows();
		}
		if (osName.contains("mac") || osName.contains("darwin")) {
			return pickFileMac(promptTitle);
		}
		return pickFileLinux();
	}

	/**
	 * Windows has no kdialog/zenity equivalent preinstalled, so this shells
	 * out to PowerShell (present on every supported Windows version) to drive
	 * WinForms' own {@code OpenFileDialog} - a real native dialog, still
	 * without touching AWT/Swing.
	 */
	private static Path pickFileWindows() throws IOException, InterruptedException {
		String home = System.getProperty("user.home", ".").replace("'", "''");
		String script = "Add-Type -AssemblyName System.Windows.Forms; " +
				"$f = New-Object System.Windows.Forms.OpenFileDialog; " +
				"$f.InitialDirectory = '" + home + "'; " +
				"$f.Filter = 'PNG images (*.png)|*.png'; " +
				"if ($f.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $f.FileName }";
		Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
				.redirectErrorStream(false).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		process.waitFor();
		return output.isBlank() ? null : Path.of(output);
	}

	private static Path pickFileMac(String promptTitle) throws IOException, InterruptedException {
		String script = "try\nPOSIX path of (choose file with prompt \"" + promptTitle.replace("\"", "'") + "\" of type {\"png\"})\non error\nreturn \"\"\nend try";
		Process process = new ProcessBuilder("osascript", "-e", script).redirectErrorStream(false).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		process.waitFor();
		return output.isBlank() ? null : Path.of(output);
	}

	private static Path pickFileLinux() throws IOException, InterruptedException {
		String home = System.getProperty("user.home", ".");
		String[][] candidates = {
				{"kdialog", "--getopenfilename", home, "*.png|PNG images"},
				{"zenity", "--file-selection", "--file-filter=PNG images | *.png"},
		};
		IOException lastMissing = null;
		for (String[] command : candidates) {
			Process process;
			try {
				process = new ProcessBuilder(command).redirectErrorStream(false).start();
			} catch (IOException notInstalled) {
				lastMissing = notInstalled;
				continue;
			}
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			int exitCode = process.waitFor();
			if (exitCode == 0 && !output.isBlank()) {
				return Path.of(output);
			}
			// Non-zero exit from a dialog that DID launch means the user
			// cancelled it - a real, final answer, not a reason to try the
			// next candidate tool.
			return null;
		}
		throw new IOException("No native file picker found (tried kdialog, zenity)", lastMissing);
	}
}
