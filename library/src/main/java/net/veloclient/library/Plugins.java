package net.veloclient.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin update checker: for each plugin name (+ the version you run) finds the project on Modrinth, then Hangar,
 * then SpigotMC (Spiget), and reports the newest release, where it's from, whether it supports Folia, and whether it
 * looks abandoned. Answers per plugin are cached for 6 hours.
 */
final class Plugins {

	record Found(String name, String source, String url, String latest, Boolean folia, long updated, String id) {
	}

	private final Cache.Memo<Found> cache = new Cache.Memo<>(5000);
	private static final String LOADERS = "[\"paper\",\"spigot\",\"bukkit\",\"folia\",\"purpur\"]";

	List<Map<String, Object>> check(List<String[]> plugins) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (String[] p : plugins) {
			String name = p[0].strip();
			String yours = p.length > 1 && p[1] != null ? p[1].strip() : null;
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", name);
			row.put("yours", yours);
			try {
				Found f = find(name);
				if (f == null) {
					row.put("state", "not found");
				} else {
					row.put("project", f.name());
					row.put("source", f.source());
					row.put("url", f.url());
					row.put("latest", f.latest());
					row.put("folia", f.folia());
					row.put("updated", f.updated());
					boolean abandoned = f.updated() > 0 && System.currentTimeMillis() - f.updated() > 2L * 365 * 86_400_000L;
					String state;
					if (abandoned) {
						state = "abandoned";
					} else if (yours == null || f.latest() == null) {
						state = "unknown";
					} else {
						int c = compare(yours, f.latest());
						state = c >= 0 ? "up to date" : "update";
					}
					row.put("state", state);
				}
			} catch (Http.Fail e) {
				row.put("state", "try again");
			} catch (Exception e) {
				row.put("state", "error");
			}
			out.add(row);
		}
		return out;
	}

	private Found find(String name) throws IOException {
		String key = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
		if (key.isEmpty()) {
			return null;
		}
		Found cached = cache.get(key);
		if (cached != null) {
			return cached.source() == null ? null : cached;
		}
		Found f = modrinth(name, key);
		if (f == null) {
			f = hangar(name, key);
		}
		if (f == null) {
			f = spiget(name, key);
		}
		cache.put(key, f == null ? new Found(null, null, null, null, null, 0, null) : f, 6 * 3_600_000L);
		return f;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static boolean same(String a, String key) {
		return a != null && a.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "").equals(key);
	}

	private Found modrinth(String name, String key) throws IOException {
		String facets = enc("[[\"categories:paper\",\"categories:spigot\",\"categories:bukkit\",\"categories:folia\",\"categories:purpur\"]]");
		Net.Response r = Net.get("https://api.modrinth.com/v2/search?limit=5&query=" + enc(name) + "&facets=" + facets, 512 * 1024);
		if (r.status() != 200) {
			return null;
		}
		for (JsonElement e : JsonParser.parseString(r.text()).getAsJsonObject().getAsJsonArray("hits")) {
			JsonObject h = e.getAsJsonObject();
			if (!same(h.get("title").getAsString(), key) && !same(h.get("slug").getAsString(), key)) {
				continue;
			}
			String id = h.get("project_id").getAsString();
			boolean folia = false;
			for (JsonElement c : h.getAsJsonArray("categories")) {
				folia |= c.getAsString().equals("folia");
			}
			String latest = null;
			long updated = 0;
			Net.Response v = Net.get("https://api.modrinth.com/v2/project/" + id + "/version?loaders=" + enc(LOADERS), 2 << 20);
			if (v.status() == 200) {
				JsonArray versions = JsonParser.parseString(v.text()).getAsJsonArray();
				for (JsonElement ve : versions) {
					JsonObject vo = ve.getAsJsonObject();
					if (latest == null && "release".equals(vo.get("version_type").getAsString())) {
						latest = vo.get("version_number").getAsString();
						updated = java.time.Instant.parse(vo.get("date_published").getAsString()).toEpochMilli();
					}
					for (JsonElement l : vo.getAsJsonArray("loaders")) {
						folia |= l.getAsString().equals("folia");
					}
				}
			}
			return new Found(h.get("title").getAsString(), "Modrinth", "https://modrinth.com/plugin/" + h.get("slug").getAsString(), latest, folia, updated, id);
		}
		return null;
	}

	private Found hangar(String name, String key) throws IOException {
		Net.Response r = Net.get("https://hangar.papermc.io/api/v1/projects?limit=5&q=" + enc(name), 512 * 1024);
		if (r.status() != 200) {
			return null;
		}
		for (JsonElement e : JsonParser.parseString(r.text()).getAsJsonObject().getAsJsonArray("result")) {
			JsonObject p = e.getAsJsonObject();
			if (!same(p.get("name").getAsString(), key)) {
				continue;
			}
			JsonObject ns = p.getAsJsonObject("namespace");
			String slug = ns.get("slug").getAsString();
			Net.Response lr = Net.get("https://hangar.papermc.io/api/v1/projects/" + enc(slug) + "/latestrelease", 4096);
			String latest = lr.status() == 200 ? lr.text().strip() : null;
			long updated = p.has("lastUpdated") ? java.time.Instant.parse(p.get("lastUpdated").getAsString()).toEpochMilli() : 0;
			return new Found(p.get("name").getAsString(), "Hangar", "https://hangar.papermc.io/" + ns.get("owner").getAsString() + "/" + slug, latest, null, updated, slug);
		}
		return null;
	}

	private Found spiget(String name, String key) throws IOException {
		Net.Response r = Net.get("https://api.spiget.org/v2/search/resources/" + enc(name) + "?field=name&size=8&sort=-downloads", 512 * 1024);
		if (r.status() != 200) {
			return null;
		}
		for (JsonElement e : JsonParser.parseString(r.text()).getAsJsonArray()) {
			JsonObject p = e.getAsJsonObject();
			String title = p.get("name").getAsString().replaceAll("[\\[(|].*$", "").strip();
			if (!same(title, key)) {
				continue;
			}
			int id = p.get("id").getAsInt();
			Net.Response v = Net.get("https://api.spiget.org/v2/resources/" + id + "/versions/latest", 64 * 1024);
			String latest = null;
			long updated = p.has("updateDate") ? p.get("updateDate").getAsLong() * 1000 : 0;
			if (v.status() == 200) {
				JsonObject vo = JsonParser.parseString(v.text()).getAsJsonObject();
				latest = vo.get("name").getAsString();
			}
			return new Found(title, "SpigotMC", "https://www.spigotmc.org/resources/" + id, latest, null, updated, String.valueOf(id));
		}
		return null;
	}

	/** Compares version strings by their numbers (1.10.2 > 1.9); text suffixes count as older (-SNAPSHOT). */
	static int compare(String a, String b) {
		String[] x = a.replaceAll("^[vV]", "").split("[^0-9]+"), y = b.replaceAll("^[vV]", "").split("[^0-9]+");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			long xi = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i].substring(0, Math.min(9, x[i].length()))) : 0;
			long yi = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i].substring(0, Math.min(9, y[i].length()))) : 0;
			if (xi != yi) {
				return Long.compare(xi, yi);
			}
		}
		return 0;
	}
}
