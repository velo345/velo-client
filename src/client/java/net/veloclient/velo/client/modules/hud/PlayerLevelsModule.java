package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.serverpacks.GameSession;
import net.veloclient.velo.client.serverpacks.ServerData;
import net.veloclient.velo.client.serverpacks.ServerPacks;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Who you're up against: the players in your game ranked by the level / stars / ELO the server
 * shows in its tab list (Hypixel Bed Wars stars, practice-server ELO, network levels...), with
 * the strongest highlighted and your own spot marked. Reads only what the server already shows
 * everyone - no API keys, nothing sent anywhere.
 */
public final class PlayerLevelsModule extends AbstractModule implements HudModule, Configurable {

	private record Entry(String name, int level, int ping, boolean you) {
	}

	private static final int WIDTH = 150;
	private static final int ROW_H = 10;

	private final HudPosition position = new HudPosition(0.80f, 0.30f);
	private double maxRows = 8;
	private double highlightAbove = 300;
	private boolean onlyInGames = true;
	private boolean showPing = false;
	private List<Entry> cached = List.of();
	private long cachedAt;
	private int lastRows = 4;

	public PlayerLevelsModule() {
		super("player-levels", "Player Levels", "Ranks the players in your game by the stars / level / ELO shown in the tab list.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	private List<Entry> entries() {
		long now = System.currentTimeMillis();
		if (now - cachedAt < 1000) {
			return cached;
		}
		cachedAt = now;
		ServerPacks.Pack pack = GameSession.get().pack();
		String me = ServerData.localName();
		List<Entry> list = new ArrayList<>();
		for (ServerData.TabEntry tab : ServerData.tabList()) {
			int level = ServerPacks.level(pack, tab.shown());
			if (level >= 0) {
				list.add(new Entry(tab.name(), level, tab.ping(), tab.name().equals(me)));
			}
		}
		list.sort(Comparator.comparingInt(Entry::level).reversed());
		cached = list;
		return list;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		boolean editing = net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;
		GameSession s = GameSession.get();
		if (!editing && (!s.online() || onlyInGames && s.mode() == ServerPacks.Mode.LOBBY)) {
			return;
		}
		List<Entry> entries = editing && !s.online()
				? List.of(new Entry("Technoblade", 812, 40, false), new Entry("You", 230, 25, true), new Entry("Steve", 12, 90, false))
				: entries();
		if (entries.isEmpty()) {
			lastRows = 1;
			return;
		}
		int limit = (int) Math.round(maxRows);
		int youIndex = -1;
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).you()) {
				youIndex = i;
			}
		}
		List<Integer> shown = new ArrayList<>();
		for (int i = 0; i < entries.size() && shown.size() < limit; i++) {
			shown.add(i);
		}
		if (youIndex >= limit && limit > 0) {
			shown.set(shown.size() - 1, youIndex);
		}
		MinecraftClient client = MinecraftClient.getInstance();
		String label = s.pack().levelLabel();
		int height = (shown.size() + 1) * ROW_H + 6;
		lastRows = shown.size() + 1;
		VeloDraw.fillRounded(context, x, y, WIDTH, height, 5, 0x90101016);
		context.drawTextWithShadow(client.textRenderer, "Players by " + label, x + 5, y + 4, 0xFFFFFFFF);
		int ry = y + 4 + ROW_H;
		for (int index : shown) {
			Entry e = entries.get(index);
			int color = e.you() ? 0xFF8FB8FF : e.level() >= highlightAbove ? 0xFFFF5555 : 0xFFDDDDDD;
			String name = (index + 1) + ". " + net.veloclient.velo.client.gui.widget.VeloUi.trim(e.name(), WIDTH - 60);
			context.drawTextWithShadow(client.textRenderer, name, x + 5, ry, color);
			String value = e.level() + (showPing ? "  " + e.ping() + "ms" : "");
			int w = client.textRenderer.getWidth(value);
			context.drawTextWithShadow(client.textRenderer, value, x + WIDTH - 5 - w, ry, color);
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
				new ConfigField.SliderField("Players Shown", 3, 16, () -> maxRows, v -> maxRows = Math.round(v), v -> String.valueOf((int) Math.round(v))),
				new ConfigField.SliderField("Highlight Above", 10, 2000, () -> highlightAbove, v -> highlightAbove = Math.round(v / 10) * 10,
						v -> String.valueOf((int) Math.round(v))),
				new ConfigField.ToggleField("Only In Games (not lobbies)", () -> onlyInGames, v -> onlyInGames = v),
				new ConfigField.ToggleField("Show Ping", () -> showPing, v -> showPing = v));
	}
}
