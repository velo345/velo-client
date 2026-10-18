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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Velo Client team and what they can do. Staff levels come from the Discord bot (whoever holds a staff role and linked their
 * account; owners always count as admins):
 * <ul>
 *   <li><b>helper</b> - on the team: the staff cape and Velo+, no moderation tools;</li>
 *   <li><b>mod</b> and up - look players up and censor a custom cape (it's removed and that exact image can't be used again);</li>
 *   <li><b>admin</b> and up - ban a player from online services (friends &amp; messages, custom capes, parties) for a while or for good,
 *   and review the chat reports players send.</li>
 * </ul>
 * Staff have Velo+ and the Velo Staff cape for as long as they're staff. Reports carry up to 25 recent messages only when the
 * reporter ticked that box. Everything persists in {@code staff.json}.
 */
final class StaffService {

	/** On the team (staff cape, Velo+) without any moderation tools. */
	static final String HELPER = "helper";
	static final String MOD = "mod";
	static final String ADMIN = "admin";
	static final Set<String> SERVICES = Set.of("friends", "capes", "parties");
	static final Set<String> CATEGORIES = Set.of("spam", "scam", "insult", "harassment", "inappropriate", "other");
	static final String STAFF_CAPE = "cape_velo_staff";
	private static final int MAX_REPORTS = 1000;

	static final class Member {
		String level;
		String name;
		String discordId;
		long since;
	}

	static final class Ban {
		List<String> services = new ArrayList<>();
		String reason;
		String by;
		long at;
		/** 0 = until lifted. */
		long until;
	}

	/** One reported message line (as the server stored it). */
	record Line(String fromName, String text, long time) {
	}

	static final class Report {
		String id;
		long at;
		String reporter;
		String reporterName;
		String target;
		String targetName;
		String category;
		String reason;
		/** "dm" or "party". */
		String context;
		List<Line> messages = new ArrayList<>();
		String status = "open";
		String handledBy;
		long handledAt;
		String note;
	}

	private static final class Data {
		Map<String, Member> staff = new LinkedHashMap<>();
		Map<String, Ban> bans = new LinkedHashMap<>();
		List<Report> reports = new ArrayList<>();
		/** Discord id -> the boost (its start time) whose reward was already claimed (boosts from before boost credits). */
		Map<String, Long> boostClaims = new LinkedHashMap<>();
		/** Discord id -> every boost Discord announced for them, each rewarded once (so a second boost stacks another reward). */
		Map<String, List<BoostCredit>> boostCredits = new LinkedHashMap<>();
	}

	/** One announced boost: the boost message's id, how many boosts it was (one message can say "boosted 2 times"). */
	static final class BoostCredit {
		String id;
		int count;
		long at;
		boolean claimed;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path file;
	private Data data = new Data();

	StaffService(Path dataDir) {
		this.file = dataDir.resolve("staff.json");
		try {
			if (Files.exists(file)) {
				Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
				if (loaded != null) {
					data = loaded;
				}
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[staff] Couldn't read staff.json: " + e.getMessage());
		}
		if (data.staff == null) {
			data.staff = new LinkedHashMap<>();
		}
		if (data.bans == null) {
			data.bans = new LinkedHashMap<>();
		}
		if (data.reports == null) {
			data.reports = new ArrayList<>();
		}
	}

	private void save() {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = Files.createTempFile(file.getParent(), "staff-", ".json.tmp");
			Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[staff] Couldn't save staff.json: " + e.getMessage());
		}
	}

	// ---- staff ----

	synchronized String level(String uuid) {
		Member m = data.staff.get(uuid);
		return m == null ? null : m.level;
	}

	synchronized boolean isStaff(String uuid) {
		return data.staff.containsKey(uuid);
	}

	static int rank(String level) {
		return ADMIN.equals(level) ? 2 : MOD.equals(level) ? 1 : 0;
	}

	synchronized Map<String, Member> staff() {
		return new LinkedHashMap<>(data.staff);
	}

	/**
	 * The bot's view of who is staff (uuid -> level, name, discord id). Returns {added, removed} uuids so the caller can hand out or
	 * take back the staff cape.
	 */
	synchronized List<List<String>> replaceStaff(Map<String, Member> fresh) {
		List<String> added = new ArrayList<>();
		List<String> removed = new ArrayList<>();
		for (String uuid : fresh.keySet()) {
			if (!data.staff.containsKey(uuid)) {
				added.add(uuid);
			}
		}
		for (String uuid : data.staff.keySet()) {
			if (!fresh.containsKey(uuid)) {
				removed.add(uuid);
			}
		}
		for (Map.Entry<String, Member> e : fresh.entrySet()) {
			Member old = data.staff.get(e.getKey());
			if (old != null) {
				e.getValue().since = old.since;
			} else {
				e.getValue().since = System.currentTimeMillis();
			}
		}
		data.staff = new LinkedHashMap<>(fresh);
		save();
		return List.of(added, removed);
	}

	// ---- bans ----

	/** The active ban covering {@code service} for this player, or null. */
	synchronized Ban banFor(String uuid, String service) {
		Ban ban = data.bans.get(uuid);
		if (ban == null) {
			return null;
		}
		if (ban.until > 0 && ban.until < System.currentTimeMillis()) {
			data.bans.remove(uuid);
			save();
			return null;
		}
		return ban.services.contains(service) ? ban : null;
	}

	synchronized Ban ban(String uuid) {
		return data.bans.get(uuid);
	}

	synchronized void setBan(String uuid, List<String> services, int days, String reason, String by) {
		Ban ban = new Ban();
		for (String s : services) {
			if (SERVICES.contains(s)) {
				ban.services.add(s);
			}
		}
		ban.reason = reason == null || reason.isBlank() ? "Breaking the Velo Client rules" : reason.strip();
		ban.by = by;
		ban.at = System.currentTimeMillis();
		ban.until = days > 0 ? ban.at + days * 86_400_000L : 0;
		if (ban.services.isEmpty()) {
			data.bans.remove(uuid);
		} else {
			data.bans.put(uuid, ban);
		}
		save();
	}

	synchronized boolean lift(String uuid) {
		boolean had = data.bans.remove(uuid) != null;
		save();
		return had;
	}

	/** 403 with a readable reason when {@code uuid} is banned from {@code service}. */
	void check(String uuid, String service) throws SocialService.SocialException {
		Ban ban = banFor(uuid, service);
		if (ban != null) {
			String what = switch (service) {
				case "friends" -> "the friends system";
				case "capes" -> "custom capes";
				default -> "parties";
			};
			String until = ban.until > 0 ? " until " + java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(ban.until), java.time.ZoneOffset.UTC) : "";
			throw new SocialService.SocialException(403, "You're banned from " + what + until + ": " + ban.reason);
		}
	}

	/** Records an announced boost; false when this boost message was already recorded. */
	synchronized boolean addBoostCredit(String discordId, String boostId, int count) {
		if (data.boostCredits == null) {
			data.boostCredits = new LinkedHashMap<>();
		}
		List<BoostCredit> credits = data.boostCredits.computeIfAbsent(discordId, k -> new ArrayList<>());
		if (credits.stream().anyMatch(c -> c.id.equals(boostId))) {
			return false;
		}
		BoostCredit credit = new BoostCredit();
		credit.id = boostId;
		credit.count = Math.max(1, Math.min(count, 30));
		credit.at = System.currentTimeMillis();
		credits.add(credit);
		save();
		return true;
	}

	/** Marks every unclaimed boost of this member claimed and returns how many boosts that was (0 = nothing pending). */
	synchronized int takeBoostCredits(String discordId) {
		List<BoostCredit> credits = data.boostCredits == null ? null : data.boostCredits.get(discordId);
		if (credits == null) {
			return 0;
		}
		int boosts = 0;
		for (BoostCredit c : credits) {
			if (!c.claimed) {
				c.claimed = true;
				boosts += c.count;
			}
		}
		if (boosts > 0) {
			save();
		}
		return boosts;
	}

	/** Whether any boost of this member was ever announced (then the per-message credits are the truth, not the boost start time). */
	synchronized boolean hasBoostCredits(String discordId) {
		return data.boostCredits != null && data.boostCredits.containsKey(discordId) && !data.boostCredits.get(discordId).isEmpty();
	}

	/** True (and remembered) the first time a reward is claimed for this boost. */
	synchronized boolean claimBoost(String discordId, long boostedAt) {
		if (data.boostClaims == null) {
			data.boostClaims = new LinkedHashMap<>();
		}
		Long claimed = data.boostClaims.get(discordId);
		if (claimed != null && claimed == boostedAt) {
			return false;
		}
		data.boostClaims.put(discordId, boostedAt);
		save();
		return true;
	}

	// ---- reports ----

	synchronized long recentReportsBy(String uuid, long sinceMillis) {
		long now = System.currentTimeMillis();
		return data.reports.stream().filter(r -> r.reporter.equals(uuid) && now - r.at < sinceMillis).count();
	}

	synchronized Report addReport(Report report) {
		report.id = UUID.randomUUID().toString().substring(0, 8);
		report.at = System.currentTimeMillis();
		report.category = CATEGORIES.contains(report.category) ? report.category : "other";
		data.reports.add(report);
		while (data.reports.size() > MAX_REPORTS) {
			data.reports.removeFirst();
		}
		save();
		return report;
	}

	synchronized List<Report> reports(String status) {
		List<Report> out = new ArrayList<>();
		for (int i = data.reports.size() - 1; i >= 0; i--) {
			Report r = data.reports.get(i);
			if (status == null || status.equals("all") || status.equals(r.status)) {
				out.add(r);
			}
		}
		return out;
	}

	synchronized long reportsAgainst(String uuid) {
		return data.reports.stream().filter(r -> uuid.equals(r.target)).count();
	}

	synchronized Report resolve(String id, String status, String by, String note) {
		for (Report r : data.reports) {
			if (r.id.equals(id)) {
				r.status = status;
				r.handledBy = by;
				r.handledAt = System.currentTimeMillis();
				r.note = note;
				save();
				return r;
			}
		}
		return null;
	}

	static String normalizeLevel(String level) {
		String l = level == null ? "" : level.toLowerCase(Locale.ROOT).strip();
		return switch (l) {
			case "admin", "administrator", "owner", "manager" -> ADMIN;
			case "mod", "moderator", "staff" -> MOD;
			case "helper", "support", "team" -> HELPER;
			default -> null;
		};
	}
}
