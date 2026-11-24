package net.veloclient.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anonymous performance reports from the game (PerfTelemetry: the first three sessions after every update) and the
 * owner panel's "did this update make things better or worse" view: median start-up time, FPS, slowest-5% FPS and
 * heap per Velo version, filterable by Minecraft version, OS and render backend.
 */
final class PerfService {

	private final Connection db;
	private final RateLimiter limiter = new RateLimiter();

	PerfService(Path dataDir) throws SQLException {
		db = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("perf.db").toAbsolutePath());
		try (Statement st = db.createStatement()) {
			st.execute("PRAGMA journal_mode=WAL");
			st.execute("""
					CREATE TABLE IF NOT EXISTS perf (
						ts INTEGER NOT NULL, install TEXT, velo TEXT, mc TEXT, os TEXT, backend TEXT, gpu TEXT,
						cores INTEGER, ram_gb INTEGER, heap_max_mb INTEGER, mods INTEGER, render_distance INTEGER,
						launch_ms INTEGER, fps_avg INTEGER, fps_low INTEGER, heap_mb INTEGER, seconds INTEGER, session INTEGER)""");
			st.execute("CREATE INDEX IF NOT EXISTS perf_velo ON perf(velo, ts)");
		}
	}

	void register(HttpServer server, String adminToken) {
		server.createContext("/v1/telemetry/perf", exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
			String ip = WebRoutes.clientIp(ex);
			if (limiter.tryAcquire(ip, "perf", new RateLimiter.Window(6, 3_600_000L), new RateLimiter.Window(40, 86_400_000L)) > 0) {
				JsonHttp.writeError(ex, 429, "Slow down");
				return;
			}
			JsonObject r = JsonParser.parseString(new String(JsonHttp.readRawBody(ex, 8 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
			try {
				store(r);
				JsonHttp.writeJson(ex, 200, Map.of("ok", true));
			} catch (IllegalArgumentException e) {
				JsonHttp.writeError(ex, 400, e.getMessage());
			}
		}));
		server.createContext("/v1/admin/panel/perf", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			String header = ex.getRequestHeaders().getFirst("X-Velo-Admin-Token");
			if (adminToken == null || header == null || !java.security.MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8),
					header.getBytes(StandardCharsets.UTF_8))) {
				JsonHttp.writeError(ex, 403, "Forbidden");
				return;
			}
			Map<String, String> q = StoreRoutes.query(ex);
			int days = Math.max(1, Math.min(365, parse(q.get("days"), 90)));
			JsonHttp.writeJson(ex, 200, overview(days, q.get("mc"), q.get("os"), q.get("backend")));
		}));
	}

	private static int parse(String s, int fallback) {
		try {
			return s == null ? fallback : Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static String text(JsonObject r, String key, int max) {
		if (!r.has(key) || r.get(key).isJsonNull()) {
			return null;
		}
		String s = r.get(key).getAsString().replaceAll("[^A-Za-z0-9 ._+()/-]", "").trim();
		return s.length() > max ? s.substring(0, max) : s;
	}

	private static long num(JsonObject r, String key, long min, long max) {
		if (!r.has(key) || r.get(key).isJsonNull()) {
			return -1;
		}
		long v = r.get(key).getAsLong();
		return v < min || v > max ? -1 : v;
	}

	synchronized void store(JsonObject r) {
		String velo = text(r, "velo", 24);
		if (velo == null || !velo.matches("[0-9]+(\\.[0-9]+){1,3}")) {
			throw new IllegalArgumentException("Bad version");
		}
		try (PreparedStatement ps = db.prepareStatement("INSERT INTO perf VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
			ps.setLong(1, System.currentTimeMillis());
			ps.setString(2, text(r, "install", 32));
			ps.setString(3, velo);
			ps.setString(4, text(r, "mc", 16));
			ps.setString(5, text(r, "os", 48));
			ps.setString(6, text(r, "backend", 12));
			ps.setString(7, text(r, "gpu", 80));
			ps.setLong(8, num(r, "cores", 1, 512));
			ps.setLong(9, num(r, "ramGb", 1, 4096));
			ps.setLong(10, num(r, "heapMaxMb", 64, 1 << 20));
			ps.setLong(11, num(r, "mods", 0, 2000));
			ps.setLong(12, num(r, "renderDistance", 2, 64));
			ps.setLong(13, num(r, "launchMs", 1000, 600_000));
			ps.setLong(14, num(r, "fpsAvg", 1, 20_000));
			ps.setLong(15, num(r, "fpsLow", 0, 20_000));
			ps.setLong(16, num(r, "heapMb", 1, 1 << 20));
			ps.setLong(17, num(r, "seconds", 1, 3600));
			ps.setLong(18, num(r, "session", 1, 10));
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Per Velo version (newest first): reports, installs and the medians, plus the filters' choices. */
	synchronized Map<String, Object> overview(int days, String mc, String os, String backend) {
		long since = System.currentTimeMillis() - days * 86_400_000L;
		StringBuilder where = new StringBuilder("ts >= ?");
		List<String> args = new ArrayList<>();
		if (mc != null && !mc.isBlank()) {
			where.append(" AND mc = ?");
			args.add(mc);
		}
		if (os != null && !os.isBlank()) {
			where.append(" AND os LIKE ?");
			args.add(os + "%");
		}
		if (backend != null && !backend.isBlank()) {
			where.append(" AND backend = ?");
			args.add(backend);
		}
		Map<String, List<long[]>> byVersion = new LinkedHashMap<>();
		Map<String, java.util.Set<String>> installs = new LinkedHashMap<>();
		Map<String, Long> firstSeen = new LinkedHashMap<>();
		try (PreparedStatement ps = db.prepareStatement("SELECT velo, launch_ms, fps_avg, fps_low, heap_mb, install, ts FROM perf WHERE " + where
				+ " ORDER BY ts")) {
			ps.setLong(1, since);
			for (int i = 0; i < args.size(); i++) {
				ps.setString(i + 2, args.get(i));
			}
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String v = rs.getString(1);
					byVersion.computeIfAbsent(v, k -> new ArrayList<>()).add(new long[] {rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)});
					installs.computeIfAbsent(v, k -> new java.util.HashSet<>()).add(String.valueOf(rs.getString(6)));
					firstSeen.putIfAbsent(v, rs.getLong(7));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
		List<Map<String, Object>> versions = new ArrayList<>();
		for (var e : byVersion.entrySet()) {
			List<long[]> rows = e.getValue();
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("velo", e.getKey());
			row.put("reports", rows.size());
			row.put("installs", installs.get(e.getKey()).size());
			row.put("since", firstSeen.get(e.getKey()));
			row.put("launchMs", median(rows, 0));
			row.put("fpsAvg", median(rows, 1));
			row.put("fpsLow", median(rows, 2));
			row.put("heapMb", median(rows, 3));
			versions.add(row);
		}
		versions.sort((a, b) -> compareVersions((String) b.get("velo"), (String) a.get("velo")));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("versions", versions);
		out.put("mcVersions", distinct("mc", since));
		out.put("oses", distinct("substr(os, 1, instr(os || ' ', ' ') - 1)", since));
		out.put("backends", distinct("backend", since));
		return out;
	}

	private List<String> distinct(String column, long since) {
		List<String> out = new ArrayList<>();
		try (PreparedStatement ps = db.prepareStatement("SELECT DISTINCT " + column + " FROM perf WHERE ts >= ? ORDER BY 1")) {
			ps.setLong(1, since);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					if (rs.getString(1) != null && !rs.getString(1).isBlank()) {
						out.add(rs.getString(1));
					}
				}
			}
		} catch (SQLException ignored) {
			// none
		}
		return out;
	}

	/** Median of column {@code i}, ignoring missing (-1) values; -1 when there's none. */
	private static long median(List<long[]> rows, int i) {
		long[] values = rows.stream().mapToLong(r -> r[i]).filter(v -> v >= 0).sorted().toArray();
		return values.length == 0 ? -1 : values[values.length / 2];
	}

	private static int compareVersions(String a, String b) {
		String[] x = a.split("\\.");
		String[] y = b.split("\\.");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? Integer.parseInt(x[i]) : 0;
			int q = i < y.length ? Integer.parseInt(y[i]) : 0;
			if (p != q) {
				return Integer.compare(p, q);
			}
		}
		return 0;
	}
}
