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
		/** The full report (text, logs, screenshots/videos) behind a "broken", see {@link Report}. */
		String reportId;
	}

	/**
	 * A tester's report from the Tester window: a "broken" test item, or a free bug report / suggestion. Text in
	 * tester.json; logs and attachments as files in tester/reports/&lt;id&gt;/.
	 */
	static final class Report {
		String id;
		String uuid;
		String name;
		String version;
		/** The test item, or null for a free report. */
		String item;
		/** "broken" (test item) / "bug" / "suggestion" */
		String type;
		String title;
		String text;
		long at;
		List<String> files = new ArrayList<>();
		/** Owner's status: "open" / "fixed" / "wontfix" */
		String status = "open";
		/** What the tester said about where it happened (sent by the Tester window; any may be null). */
		String os;
		String minecraft;
		String area;
		/** The YouTrack issue ("VELO-12") once created, its state and public comments (synced), files already uploaded. */
		String youtrack;
		String ytState;
		boolean ytResolved;
		long ytUpdated;
		List<YouTrack.Comment> ytComments = new ArrayList<>();
		Set<String> ytFiles = new LinkedHashSet<>();
		/** True when the tester hasn't seen the latest state / team comment yet. */
		boolean unread;
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
		List<Report> reports = new ArrayList<>();
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
					if (data.reports == null) {
						data.reports = new ArrayList<>();
					}
					for (Report r : data.reports) {
						if (r.ytComments == null) {
							r.ytComments = new ArrayList<>();
						}
						if (r.ytFiles == null) {
							r.ytFiles = new LinkedHashSet<>();
						}
					}
				}
			} catch (RuntimeException e) {
				System.out.println("[tester] couldn't read tester.json: " + e.getMessage());
			}
		}
	}

	/** Called once the server is up: imports reports that have no issue yet and starts the state sync. */
	void startYouTrack() {
		startSync();
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
	/** The Minecraft versions the newest build has a tester mod jar for, oldest first. */
	List<String> newestJarVersions() {
		for (Map<String, Object> b : builds()) {
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> files = (List<Map<String, Object>>) b.get("files");
			List<String> out = new ArrayList<>();
			for (Map<String, Object> f : files) {
				String name = (String) f.get("name");
				if (name.startsWith("velo-client-tester-") && name.endsWith(".jar") && name.contains("+")) {
					out.add(name.substring(name.lastIndexOf('+') + 1, name.length() - 4));
				}
			}
			if (!out.isEmpty()) {
				out.sort(Comparator.naturalOrder());
				return out;
			}
		}
		return List.of();
	}

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
					// "jar-26.2": the tester mod jar for that Minecraft version (players on another launcher)
					default -> os != null && os.startsWith("jar-") && os.length() < 20 && name.startsWith("velo-client-tester-")
							&& name.endsWith("+" + os.substring(4) + ".jar");
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

	private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
	private static final long MAX_ATTACHMENT = 200L * 1024 * 1024;
	private static final int MAX_FILES = 12;
	private static final java.util.regex.Pattern ATTACHMENT = java.util.regex.Pattern.compile(
			"[A-Za-z0-9._ ()-]{1,100}\\.(png|jpe?g|gif|webp|bmp|mp4|webm|mov|mkv|txt|log|zip|json|csv|md|toml|cfg|properties)", java.util.regex.Pattern.CASE_INSENSITIVE);

	private Path reportDir(String id) {
		return dir.resolve("reports").resolve(id);
	}

	/**
	 * A report from the Tester window (logs inline, attachments follow with {@link #putAttachment}). A "broken" test item
	 * also becomes that item's feedback.
	 */
	synchronized Report addReport(String uuid, String name, JsonObject body) throws IOException {
		String version = body.has("version") ? body.get("version").getAsString() : null;
		if (!safeVersion(version)) {
			throw new IllegalArgumentException("Bad version");
		}
		String type = body.has("type") ? body.get("type").getAsString() : "bug";
		if (!List.of("broken", "bug", "suggestion").contains(type)) {
			throw new IllegalArgumentException("Bad type");
		}
		Report r = new Report();
		byte[] idBytes = new byte[6];
		RANDOM.nextBytes(idBytes);
		r.id = java.util.HexFormat.of().formatHex(idBytes);
		r.uuid = key(uuid);
		r.name = name;
		r.version = version;
		r.item = body.has("item") && !body.get("item").isJsonNull() ? clip(body.get("item").getAsString(), 80) : null;
		r.type = type;
		r.title = clip(body.has("title") ? body.get("title").getAsString() : "", 160);
		r.text = clip(body.has("text") ? body.get("text").getAsString() : "", 8000);
		r.at = System.currentTimeMillis();
		r.os = optional(body, "os", 40);
		r.minecraft = optional(body, "minecraft", 20);
		r.area = optional(body, "area", 40);
		Path rd = reportDir(r.id);
		Files.createDirectories(rd);
		for (String log : List.of("launcherLog", "gameLog", "updateLog")) {
			if (body.has(log) && !body.get(log).isJsonNull()) {
				String name2 = log.equals("launcherLog") ? "launcher.log" : log.equals("gameLog") ? "game.log" : "update.log";
				Files.writeString(rd.resolve(name2), clip(body.get(log).getAsString(), 3 * 1024 * 1024), StandardCharsets.UTF_8);
				r.files.add(name2);
			}
		}
		data.reports.add(0, r);
		if (r.item != null) {
			Stat st = stat(uuid, version);
			Feedback f = new Feedback();
			f.state = "broken";
			f.comment = clip(r.text, 1500);
			f.at = r.at;
			f.reportId = r.id;
			st.feedback.put(r.item, f);
			st.lastSeen = r.at;
		}
		save();
		System.out.println("[tester] report " + r.id + " from " + name + " (" + type + (r.item != null ? " " + r.item : "") + "): " + r.title);
		toYouTrack(r);
		return r;
	}

	private static String optional(JsonObject body, String key, int max) {
		return body.has(key) && !body.get(key).isJsonNull() && !body.get(key).getAsString().isBlank() ? clip(body.get(key).getAsString().trim(), max) : null;
	}

	// ---- YouTrack (project VELO): issue per report, files attached, state + comments back ----

	/** One queue for every YouTrack call: in order, never two uploads of one report at once. */
	private final java.util.concurrent.ExecutorService youtrackQueue = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "velo-youtrack");
		t.setDaemon(true);
		return t;
	});
	private volatile boolean syncStarted;

	private void toYouTrack(Report r) {
		if (!YouTrack.enabled()) {
			return;
		}
		startSync();
		youtrackQueue.submit(() -> {
			try {
				if (r.youtrack == null) {
					String type = switch (r.type) {
						case "suggestion" -> "Suggestion";
						default -> r.title.toLowerCase(java.util.Locale.ROOT).contains("crash") ? "Crash" : "Bug";
					};
					Map<String, String> enums = new LinkedHashMap<>();
					enums.put("Type", type);
					enums.put("Source", r.item != null ? "Test Plan" : "Tester Window");
					enums.put("Platform", platform(r.os));
					enums.put("Minecraft", r.minecraft);
					enums.put("Subsystem", r.area);
					Map<String, String> texts = new LinkedHashMap<>();
					texts.put("Tester", r.name);
					texts.put("Tester UUID", r.uuid);
					texts.put("Test Item", r.item);
					texts.put("Velo Build", r.version);
					texts.put("Velo Report", r.id);
					String summary = r.title == null || r.title.isBlank() ? (r.item != null ? "Broken: " + r.item : "Report from " + r.name) : r.title;
					String id = YouTrack.create(summary, description(r), enums, texts, List.of("tester"));
					synchronized (this) {
						r.youtrack = id;
						r.ytState = "New";
						save();
					}
					System.out.println("[youtrack] " + r.id + " -> " + id);
				}
				uploadFiles(r);
			} catch (Exception e) {
				System.out.println("[youtrack] report " + r.id + " failed: " + e.getMessage());
			}
		});
	}

	private void uploadFiles(Report r) {
		if (r.youtrack == null) {
			return;
		}
		List<String> files;
		synchronized (this) {
			files = new ArrayList<>(r.files);
			files.removeAll(r.ytFiles);
		}
		for (String name : files) {
			Path file = reportDir(r.id).resolve(name);
			if (!Files.isRegularFile(file)) {
				continue;
			}
			try {
				YouTrack.attach(r.youtrack, file);
				String n = name.toLowerCase(java.util.Locale.ROOT);
				if (n.matches(".*\\.(mp4|webm|mov|mkv)")) {
					YouTrack.tag(r.youtrack, "has-video");
				} else if (n.matches(".*\\.(png|jpe?g|gif|webp|bmp)")) {
					YouTrack.tag(r.youtrack, "has-screenshot");
				} else if (n.endsWith(".log")) {
					YouTrack.tag(r.youtrack, "has-logs");
					if (n.equals("game.log")) {
						String log = Files.readString(file, StandardCharsets.UTF_8);
						YouTrack.tag(r.youtrack, log.contains("Vulkan") ? "vulkan" : "opengl");
						String low = log.toLowerCase(java.util.Locale.ROOT);
						for (String mod : List.of("sodium", "iris", "essential")) {
							if (low.contains(mod)) {
								YouTrack.tag(r.youtrack, mod);
							}
						}
					}
				}
				synchronized (this) {
					r.ytFiles.add(name);
					save();
				}
			} catch (IOException e) {
				System.out.println("[youtrack] " + r.youtrack + " file " + name + ": " + e.getMessage());
			}
		}
	}

	private static String platform(String os) {
		if (os == null) {
			return null;
		}
		String o = os.toLowerCase(java.util.Locale.ROOT);
		return o.contains("win") ? "Windows" : o.contains("mac") || o.contains("darwin") ? "macOS" : o.contains("linux") ? "Linux" : null;
	}

	private static String description(Report r) {
		StringBuilder sb = new StringBuilder();
		sb.append(r.text == null || r.text.isBlank() ? "_No description._" : r.text).append("\n\n---\n\n");
		sb.append("| | |\n|---|---|\n");
		sb.append("| **Tester** | ").append(r.name).append(" |\n");
		sb.append("| **Build** | ").append(r.version).append(" |\n");
		if (r.item != null) {
			sb.append("| **Test item** | ").append(r.item).append(" |\n");
		}
		if (r.os != null) {
			sb.append("| **System** | ").append(r.os).append(" |\n");
		}
		if (r.minecraft != null) {
			sb.append("| **Minecraft** | ").append(r.minecraft).append(" |\n");
		}
		sb.append("| **Report** | `").append(r.id).append("` |\n\n");
		sb.append("Screenshots, videos and the launcher / game / update logs are attached. Public comments are shown to the tester "
				+ "in their Tester window - use a comment visible to a group for team-only notes.");
		return sb.toString();
	}

	/** Every 2 minutes: state + public comments of the open reports' issues (and resolved ones for a week after). */
	private void startSync() {
		if (syncStarted || !YouTrack.enabled()) {
			return;
		}
		syncStarted = true;
		Thread.ofVirtual().name("velo-youtrack-sync").start(() -> {
			boolean first = true;
			while (true) {
				try {
					Thread.sleep(first ? 15_000 : 120_000);
					first = false;
					List<Report> open;
					synchronized (this) {
						long weekAgo = System.currentTimeMillis() - 7L * 24 * 3600_000;
						open = data.reports.stream().filter(x -> x.youtrack != null && (!x.ytResolved || x.ytUpdated > weekAgo)).toList();
						for (Report x : data.reports) {
							if (x.youtrack == null || !x.files.stream().allMatch(x.ytFiles::contains)) {
								toYouTrack(x);   // a report or file that didn't make it earlier (YouTrack down, restart)
							}
						}
					}
					for (Report x : open) {
						youtrackQueue.submit(() -> refresh(x));
					}
				} catch (InterruptedException e) {
					return;
				} catch (RuntimeException e) {
					System.out.println("[youtrack] sync: " + e.getMessage());
				}
			}
		});
	}

	private void refresh(Report r) {
		try {
			YouTrack.Status st = YouTrack.status(r.youtrack);
			synchronized (this) {
				boolean changed = !st.state().equals(r.ytState) || st.comments().size() != r.ytComments.size();
				if (changed) {
					boolean newTeamWord = st.comments().stream().filter(c -> !c.tester()).count()
							> r.ytComments.stream().filter(c -> !c.tester()).count();
					r.unread = r.unread || newTeamWord || r.ytState != null && !st.state().equals(r.ytState);
					r.ytState = st.state();
					r.ytResolved = st.resolved();
					r.ytUpdated = st.updated();
					r.ytComments = new ArrayList<>(st.comments());
					r.status = st.resolved() ? (st.state().equals("Won't Fix") || st.state().equals("Duplicate") || st.state().equals("Can't Reproduce")
							? "wontfix" : "fixed") : "open";
					save();
				}
			}
		} catch (IOException e) {
			System.out.println("[youtrack] refresh " + r.youtrack + ": " + e.getMessage());
		}
	}

	/** The tester's own reports, newest first (with YouTrack state + public comments); marks them read. */
	synchronized List<Report> myReports(String uuid, boolean markRead) {
		String me = key(uuid);
		List<Report> out = data.reports.stream().filter(x -> me.equals(x.uuid)).toList();
		if (markRead && out.stream().anyMatch(x -> x.unread)) {
			out.forEach(x -> x.unread = false);
			save();
		}
		return out;
	}

	/** A tester's reply on their own report: a YouTrack comment (marked as theirs), shown right away. */
	void reply(String uuid, String name, String reportId, String text) throws IOException {
		Report r;
		synchronized (this) {
			r = data.reports.stream().filter(x -> x.id.equals(reportId) && x.uuid.equals(key(uuid))).findFirst().orElse(null);
			if (r == null) {
				throw new IllegalArgumentException("No such report");
			}
			if (r.youtrack == null) {
				throw new IllegalArgumentException("Still sending this report - try again in a minute");
			}
		}
		String clean = clip(text.strip(), 4000);
		if (clean.isEmpty()) {
			throw new IllegalArgumentException("Write something first");
		}
		YouTrack.comment(r.youtrack, YouTrack.TESTER_MARK + "**" + name + "** (tester) wrote:\n" + clean);
		synchronized (this) {
			r.ytComments.add(new YouTrack.Comment("local-" + System.nanoTime(), "You", clean, System.currentTimeMillis(), true));
			save();
		}
	}

	/** A screenshot / video / file for one of the tester's own reports, streamed to disk. */
	long putAttachment(String uuid, boolean owner, String reportId, String fileName, InputStream body) throws IOException {
		Report r;
		synchronized (this) {
			r = data.reports.stream().filter(x -> x.id.equals(reportId)).findFirst().orElse(null);
			if (r == null || !(owner || r.uuid.equals(key(uuid)))) {
				throw new IllegalArgumentException("No such report");
			}
			if (fileName == null || !ATTACHMENT.matcher(fileName).matches() || fileName.contains("..")) {
				throw new IllegalArgumentException("Pictures, videos, logs, text files or zips only");
			}
			if (r.files.size() >= MAX_FILES) {
				throw new IllegalArgumentException("Too many files on this report");
			}
		}
		String safe = fileName.replace(' ', '_');
		Path target = reportDir(reportId).resolve(safe);
		Path tmp = target.resolveSibling(safe + ".part");
		long total = 0;
		try (InputStream in = body; var out = Files.newOutputStream(tmp)) {
			byte[] buffer = new byte[1 << 16];
			for (int n; (n = in.read(buffer)) > 0; ) {
				total += n;
				if (total > MAX_ATTACHMENT) {
					throw new IllegalArgumentException("Files up to 200 MB");
				}
				out.write(buffer, 0, n);
			}
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		synchronized (this) {
			if (!r.files.contains(safe)) {
				r.files.add(safe);
			}
			save();
		}
		if (YouTrack.enabled()) {
			youtrackQueue.submit(() -> uploadFiles(r));
		}
		return total;
	}

	/** Owner: every report, newest first. */
	synchronized List<Report> reports() {
		return List.copyOf(data.reports);
	}

	/** Owner: mark a report open / fixed / wontfix. */
	synchronized boolean setReportStatus(String id, String status) {
		if (!List.of("open", "fixed", "wontfix").contains(status)) {
			return false;
		}
		for (Report r : data.reports) {
			if (r.id.equals(id)) {
				r.status = status;
				save();
				if (r.youtrack != null && YouTrack.enabled()) {
					String state = switch (status) {
						case "fixed" -> "Fixed";
						case "wontfix" -> "Won't Fix";
						default -> "Confirmed";
					};
					youtrackQueue.submit(() -> {
						try {
							YouTrack.setState(r.youtrack, state);
							refresh(r);
						} catch (IOException e) {
							System.out.println("[youtrack] state " + r.youtrack + ": " + e.getMessage());
						}
					});
				}
				return true;
			}
		}
		return false;
	}

	/** One stored file of a report, or null. */
	synchronized Path reportFile(String id, String name) {
		if (id == null || !id.matches("[0-9a-f]{12}") || name == null) {
			return null;
		}
		for (Report r : data.reports) {
			if (r.id.equals(id) && r.files.contains(name)) {
				return reportDir(id).resolve(name);
			}
		}
		return null;
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
