package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The launcher Doctor's shared memory ({@code data/doctor.json}). Launchers send a candidate when a player fixed a
 * crash the Doctor couldn't (what they changed, the crash signature, what the Doctor had tried); identical candidates
 * are counted, not repeated. The owner approves one in the owner panel - or straight from a bug report that carries
 * one - and it becomes a rule: every launcher downloads the rules and offers that fix first for the same crash.
 */
final class DoctorService {

	static final class Candidate {
		String id;
		long firstAt;
		long lastAt;
		int count;
		String signature;
		String summary;
		String minecraft;
		String excerpt;
		JsonArray diff;
		JsonArray tried;
		String lastUsername;
		String reportId;
	}

	static final class Rule {
		String id;
		String signature;
		/** Optional regex on the crash text - matches the same crash across versions. */
		String pattern;
		String title;
		String detail;
		String fixLabel;
		JsonArray actions;
		long approvedAt;
		String fromCandidate;
		String minecraft;
	}

	private static final class Data {
		List<Candidate> candidates = new ArrayList<>();
		List<Rule> rules = new ArrayList<>();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int MAX_CANDIDATES = 500;
	private final Path file;
	private Data data;

	DoctorService(Path dataDir) {
		this.file = dataDir.resolve("doctor.json");
		Data loaded = null;
		try {
			if (Files.exists(file)) {
				loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
			}
		} catch (Exception e) {
			System.out.println("[doctor] couldn't read doctor.json: " + e.getMessage());
		}
		data = loaded == null ? new Data() : loaded;
	}

	private void save() {
		try {
			Path tmp = file.resolveSibling("doctor.json.tmp");
			Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[doctor] save failed: " + e.getMessage());
		}
	}

	synchronized List<Rule> rules() {
		return List.copyOf(data.rules);
	}

	/** A launcher reports what its player changed that made a crash go away. */
	synchronized void learn(JsonObject in, String username) {
		String signature = str(in, "signature");
		JsonArray diff = in.has("diff") && in.get("diff").isJsonArray() ? sanitizeDiff(in.getAsJsonArray("diff")) : new JsonArray();
		if (signature == null || diff.isEmpty()) {
			return;
		}
		String key = signature + "|" + diff;
		for (Candidate c : data.candidates) {
			if ((c.signature + "|" + c.diff).equals(key)) {
				c.count++;
				c.lastAt = System.currentTimeMillis();
				c.lastUsername = username;
				save();
				return;
			}
		}
		Candidate c = new Candidate();
		c.id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		c.firstAt = c.lastAt = System.currentTimeMillis();
		c.count = 1;
		c.signature = clip(signature, 40);
		c.summary = clip(str(in, "summary"), 300);
		c.minecraft = clip(str(in, "minecraft"), 16);
		c.excerpt = clip(str(in, "excerpt"), 6000);
		c.diff = diff;
		c.tried = in.has("tried") && in.get("tried").isJsonArray() ? in.getAsJsonArray("tried") : new JsonArray();
		c.lastUsername = username;
		data.candidates.add(c);
		if (data.candidates.size() > MAX_CANDIDATES) {
			data.candidates.sort((a, b) -> Long.compare(b.lastAt, a.lastAt));
			data.candidates = new ArrayList<>(data.candidates.subList(0, MAX_CANDIDATES));
		}
		save();
	}

	/** Only the ops the launcher understands, with short fields. */
	private static JsonArray sanitizeDiff(JsonArray diff) {
		JsonArray out = new JsonArray();
		for (JsonElement e : diff) {
			if (!e.isJsonObject() || out.size() >= 20) {
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			String op = str(o, "op");
			if (op == null || !List.of("disable", "enable", "remove", "install", "update", "graphics", "memory", "shaders-off").contains(op)) {
				continue;
			}
			JsonObject clean = new JsonObject();
			clean.addProperty("op", op);
			for (String k : new String[] {"mod", "name", "project", "value", "note"}) {
				String v = str(o, k);
				if (v != null) {
					clean.addProperty(k, clip(v, 120));
				}
			}
			out.add(clean);
		}
		return out;
	}

	synchronized Map<String, Object> overview() {
		Map<String, Object> out = new LinkedHashMap<>();
		List<Candidate> sorted = new ArrayList<>(data.candidates);
		sorted.sort((a, b) -> b.count != a.count ? Integer.compare(b.count, a.count) : Long.compare(b.lastAt, a.lastAt));
		out.put("candidates", sorted);
		out.put("rules", data.rules);
		return out;
	}

	/** Turns a candidate into a rule. {@code actions} (nullable) overrides the candidate's diff. */
	synchronized Rule approve(String candidateId, String title, String detail, String fixLabel, String pattern, JsonArray actions) throws ApiError {
		Candidate c = data.candidates.stream().filter(x -> x.id.equals(candidateId)).findFirst()
				.orElseThrow(() -> new ApiError(404, "No such candidate"));
		Rule rule = new Rule();
		rule.id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		rule.signature = c.signature;
		rule.pattern = pattern == null || pattern.isBlank() ? null : pattern;
		rule.title = title == null || title.isBlank() ? (c.summary != null ? "A known crash: " + clip(c.summary, 80) : "A known crash") : clip(title, 140);
		rule.detail = detail == null || detail.isBlank() ? "Other players had this exact crash - this is what fixed it for them." : clip(detail, 400);
		rule.fixLabel = fixLabel == null || fixLabel.isBlank() ? "Apply the fix that worked for others" : clip(fixLabel, 80);
		rule.actions = actions != null && !actions.isEmpty() ? sanitizeDiff(actions) : c.diff;
		rule.approvedAt = System.currentTimeMillis();
		rule.fromCandidate = c.id;
		rule.minecraft = c.minecraft;
		data.rules.add(rule);
		data.candidates.remove(c);
		save();
		return rule;
	}

	/** A bug report's Doctor section carries a learned fix: make it a candidate (and return it) so it can be approved. */
	synchronized Candidate fromReport(String reportId, JsonObject doctor, String username) throws ApiError {
		if (doctor == null || !doctor.has("learned") || !doctor.get("learned").isJsonObject()) {
			throw new ApiError(400, "This report has no fix the player found");
		}
		JsonObject learned = doctor.getAsJsonObject("learned");
		learn(learned, username);
		String key = str(learned, "signature") + "|" + sanitizeDiff(learned.getAsJsonArray("diff"));
		Candidate c = data.candidates.stream().filter(x -> (x.signature + "|" + x.diff).equals(key)).findFirst()
				.orElseThrow(() -> new ApiError(400, "Couldn't read the fix in this report"));
		c.reportId = reportId;
		save();
		return c;
	}

	synchronized void reject(String candidateId) {
		data.candidates.removeIf(c -> c.id.equals(candidateId));
		save();
	}

	synchronized void deleteRule(String ruleId) {
		data.rules.removeIf(r -> r.id.equals(ruleId));
		save();
	}

	// ---- Routes ----

	void register(HttpServer server, SessionRegistry registry, ReportService reports, String adminToken) {
		RateLimiter limiter = new RateLimiter();
		server.createContext("/v1/doctor/rules", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			ex.getResponseHeaders().set("Cache-Control", "public, max-age=600");
			JsonHttp.writeJson(ex, 200, Map.of("rules", rules()));
		}));
		server.createContext("/v1/doctor/learned", exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(ex));
			if (session == null || SessionRegistry.KIND_WEB.equals(session.kind())) {
				JsonHttp.writeError(ex, 401, "Sign in to the Velo launcher first");
				return;
			}
			if (limiter.tryAcquire(session.uuid(), "doctor-learned", new RateLimiter.Window(10, 3600_000)) > 0) {
				JsonHttp.writeError(ex, 429, "Slow down");
				return;
			}
			JsonObject body = JsonParser.parseString(new String(JsonHttp.readRawBody(ex, 64 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
			learn(body, session.username());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		}));
		admin(server, adminToken, "/v1/admin/doctor", "GET", ex -> JsonHttp.writeJson(ex, 200, overview()));
		admin(server, adminToken, "/v1/admin/doctor/approve", "POST", ex -> {
			JsonObject body = body(ex);
			String candidate = str(body, "candidateId");
			if (candidate == null && str(body, "reportId") != null) {
				ReportService.Report report = reports.get(str(body, "reportId"));
				if (report == null) {
					throw new ApiError(404, "No such report");
				}
				candidate = fromReport(report.id, report.doctor, report.username).id;
			}
			if (candidate == null) {
				throw new ApiError(400, "candidateId or reportId needed");
			}
			JsonArray actions = body.has("actions") && body.get("actions").isJsonArray() ? body.getAsJsonArray("actions") : null;
			JsonHttp.writeJson(ex, 200, approve(candidate, str(body, "title"), str(body, "detail"), str(body, "fixLabel"), str(body, "pattern"), actions));
		});
		admin(server, adminToken, "/v1/admin/doctor/reject", "POST", ex -> {
			reject(str(body(ex), "candidateId"));
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		admin(server, adminToken, "/v1/admin/doctor/rule/delete", "POST", ex -> {
			deleteRule(str(body(ex), "id"));
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
	}

	private interface Handler {
		void handle(com.sun.net.httpserver.HttpExchange ex) throws Exception;
	}

	private static void admin(HttpServer server, String adminToken, String path, String method, Handler handler) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, method, ex -> {
			String header = ex.getRequestHeaders().getFirst("X-Velo-Admin-Token");
			if (adminToken == null || header == null || !java.security.MessageDigest.isEqual(
					adminToken.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8))) {
				JsonHttp.writeError(ex, 403, "Forbidden");
				return;
			}
			try {
				handler.handle(ex);
			} catch (ApiError e) {
				JsonHttp.writeError(ex, e.status, e.getMessage());
			}
		}));
	}

	private static JsonObject body(com.sun.net.httpserver.HttpExchange ex) throws IOException {
		return JsonParser.parseString(new String(JsonHttp.readRawBody(ex, 64 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static String str(JsonObject json, String key) {
		return json != null && json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
	}

	private static String clip(String s, int max) {
		return s == null ? null : s.length() > max ? s.substring(0, max) : s;
	}
}
