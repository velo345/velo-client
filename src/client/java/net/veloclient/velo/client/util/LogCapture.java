package net.veloclient.velo.client.util;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Feeds the in-game Client Log Viewer (design spec section 6.3) by tailing
 * this client's own {@code logs/latest.log} rather than hooking into Log4j2's
 * live {@code Configuration} - mod initializers run before Minecraft finishes
 * setting up its own logging config, so an appender registered that early
 * against the bootstrap config silently vanishes once the real config
 * replaces it (the viewer would just stay empty forever). The log file on
 * disk is always complete and append-only, so tailing it sidesteps that race
 * entirely. Read-only observation of the client's own log output - never
 * sends anything anywhere.
 */
public final class LogCapture {

	private static final int MAX_LINES = 4000;
	private static final long REFRESH_INTERVAL_MS = 300;

	private static final Deque<String> LINES = new ArrayDeque<>();
	private static long filePosition;
	private static long lastRefresh;
	private static StringBuilder partialLine = new StringBuilder();

	private LogCapture() {
	}

	/** Kept for call-site compatibility - tailing needs no install step, just the first {@link #snapshot()} call. */
	public static void install() {
	}

	public static synchronized List<String> snapshot() {
		long now = System.currentTimeMillis();
		if (now - lastRefresh >= REFRESH_INTERVAL_MS) {
			lastRefresh = now;
			refresh();
		}
		return List.copyOf(LINES);
	}

	private static void refresh() {
		Path logFile = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
		try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
			long length = raf.length();
			if (length < filePosition) {
				// Log rotated out from under us (e.g. a new session) - start over.
				filePosition = 0;
				LINES.clear();
				partialLine = new StringBuilder();
			}
			raf.seek(filePosition);
			byte[] buffer = new byte[(int) (length - filePosition)];
			raf.readFully(buffer);
			filePosition = length;

			String chunk = new String(buffer, java.nio.charset.StandardCharsets.UTF_8);
			int start = 0;
			for (int i = 0; i < chunk.length(); i++) {
				if (chunk.charAt(i) == '\n') {
					partialLine.append(chunk, start, i);
					addLine(partialLine.toString());
					partialLine = new StringBuilder();
					start = i + 1;
				}
			}
			partialLine.append(chunk, start, chunk.length());
		} catch (IOException e) {
			// Log file not created yet, or momentarily locked by rotation - try again next refresh.
		}
	}

	private static void addLine(String line) {
		String trimmed = line.stripTrailing();
		if (trimmed.isEmpty()) {
			return;
		}
		LINES.addLast(trimmed);
		while (LINES.size() > MAX_LINES) {
			LINES.pollFirst();
		}
	}
}
