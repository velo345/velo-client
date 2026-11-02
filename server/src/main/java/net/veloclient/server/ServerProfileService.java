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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mod rules for known servers, set by the owner in the owner panel ({@code data/server-profiles.json}): which mods a
 * server recommends and which it doesn't allow, matched by address. Launchers download them and only WARN - players
 * can always start anyway and hide the warning per server (false positives happen). Servers running the Velo plugin
 * send their own rules instead; these are for the ones that don't.
 */
final class ServerProfileService {

	/** One mod rule: {@code match} "id" (fabric.mod.json id), "modrinth" (project id or slug) or "name" (part of the name / file). */
	static final class Rule {
		String match;
		String value;
		String label;
	}

	static final class Profile {
		String id;
		String name;
		/** "hypixel.net", "*.hypixel.net", "mc.example.com:25566" */
		List<String> addresses = new ArrayList<>();
		List<Rule> recommend = new ArrayList<>();
		List<Rule> forbid = new ArrayList<>();
		/** Shown with the warning, e.g. "Hypixel bans X-Ray and Freecam - see hypixel.net/allowed-mods". */
		String note;
		long updatedAt;
	}

	private static final class Data {
		List<Profile> profiles = new ArrayList<>();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path file;
	private Data data;

	ServerProfileService(Path dataDir) {
		this.file = dataDir.resolve("server-profiles.json");
		Data loaded = null;
		try {
			if (Files.exists(file)) {
				loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
			}
		} catch (Exception e) {
			System.out.println("[server-profiles] couldn't read: " + e.getMessage());
		}
		data = loaded == null ? new Data() : loaded;
	}

	private void save() {
		try {
			Path tmp = file.resolveSibling("server-profiles.json.tmp");
			Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[server-profiles] save failed: " + e.getMessage());
		}
	}

	synchronized List<Profile> profiles() {
		return List.copyOf(data.profiles);
	}

	synchronized Profile upsert(JsonObject in) throws ApiError {
		Profile p = new Profile();
		String id = str(in, "id");
		p.id = id == null || id.isBlank() ? UUID.randomUUID().toString().replace("-", "").substring(0, 10) : id;
		p.name = clip(str(in, "name"), 60);
		if (p.name == null || p.name.isBlank()) {
			throw new ApiError(400, "Give the server a name");
		}
		if (in.has("addresses") && in.get("addresses").isJsonArray()) {
			for (JsonElement e : in.getAsJsonArray("addresses")) {
				String a = e.getAsString().strip().toLowerCase(java.util.Locale.ROOT);
				if (a.matches("[*a-z0-9.\\-]+(:\\d{1,5})?") && p.addresses.size() < 20) {
					p.addresses.add(a);
				}
			}
		}
		if (p.addresses.isEmpty()) {
			throw new ApiError(400, "Add at least one address, e.g. hypixel.net or *.hypixel.net");
		}
		p.recommend = rules(in, "recommend");
		p.forbid = rules(in, "forbid");
		p.note = clip(str(in, "note"), 300);
		p.updatedAt = System.currentTimeMillis();
		data.profiles.removeIf(x -> x.id.equals(p.id));
		data.profiles.add(p);
		save();
		return p;
	}

	private static List<Rule> rules(JsonObject in, String key) {
		List<Rule> out = new ArrayList<>();
		if (!in.has(key) || !in.get(key).isJsonArray()) {
			return out;
		}
		for (JsonElement e : in.getAsJsonArray(key)) {
			if (!e.isJsonObject() || out.size() >= 200) {
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			Rule r = new Rule();
			r.match = str(o, "match");
			r.value = clip(str(o, "value"), 80);
			r.label = clip(str(o, "label"), 80);
			if (r.value == null || r.value.isBlank() || !List.of("id", "modrinth", "name").contains(r.match)) {
				continue;
			}
			out.add(r);
		}
		return out;
	}

	synchronized void delete(String id) {
		data.profiles.removeIf(p -> p.id.equals(id));
		save();
	}

	void register(HttpServer server, String adminToken) {
		server.createContext("/v1/servers/profiles", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			ex.getResponseHeaders().set("Cache-Control", "public, max-age=300");
			JsonHttp.writeJson(ex, 200, Map.of("profiles", profiles(), "presets", ModPresets.ALL));
		}));
		admin(server, adminToken, "/v1/admin/servers/profiles", "GET", ex -> JsonHttp.writeJson(ex, 200, Map.of("profiles", profiles(), "presets", ModPresets.ALL)));
		admin(server, adminToken, "/v1/admin/servers/profiles/save", "POST", ex -> JsonHttp.writeJson(ex, 200, upsert(body(ex))));
		admin(server, adminToken, "/v1/admin/servers/profiles/delete", "POST", ex -> {
			delete(str(body(ex), "id"));
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
			handler.handle(ex);
		}));
	}

	private static JsonObject body(com.sun.net.httpserver.HttpExchange ex) throws IOException {
		return JsonParser.parseString(new String(JsonHttp.readRawBody(ex, 128 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static String str(JsonObject json, String key) {
		return json != null && json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
	}

	private static String clip(String s, int max) {
		return s == null ? null : s.length() > max ? s.substring(0, max) : s;
	}

	/** Ready-made lists the owner (and later server plugins) add with one click. */
	static final class ModPresets {
		record Entry(String match, String value, String label) {
		}

		record Preset(String id, String name, String description, List<Entry> entries) {
		}

		static final List<Preset> ALL = List.of(
				new Preset("cheat-clients", "Cheat clients", "Hacked clients that come as Fabric mods", List.of(
						new Entry("id", "wurst", "Wurst"), new Entry("id", "meteor-client", "Meteor Client"), new Entry("id", "aristois", "Aristois"),
						new Entry("id", "liquidbounce", "LiquidBounce"), new Entry("name", "glazed", "Glazed"), new Entry("id", "thunderhack", "ThunderHack"),
						new Entry("name", "rusherhack", "RusherHack"), new Entry("name", "future client", "Future"), new Entry("name", "impact", "Impact"),
						new Entry("id", "boze", "Boze"), new Entry("name", "inertia", "Inertia"), new Entry("name", "bleachhack", "BleachHack"),
						new Entry("name", "coffee client", "Coffee Client"), new Entry("id", "kami", "KAMI"), new Entry("name", "lambda", "Lambda"),
						new Entry("name", "argon", "Argon"), new Entry("name", "prestige", "Prestige"))),
				new Preset("often-banned", "Often banned", "Allowed in singleplayer, against the rules on most PvP / survival servers", List.of(
						new Entry("id", "baritone", "Baritone"), new Entry("modrinth", "xray-ultimate", "X-Ray"), new Entry("name", "xray", "X-Ray"),
						new Entry("id", "freecam", "Freecam"), new Entry("modrinth", "freecam", "Freecam"), new Entry("name", "auto clicker", "Auto clicker"),
						new Entry("name", "autoclicker", "Auto clicker"), new Entry("name", "killaura", "Kill aura"), new Entry("name", "printer", "Litematica printer"),
						new Entry("name", "seedcracker", "SeedCrackerX"), new Entry("name", "trouser", "Trouser Streak (bot)"))),
				new Preset("radar", "Minimap entity radar", "Minimaps that show players / mobs - banned on many PvP servers", List.of(
						new Entry("id", "xaerominimap", "Xaero's Minimap (radar on)"), new Entry("id", "journeymap", "JourneyMap"),
						new Entry("id", "voxelmap", "VoxelMap"))));
	}
}
