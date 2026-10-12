package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Bug and crash reports sent from the game (pause menu "Report a bug", or the "it crashed last
 * time" prompt) and from the launcher's crash dialog. Each report is one gzip'd JSON file in
 * {@code reports/}; the owner reads them in the launcher.
 */
final class ReportService {

	static final int MAX_REPORT_BYTES = 4 * 1024 * 1024;
	private static final int MAX_LOG_CHARS = 600_000;
	private static final Gson GSON = new GsonBuilder().create();

	static final class Report {
		String id;
		long createdAt;
		/** "crash" or "bug". */
		String kind;
		/** "game" or "launcher". */
		String source;
		String uuid;
		String username;
		String message;
		String title;
		Map<String, String> system;
		List<String> mods;
		List<String> modules;
		String log;
		String crash;
		/** new, seen, fixed, wontfix. */
		String status = "new";
		String note;
		/** Sent automatically (opt-in crash telemetry) rather than by the player pressing Send. */
		boolean auto;
	}

	/**
	 * {@code group} clusters reports with the same cause (same root exception and top frames of
	 * the stack, or the same normalized title when there's no stack trace); {@code groupLabel} is a
	 * short readable name for it.
	 */
	record Summary(String id, long createdAt, String kind, String source, String username, String title, String message, String status,
			String version, String minecraft, String group, String groupLabel, boolean auto) {
	}

	private final Path dir;
	private final Map<String, Summary> index = new ConcurrentHashMap<>();

	ReportService(Path dataDir) throws IOException {
		this.dir = dataDir.resolve("reports");
		Files.createDirectories(dir);
		try (var files = Files.list(dir)) {
			for (Path path : files.filter(p -> p.toString().endsWith(".json.gz")).toList()) {
				try {
					Report report = read(path);
					index.put(report.id, summary(report));
				} catch (IOException | RuntimeException ignored) {
					// Skip a broken file.
				}
			}
		}
	}

	String submit(Report incoming, String uuid, String username) throws IOException {
		Report report = new Report();
		report.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		report.createdAt = System.currentTimeMillis();
		report.kind = "crash".equals(incoming.kind) ? "crash" : "bug";
		report.source = "launcher".equals(incoming.source) ? "launcher" : "game";
		report.uuid = uuid;
		report.username = username != null ? username : clip(incoming.username, 16);
		report.message = clip(incoming.message, 4000);
		report.title = clip(incoming.title, 200);
		report.system = incoming.system;
		report.mods = incoming.mods;
		report.modules = incoming.modules;
		report.log = tail(incoming.log);
		report.crash = clip(incoming.crash, MAX_LOG_CHARS);
		report.auto = incoming.auto;
		write(report);
		index.put(report.id, summary(report));
		return report.id;
	}

	List<Summary> list() {
		return index.values().stream().sorted(Comparator.comparingLong(s -> -s.createdAt())).toList();
	}

	Report get(String id) throws IOException {
		Path path = path(id);
		if (path == null || !Files.exists(path)) {
			throw new NewsService.NewsException(404, "No such report");
		}
		return read(path);
	}

	void setStatus(String id, String status, String note) throws IOException {
		if (!List.of("new", "seen", "fixed", "wontfix").contains(status)) {
			throw new NewsService.NewsException(400, "Unknown status");
		}
		Report report = get(id);
		report.status = status;
		if (note != null) {
			report.note = clip(note, 2000);
		}
		write(report);
		index.put(id, summary(report));
	}

	void delete(String id) throws IOException {
		Path path = path(id);
		if (path == null || !Files.deleteIfExists(path)) {
			throw new NewsService.NewsException(404, "No such report");
		}
		index.remove(id);
	}

	private Path path(String id) {
		return id != null && id.matches("[0-9a-f]{12}") ? dir.resolve(id + ".json.gz") : null;
	}

	private void write(Report report) throws IOException {
		Path tmp = dir.resolve(report.id + ".tmp");
		try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
			out.write(GSON.toJson(report).getBytes(StandardCharsets.UTF_8));
		}
		Files.move(tmp, path(report.id), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	private static Report read(Path path) throws IOException {
		try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
			return GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Report.class);
		}
	}

	private static Summary summary(Report r) {
		String version = r.system == null ? null : r.system.get("Velo Client");
		String minecraft = r.system == null ? null : r.system.get("Minecraft");
		String message = r.message == null ? "" : r.message.length() > 160 ? r.message.substring(0, 160) + "..." : r.message;
		String[] signature = signature(r);
		return new Summary(r.id, r.createdAt, r.kind, r.source, r.username, r.title, message, r.status, version, minecraft,
				signature[0], signature[1], r.auto);
	}

	private static final java.util.regex.Pattern EXCEPTION = java.util.regex.Pattern.compile(
			"^(?:Caused by: )?((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable))(?::.*)?$");
	private static final java.util.regex.Pattern FRAME = java.util.regex.Pattern.compile("^\\s*at\\s+([\\w$.<>/]+)\\(");

	/**
	 * {@code [groupId, label]}. The root cause (the last "Caused by") plus its first three frames
	 * outside the JDK, with line numbers, lambda/mixin-handler counters and the "Caused by" chain
	 * above it ignored - so the same bug lands in one group across versions and players.
	 */
	static String[] signature(Report r) {
		String text = r.crash != null ? r.crash : "crash".equals(r.kind) ? r.log : null;
		if (text != null) {
			String exception = null;
			List<String> frames = new java.util.ArrayList<>();
			List<String> current = new java.util.ArrayList<>();
			for (String raw : text.split("\\R")) {
				String line = raw.strip();
				if (line.startsWith("A detailed walkthrough of the error")) {
					break; // Minecraft crash reports repeat the stack below; the first one is the real one.
				}
				var ex = EXCEPTION.matcher(line);
				if (ex.matches()) {
					exception = ex.group(1);
					current = new java.util.ArrayList<>();
					frames = current;
					continue;
				}
				var frame = FRAME.matcher(raw);
				if (exception != null && frame.find() && current.size() < 3) {
					String method = frame.group(1);
					if (method.startsWith("java.") || method.startsWith("jdk.") || method.startsWith("sun.")) {
						continue;
					}
					current.add(method.replaceAll("\\$lambda\\$\\w*\\$\\d+", "\\$lambda")
							.replaceAll("\\$[a-z]{3}\\d{3}\\$", "\\$")
							.replaceAll("\\$\\d+", "\\$"));
				}
			}
			if (exception != null) {
				String key = exception + "|" + String.join("|", frames);
				String shortEx = exception.substring(exception.lastIndexOf('.') + 1);
				String top = frames.isEmpty() ? "" : " at " + shortName(frames.get(0));
				return new String[] {hash(key), shortEx + top};
			}
		}
		String title = r.title != null && !r.title.isBlank() ? r.title : r.message != null ? r.message : "";
		String normalized = title.toLowerCase(java.util.Locale.ROOT).replaceAll("0x[0-9a-f]+|\\d+", "#").replaceAll("\\s+", " ").strip();
		String label = title.length() > 80 ? title.substring(0, 80) + "..." : title;
		return new String[] {hash(r.kind + "|" + normalized), label.isBlank() ? "(no title)" : label};
	}

	/** "net.veloclient.velo.client.Foo.bar" -> "Foo.bar". */
	private static String shortName(String method) {
		int dot = method.lastIndexOf('.');
		int classDot = dot > 0 ? method.lastIndexOf('.', dot - 1) : -1;
		return classDot >= 0 ? method.substring(classDot + 1) : method;
	}

	private static String hash(String key) {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest).substring(0, 10);
		} catch (java.security.NoSuchAlgorithmException e) {
			return Integer.toHexString(key.hashCode());
		}
	}

	private static String clip(String text, int max) {
		if (text == null) {
			return null;
		}
		return text.length() > max ? text.substring(0, max) : text;
	}

	/** Keeps the end of a long log - that's where the problem usually is. */
	private static String tail(String log) {
		if (log == null || log.length() <= MAX_LOG_CHARS) {
			return log;
		}
		return "[... " + (log.length() - MAX_LOG_CHARS) + " earlier characters cut ...]\n" + log.substring(log.length() - MAX_LOG_CHARS);
	}
}
