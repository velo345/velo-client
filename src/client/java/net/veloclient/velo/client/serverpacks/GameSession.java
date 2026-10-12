package net.veloclient.velo.client.serverpacks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What's happening on the current server, for the server overlays: which server and game mode,
 * Bed Wars teams and generator timers, sidebar event timers, and your kills / final kills / beds
 * / deaths this game plus wins, losses and win streak this session - all read from the sidebar,
 * holograms and death messages in chat, so it works on any server that uses the usual formats.
 */
public final class GameSession {

	/** A Bed Wars team line: letter, name, status (✔ bed alive, ✗ gone, or players left) and whether it's yours. */
	public record Team(String letter, String name, String status, boolean you) {
		public boolean alive() {
			return !status.equals("✗") && !status.equals("✘") && !status.equals("0");
		}
	}

	/** A countdown read from the sidebar ("Diamond II in 4:12", "Refill 2:31"), ticking locally between updates. */
	public record Timer(String label, long endsAtMillis) {
		public int secondsLeft() {
			return (int) Math.max(0, (endsAtMillis - System.currentTimeMillis() + 999) / 1000);
		}
	}

	/** A generator's next spawn, from its hologram. */
	public record Generator(String kind, String tier, int seconds, double distance) {
	}

	private static final Pattern TEAM = Pattern.compile("^([A-Z])\\s+([A-Za-z]+):?\\s+(✔|✓|✗|✘|\\d+)(\\s+YOU)?.*$");
	private static final Pattern TIMER = Pattern.compile("^(.*?[A-Za-z].*?)[:\\s]+(?:in\\s+)?(\\d{1,2}):(\\d{2})\\s*$");
	private static final Pattern SPAWNS_IN = Pattern.compile("(?i)spawns?\\s+in\\s+(\\d+)");
	private static final Pattern TIER = Pattern.compile("(?i)tier\\s+([IVX]+|\\d)");
	private static final Pattern NUMBER_LINE = Pattern.compile("^(.*?):\\s*(\\d+)\\s*$");
	private static final List<Consumer<GameSession>> MODE_LISTENERS = new ArrayList<>();

	private static final GameSession INSTANCE = new GameSession();

	private String address;
	private ServerPacks.Pack pack = ServerPacks.forHost(null);
	private ServerPacks.Mode mode = ServerPacks.Mode.OTHER;
	private List<String> sidebar = List.of();
	private List<Team> teams = List.of();
	private final Map<String, Timer> timers = new LinkedHashMap<>();
	private final Map<String, int[]> timerHistory = new LinkedHashMap<>();
	private List<Generator> generators = List.of();
	private final Map<String, String> sidebarValues = new LinkedHashMap<>();
	private int ticks;

	private int kills;
	private int finals;
	private int beds;
	private int deaths;
	private long gameStartedAt;
	private int wins;
	private int losses;
	private int streak;
	private int bestStreak;
	private int sessionKills;
	private int sessionFinals;
	private int sessionBeds;
	private int sessionDeaths;
	private boolean gameOver;

	private GameSession() {
	}

	public static GameSession get() {
		return INSTANCE;
	}

	public static void register() {
		net.veloclient.velo.client.pvp.PvpCompat.onChatText(text -> INSTANCE.onChat(ServerData.plain(text)));
	}

	/** Called with the session whenever the detected game mode or server changes. */
	public static synchronized void onModeChange(Consumer<GameSession> listener) {
		MODE_LISTENERS.add(listener);
	}

	public synchronized void tick(boolean anyOverlayOn) {
		String current = net.veloclient.velo.client.util.ClientCompat.currentServerAddress();
		if (current == null && net.veloclient.velo.client.pvp.PvpCompat.localPlayer() == null) {
			if (address != null) {
				address = null;
				mode = ServerPacks.Mode.OTHER;
				fireModeChange();
			}
			return;
		}
		if (!java.util.Objects.equals(current, address)) {
			address = current;
			pack = ServerPacks.forHost(current);
			wins = losses = streak = bestStreak = 0;
			sessionKills = sessionFinals = sessionBeds = sessionDeaths = 0;
			resetGame();
		}
		if (++ticks % 10 != 0) {
			return;
		}
		sidebar = ServerData.sidebar();
		ServerPacks.Mode detected = ServerPacks.detect(sidebar);
		if (detected != mode) {
			mode = detected;
			if (mode == ServerPacks.Mode.LOBBY) {
				resetGame();
			}
			fireModeChange();
		}
		if (!anyOverlayOn) {
			return;
		}
		parseSidebar();
		if (mode == ServerPacks.Mode.BEDWARS && ticks % 20 == 0) {
			readGenerators();
		}
	}

	private void fireModeChange() {
		for (Consumer<GameSession> listener : List.copyOf(MODE_LISTENERS)) {
			try {
				listener.accept(this);
			} catch (RuntimeException e) {
				net.veloclient.velo.VeloClient.LOGGER.warn("Game mode listener failed", e);
			}
		}
	}

	private void resetGame() {
		kills = finals = beds = deaths = 0;
		gameStartedAt = 0;
		gameOver = false;
		timers.clear();
		timerHistory.clear();
		teams = List.of();
		generators = List.of();
	}

	private void parseSidebar() {
		List<Team> parsedTeams = new ArrayList<>();
		sidebarValues.clear();
		long now = System.currentTimeMillis();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int i = 1; i < sidebar.size(); i++) {
			String line = sidebar.get(i);
			Matcher team = TEAM.matcher(line);
			if (team.matches()) {
				parsedTeams.add(new Team(team.group(1), team.group(2), team.group(3), team.group(4) != null || line.endsWith("YOU")));
				continue;
			}
			Matcher timer = TIMER.matcher(line);
			if (timer.matches()) {
				String label = timer.group(1).strip().replaceFirst("[:\\s]+$", "");
				int seconds = Integer.parseInt(timer.group(2)) * 60 + Integer.parseInt(timer.group(3));
				seen.add(label);
				int[] history = timerHistory.get(label);
				// Count down locally only once the value was seen going down - a game clock counts up.
				boolean countdown = history != null && seconds < history[0];
				boolean countup = history != null && seconds > history[0];
				timerHistory.put(label, new int[] {seconds, countdown ? 1 : countup ? -1 : history == null ? 0 : history[1]});
				int direction = timerHistory.get(label)[1];
				if (direction >= 0 && !label.toLowerCase(Locale.ROOT).matches(".*\\b(time|duration|elapsed|played)\\b.*")) {
					timers.put(label, new Timer(label, now + seconds * 1000L));
				} else {
					timers.remove(label);
				}
				continue;
			}
			Matcher number = NUMBER_LINE.matcher(line);
			if (number.matches()) {
				sidebarValues.put(number.group(1).strip(), number.group(2));
			} else if (line.contains(":")) {
				String[] parts = line.split(":", 2);
				sidebarValues.put(parts[0].strip(), parts[1].strip());
			}
		}
		timers.keySet().retainAll(seen);
		teams = parsedTeams;
	}

	private void readGenerators() {
		List<ServerData.Hologram> holograms = ServerData.holograms(48);
		List<Generator> found = new ArrayList<>();
		var self = net.veloclient.velo.client.pvp.PvpCompat.localPlayer();
		if (self == null) {
			return;
		}
		double sx = net.veloclient.velo.client.pvp.PvpCompat.x(self);
		double sz = net.veloclient.velo.client.pvp.PvpCompat.z(self);
		for (ServerData.Hologram timer : holograms) {
			Matcher spawn = SPAWNS_IN.matcher(timer.text());
			if (!spawn.find()) {
				continue;
			}
			String kind = null;
			String tier = "";
			for (ServerData.Hologram other : holograms) {
				if (Math.abs(other.x() - timer.x()) > 1.5 || Math.abs(other.z() - timer.z()) > 1.5 || Math.abs(other.y() - timer.y()) > 2.5) {
					continue;
				}
				String lower = other.text().toLowerCase(Locale.ROOT);
				if (lower.contains("diamond")) {
					kind = "Diamond";
				} else if (lower.contains("emerald")) {
					kind = "Emerald";
				}
				Matcher t = TIER.matcher(other.text());
				if (t.find()) {
					tier = t.group(1);
				}
			}
			if (kind != null) {
				found.add(new Generator(kind, tier, Integer.parseInt(spawn.group(1)),
						Math.hypot(timer.x() - sx, timer.z() - sz)));
			}
		}
		found.sort(java.util.Comparator.comparingDouble(Generator::distance));
		generators = found;
	}

	// ---- chat ----

	synchronized void onChat(String text) {
		if (text.isEmpty() || address == null) {
			return;
		}
		String me = ServerData.localName();
		if (me.isEmpty()) {
			return;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		// Player / party / guild chat ("Name: hi", "Party > [MVP+] Name: gg") is never a game event -
		// and death messages practically never contain ": ".
		if (text.contains(": ") && !lower.contains("final kill") && !lower.startsWith("bed destruction")
				&& !lower.startsWith("winner")) {
			return;
		}
		if (lower.contains("protect your bed") || lower.contains("cages opened") || lower.contains("the game has started")
				|| lower.contains("the game starts in 1 second") || lower.contains("game started") || lower.matches(".*\\bfight!?\\s*$")) {
			if (gameStartedAt == 0 || gameOver) {
				kills = finals = beds = deaths = 0;
				gameOver = false;
			}
			gameStartedAt = System.currentTimeMillis();
			return;
		}
		boolean mine = containsName(text, me);
		if (lower.startsWith("bed destruction") || lower.contains("bed was destroyed") || lower.contains("bed has been destroyed")) {
			if (mine && !lower.contains("your bed")) {
				beds++;
				sessionBeds++;
			}
			return;
		}
		if (isWin(lower, text, me)) {
			endGame(true);
			return;
		}
		if (isLoss(lower, text, me)) {
			endGame(false);
			return;
		}
		if (!mine || !looksLikeDeath(lower)) {
			return;
		}
		boolean finalKill = lower.contains("final kill");
		if (startsWithName(text, me)) {
			deaths++;
			sessionDeaths++;
			if (finalKill && mode != ServerPacks.Mode.BEDWARS) {
				endGame(false);
			}
		} else {
			if (finalKill) {
				finals++;
				sessionFinals++;
			} else {
				kills++;
				sessionKills++;
			}
		}
	}

	private static boolean looksLikeDeath(String lower) {
		return lower.matches(".*\\b(killed|slain|died|was shot|knocked|void|fell|blown up|pummeled|eliminated|murdered|destroyed|struck|"
				+ "thrown|squashed|burnt|burned|by|final kill|meet|doomed|tried to)\\b.*");
	}

	private boolean isWin(String lower, String text, String me) {
		return lower.matches("^\\s*(victory!?|you won.*|you win.*|#1 victory.*)$")
				|| (lower.matches("^\\s*winners?\\s*[-:].*") && containsName(text, me))
				|| (startsWithName(text, me) && lower.matches(".*\\bwon (the )?(game|duel|match|round).*"));
	}

	private boolean isLoss(String lower, String text, String me) {
		return lower.matches("^\\s*(game over!?|you lost.*|you died!.*|defeat!?)$")
				|| (lower.matches("^\\s*winners?\\s*[-:].*") && !containsName(text, me));
	}

	private void endGame(boolean won) {
		if (gameOver) {
			return;
		}
		gameOver = true;
		if (won) {
			wins++;
			streak++;
			bestStreak = Math.max(bestStreak, streak);
		} else {
			losses++;
			streak = 0;
		}
	}

	private static boolean containsName(String text, String name) {
		return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])").matcher(text).find();
	}

	private static boolean startsWithName(String text, String name) {
		String stripped = text.replaceFirst("^(\\[[^\\]]*\\]\\s*)+", "").strip();
		return stripped.startsWith(name + " ") || stripped.startsWith(name + "'");
	}

	// ---- reads ----

	public synchronized ServerPacks.Pack pack() {
		return pack;
	}

	public synchronized ServerPacks.Mode mode() {
		return mode;
	}

	public synchronized boolean online() {
		return address != null;
	}

	public synchronized List<Team> teams() {
		return teams;
	}

	public synchronized List<Timer> timers() {
		return List.copyOf(timers.values());
	}

	public synchronized List<Generator> generators() {
		return generators;
	}

	/** "Label: value" lines from the sidebar (Kills: 3, Players left: 8, Opponent: Steve...). */
	public synchronized String sidebarValue(String... labels) {
		for (String label : labels) {
			for (Map.Entry<String, String> e : sidebarValues.entrySet()) {
				if (e.getKey().equalsIgnoreCase(label)) {
					return e.getValue();
				}
			}
		}
		return null;
	}

	public synchronized int kills() {
		return kills;
	}

	public synchronized int finals() {
		return finals;
	}

	public synchronized int beds() {
		return beds;
	}

	public synchronized int deaths() {
		return deaths;
	}

	public synchronized long gameStartedAt() {
		return gameStartedAt;
	}

	public synchronized int wins() {
		return wins;
	}

	public synchronized int losses() {
		return losses;
	}

	public synchronized int streak() {
		return streak;
	}

	public synchronized int bestStreak() {
		return bestStreak;
	}

	public synchronized int sessionKills() {
		return sessionKills;
	}

	public synchronized int sessionFinals() {
		return sessionFinals;
	}

	public synchronized int sessionBeds() {
		return sessionBeds;
	}

	public synchronized int sessionDeaths() {
		return sessionDeaths;
	}

	public synchronized String serverName() {
		return pack.name();
	}
}
