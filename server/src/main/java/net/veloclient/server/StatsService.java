package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Usage numbers for the owner panel, in their own SQLite file ({@code stats.db}): every player who ever signed in
 * (the closest thing to "installs" - the client has no anonymous install ping), who was active on which day and in
 * which app, and the online count sampled every five minutes. Sessions themselves stay in memory
 * ({@link SessionRegistry}); this only keeps counts and first/last-seen times.
 *
 * <p>On first start it backfills from what the server already had: {@code social.json} (everyone who signed in),
 * the store wallets and the rewards' per-day play time ({@code daily} in {@code store.db}).
 */
final class StatsService {

	private static final long SAMPLE_INTERVAL_MILLIS = 5 * 60_000L;

	private final Connection connection;
	/** "day/uuid/kind" already written today - heartbeats come every 45 s, the row only needs writing once a day. */
	private final Set<String> activeToday = ConcurrentHashMap.newKeySet();
	private volatile String activeDay = today();

	StatsService(Path dataDir) throws SQLException {
		connection = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("stats.db").toAbsolutePath());
		try (Statement st = connection.createStatement()) {
			st.execute("PRAGMA journal_mode=WAL");
			st.execute("""
					CREATE TABLE IF NOT EXISTS players (
						uuid TEXT PRIMARY KEY,
						username TEXT,
						first_seen INTEGER NOT NULL,
						last_seen INTEGER NOT NULL,
						first_kind TEXT,
						game INTEGER NOT NULL DEFAULT 0,
						launcher INTEGER NOT NULL DEFAULT 0,
						logins INTEGER NOT NULL DEFAULT 0)""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS active_days (
						day TEXT NOT NULL,
						uuid TEXT NOT NULL,
						kind TEXT NOT NULL,
						PRIMARY KEY (day, uuid, kind))""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS online_samples (
						ts INTEGER PRIMARY KEY,
						game INTEGER NOT NULL,
						launcher INTEGER NOT NULL,
						web INTEGER NOT NULL)""");
			st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
		}
		backfill(dataDir);
	}

	private static String today() {
		return LocalDate.now(ZoneOffset.UTC).toString();
	}

	/** A verified sign-in (game, launcher or web). */
	synchronized void signedIn(String uuid, String username, String kind) {
		long now = System.currentTimeMillis();
		String column = SessionRegistry.KIND_LAUNCHER.equals(kind) ? "launcher" : SessionRegistry.KIND_GAME.equals(kind) ? "game" : null;
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO players(uuid, username, first_seen, last_seen, first_kind, game, launcher, logins) VALUES(?, ?, ?, ?, ?, ?, ?, 1) "
						+ "ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, last_seen = excluded.last_seen, logins = logins + 1"
						+ (column == null ? "" : ", " + column + " = 1"))) {
			ps.setString(1, uuid);
			ps.setString(2, username);
			ps.setLong(3, now);
			ps.setLong(4, now);
			ps.setString(5, kind);
			ps.setInt(6, "game".equals(column) ? 1 : 0);
			ps.setInt(7, "launcher".equals(column) ? 1 : 0);
			ps.executeUpdate();
		} catch (SQLException e) {
			System.out.println("[stats] sign-in not recorded: " + e.getMessage());
		}
		active(uuid, kind);
	}

	/** A heartbeat: marks the player active today in that app (written once per day). */
	void active(String uuid, String kind) {
		String day = today();
		if (!day.equals(activeDay)) {
			activeDay = day;
			activeToday.clear();
		}
		if (!activeToday.add(day + "/" + uuid + "/" + kind)) {
			return;
		}
		synchronized (this) {
			try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO active_days(day, uuid, kind) VALUES(?, ?, ?)");
					PreparedStatement seen = connection.prepareStatement("UPDATE players SET last_seen = ? WHERE uuid = ?")) {
				ps.setString(1, day);
				ps.setString(2, uuid);
				ps.setString(3, kind);
				ps.executeUpdate();
				seen.setLong(1, System.currentTimeMillis());
				seen.setString(2, uuid);
				seen.executeUpdate();
			} catch (SQLException e) {
				System.out.println("[stats] activity not recorded: " + e.getMessage());
			}
		}
	}

	/** Called every five minutes with the live session counts. */
	synchronized void sample(int game, int launcher, int web) {
		long ts = System.currentTimeMillis() / SAMPLE_INTERVAL_MILLIS * SAMPLE_INTERVAL_MILLIS;
		try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO online_samples(ts, game, launcher, web) VALUES(?, ?, ?, ?)")) {
			ps.setLong(1, ts);
			ps.setInt(2, game);
			ps.setInt(3, launcher);
			ps.setInt(4, web);
			ps.executeUpdate();
		} catch (SQLException e) {
			System.out.println("[stats] sample not recorded: " + e.getMessage());
		}
	}

	/**
	 * Everything the panel's overview shows: totals, per-day series for the last {@code days} days and the online
	 * samples of the last {@code sampleHours} hours.
	 */
	synchronized Map<String, Object> overview(int days, int sampleHours) throws SQLException {
		Map<String, Object> out = new LinkedHashMap<>();
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		long dayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
		out.put("players", scalar("SELECT COUNT(*) FROM players"));
		out.put("launcherPlayers", scalar("SELECT COUNT(*) FROM players WHERE launcher = 1"));
		out.put("gamePlayers", scalar("SELECT COUNT(*) FROM players WHERE game = 1"));
		out.put("newToday", scalar("SELECT COUNT(*) FROM players WHERE first_seen >= " + dayStart));
		out.put("dau", scalar("SELECT COUNT(DISTINCT uuid) FROM active_days WHERE day = '" + today + "'"));
		out.put("wau", scalar("SELECT COUNT(DISTINCT uuid) FROM active_days WHERE day > '" + today.minusDays(7) + "'"));
		out.put("mau", scalar("SELECT COUNT(DISTINCT uuid) FROM active_days WHERE day > '" + today.minusDays(30) + "'"));
		out.put("peakOnline", scalar("SELECT COALESCE(MAX(game), 0) FROM online_samples"));
		out.put("peakOnline24h", scalar("SELECT COALESCE(MAX(game), 0) FROM online_samples WHERE ts >= " + (System.currentTimeMillis() - 86_400_000L)));

		// Per day: new players, active players (any app / game / launcher) and the running total.
		LocalDate from = today.minusDays(Math.max(1, days) - 1L);
		Map<String, long[]> perDay = new LinkedHashMap<>();
		for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
			perDay.put(d.toString(), new long[5]);
		}
		long before = scalar("SELECT COUNT(*) FROM players WHERE first_seen < " + from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
		try (Statement st = connection.createStatement()) {
			try (ResultSet rs = st.executeQuery("SELECT first_seen FROM players WHERE first_seen >= "
					+ from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())) {
				while (rs.next()) {
					long[] row = perDay.get(Instant.ofEpochMilli(rs.getLong(1)).atZone(ZoneOffset.UTC).toLocalDate().toString());
					if (row != null) {
						row[0]++;
					}
				}
			}
			try (ResultSet rs = st.executeQuery("SELECT day, COUNT(DISTINCT uuid), COUNT(DISTINCT CASE WHEN kind = 'game' THEN uuid END), "
					+ "COUNT(DISTINCT CASE WHEN kind = 'launcher' THEN uuid END) FROM active_days WHERE day >= '" + from + "' GROUP BY day")) {
				while (rs.next()) {
					long[] row = perDay.get(rs.getString(1));
					if (row != null) {
						row[1] = rs.getLong(2);
						row[2] = rs.getLong(3);
						row[3] = rs.getLong(4);
					}
				}
			}
			try (ResultSet rs = st.executeQuery("SELECT ts, game FROM online_samples WHERE ts >= " + from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())) {
				while (rs.next()) {
					long[] row = perDay.get(Instant.ofEpochMilli(rs.getLong(1)).atZone(ZoneOffset.UTC).toLocalDate().toString());
					if (row != null) {
						row[4] = Math.max(row[4], rs.getLong(2));
					}
				}
			}
		}
		List<Map<String, Object>> series = new ArrayList<>();
		long total = before;
		for (Map.Entry<String, long[]> e : perDay.entrySet()) {
			long[] r = e.getValue();
			total += r[0];
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("day", e.getKey());
			row.put("newPlayers", r[0]);
			row.put("active", r[1]);
			row.put("activeGame", r[2]);
			row.put("activeLauncher", r[3]);
			row.put("peakOnline", r[4]);
			row.put("totalPlayers", total);
			series.add(row);
		}
		out.put("daily", series);

		List<Map<String, Object>> samples = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement("SELECT ts, game, launcher, web FROM online_samples WHERE ts >= ? ORDER BY ts")) {
			ps.setLong(1, System.currentTimeMillis() - sampleHours * 3_600_000L);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					samples.add(Map.of("ts", rs.getLong(1), "game", rs.getInt(2), "launcher", rs.getInt(3), "web", rs.getInt(4)));
				}
			}
		}
		out.put("online", samples);
		return out;
	}

	/** The most recently seen players, newest first. */
	synchronized List<Map<String, Object>> players(int limit, String search) throws SQLException {
		List<Map<String, Object>> out = new ArrayList<>();
		String where = search == null || search.isBlank() ? "" : " WHERE username LIKE ? OR uuid LIKE ?";
		try (PreparedStatement ps = connection.prepareStatement("SELECT uuid, username, first_seen, last_seen, game, launcher, logins FROM players"
				+ where + " ORDER BY last_seen DESC LIMIT ?")) {
			int i = 1;
			if (!where.isEmpty()) {
				String like = "%" + search.trim().replace("%", "").replace("_", "\\_") + "%";
				ps.setString(i++, like);
				ps.setString(i++, like.replace("-", ""));
			}
			ps.setInt(i, Math.max(1, Math.min(limit, 500)));
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("uuid", rs.getString(1));
					row.put("username", rs.getString(2));
					row.put("firstSeen", rs.getLong(3));
					row.put("lastSeen", rs.getLong(4));
					row.put("game", rs.getInt(5) == 1);
					row.put("launcher", rs.getInt(6) == 1);
					row.put("logins", rs.getInt(7));
					out.add(row);
				}
			}
		}
		return out;
	}

	private long scalar(String sql) throws SQLException {
		try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : 0;
		}
	}

	// ---- First start: what the server already knew ----

	private void backfill(Path dataDir) throws SQLException {
		if (scalar("SELECT COUNT(*) FROM meta WHERE key = 'backfilled'") > 0) {
			return;
		}
		Map<String, long[]> seen = new LinkedHashMap<>();
		Map<String, String> names = new LinkedHashMap<>();
		Path social = dataDir.resolve("social.json");
		if (Files.exists(social)) {
			try (Reader reader = Files.newBufferedReader(social, StandardCharsets.UTF_8)) {
				JsonObject root = new Gson().fromJson(reader, JsonObject.class);
				JsonObject users = root == null || !root.has("users") ? null : root.getAsJsonObject("users");
				if (users != null) {
					for (String uuid : users.keySet()) {
						JsonObject u = users.getAsJsonObject(uuid);
						long last = u.has("lastSeen") ? u.get("lastSeen").getAsLong() : 0;
						seen.put(uuid, new long[] {last > 0 ? last : System.currentTimeMillis(), last});
						if (u.has("username") && !u.get("username").isJsonNull()) {
							names.put(uuid, u.get("username").getAsString());
						}
					}
				}
			} catch (Exception e) {
				System.out.println("[stats] social.json not read for the backfill: " + e.getMessage());
			}
		}
		Path store = dataDir.resolve("store.db");
		try (Statement st = connection.createStatement()) {
			if (Files.exists(store)) {
				st.execute("ATTACH DATABASE '" + store.toAbsolutePath().toString().replace("'", "''") + "' AS s");
				try {
					try (ResultSet rs = st.executeQuery("SELECT uuid, username, updated_at FROM s.wallets")) {
						while (rs.next()) {
							long[] t = seen.computeIfAbsent(rs.getString(1), k -> new long[] {Long.MAX_VALUE, 0});
							t[1] = Math.max(t[1], rs.getLong(3));
							if (rs.getString(2) != null) {
								names.putIfAbsent(rs.getString(1), rs.getString(2));
							}
						}
					}
					try (ResultSet rs = st.executeQuery("SELECT uuid, MIN(day), MAX(day) FROM s.daily GROUP BY uuid")) {
						while (rs.next()) {
							long[] t = seen.computeIfAbsent(rs.getString(1), k -> new long[] {Long.MAX_VALUE, 0});
							t[0] = Math.min(t[0], LocalDate.parse(rs.getString(2)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
							t[1] = Math.max(t[1], LocalDate.parse(rs.getString(3)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
						}
					}
					st.executeUpdate("INSERT OR IGNORE INTO active_days(day, uuid, kind) SELECT day, uuid, 'game' FROM s.daily WHERE online_seconds > 0");
				} finally {
					st.execute("DETACH DATABASE s");
				}
			}
		}
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT OR IGNORE INTO players(uuid, username, first_seen, last_seen, first_kind, game, launcher, logins) VALUES(?, ?, ?, ?, 'game', 1, 0, 0)")) {
			for (Map.Entry<String, long[]> e : seen.entrySet()) {
				long first = e.getValue()[0] == Long.MAX_VALUE ? e.getValue()[1] : e.getValue()[0];
				ps.setString(1, e.getKey());
				ps.setString(2, names.get(e.getKey()));
				ps.setLong(3, first);
				ps.setLong(4, Math.max(first, e.getValue()[1]));
				ps.executeUpdate();
			}
		}
		try (Statement st = connection.createStatement()) {
			st.executeUpdate("INSERT INTO meta(key, value) VALUES('backfilled', '" + System.currentTimeMillis() + "')");
		}
		System.out.println("[stats] Backfilled " + seen.size() + " players from the existing data");
	}
}
