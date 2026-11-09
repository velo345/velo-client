package net.veloclient.bridge.internal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * What a config button / zone / cutscene end does, as text:
 * <ul>
 * <li>{@code command:/spawn} - the player runs it</li>
 * <li>{@code console:/give %player% diamond} - the console runs it (only with {@code menus.allow-console-actions: true},
 * which can only be set in the file on the server, never from the panel)</li>
 * <li>{@code menu:shop} - opens another menu; {@code close} - closes it</li>
 * <li>{@code message:Hello &a%player%} - a chat line to the player</li>
 * <li>{@code server:lobby} - BungeeCord/Velocity connect; {@code transfer:lobby} - a transfer target from the config</li>
 * <li>{@code cutscene:intro}, {@code sound:minecraft:entity.player.levelup}, {@code music:...}, {@code music-stop}</li>
 * </ul>
 * Several actions: separate them with " ; ". {@code {input}} placeholders take the menu's field values.
 */
public final class Actions {

	/** Hooks the plugin fills in (menus, cutscenes, music, transfers). */
	public static BiConsumer<Player, String> openMenu = (p, id) -> {
	};
	public static BiConsumer<Player, String> playCutscene = (p, id) -> {
	};
	public static BiConsumer<Player, String> music = (p, spec) -> {
	};
	public static BiConsumer<Player, String> transfer = (p, target) -> {
	};
	public static java.util.function.Consumer<Player> closeMenu = p -> {
	};
	public static java.util.function.Supplier<Boolean> consoleAllowed = () -> false;
	public static java.util.function.Supplier<String> serverName = () -> "Server";

	private Actions() {
	}

	public static void run(Player p, String spec, Map<String, String> values) {
		if (spec == null || spec.isBlank()) {
			return;
		}
		for (String one : spec.split(" ; ")) {
			runOne(p, one.strip(), values);
		}
	}

	private static String fill(Player p, String text, Map<String, String> values) {
		String t = text;
		if (values != null) {
			for (var e : values.entrySet()) {
				// field values come from the player: no colour codes, no line breaks, no command separators
				String safe = e.getValue() == null ? "" : e.getValue().replaceAll("[§&\\r\\n;/]", "").strip();
				t = t.replace("{" + e.getKey() + "}", safe.length() > 64 ? safe.substring(0, 64) : safe);
			}
		}
		return Placeholders.apply(t, p, serverName.get());
	}

	private static void runOne(Player p, String spec, Map<String, String> values) {
		int colon = spec.indexOf(':');
		String kind = colon < 0 ? spec : spec.substring(0, colon).strip().toLowerCase(java.util.Locale.ROOT);
		String arg = colon < 0 ? "" : fill(p, spec.substring(colon + 1).strip(), values);
		switch (kind) {
			case "command" -> Tasks.forPlayer(p, () -> p.performCommand(arg.startsWith("/") ? arg.substring(1) : arg));
			case "console" -> {
				if (consoleAllowed.get()) {
					Tasks.global(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), arg.startsWith("/") ? arg.substring(1) : arg));
				}
			}
			case "menu" -> openMenu.accept(p, arg);
			case "close" -> closeMenu.accept(p);
			case "message" -> p.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', arg));
			case "server" -> {
				try {
					java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
					java.io.DataOutputStream out = new java.io.DataOutputStream(b);
					out.writeUTF("Connect");
					out.writeUTF(arg);
					Tasks.forPlayer(p, () -> p.sendPluginMessage(Bukkit.getPluginManager().getPlugin("VeloBridge"), "BungeeCord", b.toByteArray()));
				} catch (java.io.IOException ignored) {
					// can't happen with a byte array
				}
			}
			case "transfer" -> transfer.accept(p, arg);
			case "cutscene" -> playCutscene.accept(p, arg);
			case "sound" -> Tasks.forPlayer(p, () -> p.playSound(p.getLocation(), arg, 1f, 1f));
			case "music" -> music.accept(p, arg);
			case "music-stop" -> music.accept(p, "");
			default -> {
			}
		}
	}
}
