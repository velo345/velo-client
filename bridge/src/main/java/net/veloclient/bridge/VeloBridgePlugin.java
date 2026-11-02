package net.veloclient.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.veloclient.bridge.api.VeloBridge;
import net.veloclient.bridge.api.VeloModulesChangeEvent;
import net.veloclient.bridge.api.VeloPlayer;
import net.veloclient.bridge.api.VeloPlayerJoinEvent;
import net.veloclient.bridge.internal.Players;
import net.veloclient.bridge.internal.Presets;
import net.veloclient.bridge.internal.Protocol;
import net.veloclient.bridge.internal.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Velo Bridge: the server side of Velo Client. Players using Velo say hello on the {@code velo:bridge} channel when
 * they join; from then on the server can send them its mod hints, disabled modules, presence, teammates, waypoints,
 * native UI and effects (see {@link VeloBridge}). Everyone else never notices the plugin is there.
 */
public final class VeloBridgePlugin extends JavaPlugin implements Listener, PluginMessageListener {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private final Set<UUID> welcomed = new HashSet<>();
	private String serverId;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		Tasks.init(this);
		Protocol.init(this);
		getServer().getMessenger().registerOutgoingPluginChannel(this, Protocol.CHANNEL);
		getServer().getMessenger().registerIncomingPluginChannel(this, Protocol.CHANNEL, this);
		getServer().getPluginManager().registerEvents(this, this);
		serverId = loadServerId();
		loadWelcomed();
		// Presence placeholders (online count...) change: refresh every minute.
		Tasks.globalRepeating(this::refreshPresence, 20 * 60, 20 * 60);
		Tasks.globalRepeating(() -> Tasks.async(this::heartbeat), 20 * 30, 20 * 60 * 5);
		getLogger().info("Velo Bridge " + getDescription().getVersion() + " ready" + (Tasks.folia() ? " (Folia)" : "")
				+ " - docs: https://dev.velo-client.com");
	}

	@Override
	public void onDisable() {
		saveWelcomed();
	}

	// ---- Channel ----

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (!Protocol.CHANNEL.equals(channel)) {
			return;
		}
		JsonObject m = Protocol.parse(message);
		if (m == null || !m.has("t")) {
			return;
		}
		switch (m.get("t").getAsString()) {
			case "hello" -> onHello(player, m);
			case "modules" -> onModules(player, m);
			case "ping_result" -> {
				Map<String, Integer> results = new HashMap<>();
				if (m.has("results") && m.get("results").isJsonObject()) {
					m.getAsJsonObject("results").entrySet().forEach(e -> results.put(e.getKey(), e.getValue().getAsInt()));
				}
				VeloBridge.completePings(str(m, "nonce", ""), results);
			}
			default -> {
				// Newer client: ignore what we don't know.
			}
		}
	}

	private void onHello(Player player, JsonObject m) {
		VeloPlayer velo = new VeloPlayer(player.getUniqueId(), player.getName(), str(m, "version", "?"), str(m, "mc", "?"), strings(m, "modules"),
				System.currentTimeMillis());
		Players.put(velo);
		sendSetup(player);
		Bukkit.getPluginManager().callEvent(new VeloPlayerJoinEvent(player, velo));
		List<String> disabled = getConfig().getStringList("disabled-modules");
		Set<String> clashing = new LinkedHashSet<>(velo.activeModules());
		clashing.retainAll(disabled);
		notifyStaff(ChatColor.AQUA + "[Velo] " + ChatColor.WHITE + player.getName() + ChatColor.GRAY + " joined with Velo " + velo.veloVersion()
				+ (clashing.isEmpty() ? "" : " - switched off here: " + String.join(", ", clashing)));
	}

	private void onModules(Player player, JsonObject m) {
		VeloPlayer before = Players.get(player.getUniqueId());
		if (before == null) {
			return;
		}
		Set<String> now = strings(m, "modules");
		Players.put(new VeloPlayer(before.uuid(), before.name(), before.veloVersion(), before.minecraftVersion(), now, before.joinedAt()));
		Bukkit.getPluginManager().callEvent(new VeloModulesChangeEvent(player, before.activeModules(), now));
	}

	/** Everything a Velo player needs from the config, right after hello. */
	private void sendSetup(Player player) {
		JsonObject welcome = Protocol.message("welcome");
		welcome.addProperty("protocol", Protocol.VERSION);
		welcome.addProperty("plugin", getDescription().getVersion());
		welcome.addProperty("name", getConfig().getString("server.name", "Server"));
		welcome.addProperty("color", getConfig().getString("server.color", "#5aa9ff"));
		Protocol.send(player, welcome);

		JsonObject rules = Protocol.message("mod_rules");
		JsonArray forbid = new JsonArray();
		for (String preset : getConfig().getStringList("mod-rules.forbid-presets")) {
			for (String[] e : Presets.ALL.getOrDefault(preset, List.of())) {
				forbid.add(rule(e[0], e[1], e[2]));
			}
		}
		addRules(forbid, getConfig().getMapList("mod-rules.forbid"));
		JsonArray recommend = new JsonArray();
		addRules(recommend, getConfig().getMapList("mod-rules.recommend"));
		rules.add("forbid", forbid);
		rules.add("recommend", recommend);
		rules.addProperty("message", getConfig().getString("mod-rules.message", ""));
		if (!forbid.isEmpty() || !recommend.isEmpty()) {
			Protocol.send(player, rules);
		}

		List<String> disabled = getConfig().getStringList("disabled-modules");
		if (!disabled.isEmpty()) {
			VeloBridge.disableModules(player, disabled, getConfig().getString("server.name", "This server") + " turned these off");
		}

		sendPresence(player);

		JsonArray packs = new JsonArray();
		for (Map<?, ?> pack : getConfig().getMapList("precache-packs")) {
			JsonObject o = new JsonObject();
			o.addProperty("id", String.valueOf(pack.get("id")));
			o.addProperty("url", String.valueOf(pack.get("url")));
			o.addProperty("sha1", String.valueOf(pack.get("sha1")));
			packs.add(o);
		}
		if (!packs.isEmpty()) {
			JsonObject precache = Protocol.message("precache");
			precache.add("packs", packs);
			Protocol.send(player, precache);
		}

		ConfigurationSection targets = getConfig().getConfigurationSection("transfer-targets");
		if (targets != null && !targets.getKeys(false).isEmpty()) {
			JsonObject preconnect = Protocol.message("preconnect");
			JsonArray hosts = new JsonArray();
			targets.getKeys(false).forEach(k -> hosts.add(targets.getString(k)));
			preconnect.add("hosts", hosts);
			Protocol.send(player, preconnect);
		}

		JsonObject notes = Protocol.message("notes");
		notes.addProperty("enabled", getConfig().getBoolean("notes.enabled", true));
		notes.addProperty("maxPerPlayer", getConfig().getInt("notes.max-per-player", 20));
		notes.addProperty("images", getConfig().getBoolean("notes.images", true));
		notes.addProperty("whiteboards", getConfig().getBoolean("notes.whiteboards", true));
		JsonArray blocked = new JsonArray();
		for (Map<?, ?> area : getConfig().getMapList("notes.blocked-areas")) {
			JsonObject o = new JsonObject();
			o.addProperty("name", String.valueOf(area.get("name")));
			o.addProperty("world", String.valueOf(area.get("world")));
			for (String k : new String[] {"x1", "z1", "x2", "z2"}) {
				Object v = area.get(k);
				o.addProperty(k, v instanceof Number n ? n.doubleValue() : 0);
			}
			blocked.add(o);
		}
		notes.add("blocked", blocked);
		Protocol.send(player, notes);

		if (getConfig().getBoolean("welcome-toast.enabled") && welcomed.add(player.getUniqueId())) {
			VeloBridge.toast(player, getConfig().getString("welcome-toast.title", "Welcome!"), getConfig().getString("welcome-toast.text", ""), 8);
		}
	}

	private static JsonObject rule(String match, String value, String label) {
		JsonObject o = new JsonObject();
		o.addProperty("match", match);
		o.addProperty("value", value);
		o.addProperty("label", label);
		return o;
	}

	private static void addRules(JsonArray into, List<Map<?, ?>> list) {
		for (Map<?, ?> r : list) {
			Object value = r.get("value");
			if (value != null) {
				Object match = r.get("match");
				Object label = r.get("label");
				into.add(rule(match == null ? "name" : String.valueOf(match), String.valueOf(value), label == null ? String.valueOf(value) : String.valueOf(label)));
			}
		}
	}

	// ---- Presence ----

	private void refreshPresence() {
		for (VeloPlayer velo : Players.all()) {
			Player player = Bukkit.getPlayer(velo.uuid());
			if (player != null) {
				sendPresence(player);
			}
		}
	}

	private void sendPresence(Player player) {
		if (!getConfig().getBoolean("presence.enabled", true)) {
			return;
		}
		VeloBridge.presence(player, placeholders(getConfig().getString("presence.details", ""), player),
				placeholders(getConfig().getString("presence.state", ""), player));
	}

	private String placeholders(String text, Player player) {
		return text.replace("%server%", getConfig().getString("server.name", "Server"))
				.replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
				.replace("%max%", String.valueOf(Bukkit.getMaxPlayers()))
				.replace("%world%", player.getWorld().getName());
	}

	// ---- Stats for the Velo partner list ----

	private void heartbeat() {
		if (!getConfig().getBoolean("stats", true)) {
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("serverId", serverId);
		body.addProperty("name", getConfig().getString("server.name", "Server"));
		body.addProperty("plugin", getDescription().getVersion());
		body.addProperty("software", Bukkit.getName() + (Tasks.folia() ? " (Folia)" : ""));
		body.addProperty("minecraft", Bukkit.getMinecraftVersion());
		body.addProperty("online", Bukkit.getOnlinePlayers().size());
		body.addProperty("velo", Players.all().size());
		JsonObject versions = new JsonObject();
		Map<String, Integer> counts = new HashMap<>();
		Players.all().forEach(v -> counts.merge(v.veloVersion(), 1, Integer::sum));
		counts.forEach(versions::addProperty);
		body.add("versions", versions);
		try {
			HTTP.send(HttpRequest.newBuilder(URI.create("https://api.velo-client.com/v1/bridge/heartbeat")).timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
					HttpResponse.BodyHandlers.discarding());
		} catch (Exception ignored) {
			// Only statistics.
		}
	}

	// ---- Events & commands ----

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		Players.remove(event.getPlayer().getUniqueId());
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
			sender.sendMessage(ChatColor.AQUA + "Velo Bridge " + getDescription().getVersion() + ChatColor.GRAY + " - dev.velo-client.com");
			for (String line : new String[] {"reload", "players", "toast <player|*> <title> | <text>", "countdown <player|*> <seconds> <label>",
					"shake <player|*> [intensity 0-1]", "tint <player|*> <#hex> <seconds>", "vignette <player|*> <#hex> <seconds>",
					"progress <player|*> <0-1> <label>", "waypointat <player|*> <x> <y> <z> <label>", "team <#hex> <seconds> <player> <player>...",
					"waypoint <player|*> <label>  (at your position)", "clear <player|*>", "transfer <player> <target|host[:port]>", "ping <player>"}) {
				sender.sendMessage(ChatColor.GRAY + "/velobridge " + line);
			}
			return true;
		}
		switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
			case "reload" -> {
				reloadConfig();
				for (VeloPlayer v : Players.all()) {
					Player p = Bukkit.getPlayer(v.uuid());
					if (p != null) {
						sendSetup(p);
					}
				}
				sender.sendMessage(ChatColor.GREEN + "Velo Bridge reloaded and re-sent to " + Players.all().size() + " Velo players.");
			}
			case "players" -> {
				sender.sendMessage(ChatColor.AQUA + "" + Players.all().size() + " of " + Bukkit.getOnlinePlayers().size() + " players use Velo:");
				for (VeloPlayer v : Players.all()) {
					sender.sendMessage(ChatColor.GRAY + " " + v.name() + " - Velo " + v.veloVersion() + " (" + v.minecraftVersion() + "), modules: "
							+ (v.activeModules().isEmpty() ? "-" : String.join(", ", v.activeModules())));
				}
			}
			case "toast" -> {
				String rest = join(args, 2);
				String[] parts = rest.split("\\|", 2);
				targets(sender, args, 1).forEach(p -> VeloBridge.toast(p, parts[0].strip(), parts.length > 1 ? parts[1].strip() : "", 8));
			}
			case "countdown" -> {
				int seconds = args.length > 2 ? parseInt(args[2], 10) : 10;
				targets(sender, args, 1).forEach(p -> VeloBridge.countdown(p, join(args, 3), seconds));
			}
			case "shake" -> {
				double intensity = args.length > 2 ? Double.parseDouble(args[2]) : 0.5;
				targets(sender, args, 1).forEach(p -> VeloBridge.shake(p, intensity, 800));
			}
			case "tint" -> {
				String color = args.length > 2 ? args[2] : "#ff3b3b";
				int seconds = args.length > 3 ? parseInt(args[3], 3) : 3;
				targets(sender, args, 1).forEach(p -> VeloBridge.tint(p, color, 0.35, seconds * 1000));
			}
			case "team" -> {
				if (args.length < 4) {
					sender.sendMessage(ChatColor.RED + "/velobridge team <#hex> <seconds> <player> <player>...");
					return true;
				}
				List<Player> team = new ArrayList<>();
				for (String n : Arrays.copyOfRange(args, 3, args.length)) {
					Player p = Bukkit.getPlayerExact(n);
					if (p != null) {
						team.add(p);
					}
				}
				int seconds = parseInt(args[2], 0);
				team.forEach(p -> VeloBridge.teammates(p, team, args[1], seconds));
				sender.sendMessage(ChatColor.GREEN + "Team of " + team.size() + " set.");
			}
			case "waypoint" -> {
				if (!(sender instanceof Player self)) {
					sender.sendMessage(ChatColor.RED + "Run this in-game - the waypoint goes where you stand.");
					return true;
				}
				String name = args.length > 2 ? join(args, 2) : "Here";
				targets(sender, args, 1).forEach(p -> VeloBridge.waypoint(p, "cmd-" + name.toLowerCase(java.util.Locale.ROOT), self.getLocation(), name,
						getConfig().getString("server.color", "#5aa9ff"), 0));
			}
			case "progress" -> {
				double value = args.length > 2 ? Double.parseDouble(args[2]) : 0.5;
				String text = args.length > 3 ? join(args, 3) : "Progress";
				targets(sender, args, 1).forEach(p -> VeloBridge.progress(p, "cmd", text, value));
			}
			case "vignette" -> {
				String color = args.length > 2 ? args[2] : "#ff3b3b";
				int seconds = args.length > 3 ? parseInt(args[3], 3) : 3;
				targets(sender, args, 1).forEach(p -> VeloBridge.vignette(p, color, 0.7, seconds * 1000));
			}
			case "waypointat" -> {
				if (args.length < 6) {
					sender.sendMessage(ChatColor.RED + "/velobridge waypointat <player|*> <x> <y> <z> <label>");
					return true;
				}
				String name = args.length > 5 ? join(args, 5) : "Here";
				targets(sender, args, 1).forEach(p -> VeloBridge.waypoint(p, "cmd-" + name.toLowerCase(java.util.Locale.ROOT),
						new org.bukkit.Location(p.getWorld(), Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4])),
						name, "#f4b82e", 0));
			}
			case "clear" -> targets(sender, args, 1).forEach(p -> {
				VeloBridge.clearEffects(p);
				Protocol.send(p, Protocol.message("waypoints_clear"));
				VeloBridge.teammates(p, List.of(), "#ffffff", 0);
			});
			case "transfer" -> {
				Player p = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
				if (p == null || args.length < 3) {
					sender.sendMessage(ChatColor.RED + "/velobridge transfer <player> <target|host[:port]>");
					return true;
				}
				String target = getConfig().getString("transfer-targets." + args[2], args[2]);
				String[] hp = target.split(":");
				VeloBridge.transfer(p, hp[0], hp.length > 1 ? parseInt(hp[1], 25565) : 25565);
			}
			case "ping" -> {
				Player p = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
				ConfigurationSection targets = getConfig().getConfigurationSection("transfer-targets");
				if (p == null || targets == null) {
					sender.sendMessage(ChatColor.RED + "/velobridge ping <player> (pings your transfer-targets)");
					return true;
				}
				List<String> hosts = new ArrayList<>();
				targets.getKeys(false).forEach(k -> hosts.add(targets.getString(k)));
				VeloBridge.pings(p, hosts).thenAccept(r -> sender.sendMessage(ChatColor.AQUA + p.getName() + " → " + (r.isEmpty() ? "no answer (not on Velo?)" : r)));
			}
			default -> sender.sendMessage(ChatColor.RED + "Unknown - /velobridge help");
		}
		return true;
	}

	private List<Player> targets(CommandSender sender, String[] args, int index) {
		if (args.length <= index) {
			return sender instanceof Player p ? List.of(p) : List.of();
		}
		if (args[index].equals("*")) {
			return new ArrayList<>(Bukkit.getOnlinePlayers());
		}
		Player p = Bukkit.getPlayerExact(args[index]);
		if (p == null) {
			sender.sendMessage(ChatColor.RED + "No player " + args[index]);
			return List.of();
		}
		return List.of(p);
	}

	private void notifyStaff(String message) {
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (p.hasPermission("velobridge.notify")) {
				p.sendMessage(message);
			}
		}
	}

	// ---- Small helpers ----

	private String loadServerId() {
		Path file = getDataFolder().toPath().resolve("server-id.txt");
		try {
			if (Files.exists(file)) {
				return Files.readString(file, StandardCharsets.UTF_8).strip();
			}
			String id = UUID.randomUUID().toString();
			Files.createDirectories(file.getParent());
			Files.writeString(file, id, StandardCharsets.UTF_8);
			return id;
		} catch (IOException e) {
			return UUID.randomUUID().toString();
		}
	}

	private void loadWelcomed() {
		Path file = getDataFolder().toPath().resolve("welcomed.txt");
		try {
			if (Files.exists(file)) {
				Files.readAllLines(file).forEach(l -> {
					try {
						welcomed.add(UUID.fromString(l.strip()));
					} catch (IllegalArgumentException ignored) {
						// Skip a broken line.
					}
				});
			}
		} catch (IOException ignored) {
			// Everyone gets the toast once more.
		}
	}

	private void saveWelcomed() {
		try {
			Files.createDirectories(getDataFolder().toPath());
			Files.write(getDataFolder().toPath().resolve("welcomed.txt"), welcomed.stream().map(UUID::toString).toList());
		} catch (IOException ignored) {
			// Best effort.
		}
	}

	private static Set<String> strings(JsonObject m, String key) {
		Set<String> out = new LinkedHashSet<>();
		if (m.has(key) && m.get(key).isJsonArray()) {
			for (JsonElement e : m.getAsJsonArray(key)) {
				out.add(e.getAsString());
			}
		}
		return Set.copyOf(out);
	}

	private static String str(JsonObject m, String key, String fallback) {
		return m.has(key) && m.get(key).isJsonPrimitive() ? m.get(key).getAsString() : fallback;
	}

	private static String join(String[] args, int from) {
		return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length));
	}

	private static int parseInt(String s, int fallback) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
