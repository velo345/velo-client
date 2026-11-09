package net.veloclient.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.veloclient.bridge.api.VeloBridge;
import net.veloclient.bridge.api.VeloModulesChangeEvent;
import net.veloclient.bridge.api.VeloPlayer;
import net.veloclient.bridge.api.VeloPlayerJoinEvent;
import net.veloclient.bridge.internal.ServerNotes;
import net.veloclient.bridge.internal.SoundZones;
import net.veloclient.bridge.internal.Actions;
import net.veloclient.bridge.internal.Cutscenes;
import net.veloclient.bridge.internal.HudElements;
import net.veloclient.bridge.internal.Menus;
import net.veloclient.bridge.internal.ModLog;
import net.veloclient.bridge.internal.NpcCosmetics;
import net.veloclient.bridge.internal.Panel;
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
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
	/** Everyone who ever said hello here (first-join cutscene, "new" in the panel). */
	private final Set<UUID> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private String serverId;
	private Panel panel;
	private final Map<String, Command> menuCommands = new HashMap<>();

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
		loadIds("seen.txt", seen);
		ServerNotes.load(getDataFolder().toPath());
		Tasks.globalRepeating(() -> NpcCosmetics.tick(getConfig().getConfigurationSection("npc-cosmetics")), 60, 200);
		Tasks.globalRepeating(() -> Tasks.async(ServerNotes::flush), 100, 100);
		Actions.openMenu = this::openConfigMenu;
		Actions.closeMenu = Menus::close;
		Actions.playCutscene = (p, id) -> Cutscenes.play(p, getConfig().getConfigurationSection("cutscenes"), id, serverName());
		Actions.music = SoundZones::manual;
		Actions.transfer = (p, target) -> {
			String t = getConfig().getString("transfer-targets." + target, target);
			String[] hp = t.split(":");
			Tasks.forPlayer(p, () -> VeloBridge.transfer(p, hp[0], hp.length > 1 ? parseInt(hp[1], 25565) : 25565));
		};
		Actions.consoleAllowed = () -> getConfig().getBoolean("menus.allow-console-actions", false);
		Actions.serverName = this::serverName;
		getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
		registerMenuCommands();
		Tasks.globalRepeating(() -> HudElements.tick(getConfig().getConfigurationSection("hud"), this::serverName), 40, 20);
		Tasks.globalRepeating(() -> SoundZones.tick(getConfig().getConfigurationSection("sound-zones"), getConfig().getConfigurationSection("world-music")), 40, 10);
		panel = new Panel(this, serverId, this::liveStats, () -> Tasks.global(this::resendAll));
		Tasks.globalRepeating(() -> Tasks.async(() -> {
			if (getConfig().getBoolean("panel.enabled", true)) {
				panel.tick();
			}
		}), 20 * 10, 20 * 5);
		// Presence placeholders (online count...) change: refresh every minute.
		Tasks.globalRepeating(this::refreshPresence, 20 * 60, 20 * 60);
		Tasks.globalRepeating(() -> Tasks.async(this::heartbeat), 20 * 30, 20 * 60 * 5);
		getLogger().info("Velo Bridge " + getDescription().getVersion() + " ready" + (Tasks.folia() ? " (Folia)" : "")
				+ " - docs: https://dev.velo-client.com");
	}

	@Override
	public void onDisable() {
		saveWelcomed();
		saveIds("seen.txt", seen);
		ServerNotes.flush();
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
			case "note_put" -> Tasks.forPlayer(player, () -> ServerNotes.put(player, m, getConfig()));
			case "note_check" -> ServerNotes.check(player, m, getConfig());
			case "note_delete" -> ServerNotes.delete(player, m);
			case "menu_action" -> Menus.action(player, m);
			case "menu_closed" -> Menus.closed(player, m);
			case "cutscene_done" -> {
				String end = Cutscenes.done(player, str(m, "id", ""), getConfig().getConfigurationSection("cutscenes"));
				if (end != null) {
					Actions.run(player, end, Map.of());
				}
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
		boolean first = seen.add(player.getUniqueId());
		sendSetup(player);
		Bukkit.getPluginManager().callEvent(new VeloPlayerJoinEvent(player, velo));
		panel.joined(new Panel.Join(player.getName(), velo.veloVersion(), velo.minecraftVersion(), System.currentTimeMillis(), first));
		String intro = getConfig().getString("on-first-join-cutscene", "");
		if (first && !intro.isBlank()) {
			Tasks.forPlayerLater(player, () -> Cutscenes.play(player, getConfig().getConfigurationSection("cutscenes"), intro, serverName()), 40);
		}
		modLog(player, velo, m);
		List<String> disabled = getConfig().getStringList("disabled-modules");
		Set<String> clashing = new LinkedHashSet<>(velo.activeModules());
		clashing.retainAll(disabled);
		// Quiet by default: no chat line for every Velo player. Opt in with notify.on-join (all joins) or
		// notify.on-disabled-modules (only when someone joins with modules this server switched off).
		boolean everyJoin = getConfig().getBoolean("notify.on-join", false);
		boolean onClash = getConfig().getBoolean("notify.on-disabled-modules", false) && !clashing.isEmpty();
		if (everyJoin || onClash) {
			notifyStaff(ChatColor.AQUA + "[Velo] " + ChatColor.WHITE + player.getName() + ChatColor.GRAY + " joined with Velo " + velo.veloVersion()
					+ (clashing.isEmpty() ? "" : " - switched off here: " + String.join(", ", clashing)));
		}
		if (getConfig().getBoolean("notify.log-joins", false)) {
			getLogger().info(player.getName() + " joined with Velo " + velo.veloVersion());
		}
	}

	/** The mod list the player's Velo reported (players can switch that off): log it and look for forbidden mods. */
	private void modLog(Player player, VeloPlayer velo, JsonObject hello) {
		if (!hello.has("mods") || !getConfig().getBoolean("mod-log.enabled", true)) {
			return;
		}
		List<ModLog.Mod> mods = ModLog.parse(hello);
		List<String> detected = new ArrayList<>();
		for (JsonElement e : forbidRules()) {
			JsonObject r = e.getAsJsonObject();
			String match = r.get("match").getAsString(), value = r.get("value").getAsString().toLowerCase(java.util.Locale.ROOT);
			for (ModLog.Mod mod : mods) {
				boolean hit = switch (match) {
					case "id", "modrinth" -> mod.id().equals(value);
					default -> mod.name().toLowerCase(java.util.Locale.ROOT).contains(value) || mod.id().contains(value);
				};
				if (hit && !detected.contains(r.get("label").getAsString())) {
					detected.add(r.get("label").getAsString());
				}
			}
		}
		String name = player.getName();
		Tasks.async(() -> ModLog.log(getDataFolder().toPath(), name, velo.veloVersion(), mods, detected));
		if (!detected.isEmpty()) {
			String line = ChatColor.RED + "[Velo] " + ChatColor.WHITE + name + ChatColor.GRAY + " joined with " + ChatColor.YELLOW + String.join(", ", detected)
					+ ChatColor.DARK_GRAY + " (reported by their Velo client)";
			if (getConfig().getBoolean("notify.on-detection", false)) {
				notifyStaff(line);
			}
			getLogger().info(ChatColor.stripColor(line));
		}
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
		JsonArray forbid = forbidRules();
		JsonArray recommend = new JsonArray();
		for (String preset : getConfig().getStringList("mod-rules.recommend-presets")) {
			for (String[] e : Presets.RECOMMEND.getOrDefault(preset, List.of())) {
				recommend.add(rule(e[0], e[1], e[2]));
			}
		}
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
		ServerNotes.sendAll(player, getConfig());
		NpcCosmetics.send(player);

		if (getConfig().getBoolean("welcome-toast.enabled") && welcomed.add(player.getUniqueId())) {
			VeloBridge.toast(player, getConfig().getString("welcome-toast.title", "Welcome!"), getConfig().getString("welcome-toast.text", ""), 8);
		}
	}

	private JsonArray forbidRules() {
		JsonArray forbid = new JsonArray();
		for (String preset : getConfig().getStringList("mod-rules.forbid-presets")) {
			for (String[] e : Presets.ALL.getOrDefault(preset, List.of())) {
				forbid.add(rule(e[0], e[1], e[2]));
			}
		}
		addRules(forbid, getConfig().getMapList("mod-rules.forbid"));
		return forbid;
	}

	private String serverName() {
		return getConfig().getString("server.name", "Server");
	}

	/** After reload / a panel change: everything to every Velo player again. */
	private void resendAll() {
		registerMenuCommands();
		HudElements.resendAll();
		for (VeloPlayer v : Players.all()) {
			Player p = Bukkit.getPlayer(v.uuid());
			if (p != null) {
				Tasks.forPlayer(p, () -> sendSetup(p));
			}
		}
	}

	// ---- Menus from the config ----

	private void openConfigMenu(Player p, String id) {
		ConfigurationSection s = getConfig().getConfigurationSection("menus.list." + id);
		if (s == null) {
			return;
		}
		String perm = s.getString("permission", "");
		if (!perm.isBlank() && !p.hasPermission(perm)) {
			p.sendMessage(ChatColor.RED + "You can't open this.");
			return;
		}
		Menus.open(p, Menus.fromConfig(id, s, p, serverName()));
	}

	/** Each menu's "command:" becomes a real command (/warps), registered in the server's command map. */
	private void registerMenuCommands() {
		try {
			var map = (org.bukkit.command.CommandMap) Bukkit.getServer().getClass().getMethod("getCommandMap").invoke(Bukkit.getServer());
			ConfigurationSection list = getConfig().getConfigurationSection("menus.list");
			if (list == null) {
				return;
			}
			for (String id : list.getKeys(false)) {
				String cmd = list.getString(id + ".command", "").replace("/", "").strip().toLowerCase(java.util.Locale.ROOT);
				if (cmd.isEmpty() || !cmd.matches("[a-z0-9_-]{1,32}")) {
					continue;
				}
				Command existing = menuCommands.get(cmd);
				if (existing instanceof MenuCommand mc) {
					mc.menu = id;
					continue;
				}
				MenuCommand c = new MenuCommand(cmd, id);
				menuCommands.put(cmd, c);
				map.register("velobridge", c);
			}
			for (var e : menuCommands.entrySet()) {
				if (e.getValue() instanceof MenuCommand mc && !list.contains(mc.menu + ".command")) {
					mc.menu = null; // removed from config: does nothing until the restart unregisters it
				}
			}
		} catch (ReflectiveOperationException e) {
			getLogger().warning("Couldn't register menu commands (use /velomenu <id>): " + e.getMessage());
		}
	}

	private final class MenuCommand extends Command {
		String menu;

		MenuCommand(String name, String menu) {
			super(name, "Opens the " + menu + " menu", "/" + name, List.of());
			this.menu = menu;
		}

		@Override
		public boolean execute(CommandSender sender, String label, String[] args) {
			if (sender instanceof Player p && menu != null) {
				openConfigMenu(p, menu);
			}
			return true;
		}
	}

	// ---- Live numbers for the owner panel ----

	private JsonObject liveStats() {
		JsonObject l = new JsonObject();
		l.addProperty("online", Bukkit.getOnlinePlayers().size());
		l.addProperty("max", Bukkit.getMaxPlayers());
		l.addProperty("velo", Players.all().size());
		l.addProperty("plugin", getDescription().getVersion());
		l.addProperty("software", Bukkit.getName() + (Tasks.folia() ? " (Folia)" : ""));
		l.addProperty("minecraft", Bukkit.getMinecraftVersion());
		l.addProperty("utcOffsetMinutes", java.time.ZoneId.systemDefault().getRules().getOffset(java.time.Instant.now()).getTotalSeconds() / 60);
		Map<String, Integer> versions = new HashMap<>(), mc = new HashMap<>(), modules = new HashMap<>();
		JsonArray online = new JsonArray();
		for (VeloPlayer v : Players.all()) {
			versions.merge(v.veloVersion(), 1, Integer::sum);
			mc.merge(v.minecraftVersion(), 1, Integer::sum);
			v.activeModules().forEach(x -> modules.merge(x, 1, Integer::sum));
			JsonObject o = new JsonObject();
			o.addProperty("name", v.name());
			o.addProperty("v", v.veloVersion());
			o.addProperty("mc", v.minecraftVersion());
			o.addProperty("since", v.joinedAt());
			online.add(o);
		}
		l.add("versions", counts(versions));
		l.add("mcVersions", counts(mc));
		l.add("modules", counts(modules));
		l.add("players", online);
		l.add("mods", ModLog.top(40));
		l.add("detections", ModLog.detections());
		l.addProperty("seen", seen.size());
		l.addProperty("notes", ServerNotes.count());
		return l;
	}

	private static JsonObject counts(Map<String, Integer> m) {
		JsonObject o = new JsonObject();
		m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(60).forEach(e -> o.addProperty(e.getKey(), e.getValue()));
		return o;
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
	public void onJoin(PlayerJoinEvent event) {
		Player p = event.getPlayer();
		// Velo players say hello within a second or two; the rest count as "other" joins for the panel.
		Tasks.forPlayerLater(p, () -> {
			if (p.isOnline() && !Players.isVelo(p.getUniqueId())) {
				panel.joined(new Panel.Join(p.getName(), "", "", System.currentTimeMillis(), !p.hasPlayedBefore()));
			}
		}, 20 * 6);
	}

	@EventHandler
	public void onWorld(PlayerChangedWorldEvent event) {
		Player p = event.getPlayer();
		if (Players.isVelo(p.getUniqueId())) {
			ServerNotes.sendAll(p, getConfig());
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		UUID id = event.getPlayer().getUniqueId();
		Players.remove(id);
		Menus.forget(id);
		HudElements.forget(id);
		Cutscenes.forget(id);
		SoundZones.forget(id);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (command.getName().equalsIgnoreCase("velomenu")) {
			if (!(sender instanceof Player p) || args.length < 1) {
				sender.sendMessage(ChatColor.RED + "/velomenu <id>");
			} else {
				openConfigMenu(p, args[0]);
			}
			return true;
		}
		if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
			sender.sendMessage(ChatColor.AQUA + "Velo Bridge " + getDescription().getVersion() + ChatColor.GRAY + " - dev.velo-client.com");
			for (String line : new String[] {"reload", "players", "toast <player|*> <title> | <text>", "countdown <player|*> <seconds> <label>",
					"shake <player|*> [intensity 0-1]", "tint <player|*> <#hex> <seconds>", "vignette <player|*> <#hex> <seconds>",
					"progress <player|*> <0-1> <label>", "waypointat <player|*> <x> <y> <z> <label>", "team <#hex> <seconds> <player> <player>...",
					"waypoint <player|*> <label>  (at your position)", "clear <player|*>", "transfer <player> <target|host[:port]>", "ping <player>",
					"panel  (link to your owner panel on dev.velo-client.com)", "menu <id> [player]", "cutscene record <id> | point | save | play <id> [player|*] | stop [player|*] | list",
					"zone pos1 | pos2 | create <id> <sound> [volume]", "music <player|*> <sound> [volume] | stop", "mods <player>", "notes list [player] | remove <id> | clear <player>"}) {
				sender.sendMessage(ChatColor.GRAY + "/velobridge " + line);
			}
			return true;
		}
		switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
			case "reload" -> {
				reloadConfig();
				resendAll();
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
			case "panel" -> {
				if (!getConfig().getBoolean("panel.enabled", true)) {
					sender.sendMessage(ChatColor.RED + "The owner panel is off (panel.enabled in config.yml).");
					return true;
				}
				sender.sendMessage(ChatColor.GRAY + "Making a panel link...");
				Tasks.async(() -> {
					Panel.CompletableLink link = panel.link();
					if (link.url() == null) {
						sender.sendMessage(ChatColor.RED + "Velo panel: " + link.error());
						return;
					}
					if (sender instanceof Player p) {
						var text = new net.md_5.bungee.api.chat.TextComponent("[Velo] Open your owner panel (link works 15 minutes, only once)");
						text.setColor(net.md_5.bungee.api.ChatColor.AQUA);
						text.setUnderlined(true);
						text.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL, link.url()));
						p.spigot().sendMessage(text);
					} else {
						sender.sendMessage("Owner panel (15 minutes, once): " + link.url());
					}
				});
			}
			case "menu" -> {
				List<Player> ps = args.length > 2 ? targets(sender, args, 2) : sender instanceof Player p ? List.of(p) : List.of();
				if (args.length < 2) {
					sender.sendMessage(ChatColor.RED + "/velobridge menu <id> [player]");
					return true;
				}
				ps.forEach(p -> openConfigMenu(p, args[1]));
			}
			case "cutscene" -> cutsceneCommand(sender, args);
			case "notes" -> {
				String sub = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "list";
				switch (sub) {
					case "remove" -> sender.sendMessage(args.length > 2 && ServerNotes.remove(List.of(args[2])) > 0 ? ChatColor.GREEN + "Removed." : ChatColor.RED + "No note " + (args.length > 2 ? args[2] : ""));
					case "clear" -> {
						if (args.length < 3) {
							sender.sendMessage(ChatColor.RED + "/velobridge notes clear <player>");
							return true;
						}
						sender.sendMessage(ChatColor.GREEN + "Removed " + ServerNotes.remove(ServerNotes.idsOf(args[2])) + " notes of " + args[2] + ".");
					}
					default -> {
						var all = ServerNotes.summary();
						sender.sendMessage(ChatColor.AQUA + "" + ServerNotes.count() + " server notes" + (ServerNotes.enabled(getConfig()) ? "" : ChatColor.GRAY + " (server-wide notes are off: notes.server-wide.enabled)"));
						int shown = 0;
						for (var e : all) {
							var n = e.getAsJsonObject();
							if (args.length > 2 && !n.get("ownerName").getAsString().equalsIgnoreCase(args[2]) || shown >= 15) {
								continue;
							}
							shown++;
							sender.sendMessage(ChatColor.GRAY + " " + n.get("id").getAsString() + ChatColor.WHITE + " " + n.get("title").getAsString() + ChatColor.GRAY + " by "
									+ n.get("ownerName").getAsString() + " at " + n.get("bw").getAsString() + " " + n.get("x") + " " + n.get("y") + " " + n.get("z"));
						}
					}
				}
			}
			case "zone" -> zoneCommand(sender, args);
			case "music" -> {
				if (args.length > 1 && args[1].equalsIgnoreCase("stop")) {
					targets(sender, args, 2).forEach(p -> SoundZones.manual(p, ""));
				} else if (args.length > 2) {
					String vol = args.length > 3 ? args[3] : "0.8";
					targets(sender, args, 1).forEach(p -> SoundZones.manual(p, args[2] + "|" + vol + "|true"));
				} else {
					sender.sendMessage(ChatColor.RED + "/velobridge music <player|*> <sound> [volume]  |  /velobridge music stop [player|*]");
				}
			}
			case "mods" -> sender.sendMessage(ChatColor.GRAY + "Mod lists are written to plugins/VeloBridge/mod-logs/<date>.log and shown in /velobridge panel.");
			default -> sender.sendMessage(ChatColor.RED + "Unknown - /velobridge help");
		}
		return true;
	}

	private void cutsceneCommand(CommandSender sender, String[] args) {
		String sub = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "list";
		ConfigurationSection all = getConfig().getConfigurationSection("cutscenes");
		switch (sub) {
			case "record", "point", "save" -> {
				if (!(sender instanceof Player p)) {
					sender.sendMessage(ChatColor.RED + "Run this in-game.");
					return;
				}
				if (sub.equals("record")) {
					if (args.length < 3 || !args[2].matches("[a-zA-Z0-9_-]{1,32}")) {
						sender.sendMessage(ChatColor.RED + "/velobridge cutscene record <id>");
						return;
					}
					Cutscenes.record(p, args[2]);
					sender.sendMessage(ChatColor.AQUA + "Recording " + args[2] + ChatColor.GRAY + " - go to each camera spot, look where the camera should look and run "
							+ ChatColor.WHITE + "/vb cutscene point" + ChatColor.GRAY + ". Finish with " + ChatColor.WHITE + "/vb cutscene save");
				} else if (sub.equals("point")) {
					int n = Cutscenes.point(p);
					sender.sendMessage(n < 0 ? ChatColor.RED + "Start with /vb cutscene record <id>" : ChatColor.GREEN + "Point " + n + " added.");
				} else {
					String id = Cutscenes.save(p, getConfig());
					if (id == null) {
						sender.sendMessage(ChatColor.RED + "A cutscene needs at least 2 points.");
						return;
					}
					saveConfig();
					sender.sendMessage(ChatColor.GREEN + "Saved cutscene " + id + ". Try it: /vb cutscene play " + id + " - fine-tune it in /vb panel.");
				}
			}
			case "play" -> {
				if (args.length < 3) {
					sender.sendMessage(ChatColor.RED + "/velobridge cutscene play <id> [player|*]");
					return;
				}
				for (Player p : targets(sender, args, 3)) {
					if (!Cutscenes.play(p, all, args[2], serverName())) {
						sender.sendMessage(ChatColor.RED + "No cutscene " + args[2]);
						return;
					}
				}
			}
			case "stop" -> targets(sender, args, 2).forEach(Cutscenes::stop);
			default -> {
				Map<String, Integer> l = Cutscenes.list(all);
				sender.sendMessage(ChatColor.AQUA + "Cutscenes: " + ChatColor.GRAY + (l.isEmpty() ? "none yet - /vb cutscene record <id>" : l.toString()));
			}
		}
	}

	private void zoneCommand(CommandSender sender, String[] args) {
		if (!(sender instanceof Player p)) {
			sender.sendMessage(ChatColor.RED + "Run this in-game.");
			return;
		}
		String sub = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
		double[][] sel = SoundZones.SELECTION.computeIfAbsent(p.getUniqueId(), k -> new double[2][]);
		org.bukkit.Location l = p.getLocation();
		switch (sub) {
			case "pos1", "pos2" -> {
				sel[sub.equals("pos1") ? 0 : 1] = new double[] {l.getBlockX(), l.getBlockY(), l.getBlockZ()};
				sender.sendMessage(ChatColor.GREEN + sub + " set to " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
			}
			case "create" -> {
				if (args.length < 4 || sel[0] == null || sel[1] == null || !args[2].matches("[a-zA-Z0-9_-]{1,32}")) {
					sender.sendMessage(ChatColor.RED + "Set /vb zone pos1 and pos2 first, then /vb zone create <id> <sound> [volume]");
					return;
				}
				String base = "sound-zones." + args[2];
				getConfig().set(base + ".world", p.getWorld().getName());
				getConfig().set(base + ".from", List.of(sel[0][0], sel[0][1], sel[0][2]));
				getConfig().set(base + ".to", List.of(sel[1][0], sel[1][1], sel[1][2]));
				getConfig().set(base + ".sound", args[3]);
				getConfig().set(base + ".volume", args.length > 4 ? Double.parseDouble(args[4]) : 0.8);
				getConfig().set(base + ".loop", true);
				getConfig().set(base + ".priority", 0);
				saveConfig();
				SoundZones.SELECTION.remove(p.getUniqueId());
				sender.sendMessage(ChatColor.GREEN + "Sound zone " + args[2] + " created.");
			}
			default -> sender.sendMessage(ChatColor.RED + "/velobridge zone pos1 | pos2 | create <id> <sound> [volume]");
		}
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

	private void loadIds(String name, Set<UUID> into) {
		try {
			Path file = getDataFolder().toPath().resolve(name);
			if (Files.exists(file)) {
				for (String l : Files.readAllLines(file)) {
					try {
						into.add(UUID.fromString(l.strip()));
					} catch (IllegalArgumentException ignored) {
						// skip
					}
				}
			}
		} catch (IOException ignored) {
			// fresh start
		}
	}

	private void saveIds(String name, Set<UUID> ids) {
		try {
			Files.createDirectories(getDataFolder().toPath());
			Files.write(getDataFolder().toPath().resolve(name), ids.stream().map(UUID::toString).toList());
		} catch (IOException ignored) {
			// best effort
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
