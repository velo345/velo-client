package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import net.veloclient.launcher.LauncherLog;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Copying text that has to arrive (sign-in codes). JavaFX's clipboard silently drops text on some Windows machines -
 * another app holding the clipboard open (clipboard history, Discord, password managers) makes OpenClipboard fail and
 * JavaFX doesn't say so. So: write, read back, and fall back to AWT and finally Windows' own clip.exe.
 */
public final class Clipboards {

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	private Clipboards() {
	}

	/** Copies {@code text}; true when it is verifiably on the clipboard. Call from the FX thread. */
	public static boolean copy(String text) {
		if (text == null) {
			return false;
		}
		for (int attempt = 0; attempt < 3; attempt++) {
			if (tryJavaFx(text)) {
				return true;
			}
		}
		if (tryAwt(text)) {
			return true;
		}
		if (WINDOWS && tryClipExe(text)) {
			// clip.exe runs outside this process, so JavaFX may still report the old text for a moment.
			return true;
		}
		LauncherLog.info("Clipboard: copy failed on every route");
		return false;
	}

	private static boolean tryJavaFx(String text) {
		if (!Platform.isFxApplicationThread()) {
			return false;
		}
		try {
			ClipboardContent content = new ClipboardContent();
			content.putString(text);
			Clipboard clipboard = Clipboard.getSystemClipboard();
			return clipboard.setContent(content) && text.equals(clipboard.getString());
		} catch (Exception e) {
			LauncherLog.info("Clipboard (JavaFX): " + e);
			return false;
		}
	}

	private static boolean tryAwt(String text) {
		try {
			java.awt.datatransfer.Clipboard clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
			clipboard.setContents(new java.awt.datatransfer.StringSelection(text), null);
			Object back = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
			return text.equals(back);
		} catch (Throwable e) {
			LauncherLog.info("Clipboard (AWT): " + e);
			return false;
		}
	}

	private static boolean tryClipExe(String text) {
		try {
			Process process = new ProcessBuilder("clip.exe").redirectErrorStream(true).start();
			// clip.exe reads UTF-16LE when the input starts with a BOM - safe for any text, not just digits.
			try (var out = process.getOutputStream()) {
				out.write(new byte[] {(byte) 0xFF, (byte) 0xFE});
				out.write(text.getBytes(StandardCharsets.UTF_16LE));
			}
			return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0;
		} catch (Exception e) {
			LauncherLog.info("Clipboard (clip.exe): " + e);
			return false;
		}
	}
}
