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
		return pickFile(promptTitle, "PNG images", "png");
	}

	/** A PNG or (for animated capes) GIF image. */
	public static Path pickImageFile(String promptTitle) throws IOException, InterruptedException {
		return pickFile(promptTitle, "Images", "png", "gif");
	}

	/**
	 * @param promptTitle    shown on macOS/Linux dialogs that support a custom prompt (Windows' OpenFileDialog has none)
	 * @param filterLabel    e.g. {@code "Litematica schematics"} - shown next to the extension in the dialog's type dropdown
	 * @param extensionNoDot the file extension to filter to, without a leading dot (e.g. {@code "litematic"})
	 * @return the chosen file, or {@code null} if the user cancelled.
	 */
	public static Path pickFile(String promptTitle, String filterLabel, String... extensionsNoDot) throws IOException, InterruptedException {
		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (osName.contains("win")) {
			return pickFileWindows(filterLabel, extensionsNoDot);
		}
		if (osName.contains("mac") || osName.contains("darwin")) {
			return pickFileMac(promptTitle, extensionsNoDot);
		}
		return pickFileLinux(filterLabel, extensionsNoDot);
	}

	private static String globs(String[] extensions, String separator) {
		StringBuilder sb = new StringBuilder();
		for (String extension : extensions) {
			if (sb.length() > 0) {
				sb.append(separator);
			}
			sb.append("*.").append(extension);
		}
		return sb.toString();
	}

	/**
	 * Windows has no kdialog/zenity equivalent preinstalled, so this shells
	 * out to PowerShell (present on every supported Windows version) to drive
	 * WinForms' own {@code OpenFileDialog} - a real native dialog, still
	 * without touching AWT/Swing.
	 */
	private static Path pickFileWindows(String filterLabel, String[] extensions) throws IOException, InterruptedException {
		String home = System.getProperty("user.home", ".").replace("'", "''");
		String script = "Add-Type -AssemblyName System.Windows.Forms; " +
				"$f = New-Object System.Windows.Forms.OpenFileDialog; " +
				"$f.InitialDirectory = '" + home + "'; " +
				"$f.Filter = '" + filterLabel.replace("'", "''") + " (" + globs(extensions, ", ") + ")|" + globs(extensions, ";") + "'; " +
				"if ($f.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $f.FileName }";
		Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
				.redirectErrorStream(false).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		process.waitFor();
		return output.isBlank() ? null : Path.of(output);
	}

	private static Path pickFileMac(String promptTitle, String[] extensions) throws IOException, InterruptedException {
		String types = "\"" + String.join("\", \"", extensions) + "\"";
		String script = "try\nPOSIX path of (choose file with prompt \"" + promptTitle.replace("\"", "'") + "\" of type {" + types + "})\non error\nreturn \"\"\nend try";
		Process process = new ProcessBuilder("osascript", "-e", script).redirectErrorStream(false).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		process.waitFor();
		return output.isBlank() ? null : Path.of(output);
	}

	private static Path pickFileLinux(String filterLabel, String[] extensions) throws IOException, InterruptedException {
		String home = System.getProperty("user.home", ".");
		String[][] candidates = {
				{"kdialog", "--getopenfilename", home, globs(extensions, " ") + "|" + filterLabel},
				{"zenity", "--file-selection", "--file-filter=" + filterLabel + " | " + globs(extensions, " ")},
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

	/**
	 * A native "save as" dialog for a PNG, starting in the home folder with {@code suggestedName}.
	 * @return the chosen file (".png" added if missing), or {@code null} if the user cancelled.
	 */
	public static Path savePngFile(String promptTitle, String suggestedName) throws IOException, InterruptedException {
		return saveFile(promptTitle, suggestedName, "PNG images", "png");
	}

	/**
	 * A native "save as" dialog for one file type ({@code extensionNoDot}, e.g. "wav"), starting in the home folder.
	 * @return the chosen file (the extension added if missing), or {@code null} if the user cancelled.
	 */
	public static Path saveFile(String promptTitle, String suggestedName, String filterLabel, String extensionNoDot) throws IOException,
			InterruptedException {
		String ext = "." + extensionNoDot;
		String glob = "*" + ext;
		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home", ".");
		String output;
		if (osName.contains("win")) {
			String script = "Add-Type -AssemblyName System.Windows.Forms; "
					+ "$f = New-Object System.Windows.Forms.SaveFileDialog; "
					+ "$f.InitialDirectory = '" + home.replace("'", "''") + "'; "
					+ "$f.FileName = '" + suggestedName.replace("'", "''") + "'; "
					+ "$f.Filter = '" + filterLabel.replace("'", "''") + " (" + glob + ")|" + glob + "'; "
					+ "if ($f.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $f.FileName }";
			output = run(new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script));
		} else if (osName.contains("mac") || osName.contains("darwin")) {
			String script = "try\nPOSIX path of (choose file name with prompt \"" + promptTitle.replace("\"", "'") + "\" default name \""
					+ suggestedName.replace("\"", "'") + "\")\non error\nreturn \"\"\nend try";
			output = run(new ProcessBuilder("osascript", "-e", script));
		} else {
			String start = Path.of(home, suggestedName).toString();
			String[][] candidates = {
					{"kdialog", "--getsavefilename", start, glob + "|" + filterLabel},
					{"zenity", "--file-selection", "--save", "--confirm-overwrite", "--filename=" + start, "--file-filter=" + filterLabel + " | " + glob},
			};
			output = null;
			IOException lastMissing = null;
			for (String[] command : candidates) {
				try {
					output = run(new ProcessBuilder(command));
					break;
				} catch (IOException notInstalled) {
					lastMissing = notInstalled;
				}
			}
			if (output == null && lastMissing != null) {
				throw new IOException("No native file picker found (tried kdialog, zenity)", lastMissing);
			}
		}
		if (output == null || output.isBlank()) {
			return null;
		}
		return Path.of(output.toLowerCase(Locale.ROOT).endsWith(ext) ? output : output + ext);
	}

	/** Runs a dialog process; its trimmed output, or "" when it was cancelled. */
	private static String run(ProcessBuilder builder) throws IOException, InterruptedException {
		Process process = builder.redirectErrorStream(false).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		return process.waitFor() == 0 ? output : "";
	}
}
