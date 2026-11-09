package net.veloclient.library;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Reads a spark profiler link (spark.lucko.me/&lt;id&gt; - the raw data lives on bytebin.lucko.me) and turns the
 * sampling data into what a server owner needs: which plugins cost how much of the server thread, the hottest
 * methods, TPS/MSPT, memory, GC, entities, and plain-language advice from the server's own configs and flags.
 */
final class Spark {

	static final Pattern ID = Pattern.compile("^[A-Za-z0-9]{4,24}$");
	private final Cache.Memo<Map<String, Object>> results = new Cache.Memo<>(300);

	static String idOf(String input) {
		if (input == null) {
			return null;
		}
		String s = input.trim().replaceAll("^https?://", "").replaceAll("^(spark\\.lucko\\.me|bytebin\\.lucko\\.me)/", "").replaceAll("[?#].*$", "");
		return ID.matcher(s).matches() ? s : null;
	}

	Map<String, Object> analyze(String id) throws IOException {
		Map<String, Object> cached = results.get(id);
		if (cached != null) {
			return cached;
		}
		Net.Response r = Net.get("https://bytebin.lucko.me/" + id, 40 << 20);
		if (r.status() == 404) {
			throw new Http.Fail(404, "No spark profile with that id (they expire after a while)");
		}
		if (r.status() != 200) {
			throw new Http.Fail(502, "bytebin answered " + r.status());
		}
		String type = r.headers().getOrDefault("content-type", List.of("")).get(0);
		byte[] body = r.body();
		if (body.length > 2 && (body[0] & 255) == 0x1f && (body[1] & 255) == 0x8b) {
			try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
				body = in.readNBytes(200 << 20);
			}
		}
		if (type.contains("heap")) {
			throw new Http.Fail(400, "That's a heap summary - the analyzer reads profiler links (/spark profiler)");
		}
		if (type.contains("health")) {
			throw new Http.Fail(400, "That's a health report - the analyzer reads profiler links (/spark profiler start, then stop)");
		}
		Map<String, Object> out;
		try {
			out = sampler(Proto.parse(body));
		} catch (RuntimeException e) {
			throw new Http.Fail(422, "Couldn't read that profile (" + e.getMessage() + ")");
		}
		out.put("id", id);
		out.put("link", "https://spark.lucko.me/" + id);
		results.put(id, out, 6 * 3_600_000L);
		return out;
	}

	private static Map<String, Object> sampler(Proto data) {
		Proto meta = data.msg(1);
		Map<String, String> classSources = data.stringMap(3);
		Map<String, Object> out = new LinkedHashMap<>();
		List<Map<String, Object>> advice = new ArrayList<>();

		// --- platform & stats
		Map<String, Object> platform = new LinkedHashMap<>();
		double tps1 = -1, msptMedian = -1, msptMax = -1;
		long players = -1;
		String vmArgs = "";
		long heapUsed = 0, heapMax = 0;
		Map<String, String> configs = Map.of();
		Map<String, Proto> sources = Map.of();
		if (meta != null) {
			Proto pm = meta.msg(7);
			if (pm != null) {
				platform.put("name", pm.str(2));
				platform.put("version", pm.str(3));
				platform.put("minecraft", pm.str(4));
				platform.put("spark", pm.str(9));
			}
			platform.put("start", meta.num(2));
			platform.put("end", meta.num(11));
			platform.put("intervalMicros", meta.num(3));
			platform.put("ticks", meta.num(12));
			platform.put("comment", meta.str(6));
			Proto ps = meta.msg(8);
			if (ps != null) {
				Proto tps = ps.msg(4);
				if (tps != null) {
					tps1 = tps.dbl(1);
					platform.put("tps", Http.map("1m", round(tps.dbl(1)), "5m", round(tps.dbl(2)), "15m", round(tps.dbl(3))));
				}
				Proto mspt = ps.msg(5);
				if (mspt != null && mspt.msg(1) != null) {
					Proto m1 = mspt.msg(1);
					msptMedian = m1.dbl(4);
					msptMax = m1.dbl(2);
					platform.put("mspt", Http.map("mean", round(m1.dbl(1)), "median", round(m1.dbl(4)), "p95", round(m1.dbl(5)), "max", round(m1.dbl(2))));
				}
				players = ps.num(7);
				platform.put("players", players);
				Proto mem = ps.msg(1);
				if (mem != null && mem.msg(1) != null) {
					heapUsed = mem.msg(1).num(1);
					heapMax = mem.msg(1).num(4);
					platform.put("heap", Http.map("usedMb", heapUsed >> 20, "maxMb", heapMax >> 20));
				}
				Proto world = ps.msg(8);
				if (world != null) {
					Map<String, Long> counts = new HashMap<>();
					for (Proto e : world.msgs(2)) {
						counts.put(e.str(1), e.num(2));
					}
					List<Map<String, Object>> top = counts.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(10)
							.map(e -> Http.map("type", e.getKey(), "count", e.getValue())).toList();
					platform.put("entities", Http.map("total", world.num(1), "top", top));
					if (world.num(1) > 6000) {
						advice.add(tip("high", "Lots of entities (" + world.num(1) + ")",
								"Mob farms and dropped items cost tick time. Lower spawn limits in bukkit.yml, entity-activation-range in spigot.yml, or use a farm limiter."));
					}
				}
			}
			Proto sys = meta.msg(9);
			if (sys != null) {
				Proto cpu = sys.msg(1);
				if (cpu != null) {
					platform.put("cpu", Http.map("model", cpu.str(4), "threads", cpu.num(1),
							"process1m", cpu.msg(2) == null ? null : round(cpu.msg(2).dbl(1) * 100), "system1m", cpu.msg(3) == null ? null : round(cpu.msg(3).dbl(1) * 100)));
				}
				Proto java = sys.msg(6);
				if (java != null) {
					vmArgs = java.str(4) == null ? "" : java.str(4);
					platform.put("java", Http.map("vendor", java.str(1), "version", java.str(2), "args", vmArgs));
				}
				Map<String, Object> gcs = new LinkedHashMap<>();
				for (var e : sys.msgMap(3).entrySet()) {
					gcs.put(e.getKey(), Http.map("total", e.getValue().num(1), "avgMs", round(e.getValue().dbl(2)), "avgFrequencyMs", round(e.getValue().dbl(3))));
				}
				platform.put("gc", gcs);
			}
			configs = meta.stringMap(10);
			sources = meta.msgMap(13);
		}
		out.put("platform", platform);

		// --- the call tree: self time per source and per method
		Map<String, Double> bySource = new HashMap<>();
		Map<String, Double> byMethod = new HashMap<>();
		Map<String, String> methodSource = new HashMap<>();
		double total = 0;
		List<Map<String, Object>> threads = new ArrayList<>();
		for (Proto t : data.msgs(2)) {
			String name = t.str(1);
			List<Proto> nodes = t.msgs(3);
			double[] nodeTotal = new double[nodes.size()];
			for (int i = 0; i < nodes.size(); i++) {
				nodeTotal[i] = sum(nodes.get(i).doubles(8));
			}
			double threadTotal = sum(t.doubles(4));
			threads.add(Http.map("name", name, "ms", Math.round(threadTotal)));
			boolean main = name != null && (name.equals("Server thread") || name.startsWith("Region Scheduler Thread") || name.equals("Server Thread"));
			if (!main && data.msgs(2).size() > 1) {
				continue;
			}
			total += threadTotal;
			for (int i = 0; i < nodes.size(); i++) {
				Proto n = nodes.get(i);
				double self = nodeTotal[i];
				for (int c : n.ints(9)) {
					if (c >= 0 && c < nodeTotal.length) {
						self -= nodeTotal[c];
					}
				}
				if (self <= 0) {
					continue;
				}
				String cls = n.str(3) == null ? "?" : n.str(3);
				String src = source(cls, classSources);
				bySource.merge(src, self, Double::sum);
				String method = cls + "." + n.str(4) + "()";
				byMethod.merge(method, self, Double::sum);
				methodSource.put(method, src);
			}
		}
		threads.sort((a, b) -> Long.compare((long) b.get("ms"), (long) a.get("ms")));
		out.put("threads", threads.subList(0, Math.min(8, threads.size())));
		final double tot = Math.max(total, 1e-9);
		final Map<String, Proto> srcInfo = sources;
		List<Map<String, Object>> sourceList = bySource.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(15)
				.map(e -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("name", e.getKey());
					m.put("percent", round(e.getValue() / tot * 100));
					Proto info = srcInfo.get(e.getKey());
					if (info != null) {
						m.put("version", info.str(2));
						m.put("author", info.str(3));
					}
					m.put("plugin", !isPlatform(e.getKey()));
					return m;
				}).toList();
		out.put("sources", sourceList);
		out.put("methods", byMethod.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(15)
				.map(e -> Http.map("method", e.getKey(), "source", methodSource.get(e.getKey()), "percent", round(e.getValue() / tot * 100))).toList());

		// --- advice
		for (Map<String, Object> s : sourceList) {
			double pct = (double) s.get("percent");
			if ((boolean) s.get("plugin") && pct >= 8) {
				advice.add(tip(pct >= 20 ? "high" : "medium", s.get("name") + " uses " + pct + "% of the server thread",
						"Check its settings, update it, or look for a lighter alternative. Its hottest methods are listed below."));
			}
		}
		if (tps1 >= 0 && tps1 < 19) {
			advice.add(tip(tps1 < 15 ? "high" : "medium", "TPS is " + round(tps1),
					"The server can't keep 20 ticks per second. The share list shows what eats the tick."));
		}
		if (msptMedian > 40) {
			advice.add(tip("high", "Ticks take " + round(msptMedian) + " ms (median)", "Above 50 ms the server falls behind. Peak tick: " + round(msptMax) + " ms."));
		}
		if (heapMax > 0 && heapUsed > heapMax * 0.9) {
			advice.add(tip("high", "Memory almost full (" + (heapUsed >> 20) + " / " + (heapMax >> 20) + " MB)",
					"Give the server more RAM (-Xmx), or find what keeps memory with a heap summary (/spark heapsummary)."));
		}
		if (!vmArgs.isEmpty()) {
			if (!vmArgs.contains("UseG1GC") && !vmArgs.contains("UseZGC") && !vmArgs.contains("UseShenandoahGC")) {
				advice.add(tip("medium", "No tuned garbage collector flags", "Use Aikar's G1 flags or generational ZGC - the Flags generator writes them for your RAM."));
			}
			if (vmArgs.contains("UseConcMarkSweepGC") || vmArgs.contains("UseParallelGC")) {
				advice.add(tip("medium", "Old GC in the start flags", "CMS / Parallel GC cause long pauses on game servers - switch to G1 or ZGC."));
			}
			if (vmArgs.matches(".*-Xms(\\d+)([GgMm]).*") && vmArgs.matches(".*-Xmx(\\d+)([GgMm]).*")) {
				String xms = vmArgs.replaceAll(".*-Xms(\\d+[GgMm]).*", "$1"), xmx = vmArgs.replaceAll(".*-Xmx(\\d+[GgMm]).*", "$1");
				if (!xms.equalsIgnoreCase(xmx)) {
					advice.add(tip("low", "-Xms differs from -Xmx (" + xms + " vs " + xmx + ")", "Set both to the same value so the heap never has to grow mid-game."));
				}
			}
		}
		configAdvice(configs, advice);
		if (advice.isEmpty()) {
			advice.add(tip("info", "Nothing stands out", "No plugin dominates the server thread and the vitals look healthy."));
		}
		out.put("advice", advice);
		return out;
	}

	private static void configAdvice(Map<String, String> configs, List<Map<String, Object>> advice) {
		try {
			String props = configs.get("server.properties");
			if (props != null) {
				JsonObject p = JsonParser.parseString(props).getAsJsonObject();
				int view = p.has("view-distance") ? p.get("view-distance").getAsInt() : -1;
				int sim = p.has("simulation-distance") ? p.get("simulation-distance").getAsInt() : -1;
				if (view > 10) {
					advice.add(tip("medium", "view-distance is " + view, "8–10 is plenty for most servers; every step adds chunks to send and keep."));
				}
				if (sim > 8) {
					advice.add(tip("medium", "simulation-distance is " + sim, "Ticked chunks grow with the square of this - 4 to 6 saves a lot."));
				}
			}
			String spigot = configs.get("spigot.yml");
			if (spigot != null && spigot.contains("\"entity-activation-range\"")) {
				JsonObject s = JsonParser.parseString(spigot).getAsJsonObject();
				var ww = s.getAsJsonObject("world-settings");
				if (ww != null && ww.getAsJsonObject("default") != null && ww.getAsJsonObject("default").getAsJsonObject("entity-activation-range") != null) {
					var ear = ww.getAsJsonObject("default").getAsJsonObject("entity-activation-range");
					if (ear.has("monsters") && ear.get("monsters").getAsInt() > 32) {
						advice.add(tip("low", "Monsters are active up to " + ear.get("monsters").getAsInt() + " blocks", "entity-activation-range.monsters 24–32 is usual."));
					}
				}
			}
			for (String name : configs.keySet()) {
				if (name.contains("paper-world-defaults") && configs.get(name).contains("\"disable-move-event\":false")) {
					advice.add(tip("low", "Hopper move events are on", "hopper.disable-move-event: true in paper-world-defaults.yml saves time with many hoppers (unless a plugin needs them)."));
				}
			}
		} catch (RuntimeException ignored) {
			// configs are optional and vary - no advice from them then
		}
	}

	private static Map<String, Object> tip(String level, String title, String text) {
		return Http.map("level", level, "title", title, "text", text);
	}

	private static boolean isPlatform(String s) {
		return s.equals("Minecraft") || s.equals("Server software") || s.equals("Java") || s.equals("Networking") || s.equals("Other") || s.equals("Idle / waiting");
	}

	private static String source(String cls, Map<String, String> classSources) {
		String s = classSources.get(cls);
		if (s != null) {
			return s;
		}
		if (cls.startsWith("net.minecraft.") || cls.startsWith("com.mojang.")) {
			return "Minecraft";
		}
		if (cls.startsWith("org.bukkit.") || cls.startsWith("org.spigotmc.") || cls.startsWith("io.papermc.") || cls.startsWith("ca.spottedleaf.")
				|| cls.startsWith("com.destroystokyo.") || cls.startsWith("net.fabricmc.") || cls.startsWith("org.purpurmc.")) {
			return "Server software";
		}
		if (cls.startsWith("io.netty.")) {
			return "Networking";
		}
		if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.")) {
			return cls.contains("LockSupport") || cls.contains("Unsafe") || cls.contains("Thread") ? "Idle / waiting" : "Java";
		}
		return "Other";
	}

	private static double sum(double[] a) {
		double s = 0;
		for (double d : a) {
			s += d;
		}
		return s;
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}
}
