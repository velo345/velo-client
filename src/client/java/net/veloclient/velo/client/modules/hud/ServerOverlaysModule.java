package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.serverpacks.GameSession;
import net.veloclient.velo.client.serverpacks.ServerPacks;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One overlay that adapts to the server and game you're in: Bed Wars teams (bed alive, players
 * left), diamond / emerald generator countdowns, the server's event timers ticking smoothly,
 * your kills / final kills / beds / deaths this game, players left and opponent in SkyWars and
 * duels, and wins, losses and win streak this session. Recognises Hypixel, Minemen, PvP Land,
 * PvPClub, BlocksMC, Pika, Jartex, CubeCraft and more by name - and the game modes on any server.
 */
public final class ServerOverlaysModule extends AbstractModule implements HudModule, Configurable {

	private record Row(String label, String value, int color) {
	}

	private static volatile boolean active;
	private static final int WIDTH = 150;
	private static final int ROW_H = 11;

	private final HudPosition position = new HudPosition(0.01f, 0.40f);
	private boolean showHeader = true;
	private boolean showGame = true;
	private boolean showTeams = true;
	private boolean showGenerators = true;
	private boolean showTimers = true;
	private boolean showSession = true;
	private boolean showInLobby = false;
	private boolean background = true;
	private int lastRows = 6;

	public ServerOverlaysModule() {
		super("server-overlays", "Server Overlays", "Bed Wars teams & generator timers, final kills, event timers, win streak - adapts to the server and game.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	/** Whether the session tracker should parse sidebars and holograms at all. */
	public static boolean active() {
		return active;
	}

	@Override
	public void onEnable() {
		active = true;
	}

	@Override
	public void onDisable() {
		active = false;
	}

	@Override
	public HudPosition position() {
		return position;
	}

	private List<Row> rows(boolean editing) {
		GameSession s = GameSession.get();
		List<Row> rows = new ArrayList<>();
		if (!s.online() && !editing) {
			return rows;
		}
		ServerPacks.Mode mode = editing && !s.online() ? ServerPacks.Mode.BEDWARS : s.mode();
		if (mode == ServerPacks.Mode.LOBBY && !showInLobby && !editing) {
			return rows;
		}
		if (showHeader) {
			String server = s.serverName().isBlank() ? "Server" : s.serverName();
			rows.add(new Row(server, mode.label, 0xFFFFFFFF));
		}
		if (editing && !s.online()) {
			rows.add(new Row("Kills / Finals", "3 / 2", 0xFFFFFFFF));
			rows.add(new Row("Beds", "1", 0xFFFFFFFF));
			rows.add(new Row("R Red", "✔ YOU", 0xFFFF5555));
			rows.add(new Row("Diamond II", "0:12", 0xFF55FFFF));
			rows.add(new Row("Win streak", "4", 0xFF6FE39A));
			return rows;
		}
		if (showGame && mode != ServerPacks.Mode.LOBBY) {
			switch (mode) {
				case BEDWARS -> {
					rows.add(new Row("Kills / Finals", s.kills() + " / " + s.finals(), 0xFFFFFFFF));
					rows.add(new Row("Beds broken", String.valueOf(s.beds()), 0xFFFFFFFF));
					rows.add(new Row("Deaths", String.valueOf(s.deaths()), 0xFFB8B8C8));
				}
				case SKYWARS, EGGWARS, UHC -> {
					rows.add(new Row("Kills", String.valueOf(Math.max(s.kills() + s.finals(), parse(s.sidebarValue("Kills")))), 0xFFFFFFFF));
					String left = s.sidebarValue("Players left", "Players", "Alive", "Remaining");
					if (left != null) {
						rows.add(new Row("Players left", left, 0xFFFFC53D));
					}
				}
				case DUELS, BRIDGE, PRACTICE -> {
					String opponent = s.sidebarValue("Opponent", "Opponents", "Fighting", "Against");
					if (opponent != null) {
						rows.add(new Row("Opponent", opponent, 0xFFFF7777));
					}
					String theirPing = s.sidebarValue("Their Ping", "Opponent Ping", "Their ping");
					if (theirPing != null) {
						rows.add(new Row("Their ping", theirPing, 0xFFB8B8C8));
					}
					rows.add(new Row("Kills", String.valueOf(s.kills() + s.finals()), 0xFFFFFFFF));
				}
				default -> rows.add(new Row("Kills / Deaths", (s.kills() + s.finals()) + " / " + s.deaths(), 0xFFFFFFFF));
			}
			if (s.gameStartedAt() > 0 && mode != ServerPacks.Mode.LOBBY) {
				long seconds = (System.currentTimeMillis() - s.gameStartedAt()) / 1000;
				rows.add(new Row("Game time", String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60), 0xFFB8B8C8));
			}
		}
		if (showTeams && mode == ServerPacks.Mode.BEDWARS) {
			for (GameSession.Team team : s.teams()) {
				int color = teamColor(team.name());
				String status = team.status().equals("✔") || team.status().equals("✓") ? "bed ✔"
						: team.alive() ? team.status() + " left" : "out";
				rows.add(new Row(team.letter() + " " + team.name() + (team.you() ? " (you)" : ""), status,
						team.alive() ? color : 0xFF6A6A72));
			}
		}
		if (showGenerators && mode == ServerPacks.Mode.BEDWARS) {
			GameSession.Generator diamond = null;
			GameSession.Generator emerald = null;
			for (GameSession.Generator g : s.generators()) {
				if (g.kind().equals("Diamond") && diamond == null) {
					diamond = g;
				} else if (g.kind().equals("Emerald") && emerald == null) {
					emerald = g;
				}
			}
			if (diamond != null) {
				rows.add(new Row("Diamond " + diamond.tier(), diamond.seconds() + "s", 0xFF55FFFF));
			}
			if (emerald != null) {
				rows.add(new Row("Emerald " + emerald.tier(), emerald.seconds() + "s", 0xFF55FF55));
			}
		}
		if (showTimers) {
			for (GameSession.Timer timer : s.timers()) {
				int left = timer.secondsLeft();
				rows.add(new Row(timer.label(), String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60), left <= 10 ? 0xFFFF5555 : 0xFFFFC53D));
			}
		}
		if (showSession && (s.wins() + s.losses() > 0 || s.sessionKills() + s.sessionFinals() > 0)) {
			rows.add(new Row("Session W / L", s.wins() + " / " + s.losses(), 0xFFB8B8C8));
			if (s.streak() > 0 || s.bestStreak() > 0) {
				rows.add(new Row("Win streak", s.streak() + (s.bestStreak() > s.streak() ? " (best " + s.bestStreak() + ")" : ""), 0xFF6FE39A));
			}
			if (mode == ServerPacks.Mode.BEDWARS) {
				int deaths = Math.max(1, s.sessionDeaths());
				rows.add(new Row("Session FKDR", String.format(Locale.ROOT, "%.2f", s.sessionFinals() / (double) deaths), 0xFFB8B8C8));
			}
		}
		return rows;
	}

	private static int parse(String value) {
		if (value == null) {
			return 0;
		}
		try {
			return Integer.parseInt(value.replaceAll("[^0-9]", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static int teamColor(String name) {
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "red" -> 0xFFFF5555;
			case "blue" -> 0xFF5599FF;
			case "green" -> 0xFF55FF55;
			case "yellow" -> 0xFFFFFF55;
			case "aqua", "cyan" -> 0xFF55FFFF;
			case "white" -> 0xFFFFFFFF;
			case "pink" -> 0xFFFF88CC;
			case "gray", "grey" -> 0xFFAAAAAA;
			case "orange" -> 0xFFFFAA33;
			case "purple" -> 0xFFAA55FF;
			default -> 0xFFDDDDDD;
		};
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		boolean editing = net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;
		List<Row> rows = rows(editing);
		lastRows = Math.max(1, rows.size());
		if (rows.isEmpty()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		int height = rows.size() * ROW_H + 6;
		if (background) {
			VeloDraw.fillRounded(context, x, y, WIDTH, height, 5, 0x90101016);
		}
		int ry = y + 4;
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			boolean header = i == 0 && showHeader;
			String label = net.veloclient.velo.client.gui.widget.VeloUi.trim(row.label(), WIDTH / 2 + 10);
			context.drawTextWithShadow(client.textRenderer, label, x + 5, ry, header ? 0xFFFFFFFF : 0xFFB8B8C8);
			int w = client.textRenderer.getWidth(row.value());
			context.drawTextWithShadow(client.textRenderer, row.value(), x + WIDTH - 5 - w, ry, header ? 0xFF8FB8FF : row.color());
			if (header && rows.size() > 1) {
				context.fill(x + 4, ry + 10, x + WIDTH - 4, ry + 11, 0x30FFFFFF);
			}
			ry += ROW_H;
		}
	}

	@Override
	public int width() {
		return WIDTH;
	}

	@Override
	public int height() {
		return lastRows * ROW_H + 6;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Server & Mode Header", () -> showHeader, v -> showHeader = v),
				new ConfigField.ToggleField("Kills, Finals, Beds, Deaths", () -> showGame, v -> showGame = v),
				new ConfigField.ToggleField("Bed Wars Teams", () -> showTeams, v -> showTeams = v),
				new ConfigField.ToggleField("Generator Timers", () -> showGenerators, v -> showGenerators = v),
				new ConfigField.ToggleField("Event Timers", () -> showTimers, v -> showTimers = v),
				new ConfigField.ToggleField("Session Wins & Streak", () -> showSession, v -> showSession = v),
				new ConfigField.ToggleField("Show In Lobbies", () -> showInLobby, v -> showInLobby = v),
				new ConfigField.ToggleField("Background", () -> background, v -> background = v));
	}
}
