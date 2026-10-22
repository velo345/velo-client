package net.veloclient.panel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every sign-in and every change made through the panel, one JSON line each (append-only file). */
final class Audit {

	private final Path file;

	Audit(Path file) {
		this.file = file;
	}

	synchronized void log(String user, String ip, String action, String detail) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("time", System.currentTimeMillis());
		row.put("user", user);
		row.put("ip", ip);
		row.put("action", action);
		row.put("detail", detail == null ? "" : detail.length() > 500 ? detail.substring(0, 500) : detail);
		try {
			Files.writeString(file, Http.GSON.toJson(row) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			System.out.println("[panel] audit log not written: " + e.getMessage());
		}
		System.out.println("[audit] " + user + " " + ip + " " + action + " " + row.get("detail"));
	}

	/** The newest {@code limit} entries, newest first. */
	synchronized List<Object> recent(int limit) throws IOException {
		if (!Files.exists(file)) {
			return List.of();
		}
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		List<Object> out = new ArrayList<>();
		for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
			try {
				out.add(Http.GSON.fromJson(lines.get(i), Map.class));
			} catch (RuntimeException ignored) {
				// A torn line from a crash - skip it.
			}
		}
		return Collections.unmodifiableList(out);
	}
}
