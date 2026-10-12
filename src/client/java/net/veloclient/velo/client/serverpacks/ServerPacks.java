package net.veloclient.velo.client.serverpacks;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Known servers: how to recognise them, what they're called, and how their tab list shows a
 * player's level / stars / ELO. Game modes are recognised the same way everywhere (from the
 * sidebar title and lines), so an unknown server running Bed Wars still gets the Bed Wars overlay.
 */
public final class ServerPacks {

	public enum Mode {
		BEDWARS("Bed Wars"), SKYWARS("SkyWars"), DUELS("Duels"), PRACTICE("Practice"), BRIDGE("The Bridge"),
		BUILD_BATTLE("Build Battle"), MURDER_MYSTERY("Murder Mystery"), UHC("UHC"), PIT("The Pit"), SKYBLOCK("SkyBlock"),
		EGGWARS("EggWars"), KITPVP("KitPvP"), PARKOUR("Parkour"), LOBBY("Lobby"), OTHER("In game");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	/** A known server. {@code levelPattern}'s group 1 is the number shown in the tab list (stars, level, ELO). */
	public record Pack(String id, String name, List<String> hosts, Pattern levelPattern, String levelLabel) {
		boolean matches(String host) {
			for (String h : hosts) {
				if (host.equals(h) || host.endsWith("." + h)) {
					return true;
				}
			}
			return false;
		}
	}

	private static final Pattern STARS = Pattern.compile("\\[(\\d{1,4})\\s?[✫✪⚝✥✦★☆❂✿]");
	private static final Pattern BRACKET_NUMBER = Pattern.compile("\\[(\\d{1,4})\\]");
	private static final Pattern ELO = Pattern.compile("(?:\\[|\\()?(\\d{3,4})(?:\\]|\\))?\\s*(?:ELO|Elo|elo)|(?:ELO|Elo)[: ]+(\\d{3,4})");
	private static final Pattern LEVEL = Pattern.compile("(?:Lv\\.?|Lvl\\.?|Level)\\s*(\\d{1,4})|\\[(\\d{1,4})\\]");

	public static final List<Pack> PACKS = List.of(
			new Pack("hypixel", "Hypixel", List.of("hypixel.net", "hypixel.io"), STARS, "Stars"),
			new Pack("minemen", "Minemen Club", List.of("minemen.club", "minemen.gg"), ELO, "ELO"),
			new Pack("pvpland", "PvP Land", List.of("pvp.land"), ELO, "ELO"),
			new Pack("pvpclub", "PvPClub", List.of("pvpclub.net", "pvpclub.eu", "pvpclub.com", "pvp.club"), ELO, "ELO"),
			new Pack("blocksmc", "BlocksMC", List.of("blocksmc.com"), BRACKET_NUMBER, "Level"),
			new Pack("pika", "Pika Network", List.of("pika-network.net", "pika.host"), LEVEL, "Level"),
			new Pack("jartex", "Jartex", List.of("jartex.fun", "jartexnetwork.com"), LEVEL, "Level"),
			new Pack("cubecraft", "CubeCraft", List.of("cubecraft.net"), LEVEL, "Level"),
			new Pack("mineplex", "Mineplex", List.of("mineplex.com"), LEVEL, "Level"),
			new Pack("mccisland", "MCC Island", List.of("mccisland.net"), LEVEL, "Level"),
			new Pack("hylex", "Hylex", List.of("hylex.net"), STARS, "Stars"),
			new Pack("coralmc", "CoralMC", List.of("coralmc.it"), LEVEL, "Level"),
			new Pack("mush", "Mush", List.of("mush.com.br"), LEVEL, "Level"),
			new Pack("bedwarspractice", "Bedwars Practice", List.of("bedwarspractice.club"), ELO, "ELO"),
			new Pack("ranked", "Ranked Bedwars", List.of("rbw.gg", "rankedbw.net"), ELO, "ELO"),
			new Pack("invadedlands", "InvadedLands", List.of("invadedlands.net"), LEVEL, "Level"),
			new Pack("hoplite", "Hoplite", List.of("hoplite.gg"), LEVEL, "Level"),
			new Pack("lunar", "Lunar Network", List.of("lunar.gg"), ELO, "ELO"),
			new Pack("zonix", "Zonix", List.of("zonix.us"), ELO, "ELO"),
			new Pack("vanity", "Vanity", List.of("vanitymc.co"), ELO, "ELO"));

	private static final Pack UNKNOWN = new Pack("other", "", List.of(), LEVEL, "Level");

	private ServerPacks() {
	}

	public static Pack forHost(String address) {
		if (address == null || address.isBlank()) {
			return UNKNOWN;
		}
		String host = address.toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "");
		for (Pack pack : PACKS) {
			if (pack.matches(host)) {
				return pack;
			}
		}
		return new Pack("other", host, List.of(), LEVEL, "Level");
	}

	/** Game mode from the sidebar ({@code [title, lines...]}). */
	public static Mode detect(List<String> sidebar) {
		if (sidebar.isEmpty()) {
			return Mode.OTHER;
		}
		String title = sidebar.get(0).toUpperCase(Locale.ROOT).replace(" ", "");
		String body = String.join("\n", sidebar.subList(1, sidebar.size())).toUpperCase(Locale.ROOT);
		if (title.contains("BEDWARS") || body.contains("DIAMOND II") || body.contains("BED GONE") || body.contains("BEDS DESTROYED")
				|| body.matches("(?s).*\\bR\\s+RED\\W.*")) {
			return Mode.BEDWARS;
		}
		if (title.contains("SKYWARS") || body.contains("REFILL")) {
			return Mode.SKYWARS;
		}
		if (title.contains("EGGWARS")) {
			return Mode.EGGWARS;
		}
		if (title.contains("BRIDGE")) {
			return Mode.BRIDGE;
		}
		if (title.contains("DUEL") || body.contains("OPPONENT")) {
			return Mode.DUELS;
		}
		if (title.contains("BUILDBATTLE") || body.contains("THEME:")) {
			return Mode.BUILD_BATTLE;
		}
		if (title.contains("MURDER")) {
			return Mode.MURDER_MYSTERY;
		}
		if (title.contains("UHC")) {
			return Mode.UHC;
		}
		if (title.contains("THEPIT") || title.equals("PIT")) {
			return Mode.PIT;
		}
		if (title.contains("SKYBLOCK")) {
			return Mode.SKYBLOCK;
		}
		if (title.contains("KITPVP") || title.contains("FFA")) {
			return Mode.KITPVP;
		}
		if (title.contains("PARKOUR")) {
			return Mode.PARKOUR;
		}
		if (title.contains("PRACTICE") || body.contains("IN QUEUE") || body.contains("FIGHTING") || body.contains("RANKED")) {
			return body.contains("OPPONENT") || body.contains("FIGHTING") ? Mode.DUELS : Mode.PRACTICE;
		}
		if (body.contains("LOBBY") || body.contains("HUB") || title.contains("LOBBY")) {
			return Mode.LOBBY;
		}
		return Mode.OTHER;
	}

	/** The level / stars / ELO number in a tab list name, or -1. */
	public static int level(Pack pack, String shown) {
		var m = pack.levelPattern().matcher(shown);
		if (m.find()) {
			for (int g = 1; g <= m.groupCount(); g++) {
				if (m.group(g) != null) {
					try {
						return Integer.parseInt(m.group(g));
					} catch (NumberFormatException ignored) {
						return -1;
					}
				}
			}
		}
		return -1;
	}
}
