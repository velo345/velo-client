package net.veloclient.launcher.launch;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Turns whatever stopped an install or launch into what it really was, for the player: their internet dropped, the disk
 * is full, the PC ran out of memory, Windows/an antivirus locked a file. Those aren't Velo bugs and must not read like
 * one ("Couldn't launch: null" made people blame us for a dead Wi-Fi).
 */
public final class LaunchErrors {

	/** {@code ours}: false = something on the player's side, so the dialog doesn't offer a bug report first. */
	public record Explained(String title, String message, boolean ours) {
	}

	private LaunchErrors() {
	}

	public static Explained explain(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
			String msg = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
			if (t instanceof OutOfMemoryError) {
				return new Explained("Your PC ran out of memory",
						"Windows / Linux couldn't give the launcher more memory. Close the browser and other programs, then press Play again.", false);
			}
			if (diskFull(t, msg)) {
				return new Explained("Your disk is full",
						"There's no space left to install Minecraft. Free up a few GB (empty the recycle bin, delete old downloads) and press Play again.",
						false);
			}
			if (t instanceof UnknownHostException || t instanceof HttpConnectTimeoutException || t instanceof ConnectException
					|| t instanceof NoRouteToHostException || t instanceof SocketTimeoutException || t instanceof HttpTimeoutException
					|| t instanceof javax.net.ssl.SSLException
					|| t instanceof SocketException && (msg.contains("reset") || msg.contains("closed") || msg.contains("abort") || msg.contains("unreachable"))
					|| t instanceof IOException && (msg.contains("connection reset") || msg.contains("timed out") || msg.contains("premature eof")
							|| msg.contains("received no bytes") || msg.contains("goaway") || msg.contains("connection closed"))) {
				return new Explained("Your internet connection dropped",
						"The download stopped because the connection to " + host(t) + " was lost or too slow. Check your internet (Wi-Fi, VPN, firewall)"
								+ " and press Play again - finished files are kept, it continues where it stopped.", false);
			}
			if (t instanceof AccessDeniedException || msg.contains("being used by another process") || msg.contains("access is denied")
					|| msg.contains("zugriff verweigert")) {
				return new Explained("A file is locked",
						"Windows or an antivirus is blocking " + fileOf(t) + ". Close other Minecraft windows, add the VeloClient folder to your antivirus"
								+ " exceptions, and press Play again.", false);
			}
		}
		String detail = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
		return new Explained("Couldn't start the game", detail, true);
	}

	private static boolean diskFull(Throwable t, String msg) {
		return msg.contains("no space left") || msg.contains("not enough space") || msg.contains("disk full")
				|| msg.contains("there is not enough space on the disk") || msg.contains("nicht genügend speicherplatz")
				|| t instanceof FileSystemException && msg.contains("space");
	}

	private static String host(Throwable t) {
		String m = t.getMessage();
		if (m != null) {
			java.util.regex.Matcher host = java.util.regex.Pattern.compile("([a-z0-9-]+\\.)+(com|net|org|io|gg)").matcher(m.toLowerCase(Locale.ROOT));
			if (host.find()) {
				return host.group();
			}
		}
		return "the download server";
	}

	private static String fileOf(Throwable t) {
		return t instanceof FileSystemException f && f.getFile() != null ? Path.of(f.getFile()).getFileName().toString() : "a game file";
	}

	/** Refuses to start a download that can't fit: a clear "disk is full" beats a half-written game. */
	public static void requireFreeSpace(Path dir, long neededBytes) throws IOException {
		try {
			Path existing = dir;
			while (existing != null && !Files.exists(existing)) {
				existing = existing.getParent();
			}
			if (existing == null) {
				return;
			}
			long free = Files.getFileStore(existing).getUsableSpace();
			if (free >= 0 && free < neededBytes) {
				throw new IOException("Not enough space left on the disk: " + (free / (1024 * 1024)) + " MB free, about "
						+ (neededBytes / (1024 * 1024)) + " MB needed");
			}
		} catch (IOException e) {
			if (String.valueOf(e.getMessage()).startsWith("Not enough space")) {
				throw e;
			}
			// can't tell (network drive...) - just try
		}
	}
}
