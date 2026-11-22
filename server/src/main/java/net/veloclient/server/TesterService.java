package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tester programme (owner 2026-11-22): players with the Discord tester role get the separate Velo Client Tester
 * launcher with everything still in development. Builds live only here (CI uploads them, never a public GitHub
 * release); only testers and owners can list and download them. Per tester and build the server keeps what they
 * actually did - downloaded, launched, minutes played, which features they used, and their feedback on each "what to
 * test" item - so the owner sees who really tests.
 *
 * <p>Files: {@code data/tester/testers.json} (synced from the bot), {@code data/tester/stats.json},
 * {@code data/tester/builds/<version>/notes.json} plus the build's files.
 */
final class TesterService {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long MAX_FILE = 1024L * 1024 * 1024;
	/** Builds kept on disk (each is ~2-3 GB with every platform's installer and update zip). */
	private static final int KEEP_BUILDS = 3;

	static final class Tester {
		String uuid;
		String name;
		String discordId;
		long since;
	}

	static final class Feedback {
		String state;      // "works" / "broken" / "skip"
		String comment;
		long at;
	}

	static final class Stat {
		long downloadedAt;
		int downloads;
		int launches;
		double minutes;
		long lastSeen;
		long seenNotesAt;
		Set<String> features = new LinkedHashSet<>();
		Map<String, Feedback> feedback = new LinkedHashMap<>();
	}

	private static final class Data {
		Map<String, Tester> testers = new LinkedHashMap<>();
		/** uuid -> version -> stat */
		Map<String, Map<String, Stat>> stats = new LinkedHashMap<>();
	}

	private final Path dir;
	private final Path buildsDir;
	private final DiscordLinks links;
	private Data data = new Data();
	private final Map<String, JsonObject> notesCache = new ConcurrentHashMap<>();

	TesterService(Path dataDir, DiscordLinks links) throws IOException {
		this.dir = dataDir.resolve("tester");
		this.buildsDir = dir.resolve("builds");
		this.links = links;
		Files.createDirectories(buildsDir);
		Path file = dir.resolve("tester.json");
		if (Files.exists(file)) {
			try {
				Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
				if (loaded != null) {
					data = loaded;
					if (data.testers == null) {
						data.testers = new LinkedHashMap<>();
					}
					if (data.stats == null) {
						data.stats = new LinkedHashMap<>();
					}
				}
			} catch (RuntimeException e) {
				System.out.println("[tester] couldn't read tester.json: " + e.getMessage());
			}
		}
	}

	private synchronized void save() {
		try {
			Path tmp = dir.resolve("tester.json.tmp");
			Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
			Files.move(tmp, dir.resolve("tester.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[tester] save failed: " + e.getMessage());
		}
	}

	private static String key(String uuid) {
		return uuid == null ? "" : uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
	}

	synchronized boolean isTester(String uuid) {
		return data.testers.containsKey(key(uuid));
	}

	/** The bot's tester role holders (Discord ids): linked accounts become testers, everyone else stops being one. */
	synchronized Map<String, Object> sync(List<String> discordIds) {
		Map<String, Tester> next = new LinkedHashMap<>();
		int unlinked = 0;
		for (String discordId : discordIds) {
			DiscordLinks.Link link = links.byDiscordId(discordId);
			if (link == null) {
				unlinked++;
				continue;
			}
			String k = key(link.uuid());
			Tester previous = data.testers.get(k);
			Tester t = new Tester();
			t.uuid = k;
			t.name = link.username();
			t.discordId = discordId;
			t.since = previous != null ? previous.since : System.currentTimeMillis();
			next.put(k, t);
		}
		int added = (int) next.keySet().stream().filter(k -> !data.testers.containsKey(k)).count();
		int removed = (int) data.testers.keySet().stream().filter(k -> !next.containsKey(k)).count();
		data.testers = next;
		save();
		return Map.of("testers", next.size(), "added", added, "removed", removed, "unlinked", unlinked);
	}

	// ---- builds ----

	private static boolean safeVersion(String v) {
		return v != null && v.matches("[0-9]+(\\.[0-9]+){2,3}");
	}

	private static boolean safeName(String n) {
		return n != null && n.matches("[A-Za-z0-9._+-]{1,120}") && !n.contains("..");
	}

	Path filePath(String version, String name) {
		return safeVersion(version) && safeName(name) ? buildsDir.resolve(version).resolve(name) : null;
	}

	/** CI upload of one build file, streamed to disk. */
	long putFile(String version, String name, InputStream body) throws IOException {
		Path target = filePath(version, name);
		if (target == null || name.equals("notes.json")) {
			throw new IllegalArgumentException("Bad version or file name");
		}
		Files.createDirectories(target.getParent());
		Path tmp = target.resolveSibling(name + ".part");
		long total = 0;
		try (InputStream in = body; var out = Files.newOutputStream(tmp)) {
			byte[] buffer = new byte[1 << 16];
			for (int n; (n = in.read(buffer)) > 0; ) {
				total += n;
				if (total > MAX_FILE) {
					throw new IOException("File too large");
				}
				out.write(buffer, 0, n);
			}
		} catch (IOException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		prune(version);
		return total;
	}

	/** Only the newest builds stay on disk (their notes and the testers' stats stay). */
	private void prune(String keepAtLeast) {
		try (var folders = Files.list(buildsDir)) {
			List<String> versions = folders.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(TesterService::safeVersion)
					.sorted(TesterService::compareVersions).toList();
			for (int i = 0; i < versions.size() - KEEP_BUILDS; i++) {
				String old = versions.get(i);
				if (old.equals(keepAtLeast)) {
					continue;
				}
				try (var files = Files.list(buildsDir.resolve(old))) {
					for (Path f : files.toList()) {
						if (!f.getFileName().toString().equals("notes.json")) {
							Files.deleteIfExists(f);
						}
					}
				}
			}
		} catch (IOException e) {
			System.out.println("[tester] prune failed: " + e.getMessage());
		}
	}

	/** The build's "what changed / what to test" notes (tester/builds/<version>.json in the repo). */
	void putNotes(String version, String json) throws IOException {
		if (!safeVersion(version)) {
			throw new IllegalArgumentException("Bad version");
		}
		JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
		Path folder = buildsDir.resolve(version);
		Files.createDirectories(folder);
		if (!parsed.has("createdAt")) {
			parsed.addProperty("createdAt", System.currentTimeMillis());
		}
		Files.writeString(folder.resolve("notes.json"), parsed.toString(), StandardCharsets.UTF_8);
		notesCache.remove(version);
	}

	private JsonObject notes(String version) {
		return notesCache.computeIfAbsent(version, v -> {
			try {
				Path f = buildsDir.resolve(v).resolve("notes.json");
				return Files.exists(f) ? JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
			} catch (IOException | RuntimeException e) {
				return new JsonObject();
			}
		});
	}

	/** Every build, newest first: version, notes, files (name + size). */
	List<Map<String, Object>> builds() {
		List<Map<String, Object>> out = new ArrayList<>();
		try (var folders = Files.list(buildsDir)) {
			for (Path folder : folders.filter(Files::isDirectory).toList()) {
				String version = folder.getFileName().toString();
				if (!safeVersion(version)) {
					continue;
				}
				List<Map<String, Object>> files = new ArrayList<>();
				try (var list = Files.list(folder)) {
					for (Path f : list.filter(p -> !p.getFileName().toString().equals("notes.json") && !p.toString().endsWith(".part")).sorted().toList()) {
						files.add(Map.of("name", f.getFileName().toString(), "size", Files.size(f)));
					}
				}
				Map<String, Object> b = new LinkedHashMap<>();
				b.put("version", version);
				b.put("notes", notes(version));
				b.put("files", files);
				out.add(b);
			}
		} catch (IOException ignored) {
			// none yet
		}
		out.sort(Comparator.comparing((Map<String, Object> b) -> (String) b.get("version"), TesterService::compareVersions).reversed());
		return out;
	}

	static int compareVersions(String a, String b) {
		String[] pa = a.split("\\.");
		String[] pb = b.split("\\.");
		for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
			int x = i < pa.length ? Integer.parseInt(pa[i]) : 0;
			int y = i < pb.length ? Integer.parseInt(pb[i]) : 0;
			if (x != y) {
				return Integer.compare(x, y);
			}
		}
		return 0;
	}

	/** The Discord member behind a /tester request, if they're a linked tester (else null). */
	synchronized Tester testerByDiscord(String discordId) {
		for (Tester t : data.testers.values()) {
			if (discordId.equals(t.discordId)) {
				return t;
			}
		}
		return null;
	}

	/** The newest build's installer for a system: windows / mac-arm / mac-intel / linux-deb / linux-tar. */
	Path newestInstaller(String os) {
		for (Map<String, Object> b : builds()) {
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> files = (List<Map<String, Object>>) b.get("files");
			for (Map<String, Object> f : files) {
				String name = (String) f.get("name");
				boolean match = switch (os == null ? "" : os) {
					case "windows" -> name.endsWith(".exe");
					case "mac-arm" -> name.endsWith("-macos-arm64.dmg");
					case "mac-intel" -> name.endsWith("-macos-x64.dmg");
					case "linux-deb" -> name.endsWith(".deb");
					case "linux-tar" -> name.endsWith("-linux-x64.tar.gz");
					default -> false;
				};
				if (match) {
					return buildsDir.resolve((String) b.get("version")).resolve(name);
				}
			}
		}
		return null;
	}

	// ---- what testers do ----	// ---- what testers do ----

	private Stat stat(String uuid, String version) {
		return data.stats.computeIfAbsent(key(uuid), k -> new LinkedHashMap<>()).computeIfAbsent(version, v -> new Stat());
	}

	synchronized void downloaded(String uuid, String version, String fileName) {
		if (!safeVersion(version)) {
			return;
		}
		Stat s = stat(uuid, version);
		if (s.downloadedAt == 0) {
			s.downloadedAt = System.currentTimeMillis();
		}
		s.downloads++;
		s.lastSeen = System.currentTimeMillis();
		save();
	}

	/** launch / session (minutes + modules on at the end) / seen (opened the notes) / feedback (item + state + comment). */
	synchronized void event(String uuid, JsonObject e) {
		String version = e.has("version") ? e.get("version").getAsString() : null;
		String kind = e.has("kind") ? e.get("kind").getAsString() : "";
		if (!safeVersion(version)) {
			throw new IllegalArgumentException("Bad version");
		}
		Stat s = stat(uuid, version);
		long now = System.currentTimeMillis();
		s.lastSeen = now;
		switch (kind) {
			case "launch" -> s.launches++;
			case "session" -> {
				double minutes = e.has("minutes") ? e.get("minutes").getAsDouble() : 0;
				s.minutes += Math.max(0, Math.min(minutes, 24 * 60));
				if (e.has("modules") && e.get("modules").isJsonArray()) {
					for (var m : e.getAsJsonArray("modules")) {
						if (s.features.size() < 400) {
							s.features.add(m.getAsString());
						}
					}
				}
			}
			case "feature" -> {
				if (e.has("id") && s.features.size() < 400) {
					s.features.add(e.get("id").getAsString());
				}
			}
			case "seen" -> s.seenNotesAt = now;
			case "feedback" -> {
				String item = e.has("item") ? e.get("item").getAsString() : null;
				if (item == null || item.length() > 80) {
					throw new IllegalArgumentException("Bad item");
				}
				Feedback f = new Feedback();
				f.state = e.has("state") ? e.get("state").getAsString() : "works";
				f.comment = e.has("comment") ? clip(e.get("comment").getAsString(), 1500) : null;
				f.at = now;
				s.feedback.put(item, f);
			}
			default -> throw new IllegalArgumentException("Unknown event");
		}
		save();
	}

	private static String clip(String s, int max) {
		return s == null ? null : s.length() > max ? s.substring(0, max) : s;
	}

	/** One tester's own progress (their feedback per build), for the Testing page. */
	synchronized Map<String, Stat> mine(String uuid) {
		return Map.copyOf(data.stats.getOrDefault(key(uuid), Map.of()));
	}

	/** Owner view: every tester with every build's stats, plus the notes' test items for the x/y counts. */
	synchronized Map<String, Object> overview() {
		List<Map<String, Object>> testers = new ArrayList<>();
		for (Tester t : data.testers.values()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("uuid", t.uuid);
			row.put("name", t.name);
			row.put("since", t.since);
			row.put("builds", data.stats.getOrDefault(t.uuid, Map.of()));
			testers.add(row);
		}
		return Map.of("testers", testers, "builds", builds());
	}
}
