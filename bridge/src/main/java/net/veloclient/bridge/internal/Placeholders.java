package net.veloclient.bridge.internal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Placeholders for everything the config can show (HUD elements, menus, presence): Velo's own (%player%, %health%,
 * %online%...) and, when PlaceholderAPI is installed, every PlaceholderAPI placeholder too.
 */
public final class Placeholders {

	private static Method papi;
	private static boolean looked;

	private Placeholders() {
	}

	public static String apply(String text, Player p, String serverName) {
		if (text == null) {
			return "";
		}
		String t = text;
		if (t.indexOf('%') >= 0) {
			t = t.replace("%server%", serverName == null ? "Server" : serverName)
					.replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
					.replace("%max%", String.valueOf(Bukkit.getMaxPlayers()))
					.replace("%time%", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
			if (p != null) {
				t = t.replace("%player%", p.getName())
						.replace("%world%", p.getWorld().getName())
						.replace("%health%", String.valueOf((int) Math.ceil(p.getHealth())))
						.replace("%max_health%", String.valueOf((int) Math.round(maxHealth(p))))
						.replace("%food%", String.valueOf(p.getFoodLevel()))
						.replace("%level%", String.valueOf(p.getLevel()))
						.replace("%x%", String.valueOf(p.getLocation().getBlockX()))
						.replace("%y%", String.valueOf(p.getLocation().getBlockY()))
						.replace("%z%", String.valueOf(p.getLocation().getBlockZ()))
						.replace("%ping%", String.valueOf(p.getPing()));
				t = papi(p, t);
			}
		}
		return t;
	}

	private static double maxHealth(Player p) {
		try {
			var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
			return attr == null ? 20 : attr.getValue();
		} catch (Throwable e) {
			return 20;
		}
	}

	/** PlaceholderAPI.setPlaceholders(Player, String) through reflection - no hard dependency. */
	private static String papi(Player p, String text) {
		if (!looked) {
			looked = true;
			try {
				if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
					papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI").getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
				}
			} catch (Throwable ignored) {
				papi = null;
			}
		}
		if (papi == null || text.indexOf('%') < 0) {
			return text;
		}
		try {
			return (String) papi.invoke(null, p, text);
		} catch (Throwable e) {
			return text;
		}
	}

	/** A number from a placeholder text ("17.5", "%health%" after apply); fallback when it isn't one. */
	public static double number(String text, double fallback) {
		try {
			return Double.parseDouble(text.trim().replace(',', '.'));
		} catch (RuntimeException e) {
			return fallback;
		}
	}
}
