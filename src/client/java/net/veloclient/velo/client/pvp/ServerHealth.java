package net.veloclient.velo.client.pvp;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Health that the server itself shows next to a player's name - a "20 ❤" line under the name (a
 * scoreboard health objective) or a heart suffix on the name tag, which PvP servers use. Where a
 * server does that, its number is the real one (some send other players' entity health inaccurately
 * or not at all), so Health Indicator steps aside and Damage Numbers counts from it.
 */
public final class ServerHealth {

	private static final Pattern BEFORE_HEART = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*[❤♥❣♡]");
	private static final Pattern AFTER_HEART = Pattern.compile("[❤♥❣♡]\\s*(\\d+(?:[.,]\\d+)?)");
	private static final Pattern NAMED = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(?:hp|health)\\b|(?i)\\b(?:hp|health)\\s*:?\\s*(\\d+(?:[.,]\\d+)?)");

	private record Reading(float value, long atMillis) {
	}

	private static final Map<Integer, Reading> READINGS = new HashMap<>();

	private ServerHealth() {
	}

	/** Called while a player's name tag is drawn, with the plain text of the name and the line under it (or null). */
	public static synchronized void observe(int entityId, String name, String score) {
		Float value = parse(score);
		if (value == null) {
			value = parse(name);
		}
		if (value != null) {
			READINGS.put(entityId, new Reading(value, System.currentTimeMillis()));
		}
	}

	private static int ticks;

	/** Every 5 ticks: read health from floating text (holograms) above each player, for servers that hide name tags. */
	public static void tick() {
		if (++ticks % 5 != 0 || !(enabled("health-indicator") || enabled("damage-numbers"))) {
			return;
		}
		for (var player : PvpCompat.otherPlayers()) {
			for (String text : PvpCompat.floatingTextAbove(player)) {
				Float value = parse(text);
				if (value != null) {
					synchronized (ServerHealth.class) {
						READINGS.put(player.getId(), new Reading(value, System.currentTimeMillis()));
					}
					break;
				}
			}
		}
	}

	private static boolean enabled(String id) {
		return net.veloclient.velo.module.ModuleRegistry.get(id).map(net.veloclient.velo.module.Module::isEnabled).orElse(false);
	}

	/** The server-shown health for this player, or null if the server doesn't show one (seen in the last 3 s). */
	public static synchronized Float get(int entityId) {
		Reading reading = READINGS.get(entityId);
		if (reading == null || System.currentTimeMillis() - reading.atMillis() > 3000) {
			return null;
		}
		return reading.value();
	}

	static Float parse(String text) {
		if (text == null || text.isEmpty()) {
			return null;
		}
		for (Pattern pattern : new Pattern[] {BEFORE_HEART, AFTER_HEART, NAMED}) {
			Matcher m = pattern.matcher(text);
			if (m.find()) {
				String number = m.group(1) != null ? m.group(1) : m.groupCount() > 1 ? m.group(2) : null;
				if (number != null) {
					try {
						return Float.parseFloat(number.replace(',', '.'));
					} catch (NumberFormatException ignored) {
						// Not a number after all.
					}
				}
			}
		}
		return null;
	}
}
