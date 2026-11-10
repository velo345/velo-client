package net.veloclient.velo.dev.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleLocks;
import net.veloclient.velo.module.ModuleRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Velo Client's side of Velo Bridge (the server plugin). On joining a server it says hello on {@code velo:bridge}
 * with the Velo / Minecraft version and the Velo modules that are on (only the public client modules - never add-ons);
 * a server with the plugin answers with its name, mod hints, modules it switches off, presence, teammates, waypoints,
 * UI and effects. Everything the server shows is labelled "From &lt;server&gt;" so it can't be mistaken for Velo itself.
 * Leaving the server forgets all of it and gives switched-off modules back.
 */
public final class VeloBridgeClient {

	/** What the server put on screen; drawn by {@link BridgeHud}. */
	record Toast(String title, String text, long shownAt, long until) {
	}

	record Countdown(String label, long endsAt) {
	}

	record Progress(String label, double value, long updatedAt) {
	}

	record Waypoint(String id, double x, double y, double z, String label, int color, long until) {
	}

	record Effect(int color, double strength, long startedAt, long millis) {
	}

	private static volatile String serverName;
	private static volatile int serverColor = 0xFF5AA9FF;
	private static volatile boolean connectedToBridge;
	static final List<Toast> TOASTS = new CopyOnWriteArrayList<>();
	static volatile Countdown countdown;
	static final Map<String, Progress> PROGRESS = new ConcurrentHashMap<>();
	static final Map<String, Waypoint> WAYPOINTS = new ConcurrentHashMap<>();
	private static final Map<UUID, String> TEAMMATE_NAMES = new ConcurrentHashMap<>();
	private static volatile int teammateColor = 0xFF4ADE80;
	private static volatile long teammatesUntil;
	static volatile Effect vignette;
	static volatile Effect tint;
	static volatile Effect shake;
	private static volatile String presenceDetails;
	private static volatile String presenceState;
	private static volatile JsonObject notesPolicy;
	private static Set<String> reportedModules = Set.of();
	private static int ticks;
	private static boolean helloSent;

	private VeloBridgeClient() {
	}

	public static void init() {
		BridgeNet.init(json -> {
			try {
				handle(JsonParser.parseString(json).getAsJsonObject());
			} catch (RuntimeException e) {
				VeloClient.LOGGER.debug("Velo Bridge: bad message", e);
			}
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> helloSent = false);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
		BridgeHud.register();
		BridgeModRules.register();
	}

	private static void tick() {
		if (!BridgeMc.inWorld()) {
			return;
		}
		// Hello as soon as the channel is open (the server registers it during join).
		if (!helloSent && BridgeNet.canSend()) {
			helloSent = true;
			JsonObject hello = message("hello");
			hello.addProperty("protocol", 1);
			hello.addProperty("version", FabricLoader.getInstance().getModContainer("velo-client").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
			hello.addProperty("mc", ClientCompat.minecraftVersion());
			reportedModules = activeModules();
			hello.add("modules", array(reportedModules));
			if (ServerHudModule.shareMods()) {
				hello.add("mods", modList());
			}
			BridgeNet.send(hello.toString());
		}
		if (++ticks % 40 == 0 && connectedToBridge) {
			Set<String> now = activeModules();
			if (!now.equals(reportedModules)) {
				reportedModules = now;
				JsonObject m = message("modules");
				m.add("modules", array(now));
				BridgeNet.send(m.toString());
			}
		}
		BridgeCutscene.tick();
		BridgeServerUi.tick(net.minecraft.client.MinecraftClient.getInstance());
		BridgeNpcs.tick(net.minecraft.client.MinecraftClient.getInstance());
		long t = System.currentTimeMillis();
		TOASTS.removeIf(x -> x.until() < t - 400);
		WAYPOINTS.values().removeIf(w -> w.until() > 0 && w.until() < t);
		PROGRESS.values().removeIf(p -> t - p.updatedAt() > 10 * 60_000L);
		if (teammatesUntil > 0 && teammatesUntil < t) {
			TEAMMATE_NAMES.clear();
			teammatesUntil = 0;
		}
		if (countdown != null && countdown.endsAt() < t - 1200) {
			countdown = null;
		}
	}

	/** Public client modules that are on - add-on and unreleased modules are never told to servers. */
	private static Set<String> activeModules() {
		Set<String> out = new LinkedHashSet<>();
		for (Module m : ModuleRegistry.all()) {
			if (m.isEnabled() && m.getClass().getName().startsWith("net.veloclient.velo.client.")) {
				out.add(m.id());
			}
		}
		return out;
	}

	private static void handle(JsonObject m) {
		String type = m.has("t") ? m.get("t").getAsString() : "";
		long now = System.currentTimeMillis();
		switch (type) {
			case "welcome" -> {
				connectedToBridge = true;
				serverName = str(m, "name", "This server");
				serverColor = color(str(m, "color", "#5aa9ff"));
				modListNotice();
			}
			case "mod_rules" -> BridgeModRules.check(server(), m);
			case "lock_modules" -> {
				List<String> names = new ArrayList<>();
				for (JsonElement e : arr(m, "modules")) {
					String id = e.getAsString();
					ModuleRegistry.get(id).ifPresent(mod -> {
						ModuleLocks.lock(id, server() + " turned this off");
						names.add(mod.displayName());
					});
				}
				// once per server (and again only when its list changes) - it popped up on every join
				if (!names.isEmpty() && firstLockNotice(server() + "|" + String.join(",", new java.util.TreeSet<>(names)))) {
					toast("Modules switched off here", String.join(", ", names) + " - back on when you leave.", 7);
				}
			}
			case "presence" -> {
				presenceDetails = str(m, "details", null);
				presenceState = str(m, "state", null);
			}
			case "toast" -> toast(str(m, "title", ""), str(m, "text", ""), m.has("seconds") ? m.get("seconds").getAsInt() : 6);
			case "countdown" -> countdown = new Countdown(str(m, "label", ""), now + (m.has("seconds") ? m.get("seconds").getAsInt() : 10) * 1000L);
			case "progress" -> PROGRESS.put(str(m, "id", "main"), new Progress(str(m, "label", ""), m.has("value") ? m.get("value").getAsDouble() : 0, now));
			case "progress_remove" -> PROGRESS.remove(str(m, "id", "main"));
			case "shake" -> shake = new Effect(0, m.get("intensity").getAsDouble(), now, m.get("millis").getAsLong());
			case "vignette" -> vignette = new Effect(color(str(m, "color", "#000000")), m.get("strength").getAsDouble(), now, m.get("millis").getAsLong());
			case "tint" -> tint = new Effect(color(str(m, "color", "#000000")), m.get("strength").getAsDouble(), now, m.get("millis").getAsLong());
			case "effects_clear" -> {
				vignette = null;
				tint = null;
				shake = null;
			}
			case "teammates" -> {
				TEAMMATE_NAMES.clear();
				for (JsonElement e : arr(m, "list")) {
					JsonObject o = e.getAsJsonObject();
					try {
						TEAMMATE_NAMES.put(UUID.fromString(o.get("uuid").getAsString()), str(o, "name", "?"));
					} catch (IllegalArgumentException ignored) {
						// Skip a broken entry.
					}
				}
				teammateColor = color(str(m, "color", "#4ade80"));
				int seconds = m.has("seconds") ? m.get("seconds").getAsInt() : 0;
				teammatesUntil = seconds > 0 ? now + seconds * 1000L : 0;
			}
			case "waypoint" -> {
				int seconds = m.has("seconds") ? m.get("seconds").getAsInt() : 0;
				WAYPOINTS.put(str(m, "id", "wp"), new Waypoint(str(m, "id", "wp"), m.get("x").getAsDouble(), m.get("y").getAsDouble(), m.get("z").getAsDouble(),
						str(m, "label", ""), color(str(m, "color", "#5aa9ff")), seconds > 0 ? now + seconds * 1000L : 0));
			}
			case "waypoint_remove" -> WAYPOINTS.remove(str(m, "id", ""));
			case "waypoints_clear" -> WAYPOINTS.clear();
			case "ping" -> BridgeNetTools.answerPings(str(m, "nonce", ""), arr(m, "servers"));
			case "precache" -> BridgeNetTools.precache(arr(m, "packs"));
			case "preconnect" -> BridgeNetTools.preconnect(arr(m, "hosts"));
			case "notes" -> notesPolicy = m;
			case "npc_cosmetics" -> BridgeNpcs.set(m);
			case "hud_set", "hud_remove", "hud_clear", "menu", "menu_close", "cutscene", "cutscene_stop", "music", "music_stop" -> BridgeServerUi.handle(type, m);
			case "server_notes", "server_note", "server_note_remove", "note_result" ->
					net.minecraft.client.MinecraftClient.getInstance().execute(() -> net.veloclient.velo.dev.notes.NoteStore.onBridge(type, m));
			default -> {
				// A newer plugin: ignore what this client doesn't know yet.
			}
		}
	}

	private static void reset() {
		connectedToBridge = false;
		serverName = null;
		TOASTS.clear();
		countdown = null;
		PROGRESS.clear();
		WAYPOINTS.clear();
		TEAMMATE_NAMES.clear();
		vignette = null;
		tint = null;
		shake = null;
		presenceDetails = null;
		presenceState = null;
		notesPolicy = null;
		helloSent = false;
		ModuleLocks.unlockAll();
		BridgeServerUi.reset();
		BridgeNpcs.reset();
		net.minecraft.client.MinecraftClient.getInstance().execute(() -> net.veloclient.velo.dev.notes.NoteStore.onBridge("server_notes_reset", new JsonObject()));
	}

	/** True the first time this server + module list is seen (remembered in config/bridge-lock-notices.json). */
	private static boolean firstLockNotice(String key) {
		java.nio.file.Path file = net.veloclient.velo.config.VeloPaths.config().resolve("bridge-lock-notices.json");
		java.util.Set<String> seen = new java.util.LinkedHashSet<>();
		try {
			if (java.nio.file.Files.exists(file)) {
				com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonArray().forEach(e -> seen.add(e.getAsString()));
			}
		} catch (Exception ignored) {
			// unreadable: show it
		}
		if (!seen.add(key)) {
			return false;
		}
		JsonArray list = new JsonArray();
		seen.forEach(list::add);
		try {
			java.nio.file.Files.createDirectories(file.getParent());
			java.nio.file.Files.writeString(file, list.toString());
		} catch (Exception ignored) {
			// shown again next time
		}
		return true;
	}

	// ---- For the HUD, mixins, Discord and Notes ----

	static void toast(String title, String text, int seconds) {
		long now = System.currentTimeMillis();
		TOASTS.add(new Toast(title, text, now, now + Math.max(1, Math.min(30, seconds)) * 1000L));
		while (TOASTS.size() > 4) {
			TOASTS.remove(0);
		}
	}

	/** A short notice about a server rule, shown as a server card ("From <server>"). */
	public static void serverNotice(String title, String text) {
		toast(title, text, 5);
	}

	/** The server's display name - every server-sent element carries it. */
	public static String server() {
		String name = serverName;
		if (name != null) {
			return name;
		}
		String address = ClientCompat.currentServerName();
		return address == null || address.isBlank() ? "This server" : address;
	}

	static int serverColor() {
		return serverColor;
	}

	/** Sends one raw protocol message (World Notes' server-wide notes). */
	public static void sendRaw(String json) {
		BridgeNet.send(json);
	}

	public static boolean onBridgeServer() {
		return connectedToBridge;
	}

	/** Outline color (RGB) for a teammate, or null when the player isn't one. */
	public static Integer teammateColor(UUID uuid) {
		return TEAMMATE_NAMES.containsKey(uuid) ? teammateColor & 0xFFFFFF : null;
	}

	static Map<UUID, String> teammates() {
		return TEAMMATE_NAMES;
	}

	static int teammateArgb() {
		return teammateColor;
	}

	public static String presenceDetails() {
		return presenceDetails;
	}

	public static String presenceState() {
		return presenceState;
	}

	/** World Notes on this server: false when the server turned them off, or {@code x/z} lies in a blocked area. */
	public static boolean notesAllowedAt(double x, double z) {
		JsonObject p = notesPolicy;
		if (p == null) {
			return true;
		}
		if (p.has("enabled") && !p.get("enabled").getAsBoolean()) {
			return false;
		}
		for (JsonElement e : arr(p, "blocked")) {
			JsonObject a = e.getAsJsonObject();
			double x1 = Math.min(a.get("x1").getAsDouble(), a.get("x2").getAsDouble());
			double x2 = Math.max(a.get("x1").getAsDouble(), a.get("x2").getAsDouble());
			double z1 = Math.min(a.get("z1").getAsDouble(), a.get("z2").getAsDouble());
			double z2 = Math.max(a.get("z1").getAsDouble(), a.get("z2").getAsDouble());
			if (x >= x1 && x <= x2 && z >= z1 && z <= z2) {
				return false;
			}
		}
		return true;
	}

	/** Whether this server allows pictures on notes (Velo Bridge "notes.images"; true without the plugin). */
	public static boolean notesImages() {
		JsonObject p = notesPolicy;
		return p == null || !p.has("images") || p.get("images").getAsBoolean();
	}

	/** Whether this server allows whiteboards on notes (Velo Bridge "notes.whiteboards"; true without the plugin). */
	public static boolean notesWhiteboards() {
		JsonObject p = notesPolicy;
		return p == null || !p.has("whiteboards") || p.get("whiteboards").getAsBoolean();
	}

	/** Most notes one player may place on this server (0 = no limit). */
	public static int notesLimit() {
		JsonObject p = notesPolicy;
		return p != null && p.has("maxPerPlayer") ? p.get("maxPerPlayer").getAsInt() : 0;
	}

	// ---- JSON helpers ----

	static JsonObject message(String type) {
		JsonObject o = new JsonObject();
		o.addProperty("t", type);
		return o;
	}

	private static JsonArray array(Set<String> values) {
		JsonArray a = new JsonArray();
		values.forEach(a::add);
		return a;
	}

	static JsonArray arr(JsonObject m, String key) {
		return m.has(key) && m.get(key).isJsonArray() ? m.getAsJsonArray(key) : new JsonArray();
	}

	static String str(JsonObject m, String key, String fallback) {
		return m.has(key) && m.get(key).isJsonPrimitive() ? m.get(key).getAsString() : fallback;
	}

	static int color(String hex) {
		try {
			String h = hex.strip().replace("#", "");
			return 0xFF000000 | Integer.parseInt(h.length() > 6 ? h.substring(h.length() - 6) : h, 16);
		} catch (RuntimeException e) {
			return 0xFF5AA9FF;
		}
	}

	/** The mods the player installed themselves (no bundled libraries), for the server's mod log and hints. */
	private static JsonArray modList() {
		JsonArray a = new JsonArray();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			if (mod.getContainingMod().isPresent() || a.size() >= 300) {
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", cut(mod.getMetadata().getId(), 48));
			o.addProperty("name", cut(mod.getMetadata().getName(), 40));
			o.addProperty("version", cut(mod.getMetadata().getVersion().getFriendlyString(), 24));
			a.add(o);
		}
		// one plugin message from the client is at most 32 KB: drop names, then trim the list, until it fits
		if (a.toString().length() > 24_000) {
			a.forEach(e -> e.getAsJsonObject().remove("name"));
		}
		while (a.toString().length() > 24_000 && !a.isEmpty()) {
			a.remove(a.size() - 1);
		}
		return a;
	}

	private static String cut(String s, int max) {
		return s == null ? "" : s.length() > max ? s.substring(0, max) : s;
	}

	/** Once ever: tell the player Velo Bridge servers see their mod list, and where to switch that off. */
	private static void modListNotice() {
		if (!ServerHudModule.shareMods()) {
			return;
		}
		java.nio.file.Path flag = net.veloclient.velo.config.VeloPaths.config().resolve("velo-bridge-modlist-notice");
		if (java.nio.file.Files.exists(flag)) {
			return;
		}
		try {
			java.nio.file.Files.createDirectories(flag.getParent());
			java.nio.file.Files.writeString(flag, "shown");
		} catch (java.io.IOException ignored) {
			// shows again next time
		}
		net.minecraft.client.MinecraftClient.getInstance().execute(() -> net.veloclient.velo.client.social.NotificationOverlay.show(
				new net.veloclient.velo.client.social.NotificationOverlay.Toast(null, null, "This server sees your mod list",
						"Velo Bridge servers get it for their mod rules. Switch it off: Server HUD settings.", 9000, null, null, null)));
	}

	/** The player's own mods as {id, name, file name} (lowercase), for the mod hints. */
	static List<String[]> installedMods() {
		List<String[]> out = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String file = mod.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH && !mod.getOrigin().getPaths().isEmpty()
					? mod.getOrigin().getPaths().get(0).getFileName().toString() : "";
			out.add(new String[] {mod.getMetadata().getId().toLowerCase(Locale.ROOT), mod.getMetadata().getName(), file.toLowerCase(Locale.ROOT)});
		}
		return out;
	}

	static Map<String, Object> debug() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("server", serverName);
		m.put("bridge", connectedToBridge);
		return m;
	}
}
