package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Player reports of shared clips and public worlds, what staff did about them, and the blocks they hand out.
 * Every staff member (helper and up) can handle these: watch the clip / look at the world, then remove it or dismiss
 * the report. Removing can also stop the player sharing clips / listing public worlds for a while.
 *
 * <p>Also keeps the staff action log (every removal, dismissal, takedown, cape review), so the owner can see who did
 * what. Everything persists in {@code moderation.json}.
 */
final class ModerationService {

	static final String CLIP = "clip";
	static final String WORLD = "world";
	static final Set<String> CATEGORIES = Set.of("inappropriate", "harassment", "cheating", "spam", "scam", "copyright", "other");
	private static final int MAX_REPORTS = 3000;
	private static final int MAX_LOG = 1000;

	static final class Report {
		String id;
		/** {@link #CLIP} or {@link #WORLD}. */
		String kind;
		/** The clip id or world id. */
		String target;
		String targetTitle;
		String ownerUuid;
		String ownerName;
		String reporter;
		String reporterName;
		String category;
		String reason;
		long at;
		/** open, removed, dismissed. */
		String status = "open";
		String handledBy;
		long handledAt;
		String note;
	}

	/** No clip sharing / no public worlds until {@code until} (0 = until lifted). */
	static final class Block {
		String reason;
		String by;
		long at;
		long until;
	}

	record Action(long at, String by, String action, String target, String note) {
	}

	private static final class Data {
		List<Report> reports = new ArrayList<>();
		Map<String, Block> shareBlocks = new LinkedHashMap<>();
		Map<String, Block> hostBlocks = new LinkedHashMap<>();
		List<Action> log = new ArrayList<>();
		/** Cape hashes a staff member looked at and kept. */
		List<String> reviewedCapes = new ArrayList<>();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path file;
	private Data data = new Data();

	ModerationService(Path dataDir) {
		this.file = dataDir.resolve("moderation.json");
		try {
			if (Files.exists(file)) {
				Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
				if (loaded != null) {
					data = loaded;
				}
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[moderation] Couldn't read moderation.json: " + e.getMessage());
		}
		if (data.reports == null) {
			data.reports = new ArrayList<>();
		}
		if (data.shareBlocks == null) {
			data.shareBlocks = new LinkedHashMap<>();
		}
		if (data.hostBlocks == null) {
			data.hostBlocks = new LinkedHashMap<>();
		}
		if (data.log == null) {
			data.log = new ArrayList<>();
		}
		if (data.reviewedCapes == null) {
			data.reviewedCapes = new ArrayList<>();
		}
	}

	private void save() {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = Files.createTempFile(file.getParent(), "moderation-", ".json.tmp");
			Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[moderation] Couldn't save moderation.json: " + e.getMessage());
		}
	}

	// ---- Reports ----

	/**
	 * Files a report; the same reporter reporting the same thing again while it's open just updates their reason.
	 * Throws when they've sent too many lately.
	 */
	synchronized Report report(String kind, String target, String targetTitle, String ownerUuid, String ownerName, String reporter,
			String reporterName, String category, String reason) throws ApiError {
		long now = System.currentTimeMillis();
		long recent = data.reports.stream().filter(r -> r.reporter.equals(reporter) && now - r.at < 3_600_000L).count();
		if (recent >= 10) {
			throw new ApiError(429, "You've sent a lot of reports - try again later");
		}
		if (reporter.equals(ownerUuid)) {
			throw new ApiError(400, "That's your own");
		}
		for (Report r : data.reports) {
			if (r.kind.equals(kind) && r.target.equals(target) && r.reporter.equals(reporter) && "open".equals(r.status)) {
				r.reason = CloudBackupService.clean(reason, 500);
				r.category = CATEGORIES.contains(category) ? category : "other";
				save();
				return r;
			}
		}
		Report r = new Report();
		r.id = UUID.randomUUID().toString().substring(0, 8);
		r.kind = kind;
		r.target = target;
		r.targetTitle = CloudBackupService.clean(targetTitle, 100);
		r.ownerUuid = ownerUuid;
		r.ownerName = ownerName;
		r.reporter = reporter;
		r.reporterName = reporterName;
		r.category = CATEGORIES.contains(category) ? category : "other";
		r.reason = CloudBackupService.clean(reason, 500);
		r.at = now;
		data.reports.add(r);
		while (data.reports.size() > MAX_REPORTS) {
			data.reports.removeFirst();
		}
		save();
		return r;
	}

	/** Newest first; {@code kind}/{@code status} null or "all" for every one. */
	synchronized List<Report> reports(String kind, String status) {
		List<Report> out = new ArrayList<>();
		for (int i = data.reports.size() - 1; i >= 0; i--) {
			Report r = data.reports.get(i);
			if ((kind == null || kind.equals("all") || kind.equals(r.kind)) && (status == null || status.equals("all") || status.equals(r.status))) {
				out.add(r);
			}
		}
		return out;
	}

	synchronized Report report(String id) {
		return data.reports.stream().filter(r -> r.id.equals(id)).findFirst().orElse(null);
	}

	synchronized int openCount(String kind) {
		return (int) data.reports.stream().filter(r -> r.kind.equals(kind) && "open".equals(r.status)).count();
	}

	/** Open reports against one clip / world. */
	synchronized int openAgainst(String kind, String target) {
		return (int) data.reports.stream().filter(r -> r.kind.equals(kind) && r.target.equals(target) && "open".equals(r.status)).count();
	}

	/** Closes every open report on that clip / world (one decision covers them all); returns how many. */
	synchronized int close(String kind, String target, String status, String by, String note) {
		int n = 0;
		for (Report r : data.reports) {
			if (r.kind.equals(kind) && r.target.equals(target) && "open".equals(r.status)) {
				r.status = status;
				r.handledBy = by;
				r.handledAt = System.currentTimeMillis();
				r.note = CloudBackupService.clean(note, 500);
				n++;
			}
		}
		save();
		return n;
	}

	// ---- Blocks ----

	private static Block active(Map<String, Block> blocks, String uuid) {
		Block b = blocks.get(uuid);
		if (b == null) {
			return null;
		}
		if (b.until > 0 && b.until < System.currentTimeMillis()) {
			blocks.remove(uuid);
			return null;
		}
		return b;
	}

	synchronized Block shareBlock(String uuid) {
		return active(data.shareBlocks, uuid);
	}

	synchronized Block hostBlock(String uuid) {
		return active(data.hostBlocks, uuid);
	}

	/** {@code days} 0 = until lifted, negative = lift. */
	synchronized void block(String what, String uuid, int days, String reason, String by) {
		Map<String, Block> blocks = CLIP.equals(what) ? data.shareBlocks : data.hostBlocks;
		if (days < 0) {
			blocks.remove(uuid);
		} else {
			Block b = new Block();
			b.reason = reason == null || reason.isBlank() ? "Breaking the Velo Client rules" : CloudBackupService.clean(reason, 200);
			b.by = by;
			b.at = System.currentTimeMillis();
			b.until = days > 0 ? b.at + days * 86_400_000L : 0;
			blocks.put(uuid, b);
		}
		save();
	}

	synchronized Map<String, Block> blocks(String what) {
		Map<String, Block> blocks = CLIP.equals(what) ? data.shareBlocks : data.hostBlocks;
		blocks.entrySet().removeIf(e -> e.getValue().until > 0 && e.getValue().until < System.currentTimeMillis());
		return new LinkedHashMap<>(blocks);
	}

	/** 403 with a readable reason when the player is blocked from sharing clips / listing worlds. */
	void check(String what, String uuid) throws ApiError {
		Block b = CLIP.equals(what) ? shareBlock(uuid) : hostBlock(uuid);
		if (b != null) {
			String until = b.until > 0 ? " until " + java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(b.until), java.time.ZoneOffset.UTC) : "";
			throw new ApiError(403, (CLIP.equals(what) ? "You can't share clips" : "You can't list public worlds") + until + ": " + b.reason);
		}
	}

	// ---- Capes ----

	synchronized boolean capeReviewed(String hash) {
		return data.reviewedCapes.contains(hash);
	}

	synchronized void markCapeReviewed(String hash) {
		if (!data.reviewedCapes.contains(hash)) {
			data.reviewedCapes.add(hash);
			while (data.reviewedCapes.size() > 20_000) {
				data.reviewedCapes.removeFirst();
			}
			save();
		}
	}

	// ---- Log ----

	synchronized void log(String by, String action, String target, String note) {
		data.log.add(new Action(System.currentTimeMillis(), by, action, target, CloudBackupService.clean(note, 300)));
		while (data.log.size() > MAX_LOG) {
			data.log.removeFirst();
		}
		save();
		System.out.println("[moderation] " + by + " " + action + " " + target + (note == null || note.isBlank() ? "" : " (" + note + ")"));
	}

	synchronized List<Action> log(int limit) {
		List<Action> out = new ArrayList<>();
		for (int i = data.log.size() - 1; i >= 0 && out.size() < limit; i--) {
			out.add(data.log.get(i));
		}
		return out;
	}
}
