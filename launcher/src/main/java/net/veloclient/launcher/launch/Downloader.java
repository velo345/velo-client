package net.veloclient.launcher.launch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import java.util.function.LongConsumer;

/**
 * Shared download-to-file helper for libraries/assets/client jars: skips
 * re-downloading a file already present with the expected sha1/size, verifies
 * whatever it does download, and reports bytes as they arrive so callers can
 * aggregate an overall progress fraction.
 */
public final class Downloader {

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/**
	 * Callers (asset/library installers) fan out one virtual thread per file,
	 * which for a modern asset index is thousands of files at once - without
	 * a cap here they all open a connection to Mojang's CDN simultaneously,
	 * which reliably got connections reset/refused mid-download. This bounds
	 * how many downloads are actually in flight at once, process-wide,
	 * regardless of how many callers are queued up.
	 */
	private static final Semaphore CONCURRENT_DOWNLOADS = new Semaphore(24);

	private Downloader() {
	}

	/**
	 * @param expectedSha1 nullable - when present, an existing file is trusted only if it matches, and a freshly
	 *                     downloaded file is verified against it
	 * @param onBytes      called with the number of new bytes read/skipped, for progress aggregation
	 */
	public static void ensure(URI url, Path dest, String expectedSha1, long expectedSize, LongConsumer onBytes) throws IOException {
		if (Files.exists(dest) && matches(dest, expectedSha1, expectedSize)) {
			onBytes.accept(Files.size(dest));
			return;
		}
		Files.createDirectories(dest.getParent());
		Path temp = dest.resolveSibling(dest.getFileName() + ".part");
		try {
			CONCURRENT_DOWNLOADS.acquire();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Download interrupted: " + url, e);
		}
		try {
			IOException lastError = null;
			for (int attempt = 0; attempt < 2; attempt++) {
				try {
					downloadOnce(url, temp, onBytes);
					if (expectedSha1 != null && !sha1Of(temp).equalsIgnoreCase(expectedSha1)) {
						throw new IOException("Checksum mismatch downloading " + url + " (expected " + expectedSha1 + ")");
					}
					Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
					return;
				} catch (IOException e) {
					lastError = e;
					Files.deleteIfExists(temp);
				}
			}
			throw lastError;
		} finally {
			CONCURRENT_DOWNLOADS.release();
		}
	}

	private static void downloadOnce(URI url, Path temp, LongConsumer onBytes) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(2)).GET().build();
		try {
			HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() >= 400) {
				throw new IOException("HTTP " + response.statusCode() + " downloading " + url);
			}
			try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(temp)) {
				byte[] buffer = new byte[16 * 1024];
				int read;
				while ((read = in.read(buffer)) != -1) {
					out.write(buffer, 0, read);
					onBytes.accept(read);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Download interrupted: " + url, e);
		}
	}

	private static boolean matches(Path file, String expectedSha1, long expectedSize) throws IOException {
		if (expectedSize >= 0 && Files.size(file) != expectedSize) {
			return false;
		}
		return expectedSha1 == null || sha1Of(file).equalsIgnoreCase(expectedSha1);
	}

	public static String sha1Of(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			try (InputStream in = Files.newInputStream(file)) {
				byte[] buffer = new byte[16 * 1024];
				int read;
				while ((read = in.read(buffer)) != -1) {
					digest.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
