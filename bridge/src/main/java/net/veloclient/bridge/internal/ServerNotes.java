package net.veloclient.bridge.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-wide World Notes (opt-in, {@code notes.server-wide.enabled}): any Velo player may pin a note for everyone on
 * this server, inside the allowed areas. The notes live here on the Minecraft server ({@code server-notes.json}), so
 * the owner has them: remove them in the owner panel, with {@code /velobridge notes}, or staff with
 * {@code velobridge.notes.manage} right in Velo. No pictures (they'd go through the game connection); text, checklists
 * and small whiteboards are fine.
 */
public final class ServerNotes {

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Map<String, JsonObject> NOTES = new LinkedHashMap<>();
	private static final List<String> STYLES = List.of("paper", "sticky", "glass", "sign", "neon");
	private static Path file;
	private static boolean dirty;

	private ServerNotes() {
	}

	public static synchronized void load(Path dataDir) {
		file = dataDir.resolve("server-notes.json");
		NOTES.clear();
		try {
			if (Files.exists(file)) {
				JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				for (JsonElement e : o.getAsJsonArray("notes")) {
					JsonObject n = e.getAsJsonObject();
					NOTES.put(n.get("id").getAsString(), n);
				}
			}
		} catch (Exception e) {
			Bukkit.getLogger().warning("[VeloBridge] server-notes.json unreadable: " + e.getMessage());
		}
	}

	/** Writes the file if something changed (called every few seconds, async). */
	public static void flush() {
		String json;
		synchronized (ServerNotes.class) {
			if (!dirty || file == null) {
				return;
			}
			dirty = false;
			JsonObject o = new JsonObject();
			JsonArray a = new JsonArray();
			NOTES.values().forEach(a::add);
			o.add("notes", a);
			json = GSON.toJson(o);
		}
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("server-notes.json.tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception ignored) {
			synchronized (ServerNotes.class) {
				dirty = true;
			}
		}
	}

	private static ConfigurationSection cfg(ConfigurationSection root) {
		return root == null ? null : root.getConfigurationSection("notes.server-wide");
	}

	public static boolean enabled(ConfigurationSection root) {
		ConfigurationSection c = cfg(root);
		return c != null && c.getBoolean("enabled", false) && root.getBoolean("notes.enabled", true);
	}

	private static boolean mayPlace(Player p, ConfigurationSection root) {
		String perm = cfg(root).getString("permission", "");
		return perm.isBlank() || p.hasPermission(perm);
	}

	private static boolean manager(Player p) {
		return p.hasPermission("velobridge.notes.manage");
	}

	/** Allowed: inside one of the areas (none listed = everywhere) and outside notes.blocked-areas. */
	static String placeError(ConfigurationSection root, String world, double x, double z) {
		for (Map<?, ?> a : root.getMapList("notes.blocked-areas")) {
			if (in(a, world, x, z)) {
				return "Notes can't be placed in " + a.get("name") + ".";
			}
		}
		List<Map<?, ?>> areas = cfg(root).getMapList("areas");
		if (areas.isEmpty()) {
			return null;
		}
		for (Map<?, ?> a : areas) {
			if (in(a, world, x, z)) {
				return null;
			}
		}
		StringBuilder names = new StringBuilder();
		for (Map<?, ?> a : areas) {
			if (names.length() > 0) {
				names.append(", ");
			}
			names.append(a.get("name"));
		}
		return "Server notes only go in: " + names + ".";
	}

	private static boolean in(Map<?, ?> a, String world, double x, double z) {
		if (a.get("world") != null && !String.valueOf(a.get("world")).equals(world)) {
			return false;
		}
		double x1 = d(a.get("x1")), x2 = d(a.get("x2")), z1 = d(a.get("z1")), z2 = d(a.get("z2"));
		return x >= Math.min(x1, x2) && x <= Math.max(x1, x2) + 1 && z >= Math.min(z1, z2) && z <= Math.max(z1, z2) + 1;
	}

	private static double d(Object o) {
		return o instanceof Number n ? n.doubleValue() : 0;
	}

	// ---- to players

	private static JsonObject view(JsonObject n, Player p) {
		JsonObject v = n.deepCopy();
		boolean mine = p.getUniqueId().toString().equals(str(n, "owner"));
		v.addProperty("mine", mine);
		v.addProperty("canEdit", mine || manager(p));
		return v;
	}

	/** Everything for one player: the settings and the notes in their world. */
	public static void sendAll(Player p, ConfigurationSection root) {
		JsonObject m = Protocol.message("server_notes");
		boolean on = enabled(root);
		m.addProperty("enabled", on);
		JsonArray list = new JsonArray();
		if (on) {
			ConfigurationSection c = cfg(root);
			m.addProperty("canPlace", mayPlace(p, root));
			m.addProperty("max", c.getInt("max-per-player", 5));
			m.addProperty("manage", manager(p));
			m.addProperty("whiteboards", c.getBoolean("whiteboards", true) && root.getBoolean("notes.whiteboards", true));
			String world = p.getWorld().getName();
			synchronized (ServerNotes.class) {
				int bytes = 0;
				for (JsonObject n : NOTES.values()) {
					if (world.equals(str(n, "bw")) && list.size() < 400) {
						JsonObject v = view(n, p);
						int size = v.toString().length();
						if (bytes + size > 900_000) {
							break; // one message to the client is at most 1 MB
						}
						bytes += size;
						list.add(v);
					}
				}
			}
		}
		m.add("notes", list);
		Protocol.send(p, m);
	}

	private static void broadcast(JsonObject n, boolean removed) {
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (!Players.isVelo(p.getUniqueId()) || !p.getWorld().getName().equals(str(n, "bw"))) {
				continue;
			}
			if (removed) {
				JsonObject m = Protocol.message("server_note_remove");
				m.addProperty("id", str(n, "id"));
				Protocol.send(p, m);
			} else {
				JsonObject m = Protocol.message("server_note");
				m.add("note", view(n, p));
				Protocol.send(p, m);
			}
		}
	}

	private static void reply(Player p, String ref, String error, JsonObject note) {
		JsonObject m = Protocol.message("note_result");
		m.addProperty("ref", ref);
		m.addProperty("ok", error == null);
		if (error != null) {
			m.addProperty("error", error);
		}
		if (note != null) {
			m.add("note", view(note, p));
		}
		Protocol.send(p, m);
	}

	// ---- from players

	/** {@code note_put}: create or change a note. Runs on the player's thread (position check). */
	public static void put(Player p, JsonObject msg, ConfigurationSection root) {
		String ref = str(msg, "ref");
		if (!enabled(root)) {
			reply(p, ref, "Server notes are off here.", null);
			return;
		}
		if (!msg.has("note") || !msg.get("note").isJsonObject() || msg.get("note").toString().length() > 30_000) {
			reply(p, ref, "That note is too big.", null);
			return;
		}
		JsonObject in = msg.getAsJsonObject("note");
		String id = str(in, "id");
		JsonObject old;
		synchronized (ServerNotes.class) {
			old = id == null ? null : NOTES.get(id);
		}
		boolean mine = old != null && p.getUniqueId().toString().equals(str(old, "owner"));
		if (old != null && !mine && !manager(p)) {
			reply(p, ref, "Only " + str(old, "ownerName") + " can change this note.", null);
			return;
		}
		if (old == null && !mayPlace(p, root)) {
			reply(p, ref, "You can't place server notes here.", null);
			return;
		}
		double x = num(in, "x"), y = num(in, "y"), z = num(in, "z");
		Location at = p.getLocation();
		boolean moved = old == null || num(old, "x") != x || num(old, "y") != y || num(old, "z") != z;
		if (moved) {
			if (Math.abs(at.getX() - x) > 16 || Math.abs(at.getY() - y) > 16 || Math.abs(at.getZ() - z) > 16) {
				reply(p, ref, "Stand next to the spot to place a server note there.", null);
				return;
			}
			String err = placeError(root, at.getWorld().getName(), x, z);
			if (err != null && !manager(p)) {
				reply(p, ref, err, null);
				return;
			}
		}
		if (old == null && !manager(p)) {
			int max = cfg(root).getInt("max-per-player", 5);
			long have;
			synchronized (ServerNotes.class) {
				have = NOTES.values().stream().filter(n -> p.getUniqueId().toString().equals(str(n, "owner"))).count();
			}
			if (max > 0 && have >= max) {
				reply(p, ref, "You already have " + have + " server notes (most: " + max + "). Remove one first.", null);
				return;
			}
		}
		JsonObject n = old == null ? new JsonObject() : old.deepCopy();
		long now = System.currentTimeMillis();
		if (old == null) {
			n.addProperty("id", "srv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
			n.addProperty("owner", p.getUniqueId().toString());
			n.addProperty("ownerName", p.getName());
			n.addProperty("created", now);
			n.addProperty("bw", at.getWorld().getName());
		}
		n.addProperty("updated", now);
		n.addProperty("editorName", p.getName());
		n.addProperty("version", (n.has("version") ? n.get("version").getAsInt() : 0) + 1);
		n.addProperty("x", x);
		n.addProperty("y", y);
		n.addProperty("z", z);
		if (moved) {
			n.addProperty("bw", at.getWorld().getName());
		}
		n.addProperty("title", clip(str(in, "title"), 80));
		n.addProperty("text", clip(str(in, "text"), 2000));
		n.addProperty("color", in.has("color") ? in.get("color").getAsInt() & 0xFFFFFF : 0xF5D76E);
		n.addProperty("icon", clip(str(in, "icon"), 40));
		String style = str(in, "style");
		n.addProperty("style", style != null && STYLES.contains(style) ? style : "paper");
		n.addProperty("size", Math.max(0.3, Math.min(4, in.has("size") ? num(in, "size") : 1)));
		n.addProperty("face", !in.has("face") || in.get("face").getAsBoolean());
		n.addProperty("yaw", num(in, "yaw"));
		n.addProperty("kind", "note");
		n.addProperty("share", "server");
		JsonArray checks = new JsonArray();
		if (in.has("checks") && in.get("checks").isJsonArray()) {
			for (JsonElement e : in.getAsJsonArray("checks")) {
				if (checks.size() >= 30 || !e.isJsonObject()) {
					continue;
				}
				JsonObject c = e.getAsJsonObject(), out = new JsonObject();
				out.addProperty("id", clip(str(c, "id") == null ? UUID.randomUUID().toString().substring(0, 8) : str(c, "id"), 16));
				out.addProperty("text", clip(str(c, "text"), 120));
				out.addProperty("done", c.has("done") && c.get("done").getAsBoolean());
				if (str(c, "doneBy") != null) {
					out.addProperty("doneBy", clip(str(c, "doneBy"), 16));
				}
				checks.add(out);
			}
		}
		n.add("checks", checks);
		boolean boards = cfg(root).getBoolean("whiteboards", true) && root.getBoolean("notes.whiteboards", true);
		if (boards && in.has("board") && in.get("board").isJsonArray()) {
			JsonArray board = in.getAsJsonArray("board");
			n.add("board", board.size() > 300 ? new JsonArray() : board);
		} else if (!boards) {
			n.add("board", new JsonArray());
		}
		synchronized (ServerNotes.class) {
			NOTES.put(n.get("id").getAsString(), n);
			dirty = true;
		}
		reply(p, ref, null, n);
		broadcast(n, false);
	}

	public static void check(Player p, JsonObject msg, ConfigurationSection root) {
		if (!enabled(root)) {
			return;
		}
		JsonObject n;
		synchronized (ServerNotes.class) {
			n = NOTES.get(str(msg, "id"));
			if (n == null || !n.has("checks")) {
				return;
			}
			for (JsonElement e : n.getAsJsonArray("checks")) {
				JsonObject c = e.getAsJsonObject();
				if (c.get("id").getAsString().equals(str(msg, "check"))) {
					boolean done = msg.has("done") && msg.get("done").getAsBoolean();
					c.addProperty("done", done);
					if (done) {
						c.addProperty("doneBy", p.getName());
						c.addProperty("doneAt", System.currentTimeMillis());
					} else {
						c.remove("doneBy");
						c.remove("doneAt");
					}
				}
			}
			dirty = true;
		}
		broadcast(n, false);
	}

	public static void delete(Player p, JsonObject msg) {
		JsonObject n;
		synchronized (ServerNotes.class) {
			n = NOTES.get(str(msg, "id"));
			if (n == null || !(p.getUniqueId().toString().equals(str(n, "owner")) || manager(p))) {
				return;
			}
			NOTES.remove(str(n, "id"));
			dirty = true;
		}
		broadcast(n, true);
	}

	/** Owner panel / command: remove these notes. Returns how many went. */
	public static int remove(Collection<String> ids) {
		List<JsonObject> gone = new ArrayList<>();
		synchronized (ServerNotes.class) {
			for (String id : ids) {
				JsonObject n = NOTES.remove(id);
				if (n != null) {
					gone.add(n);
				}
			}
			dirty |= !gone.isEmpty();
		}
		Tasks.global(() -> gone.forEach(n -> broadcast(n, true)));
		return gone.size();
	}

	/** All notes of one player (by name, any case). */
	public static List<String> idsOf(String playerName) {
		List<String> out = new ArrayList<>();
		synchronized (ServerNotes.class) {
			for (JsonObject n : NOTES.values()) {
				if (playerName.equalsIgnoreCase(str(n, "ownerName"))) {
					out.add(str(n, "id"));
				}
			}
		}
		return out;
	}

	public static synchronized int count() {
		return NOTES.size();
	}

	/** Short list for the owner panel (no whiteboards). */
	public static synchronized JsonArray summary() {
		JsonArray a = new JsonArray();
		List<JsonObject> all = new ArrayList<>(NOTES.values());
		all.sort((x, y) -> Long.compare(y.has("updated") ? y.get("updated").getAsLong() : 0, x.has("updated") ? x.get("updated").getAsLong() : 0));
		for (JsonObject n : all) {
			if (a.size() >= 300) {
				break;
			}
			JsonObject o = new JsonObject();
			for (String k : new String[] {"id", "ownerName", "title", "bw", "style"}) {
				if (n.has(k)) {
					o.add(k, n.get(k));
				}
			}
			String text = str(n, "text");
			o.addProperty("text", text == null ? "" : clip(text, 160));
			o.addProperty("x", Math.round(num(n, "x")));
			o.addProperty("y", Math.round(num(n, "y")));
			o.addProperty("z", Math.round(num(n, "z")));
			o.addProperty("checks", n.has("checks") ? n.getAsJsonArray("checks").size() : 0);
			o.addProperty("board", n.has("board") && n.getAsJsonArray("board").size() > 0);
			o.addProperty("color", n.has("color") ? n.get("color").getAsInt() : 0xF5D76E);
			o.addProperty("updated", n.has("updated") ? n.get("updated").getAsLong() : 0);
			a.add(o);
		}
		return a;
	}

	private static String str(JsonObject o, String k) {
		return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}

	private static double num(JsonObject o, String k) {
		try {
			return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String clip(String s, int max) {
		if (s == null) {
			return "";
		}
		String t = s.replace("§", "");
		return t.length() > max ? t.substring(0, max) : t;
	}
}
