package net.veloclient.bridge.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The owner panel link: syncs live numbers and the config to the Velo server (dev.velo-client.com/panel shows them)
 * and applies config changes made there. Full sync every minute; while someone has the panel open, a quick check
 * every 5 seconds so a saved change lands almost at once.
 */
public final class Panel {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static final String API = "https://api.velo-client.com";
	/** Settings the panel may never change (only the file on the server can). */
	private static final Set<String> LOCAL_ONLY = Set.of("menus.allow-console-actions");

	public record Join(String name, String version, String mc, long t, boolean first) {
	}

	private final JavaPlugin plugin;
	private final String serverId;
	private final String secret;
	private final Runnable onApplied;
	private final java.util.function.Supplier<JsonObject> live;
	private final ConcurrentLinkedQueue<Join> joins = new ConcurrentLinkedQueue<>();
	private volatile boolean panelOpen;
	private volatile long applied;
	private volatile String applyError;
	private long lastFull;

	public Panel(JavaPlugin plugin, String serverId, java.util.function.Supplier<JsonObject> live, Runnable onApplied) {
		this.plugin = plugin;
		this.serverId = serverId;
		this.live = live;
		this.onApplied = onApplied;
		this.secret = loadSecret(plugin.getDataFolder().toPath().resolve("panel-secret.txt"));
	}

	private static String loadSecret(Path file) {
		try {
			if (Files.exists(file)) {
				return Files.readString(file, StandardCharsets.UTF_8).strip();
			}
			byte[] b = new byte[32];
			new SecureRandom().nextBytes(b);
			String s = HexFormat.of().formatHex(b);
			Files.createDirectories(file.getParent());
			Files.writeString(file, s, StandardCharsets.UTF_8);
			return s;
		} catch (Exception e) {
			return "0".repeat(64);
		}
	}

	public void joined(Join j) {
		joins.add(j);
		while (joins.size() > 200) {
			joins.poll();
		}
	}

	/** Called every 5 s (async): a full sync once a minute, a quick check in between while the panel is open. */
	public void tick() {
		long now = System.currentTimeMillis();
		boolean full = now - lastFull >= 60_000;
		if (!full && !panelOpen) {
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("serverId", serverId);
		body.addProperty("secret", secret);
		body.addProperty("applied", applied);
		if (applyError != null) {
			body.addProperty("applyError", applyError);
		}
		if (full) {
			lastFull = now;
			body.addProperty("name", plugin.getConfig().getString("server.name", "Server"));
			JsonObject l = live.get();
			JsonArray js = new JsonArray();
			Join j;
			while ((j = joins.poll()) != null) {
				JsonObject o = new JsonObject();
				o.addProperty("name", j.name());
				o.addProperty("v", j.version());
				o.addProperty("mc", j.mc());
				o.addProperty("t", j.t());
				o.addProperty("first", j.first());
				js.add(o);
			}
			l.add("joins", js);
			body.add("live", l);
			body.add("config", toJson(plugin.getConfig()));
			body.add("notes", ServerNotes.summary());
			body.add("npcs", NpcCosmetics.names());
		}
		try {
			HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(API + "/v1/bridge/sync")).timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
					HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() != 200) {
				if (r.statusCode() == 403) {
					plugin.getLogger().warning("Velo panel: " + r.body());
				}
				return;
			}
			JsonObject res = JsonParser.parseString(r.body()).getAsJsonObject();
			panelOpen = res.has("panel") && res.get("panel").getAsBoolean();
			if (res.has("removeNotes") && res.get("removeNotes").isJsonArray()) {
				List<String> ids = new ArrayList<>();
				res.getAsJsonArray("removeNotes").forEach(e -> ids.add(e.getAsString()));
				int n = ServerNotes.remove(ids);
				if (n > 0) {
					plugin.getLogger().info("Velo panel: removed " + n + " server note" + (n == 1 ? "" : "s"));
					lastFull = 0;
				}
			}
			if (res.has("config") && res.get("config").isJsonObject()) {
				long version = res.get("version").getAsLong();
				if (version > applied) {
					JsonObject cfg = res.getAsJsonObject("config");
					Tasks.global(() -> apply(cfg, version));
				}
			}
		} catch (Exception ignored) {
			// offline: next round
		}
	}

	/** Writes the panel's config into config.yml (comments of existing keys stay), reloads, re-sends to players. */
	private void apply(JsonObject cfg, long version) {
		try {
			var config = plugin.getConfig();
			Map<String, Boolean> keepLocal = new LinkedHashMap<>();
			LOCAL_ONLY.forEach(k -> keepLocal.put(k, config.getBoolean(k, false)));
			for (String key : new ArrayList<>(config.getKeys(false))) {
				if (!cfg.has(key)) {
					config.set(key, null);
				}
			}
			for (var e : cfg.entrySet()) {
				config.set(e.getKey(), fromJson(e.getValue()));
			}
			keepLocal.forEach(config::set);
			plugin.saveConfig();
			plugin.reloadConfig();
			applied = version;
			applyError = null;
			onApplied.run();
			plugin.getLogger().info("Velo panel: settings changed (version " + version + ")");
		} catch (Exception e) {
			applied = version;
			applyError = e.getMessage();
			plugin.getLogger().warning("Velo panel: couldn't apply settings - " + e.getMessage());
		}
	}

	public CompletableLink link() {
		JsonObject body = new JsonObject();
		body.addProperty("serverId", serverId);
		body.addProperty("secret", secret);
		try {
			HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(API + "/v1/bridge/panel-code")).timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
					HttpResponse.BodyHandlers.ofString());
			JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
			if (r.statusCode() != 200) {
				return new CompletableLink(null, o.has("error") ? o.get("error").getAsString() : "HTTP " + r.statusCode());
			}
			lastFull = 0; // make sure the panel has fresh numbers
			return new CompletableLink(o.get("url").getAsString(), null);
		} catch (Exception e) {
			return new CompletableLink(null, "Can't reach api.velo-client.com (" + e.getMessage() + ")");
		}
	}

	public record CompletableLink(String url, String error) {
	}

	// ---- YAML <-> JSON

	static JsonElement toJson(Object v) {
		if (v instanceof ConfigurationSection s) {
			JsonObject o = new JsonObject();
			for (String k : s.getKeys(false)) {
				o.add(k, toJson(s.get(k)));
			}
			return o;
		}
		if (v instanceof Map<?, ?> m) {
			JsonObject o = new JsonObject();
			m.forEach((k, x) -> o.add(String.valueOf(k), toJson(x)));
			return o;
		}
		if (v instanceof List<?> l) {
			JsonArray a = new JsonArray();
			l.forEach(x -> a.add(toJson(x)));
			return a;
		}
		if (v instanceof Number n) {
			return new JsonPrimitive(n);
		}
		if (v instanceof Boolean b) {
			return new JsonPrimitive(b);
		}
		return v == null ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(String.valueOf(v));
	}

	static Object fromJson(JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (e.isJsonObject()) {
			Map<String, Object> m = new LinkedHashMap<>();
			for (var x : e.getAsJsonObject().entrySet()) {
				m.put(x.getKey(), fromJson(x.getValue()));
			}
			return m;
		}
		if (e.isJsonArray()) {
			List<Object> l = new ArrayList<>();
			e.getAsJsonArray().forEach(x -> l.add(fromJson(x)));
			return l;
		}
		JsonPrimitive p = e.getAsJsonPrimitive();
		if (p.isBoolean()) {
			return p.getAsBoolean();
		}
		if (p.isNumber()) {
			double d = p.getAsDouble();
			return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
		}
		return p.getAsString();
	}
}
