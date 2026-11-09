package net.veloclient.bridge.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.veloclient.bridge.internal.Players;
import net.veloclient.bridge.internal.Protocol;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Velo Bridge API - everything your plugin can do with players using Velo Client.
 *
 * <pre>{@code
 * if (VeloBridge.isVelo(player)) {
 *     VeloBridge.toast(player, "Round 2", "The arena shrinks in 30 seconds", 5);
 *     VeloBridge.countdown(player, "Starting", 10);
 *     VeloBridge.teammates(player, List.of(ally1, ally2), "#4ade80", 600);
 * }
 * }</pre>
 *
 * Every call is safe for players without Velo (it just does nothing) and safe from any thread (Folia included).
 * Everything the server shows is labelled with your server's name in the client, so players always know it comes
 * from your server - never from Velo Client itself.
 */
public final class VeloBridge {

	private static final Map<String, CompletableFuture<Map<String, Integer>>> PINGS = new ConcurrentHashMap<>();

	private VeloBridge() {
	}

	// ---- Who uses Velo ----

	public static boolean isVelo(Player player) {
		return Players.isVelo(player.getUniqueId());
	}

	public static Optional<VeloPlayer> info(Player player) {
		return Optional.ofNullable(Players.get(player.getUniqueId()));
	}

	public static Collection<VeloPlayer> onlineVeloPlayers() {
		return Players.all();
	}

	// ---- Native UI (shown with "From <your server>") ----

	/** A notification card in the corner. {@code seconds}: how long it stays (1-30). */
	public static void toast(Player player, String title, String text, int seconds) {
		JsonObject m = Protocol.message("toast");
		m.addProperty("title", title);
		m.addProperty("text", text);
		m.addProperty("seconds", clamp(seconds, 1, 30));
		Protocol.send(player, m);
	}

	/** A big centered countdown (3, 2, 1...) with a label, e.g. "Game starts". */
	public static void countdown(Player player, String label, int seconds) {
		JsonObject m = Protocol.message("countdown");
		m.addProperty("label", label);
		m.addProperty("seconds", clamp(seconds, 1, 3600));
		Protocol.send(player, m);
	}

	/** A labelled progress bar at the top of the screen; call again with the same id to update it. {@code value} 0..1. */
	public static void progress(Player player, String id, String label, double value) {
		JsonObject m = Protocol.message("progress");
		m.addProperty("id", id);
		m.addProperty("label", label);
		m.addProperty("value", Math.max(0, Math.min(1, value)));
		Protocol.send(player, m);
	}

	public static void removeProgress(Player player, String id) {
		JsonObject m = Protocol.message("progress_remove");
		m.addProperty("id", id);
		Protocol.send(player, m);
	}

	// ---- Camera & effects ----

	/** Shakes the camera ({@code intensity} 0..1) - an explosion, a boss stomp. The player's aim is never moved. */
	public static void shake(Player player, double intensity, int millis) {
		JsonObject m = Protocol.message("shake");
		m.addProperty("intensity", Math.max(0, Math.min(1, intensity)));
		m.addProperty("millis", clamp(millis, 50, 10_000));
		Protocol.send(player, m);
	}

	/** A colored vignette around the screen edges ({@code strength} 0..1), fading out over {@code millis}. 0 millis = until cleared. */
	public static void vignette(Player player, String hexColor, double strength, int millis) {
		effect(player, "vignette", hexColor, strength, millis);
	}

	/** Tints the whole screen (an event mood: red for danger, blue for night...). */
	public static void tint(Player player, String hexColor, double strength, int millis) {
		effect(player, "tint", hexColor, strength, millis);
	}

	public static void clearEffects(Player player) {
		Protocol.send(player, Protocol.message("effects_clear"));
	}

	private static void effect(Player player, String type, String hexColor, double strength, int millis) {
		JsonObject m = Protocol.message(type);
		m.addProperty("color", hexColor);
		m.addProperty("strength", Math.max(0, Math.min(1, strength)));
		m.addProperty("millis", clamp(millis, 0, 600_000));
		Protocol.send(player, m);
	}

	// ---- Teammates & waypoints ----

	/**
	 * Shows {@code mates} to {@code viewer} as teammates: an outline through walls in {@code hexColor}, their name and
	 * an arrow at the screen edge when they're off-screen. {@code seconds} 0 = until changed. Empty list clears.
	 */
	public static void teammates(Player viewer, Collection<? extends Player> mates, String hexColor, int seconds) {
		JsonObject m = Protocol.message("teammates");
		JsonArray list = new JsonArray();
		for (Player mate : mates) {
			if (mate.getUniqueId().equals(viewer.getUniqueId())) {
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("uuid", mate.getUniqueId().toString());
			o.addProperty("name", mate.getName());
			list.add(o);
		}
		m.add("list", list);
		m.addProperty("color", hexColor);
		m.addProperty("seconds", Math.max(0, seconds));
		Protocol.send(viewer, m);
	}

	/** A marker at a place: label, distance, an arrow when it's off-screen. Same id = moves / updates it. {@code seconds} 0 = until removed. */
	public static void waypoint(Player player, String id, Location at, String label, String hexColor, int seconds) {
		JsonObject m = Protocol.message("waypoint");
		m.addProperty("id", id);
		m.addProperty("x", at.getX());
		m.addProperty("y", at.getY());
		m.addProperty("z", at.getZ());
		m.addProperty("world", at.getWorld() == null ? "" : at.getWorld().getName());
		m.addProperty("label", label);
		m.addProperty("color", hexColor);
		m.addProperty("seconds", Math.max(0, seconds));
		Protocol.send(player, m);
	}

	public static void removeWaypoint(Player player, String id) {
		JsonObject m = Protocol.message("waypoint_remove");
		m.addProperty("id", id);
		Protocol.send(player, m);
	}

	// ---- Rich Presence ----

	/** The player's Discord / Velo friends status, e.g. details "In BedWars", state "Team Red - 2/8". */
	public static void presence(Player player, String details, String state) {
		JsonObject m = Protocol.message("presence");
		m.addProperty("details", details);
		m.addProperty("state", state);
		Protocol.send(player, m);
	}

	// ---- Modules ----

	/** Switches Velo modules off for this player while they're on your server (enforced - they can't switch them back on here). */
	public static void disableModules(Player player, List<String> moduleIds, String reason) {
		JsonObject m = Protocol.message("lock_modules");
		JsonArray ids = new JsonArray();
		moduleIds.forEach(ids::add);
		m.add("modules", ids);
		m.addProperty("reason", reason);
		Protocol.send(player, m);
	}

	// ---- Menus, HUD elements, cutscenes, music ----

	/** Shows a menu: a Velo window for Velo players, a chest menu for everyone else. */
	public static void openMenu(Player player, VeloMenu menu) {
		net.veloclient.bridge.internal.Menus.open(player, menu);
	}

	public static void closeMenu(Player player) {
		net.veloclient.bridge.internal.Menus.close(player);
	}

	/**
	 * A text line in the player's Server HUD (players move it in Velo's HUD editor). Same id again = update.
	 * {@code color}: hex or null.
	 */
	public static void hudText(Player player, String id, String title, String text, String color) {
		JsonObject m = hud(id, "text", title, color);
		m.addProperty("text", text);
		Protocol.send(player, m);
	}

	/** A bar (boss health, quest progress). {@code value}: 0-1. */
	public static void hudBar(Player player, String id, String title, double value, String text, String color) {
		JsonObject m = hud(id, "bar", title, color);
		m.addProperty("value", Math.max(0, Math.min(1, value)));
		m.addProperty("text", text == null ? "" : text);
		Protocol.send(player, m);
	}

	/** A countdown to {@code endsAtMillis} (epoch ms), ticking on the client. */
	public static void hudTimer(Player player, String id, String title, long endsAtMillis, String color) {
		JsonObject m = hud(id, "timer", title, color);
		m.addProperty("endsAt", endsAtMillis);
		Protocol.send(player, m);
	}

	/** A picture from a URL (PNG, up to 256x256), shown {@code width}x{@code height} HUD pixels. */
	public static void hudImage(Player player, String id, String url, int width, int height) {
		JsonObject m = hud(id, "image", "", null);
		m.addProperty("url", url);
		m.addProperty("w", clamp(width, 4, 256));
		m.addProperty("h", clamp(height, 4, 256));
		Protocol.send(player, m);
	}

	public static void hudRemove(Player player, String id) {
		JsonObject m = Protocol.message("hud_remove");
		m.addProperty("id", "api-" + id);
		Protocol.send(player, m);
	}

	private static JsonObject hud(String id, String type, String title, String color) {
		JsonObject m = Protocol.message("hud_set");
		m.addProperty("id", "api-" + id);
		m.addProperty("type", type);
		m.addProperty("title", title == null ? "" : title);
		if (color != null) {
			m.addProperty("color", color);
		}
		return m;
	}

	/**
	 * A camera flight through {@code points} (at least 2) over {@code seconds}. Players without Velo get the title.
	 * The player's real position never changes.
	 */
	public static void cutscene(Player player, String id, List<Location> points, double seconds, boolean letterbox, String title, String subtitle) {
		if (!isVelo(player)) {
			if (title != null && !title.isEmpty()) {
				player.sendTitle(title, subtitle == null ? "" : subtitle, 10, 60, 20);
			}
			return;
		}
		JsonObject m = Protocol.message("cutscene");
		m.addProperty("id", id);
		JsonArray pts = new JsonArray();
		for (Location l : points) {
			JsonArray p = new JsonArray();
			p.add(l.getX());
			p.add(l.getY());
			p.add(l.getZ());
			p.add(l.getYaw());
			p.add(l.getPitch());
			pts.add(p);
		}
		m.add("points", pts);
		m.addProperty("duration", Math.max(1, seconds) * 1000);
		m.addProperty("letterbox", letterbox);
		m.addProperty("lock", true);
		m.addProperty("title", title == null ? "" : title);
		m.addProperty("subtitle", subtitle == null ? "" : subtitle);
		Protocol.send(player, m);
	}

	public static void stopCutscene(Player player) {
		Protocol.send(player, Protocol.message("cutscene_stop"));
	}

	/** Music that fades in (sound event id, also from your resource pack). Sound zones pause until {@link #stopMusic}. */
	public static void music(Player player, String sound, double volume, boolean loop) {
		net.veloclient.bridge.internal.SoundZones.manual(player, sound + "|" + volume + "|" + loop);
	}

	public static void stopMusic(Player player) {
		net.veloclient.bridge.internal.SoundZones.manual(player, "");
	}

	// ---- Transfers ----

	/** Moves the player to another server with the 1.20.5+ transfer packet - no proxy needed. */
	public static void transfer(Player player, String host, int port) {
		player.transfer(host, port);
	}

	/**
	 * Asks the player's client how fast it reaches each server ("host" or "host:port"), in milliseconds (-1 = not
	 * reachable). Completes within 6 seconds; players without Velo get an empty map.
	 */
	public static CompletableFuture<Map<String, Integer>> pings(Player player, List<String> servers) {
		if (!isVelo(player)) {
			return CompletableFuture.completedFuture(Map.of());
		}
		String nonce = UUID.randomUUID().toString();
		CompletableFuture<Map<String, Integer>> future = new CompletableFuture<Map<String, Integer>>().completeOnTimeout(Map.of(), 6, TimeUnit.SECONDS);
		PINGS.put(nonce, future);
		future.whenComplete((r, e) -> PINGS.remove(nonce));
		JsonObject m = Protocol.message("ping");
		m.addProperty("nonce", nonce);
		JsonArray list = new JsonArray();
		servers.forEach(list::add);
		m.add("servers", list);
		Protocol.send(player, m);
		return future;
	}

	/** Internal: the client answered a {@link #pings} request. */
	public static void completePings(String nonce, Map<String, Integer> results) {
		CompletableFuture<Map<String, Integer>> future = PINGS.remove(nonce);
		if (future != null) {
			future.complete(results);
		}
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
