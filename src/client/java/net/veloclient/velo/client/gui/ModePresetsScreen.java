package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.modules.servertools.GameModePresetsModule;
import net.veloclient.velo.client.serverpacks.ServerPacks;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.profile.VeloProfileManager;

import java.util.ArrayList;
import java.util.List;

/** Picks which Velo profile each game mode loads (Game Mode Presets module). Click a row to cycle through your profiles. */
public final class ModePresetsScreen extends VeloWindow {

	private static final ServerPacks.Mode[] MODES = {ServerPacks.Mode.BEDWARS, ServerPacks.Mode.SKYWARS, ServerPacks.Mode.DUELS,
			ServerPacks.Mode.PRACTICE, ServerPacks.Mode.BRIDGE, ServerPacks.Mode.KITPVP, ServerPacks.Mode.UHC, ServerPacks.Mode.EGGWARS,
			ServerPacks.Mode.BUILD_BATTLE, ServerPacks.Mode.MURDER_MYSTERY, ServerPacks.Mode.PIT, ServerPacks.Mode.SKYBLOCK,
			ServerPacks.Mode.PARKOUR, ServerPacks.Mode.LOBBY};

	private final List<VeloUi.Hit> hits = new ArrayList<>();

	public ModePresetsScreen(Screen parent) {
		super(Text.literal("Game Mode Presets"), 420, 360);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		GameModePresetsModule module = GameModePresetsModule.instance();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		context.drawTextWithShadow(this.textRenderer, "Click a game mode to pick the profile it loads.", x, y, VeloUi.muted());
		int rowY = y + 16;
		int columnW = (width - 8) / 2;
		for (int i = 0; i < MODES.length; i++) {
			ServerPacks.Mode mode = MODES[i];
			int rx = x + (i % 2) * (columnW + 8);
			int ry = rowY + (i / 2) * 26;
			boolean hover = VeloUi.inside(mouseX, mouseY, rx, ry, columnW, 22);
			VeloDraw.fillRounded(context, rx, ry, columnW, 22, 5, hover ? VeloUi.withAlpha(ThemeManager.active().accentStart(), 0x40) : VeloStyle.card());
			context.drawTextWithShadow(this.textRenderer, mode.label, rx + 6, ry + 7, ThemeManager.active().text());
			String preset = module.presetFor(mode);
			String shown = VeloUi.trim(preset, columnW - this.textRenderer.getWidth(mode.label) - 20);
			int color = preset.equals(GameModePresetsModule.NONE) ? VeloUi.muted() : 0xFF8FB8FF;
			context.drawTextWithShadow(this.textRenderer, shown, rx + columnW - 6 - this.textRenderer.getWidth(shown), ry + 7, color);
			hits.add(new VeloUi.Hit(rx, ry, columnW, 22, () -> cycle(module, mode)));
		}
	}

	private static void cycle(GameModePresetsModule module, ServerPacks.Mode mode) {
		List<String> options = new ArrayList<>();
		options.add(GameModePresetsModule.NONE);
		options.addAll(VeloProfileManager.listProfileNames());
		int index = options.indexOf(module.presetFor(mode));
		module.setPreset(mode, options.get((index + 1) % options.size()));
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}
}
