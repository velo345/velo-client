package net.veloclient.bridge.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which mods Velo players join with (they report their mod list in hello; players can switch that off in Velo).
 * Logged per day to {@code plugins/VeloBridge/mod-logs/<date>.log}, counted for the owner panel, and matched against
 * the forbid rules - a match is a "detection" (staff are told when notify.on-detection is on).
 */
public final class ModLog {

	public record Mod(String id, String name, String version) {
	}

	private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();
	private static final List<Map<String, Object>> DETECTIONS = new ArrayList<>();

	private ModLog() {
	}

	public static List<Mod> parse(JsonObject hello) {
		List<Mod> out = new ArrayList<>();
		if (!hello.has("mods") || !hello.get("mods").isJsonArray()) {
			return out;
		}
		for (JsonElement e : hello.getAsJsonArray("mods")) {
			if (out.size() >= 600 || !e.isJsonObject()) {
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			String id = clip(str(o, "id"), 64);
			if (id.isEmpty()) {
				continue;
			}
			out.add(new Mod(id.toLowerCase(Locale.ROOT), clip(str(o, "name"), 80), clip(str(o, "version"), 40)));
		}
		return out;
	}

	/** Mods that aren't part of every Fabric game (Minecraft, Java, Fabric itself, Velo's own). */
	public static boolean interesting(Mod m) {
		String id = m.id();
		return !(id.equals("minecraft") || id.equals("java") || id.startsWith("fabric") || id.equals("fabricloader") || id.startsWith("velo")
				|| id.equals("mixinextras") || id.startsWith("org_") || id.startsWith("com_") || id.startsWith("net_") || id.startsWith("io_"));
	}

	public static void log(Path dataDir, String player, String veloVersion, List<Mod> mods, List<String> detected) {
		for (Mod m : mods) {
			if (interesting(m)) {
				COUNTS.merge(m.id() + "|" + (m.name().isEmpty() ? m.id() : m.name()), 1, Integer::sum);
			}
		}
		synchronized (DETECTIONS) {
			if (!detected.isEmpty()) {
				Map<String, Object> d = new LinkedHashMap<>();
				d.put("player", player);
				d.put("mods", detected);
				d.put("t", System.currentTimeMillis());
				DETECTIONS.add(0, d);
				while (DETECTIONS.size() > 100) {
					DETECTIONS.remove(DETECTIONS.size() - 1);
				}
			}
		}
		try {
			Path dir = Files.createDirectories(dataDir.resolve("mod-logs"));
			StringBuilder line = new StringBuilder(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))).append(' ').append(player)
					.append(" (Velo ").append(veloVersion).append(")");
			if (!detected.isEmpty()) {
				line.append(" DETECTED: ").append(String.join(", ", detected));
			}
			line.append(" mods: ");
			List<String> names = new ArrayList<>();
			for (Mod m : mods) {
				if (interesting(m)) {
					names.add(m.id() + (m.version().isEmpty() ? "" : "@" + m.version()));
				}
			}
			line.append(names.isEmpty() ? "-" : String.join(", ", names)).append('\n');
			Files.writeString(dir.resolve(LocalDate.now() + ".log"), line.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
			// logging only
		}
	}

	/** Top mods among Velo players since the server started, for the panel. */
	public static JsonObject top(int n) {
		JsonObject o = new JsonObject();
		COUNTS.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(n).forEach(e -> o.addProperty(e.getKey(), e.getValue()));
		return o;
	}

	public static com.google.gson.JsonArray detections() {
		com.google.gson.JsonArray a = new com.google.gson.JsonArray();
		synchronized (DETECTIONS) {
			for (Map<String, Object> d : DETECTIONS) {
				JsonObject o = new JsonObject();
				o.addProperty("player", String.valueOf(d.get("player")));
				o.addProperty("mods", String.join(", ", (List<String>) d.get("mods")));
				o.addProperty("t", (Long) d.get("t"));
				a.add(o);
			}
		}
		return a;
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}

	private static String clip(String s, int max) {
		String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}§]", "");
		return t.length() > max ? t.substring(0, max) : t;
	}
}
