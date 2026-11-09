package net.veloclient.library;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Community presets for every tool (MOTDs, TAB animations, banners, poses, enchant plans, flags...). Publishing and
 * liking need a velo-client.com sign-in; anyone can report. Five reports hide a preset until the owner looks at it.
 */
final class Presets {

	static final Set<String> TOOLS = Set.of("motd", "tab", "banner", "mcbanner", "pose", "enchant", "flags", "gradient", "seed", "color", "crosshair", "hud");
	private static final Pattern BAD = Pattern.compile("(?i)(n[i1!]gg|f[a@]gg?[o0]t|retard|kys\\b|hitler|nazi|heil|https?://|discord\\.gg|\\.(ru|xyz|tk)\\b)");
	private static final SecureRandom RANDOM = new SecureRandom();

	static final class Preset {
		String id;
		String tool;
		String name;
		JsonObject data;
		String author;
		String authorName;
		long created;
		Set<String> likes = new HashSet<>();
		long uses;
		Set<String> reports = new HashSet<>();
		boolean hidden;
	}

	private final JsonFile<Map<String, Preset>> store;
	private final Map<String, List<Long>> recentPosts = new HashMap<>();

	Presets(Path dataDir) {
		store = new JsonFile<>(dataDir.resolve("presets.json"), new TypeToken<Map<String, Preset>>() {
		}.getType(), HashMap::new);
	}

	List<Map<String, Object>> list(String tool, String sort, String query, int limit, String viewer) {
		Map<String, Preset> all = store.get();
		List<Preset> hits = new ArrayList<>();
		String q = query == null ? null : query.toLowerCase(java.util.Locale.ROOT);
		synchronized (all) {
			for (Preset p : all.values()) {
				if (p.hidden || (tool != null && !tool.equals(p.tool))) {
					continue;
				}
				if (q != null && !p.name.toLowerCase(java.util.Locale.ROOT).contains(q) && !p.authorName.toLowerCase(java.util.Locale.ROOT).contains(q)) {
					continue;
				}
				hits.add(p);
			}
		}
		if ("new".equals(sort)) {
			hits.sort((a, b) -> Long.compare(b.created, a.created));
		} else {
			long now = System.currentTimeMillis();
			// popular: likes and uses, gently favouring recent ones
			hits.sort((a, b) -> Double.compare(score(b, now), score(a, now)));
		}
		return hits.stream().limit(limit).map(p -> json(p, viewer)).toList();
	}

	private static double score(Preset p, long now) {
		double ageDays = (now - p.created) / 86_400_000.0;
		return (p.likes.size() * 3 + Math.log1p(p.uses) * 2) / Math.pow(ageDays + 2, 0.3);
	}

	Map<String, Object> get(String id, String viewer) {
		Preset p = find(id);
		if (p == null || p.hidden) {
			throw new Http.Fail(404, "No such preset");
		}
		return json(p, viewer);
	}

	Map<String, Object> create(Accounts.User u, String tool, String name, JsonObject data) {
		if (tool == null || !TOOLS.contains(tool)) {
			throw new Http.Fail(400, "Unknown tool");
		}
		String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", "").strip();
		if (n.length() < 3 || n.length() > 40) {
			throw new Http.Fail(400, "Give it a name with 3 to 40 characters");
		}
		boolean pictures = tool.equals("crosshair") || tool.equals("hud");
		if (data == null || data.toString().length() > (pictures ? 60_000 : 8000)) {
			throw new Http.Fail(400, "The preset is too large");
		}
		if (pictures) {
			Designs.check(data);
		}
		if (BAD.matcher(n).find() || !pictures && BAD.matcher(strings(data)).find()) {
			throw new Http.Fail(400, "That name or text isn't allowed");
		}
		Map<String, Preset> all = store.get();
		synchronized (all) {
			long mine = all.values().stream().filter(p -> p.author.equals(u.uuid())).count();
			if (mine >= 30) {
				throw new Http.Fail(400, "You have 30 presets already - delete one first");
			}
			List<Long> times = recentPosts.computeIfAbsent(u.uuid(), k -> new ArrayList<>());
			long now = System.currentTimeMillis();
			times.removeIf(t -> now - t > 3_600_000);
			if (times.size() >= 6) {
				throw new Http.Fail(429, "That's a lot of presets in one hour - try again later", 600);
			}
			times.add(now);
			Preset p = new Preset();
			byte[] b = new byte[6];
			RANDOM.nextBytes(b);
			p.id = HexFormat.of().formatHex(b);
			p.tool = tool;
			p.name = n;
			p.data = data;
			p.author = u.uuid();
			p.authorName = u.username();
			p.created = now;
			all.put(p.id, p);
			store.changed();
			return json(p, u.uuid());
		}
	}

	private static String strings(JsonElement e) {
		StringBuilder sb = new StringBuilder();
		if (e.isJsonPrimitive()) {
			sb.append(e.getAsString()).append(' ');
		} else if (e.isJsonObject()) {
			e.getAsJsonObject().entrySet().forEach(x -> sb.append(strings(x.getValue())));
		} else if (e.isJsonArray()) {
			e.getAsJsonArray().forEach(x -> sb.append(strings(x)));
		}
		return sb.toString();
	}

	Map<String, Object> like(String id, Accounts.User u) {
		Preset p = find(id);
		if (p == null || p.hidden) {
			throw new Http.Fail(404, "No such preset");
		}
		synchronized (store.get()) {
			if (!p.likes.remove(u.uuid())) {
				p.likes.add(u.uuid());
			}
			store.changed();
			return json(p, u.uuid());
		}
	}

	void use(String id) {
		Preset p = find(id);
		if (p != null) {
			synchronized (store.get()) {
				p.uses++;
				store.changed();
			}
		}
	}

	void report(String id, String reporter) {
		Preset p = find(id);
		if (p == null) {
			return;
		}
		synchronized (store.get()) {
			if (p.reports.size() < 50) {
				p.reports.add(reporter);
			}
			if (p.reports.size() >= 5) {
				p.hidden = true;
			}
			store.changed();
		}
	}

	boolean delete(String id, Accounts.User u, boolean owner) {
		Map<String, Preset> all = store.get();
		synchronized (all) {
			Preset p = all.get(id);
			if (p == null || (!p.author.equals(u.uuid()) && !owner)) {
				return false;
			}
			all.remove(id);
			store.changed();
			return true;
		}
	}

	/** Owner view: reported or hidden presets first. */
	List<Map<String, Object>> moderation() {
		Map<String, Preset> all = store.get();
		synchronized (all) {
			return all.values().stream().filter(p -> p.hidden || !p.reports.isEmpty())
					.sorted((a, b) -> Integer.compare(b.reports.size(), a.reports.size())).limit(200)
					.map(p -> {
						Map<String, Object> m = json(p, null);
						m.put("reports", p.reports.size());
						m.put("hidden", p.hidden);
						return m;
					}).toList();
		}
	}

	void setHidden(String id, boolean hidden) {
		Preset p = find(id);
		if (p != null) {
			synchronized (store.get()) {
				p.hidden = hidden;
				if (!hidden) {
					p.reports.clear();
				}
				store.changed();
			}
		}
	}

	private Preset find(String id) {
		if (id == null || !id.matches("[0-9a-f]{12}")) {
			return null;
		}
		Map<String, Preset> all = store.get();
		synchronized (all) {
			return all.get(id);
		}
	}

	private static Map<String, Object> json(Preset p, String viewer) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", p.id);
		m.put("tool", p.tool);
		m.put("name", p.name);
		m.put("data", p.data);
		m.put("author", p.authorName);
		m.put("authorUuid", p.author);
		m.put("created", p.created);
		m.put("likes", p.likes.size());
		m.put("liked", viewer != null && p.likes.contains(viewer));
		m.put("uses", p.uses);
		m.put("mine", viewer != null && viewer.equals(p.author));
		return m;
	}

	void save() {
		store.saveIfDirty();
	}
}
