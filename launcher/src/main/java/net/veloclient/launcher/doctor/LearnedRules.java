package net.veloclient.launcher.doctor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.doctor.Diagnosis.Action;
import net.veloclient.launcher.doctor.Diagnosis.Fix;
import net.veloclient.launcher.doctor.Diagnosis.Issue;
import net.veloclient.launcher.doctor.Diagnosis.Severity;
import net.veloclient.launcher.social.LauncherSocial;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fixes the Doctor learned from players. When a crash the Doctor couldn't fix went away after the player changed
 * something themselves, that change is sent to the Velo server as a candidate; once the owner approves it in the owner
 * panel it becomes a rule every launcher downloads, and the same crash gets that fix offered (first) from then on.
 */
final class LearnedRules {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static final long REFRESH_MS = 6 * 3600_000L;
	private static volatile JsonArray rules;
	private static volatile long fetchedAt;

	private LearnedRules() {
	}

	private static Path cacheFile() {
		return VeloPaths.root().resolve("cache").resolve("doctor-rules.json");
	}

	/** The approved rules - from the cache right away, refreshed from the server in the background. */
	static JsonArray rules() {
		JsonArray now = rules;
		if (now == null) {
			try {
				Path file = cacheFile();
				now = Files.exists(file) ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray() : new JsonArray();
				fetchedAt = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
			} catch (Exception e) {
				now = new JsonArray();
			}
			rules = now;
		}
		if (System.currentTimeMillis() - fetchedAt > REFRESH_MS) {
			fetchedAt = System.currentTimeMillis();
			Thread.ofVirtual().start(LearnedRules::refresh);
		}
		return now;
	}

	static void refresh() {
		String base = LauncherSocial.baseUrl();
		if (base == null) {
			return;
		}
		try {
			HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/doctor/rules")).timeout(Duration.ofSeconds(15)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() == 200) {
				JsonArray fresh = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("rules");
				rules = fresh;
				Files.createDirectories(cacheFile().getParent());
				Files.writeString(cacheFile(), fresh.toString(), StandardCharsets.UTF_8);
			}
		} catch (Exception e) {
			LauncherLog.info("Doctor rules: " + e);
		}
	}

	/** Problems from approved rules that match this crash (by signature, or by a pattern in the crash text). */
	static List<Issue> match(Snapshot s, String signature, String text) {
		List<Issue> out = new ArrayList<>();
		if (text == null && signature == null) {
			return out;
		}
		for (JsonElement element : rules()) {
			JsonObject rule = element.getAsJsonObject();
			boolean hit = signature != null && signature.equals(str(rule, "signature"));
			String pattern = str(rule, "pattern");
			if (!hit && pattern != null && !pattern.isBlank() && text != null) {
				try {
					hit = Pattern.compile(pattern).matcher(text).find();
				} catch (RuntimeException ignored) {
					// A broken pattern never matches.
				}
			}
			if (!hit) {
				continue;
			}
			List<Action> actions = new ArrayList<>();
			for (JsonElement a : rule.has("actions") ? rule.getAsJsonArray("actions") : new JsonArray()) {
				Action action = toAction(s, a.getAsJsonObject());
				if (action != null) {
					actions.add(action);
				}
			}
			if (actions.isEmpty()) {
				continue;
			}
			String title = str(rule, "title");
			out.add(new Issue("learned:" + str(rule, "id"), Severity.CRASH, title != null ? title : "A known crash",
					str(rule, "detail") != null ? str(rule, "detail") : "Other players had this crash - this is what fixed it for them.",
					List.of(new Fix(str(rule, "fixLabel") != null ? str(rule, "fixLabel") : "Apply the known fix", "Approved by the Velo team", actions)),
					List.of()));
		}
		return out;
	}

	private static Action toAction(Snapshot s, JsonObject a) {
		String op = str(a, "op");
		String mod = str(a, "mod");
		String name = str(a, "name") != null ? str(a, "name") : mod;
		if (op == null) {
			return null;
		}
		switch (op) {
			case "disable", "remove", "update" -> {
				ModScanner.ModInfo found = mod == null ? null : s.enabledById(mod);
				if (found == null) {
					return null;
				}
				return op.equals("disable") ? new Action.Disable(found.file(), found.displayName())
						: op.equals("remove") ? new Action.Remove(found.file(), found.displayName())
						: new Action.Update(found.file(), found.displayName(), mod, true);
			}
			case "enable" -> {
				List<ModScanner.ModInfo> off = mod == null ? List.of() : s.disabledProviders(mod);
				return off.isEmpty() ? null : new Action.Enable(off.get(0).file(), off.get(0).displayName());
			}
			case "install" -> {
				String project = str(a, "project") != null ? str(a, "project") : mod;
				return project == null || mod != null && s.enabledById(mod) != null ? null : new Action.Install(project, name, mod != null ? mod : project);
			}
			case "graphics" -> {
				return str(a, "value") == null ? null : new Action.SetGraphicsApi(str(a, "value"));
			}
			case "memory" -> {
				try {
					return new Action.SetMemory(Integer.parseInt(str(a, "value")));
				} catch (RuntimeException e) {
					return null;
				}
			}
			case "shaders-off" -> {
				return new Action.DisableShaders();
			}
			default -> {
				return null;
			}
		}
	}

	/** Sends what the player changed that made a crash go away (the server keeps it until the owner decides). */
	static void submit(JsonObject candidate) {
		String base = LauncherSocial.baseUrl();
		String token = LauncherSocial.sessionToken();
		if (base == null || token == null) {
			return;
		}
		Thread.ofVirtual().start(() -> {
			try {
				HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/doctor/learned")).timeout(Duration.ofSeconds(15))
						.header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(candidate.toString())).build(), HttpResponse.BodyHandlers.discarding());
			} catch (Exception e) {
				LauncherLog.info("Doctor learned fix not sent: " + e);
			}
		});
	}

	private static String str(JsonObject json, String key) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
	}
}
