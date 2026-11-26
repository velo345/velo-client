package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Players' bug and crash reports (game pause menu, launcher crash dialog / "Report a bug", crash telemetry) in YouTrack:
 * every bug is its own issue; crashes with the same cause (ReportService.signature) share one issue whose Occurrences
 * count up, with the newest crash attached. On the first start every stored report is imported; after that each new
 * one follows within seconds. Which report went where: reports/youtrack.json.
 */
final class PlayerReportsYouTrack {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

	private static final class Map_ {
		/** crash group -> issue */
		Map<String, String> groups = new LinkedHashMap<>();
		Map<String, Integer> counts = new LinkedHashMap<>();
		/** bug report -> issue */
		Map<String, String> bugs = new LinkedHashMap<>();
		/** every report already in YouTrack */
		Set<String> done = new LinkedHashSet<>();
		/** 1 = the setup tags (vulkan, sodium...) were added to every issue imported before they existed */
		int tagsVersion;
	}

	private final ReportService reports;
	private final Path file;
	private Map_ map = new Map_();
	private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "velo-youtrack-players");
		t.setDaemon(true);
		return t;
	});

	PlayerReportsYouTrack(ReportService reports, Path dataDir) {
		this.reports = reports;
		this.file = dataDir.resolve("reports").resolve("youtrack.json");
		try {
			if (Files.exists(file)) {
				Map_ loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Map_.class);
				if (loaded != null) {
					map = loaded;
				}
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[youtrack] couldn't read " + file + ": " + e.getMessage());
		}
	}

	/** Imports what isn't in YouTrack yet (first start: everything), in the background; then keeps states in step. */
	void start() {
		if (!YouTrack.enabled()) {
			return;
		}
		queue.submit(this::importAll);
		reports.statusListener = (id, status) -> queue.submit(() -> pushStatus(id, status));
		Thread.ofVirtual().name("velo-youtrack-player-sync").start(() -> {
			while (true) {
				try {
					Thread.sleep(5 * 60_000);
					queue.submit(this::pullStates);
				} catch (InterruptedException e) {
					return;
				}
			}
		});
	}

	/** The owner changed a report's status in the launcher: the issue follows. */
	private void pushStatus(String reportId, String status) {
		String issue = map.bugs.get(reportId);
		if (issue == null) {
			for (ReportService.Summary s : reports.list()) {
				if (s.id().equals(reportId) && "crash".equals(s.kind())) {
					issue = map.groups.get(s.group());
				}
			}
		}
		if (issue == null) {
			return;
		}
		try {
			YouTrack.setState(issue, state(status));
		} catch (IOException e) {
			System.out.println("[youtrack] state of " + issue + ": " + e.getMessage());
		}
	}

	/** States changed in YouTrack come back to the reports (the launcher's report list shows the same). */
	private void pullStates() {
		Map<String, ReportService.Summary> byId = new LinkedHashMap<>();
		reports.list().forEach(s -> byId.put(s.id(), s));
		Map<String, List<String>> issueReports = new LinkedHashMap<>();
		map.bugs.forEach((report, issue) -> issueReports.computeIfAbsent(issue, k -> new ArrayList<>()).add(report));
		for (ReportService.Summary s : byId.values()) {
			String issue = "crash".equals(s.kind()) ? map.groups.get(s.group()) : null;
			if (issue != null) {
				issueReports.computeIfAbsent(issue, k -> new ArrayList<>()).add(s.id());
			}
		}
		for (var entry : issueReports.entrySet()) {
			try {
				String status = switch (YouTrack.status(entry.getKey()).state()) {
					case "Fixed", "Verified" -> "fixed";
					case "Won't Fix", "Duplicate", "Can't Reproduce" -> "wontfix";
					case "New" -> "new";
					default -> "seen";
				};
				for (String id : entry.getValue()) {
					ReportService.Summary s = byId.get(id);
					if (s != null && !status.equals(s.status())) {
						reports.setStatusQuiet(id, status, null);
					}
				}
			} catch (IOException | RuntimeException e) {
				System.out.println("[youtrack] pull " + entry.getKey() + ": " + e.getMessage());
			}
		}
	}

	/** A new report: into YouTrack within seconds. */
	void submitted(ReportService.Summary summary) {
		if (YouTrack.enabled()) {
			queue.submit(() -> push(summary, true));
		}
	}

	/**
	 * The player's setup as tags: graphics API from the log, Sodium / Iris / Essential / other mods from the mod list,
	 * crash telemetry. Lets YouTrack filter e.g. "tag: vulkan Type: Crash".
	 */
	private static List<String> setupTags(ReportService.Report r) {
		List<String> tags = new ArrayList<>();
		String log = (r.log == null ? "" : r.log) + "\n" + (r.crash == null ? "" : r.crash);
		String sys = r.system == null ? "" : r.system.toString();
		if (log.contains("Vulkan") || sys.contains("Vulkan") || log.contains("vulkan")) {
			tags.add("vulkan");
		} else if (log.contains("OpenGL") || sys.contains("OpenGL") || log.contains("GL version")) {
			tags.add("opengl");
		}
		List<String> mods = r.mods == null ? List.of() : r.mods;
		String all = String.join("\n", mods).toLowerCase(java.util.Locale.ROOT) + "\n" + log.toLowerCase(java.util.Locale.ROOT);
		if (all.contains("sodium")) {
			tags.add("sodium");
		}
		if (all.contains("iris")) {
			tags.add("iris");
		}
		if (all.contains("essential")) {
			tags.add("essential");
		}
		long others = mods.stream().map(m -> m.toLowerCase(java.util.Locale.ROOT))
				.filter(m -> !m.contains("velo") && !m.contains("fabric") && !m.contains("minecraft") && !m.contains("java")
						&& !m.contains("sodium") && !m.contains("mixinextras")).count();
		if (others >= 5) {
			tags.add("modded");
		}
		if (r.auto) {
			tags.add("telemetry");
		}
		return tags;
	}

	private void tagSetup(String issue, String reportId) {
		try {
			for (String tag : setupTags(reports.get(reportId))) {
				YouTrack.tag(issue, tag);
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[youtrack] tags " + issue + ": " + e.getMessage());
		}
	}

	/** Once: the setup tags on every issue imported before they existed. */
	private void retagAll() {
		if (map.tagsVersion >= 1) {
			return;
		}
		map.bugs.forEach((report, issue) -> {
			tagSetup(issue, report);
			YouTrack.tag(issue, "has-logs");
		});
		Map<String, String> newestOfGroup = new LinkedHashMap<>();
		for (ReportService.Summary s : reports.list()) {
			if ("crash".equals(s.kind()) && map.groups.containsKey(s.group())) {
				newestOfGroup.putIfAbsent(s.group(), s.id());   // list() is newest first
			}
		}
		newestOfGroup.forEach((group, report) -> {
			String issue = map.groups.get(group);
			tagSetup(issue, report);
			YouTrack.tag(issue, "has-crash-log");
		});
		synchronized (this) {
			map.tagsVersion = 1;
			save();
		}
		System.out.println("[youtrack] setup tags added to " + (map.bugs.size() + newestOfGroup.size()) + " issues");
	}

	private void importAll() {
		try {
			List<ReportService.Summary> all = new ArrayList<>(reports.list());
			all.sort(Comparator.comparingLong(ReportService.Summary::createdAt));
			// crashes: one issue per cause, built from all its reports at once
			Map<String, List<ReportService.Summary>> groups = new LinkedHashMap<>();
			for (ReportService.Summary s : all) {
				if (map.done.contains(s.id())) {
					continue;
				}
				if ("crash".equals(s.kind())) {
					groups.computeIfAbsent(s.group(), k -> new ArrayList<>()).add(s);
				} else {
					push(s, false);
				}
			}
			int n = 0;
			for (var entry : groups.entrySet()) {
				if (map.groups.containsKey(entry.getKey())) {
					for (ReportService.Summary s : entry.getValue()) {
						push(s, true);
					}
				} else {
					crashGroup(entry.getKey(), entry.getValue());
				}
				n++;
			}
			System.out.println("[youtrack] player reports imported (" + groups.size() + " crash causes, " + map.bugs.size() + " bugs)");
			retagAll();
		} catch (RuntimeException e) {
			System.out.println("[youtrack] import failed: " + e.getMessage());
		}
	}

	/** A new crash cause: its issue with every report so far. */
	private void crashGroup(String group, List<ReportService.Summary> list) {
		try {
			ReportService.Summary newest = list.get(list.size() - 1);
			Set<String> players = new LinkedHashSet<>();
			Set<String> versions = new LinkedHashSet<>();
			Set<String> minecraft = new LinkedHashSet<>();
			boolean allAuto = true;
			boolean launcher = false;
			for (ReportService.Summary s : list) {
				if (s.username() != null) {
					players.add(s.username());
				}
				String v = version(s);
				if (v != null) {
					versions.add(v);
				}
				if (s.minecraft() != null) {
					minecraft.add(s.minecraft());
				}
				allAuto &= s.auto();
			}
			launcher = launcherCrash(newest);
			StringBuilder d = new StringBuilder();
			d.append("**").append(list.size()).append(list.size() == 1 ? " crash" : " crashes").append("** with this cause, from ")
					.append(players.size()).append(players.size() == 1 ? " player" : " players").append(".\n\n");
			d.append("| | |\n|---|---|\n");
			d.append("| **Cause** | `").append(newest.groupLabel()).append("` |\n");
			d.append("| **Where** | ").append(launcher ? "Launcher" : "Game").append(" (reported from the ")
					.append("launcher".equals(newest.source()) ? "launcher's crash dialog" : "game").append(") |\n");
			d.append("| **Velo versions** | ").append(String.join(", ", versions)).append(" |\n");
			if (!minecraft.isEmpty()) {
				d.append("| **Minecraft** | ").append(String.join(", ", minecraft)).append(" |\n");
			}
			d.append("| **First / last** | ").append(DAY.format(Instant.ofEpochMilli(list.get(0).createdAt()))).append(" / ")
					.append(DAY.format(Instant.ofEpochMilli(newest.createdAt()))).append(" UTC |\n\n");
			d.append("### Occurrences\n\n| When (UTC) | Player | Velo | Minecraft | Report |\n|---|---|---|---|---|\n");
			List<ReportService.Summary> shown = list.size() > 40 ? list.subList(list.size() - 40, list.size()) : list;
			for (int i = shown.size() - 1; i >= 0; i--) {
				ReportService.Summary s = shown.get(i);
				d.append("| ").append(DAY.format(Instant.ofEpochMilli(s.createdAt()))).append(" | ").append(nz(s.username()))
						.append(s.auto() ? " (auto)" : "").append(" | ").append(nz(version(s))).append(" | ").append(nz(s.minecraft()))
						.append(" | `").append(s.id()).append("` |\n");
			}
			d.append("\nThe newest crash report is attached. New crashes with the same cause count up **Occurrences** and add a comment.");
			List<JsonObject> fields = new ArrayList<>();
			fields.add(YouTrack.enumField("Type", "Crash"));
			fields.add(YouTrack.enumField("State", state(newest.status())));
			fields.add(YouTrack.enumField("Priority", list.size() >= 10 ? "Critical" : list.size() >= 3 ? "Major" : "Normal"));
			fields.add(YouTrack.enumField("Source", allAuto ? "Crash Telemetry" : "launcher".equals(newest.source()) ? "Launcher" : "In-Game"));
			fields.add(YouTrack.enumField("Subsystem", launcher ? "Launcher" : "Game"));
			if (newest.minecraft() != null) {
				fields.add(YouTrack.enumField("Minecraft", newest.minecraft()));
			}
			fields.add(YouTrack.numberField("Occurrences", list.size()));
			fields.add(YouTrack.textField("Player", String.join(", ", players).length() > 200 ? players.iterator().next() + " +" + (players.size() - 1)
					: String.join(", ", players)));
			fields.add(YouTrack.textField("Velo Build", nz(version(newest))));
			fields.add(YouTrack.textField("Velo Report", newest.id()));
			String summary = (launcher ? "Launcher crash: " : "Crash: ") + newest.groupLabel();
			String issue = YouTrack.createRaw(summary, d.toString(), fields, List.of("player-report", "has-crash-log"));
			attachCrash(issue, newest.id());
			tagSetup(issue, newest.id());
			synchronized (this) {
				map.groups.put(group, issue);
				map.counts.put(group, list.size());
				list.forEach(s -> map.done.add(s.id()));
				save();
			}
		} catch (IOException e) {
			System.out.println("[youtrack] crash group " + group + ": " + e.getMessage());
		}
	}

	/** One report: a bug becomes its own issue, a crash counts up (or starts) its cause's issue. */
	private void push(ReportService.Summary s, boolean live) {
		if (map.done.contains(s.id())) {
			return;
		}
		try {
			if ("crash".equals(s.kind())) {
				String issue = map.groups.get(s.group());
				if (issue == null) {
					crashGroup(s.group(), List.of(s));
					return;
				}
				int count = map.counts.getOrDefault(s.group(), 1) + 1;
				YouTrack.setFields(issue, List.of(YouTrack.numberField("Occurrences", count),
						YouTrack.enumField("Priority", count >= 10 ? "Critical" : count >= 3 ? "Major" : "Normal")));
				YouTrack.comment(issue, "Again: **" + nz(s.username()) + "**" + (s.auto() ? " (auto)" : "") + " on Velo " + nz(version(s))
						+ (s.minecraft() != null ? ", Minecraft " + s.minecraft() : "") + " - report `" + s.id() + "` (" + count + " so far)");
				if (live) {
					attachCrash(issue, s.id());
				}
				synchronized (this) {
					map.counts.put(s.group(), count);
					map.done.add(s.id());
					save();
				}
				return;
			}
			ReportService.Report r = reports.get(s.id());
			String title = s.title() != null && !s.title().isBlank() ? s.title() : firstLine(r.message);
			StringBuilder d = new StringBuilder();
			d.append(r.message == null || r.message.isBlank() ? "_No description._" : r.message).append("\n\n---\n\n| | |\n|---|---|\n");
			d.append("| **Player** | ").append(nz(r.username)).append(" |\n");
			d.append("| **From** | ").append("launcher".equals(r.source) ? "Launcher" : "Game").append(r.area != null ? " · " + r.area : "").append(" |\n");
			if (r.system != null) {
				for (var e : r.system.entrySet()) {
					d.append("| **").append(e.getKey()).append("** | ").append(e.getValue().replace("|", "/")).append(" |\n");
				}
			}
			d.append("| **Sent** | ").append(DAY.format(Instant.ofEpochMilli(r.createdAt))).append(" UTC |\n");
			d.append("| **Report** | `").append(r.id).append("` |\n");
			if (r.note != null && !r.note.isBlank()) {
				d.append("\n> Owner note: ").append(r.note.replace("\n", " ")).append("\n");
			}
			List<JsonObject> fields = new ArrayList<>();
			fields.add(YouTrack.enumField("Type", "Bug"));
			fields.add(YouTrack.enumField("State", state(r.status)));
			fields.add(YouTrack.enumField("Source", "launcher".equals(r.source) ? "Launcher" : "In-Game"));
			fields.add(YouTrack.enumField("Subsystem", area(r)));
			if (s.minecraft() != null) {
				fields.add(YouTrack.enumField("Minecraft", s.minecraft()));
			}
			String os = r.system == null ? null : r.system.getOrDefault("OS", r.system.get("System"));
			if (os != null) {
				String o = os.toLowerCase(java.util.Locale.ROOT);
				fields.add(YouTrack.enumField("Platform", o.contains("win") ? "Windows" : o.contains("mac") ? "macOS" : "Linux"));
			}
			fields.add(YouTrack.textField("Player", nz(r.username)));
			fields.add(YouTrack.textField("Velo Build", nz(version(s))));
			fields.add(YouTrack.textField("Velo Report", r.id));
			String issue = YouTrack.createRaw(title.isBlank() ? "Bug report from " + nz(r.username) : title, d.toString(), fields,
					List.of("player-report"));
			if (r.log != null && !r.log.isBlank()) {
				YouTrack.attachText(issue, ("launcher".equals(r.source) ? "launcher" : "game") + "-" + r.id + ".log", r.log);
				YouTrack.tag(issue, "has-logs");
			}
			if (r.mods != null && !r.mods.isEmpty()) {
				YouTrack.attachText(issue, "mods-" + r.id + ".txt", String.join("\n", r.mods));
			}
			for (String tag : setupTags(r)) {
				YouTrack.tag(issue, tag);
			}
			synchronized (this) {
				map.bugs.put(r.id, issue);
				map.done.add(r.id);
				save();
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[youtrack] report " + s.id() + ": " + e.getMessage());
		}
	}

	/** The Velo version a report came from (game: "Velo Client", launcher: "Velo Launcher"). */
	private String version(ReportService.Summary s) {
		if (s.version() != null) {
			return s.version();
		}
		try {
			ReportService.Report r = reports.get(s.id());
			return r.system == null ? null : r.system.getOrDefault("Velo Launcher", r.system.get("Launcher"));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Whether a crash happened in the launcher itself (its own stack) rather than in the game it reported. */
	private boolean launcherCrash(ReportService.Summary s) {
		try {
			ReportService.Report r = reports.get(s.id());
			String text = r.crash != null ? r.crash : r.log;
			return text != null && text.contains("net.veloclient.launcher.") && !text.contains("Minecraft Crash Report")
					&& !text.contains("---- Minecraft");
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private void attachCrash(String issue, String reportId) {
		try {
			ReportService.Report r = reports.get(reportId);
			String text = r.crash != null && !r.crash.isBlank() ? r.crash : r.log;
			if (text != null && !text.isBlank()) {
				YouTrack.attachText(issue, "crash-" + reportId + ".txt", text);
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[youtrack] crash file " + reportId + ": " + e.getMessage());
		}
	}

	private static String area(ReportService.Report r) {
		String a = r.area == null ? "" : r.area.toLowerCase(java.util.Locale.ROOT);
		return switch (a) {
			case "cosmetics" -> "Cosmetics";
			case "store" -> "Store";
			case "mods" -> "HUD & Modules";
			case "game" -> "Game";
			case "launcher" -> "Launcher";
			default -> "launcher".equals(r.source) ? "Launcher" : "Game";
		};
	}

	private static String state(String status) {
		return switch (status == null ? "new" : status) {
			case "seen" -> "Confirmed";
			case "fixed" -> "Fixed";
			case "wontfix" -> "Won't Fix";
			default -> "New";
		};
	}

	private static String firstLine(String text) {
		if (text == null) {
			return "";
		}
		String line = text.strip().split("\\R", 2)[0];
		return line.length() > 90 ? line.substring(0, 90) + "..." : line;
	}

	private static String nz(String s) {
		return s == null || s.isBlank() ? "-" : s;
	}

	private synchronized void save() {
		try {
			Path tmp = file.resolveSibling("youtrack.json.tmp");
			Files.writeString(tmp, GSON.toJson(map), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[youtrack] save failed: " + e.getMessage());
		}
	}
}
