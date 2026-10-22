package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.hosting.WorldHosting;
import net.veloclient.velo.client.hosting.WorldMods;

import java.util.ArrayList;
import java.util.List;

/**
 * Before joining a hosted world whose world mods you don't have (or have in another version): what's different, and
 * "Relaunch with matching mods" (the launcher builds a matching profile and joins), "Join anyway" or "Cancel".
 */
public final class WorldModsScreen extends VeloWindow {

	private record Hit(int x, int y, int w, int h, Runnable action) {
	}

	private final String worldId;
	private final String hostName;
	private final String version;
	private final List<WorldMods.Mod> needed;
	private final WorldMods.Diff diff;
	private final List<Hit> hits = new ArrayList<>();
	private String status = "";

	public WorldModsScreen(Screen parent, String worldId, String hostName, String version, List<WorldMods.Mod> needed, WorldMods.Diff diff) {
		super(Text.literal("Mods don't match"), 460, 360);
		returnTo(parent);
		this.worldId = worldId;
		this.hostName = hostName;
		this.version = version;
		this.needed = needed;
		this.diff = diff;
	}

	@Override
	protected void layoutContent() {
		// Drawn by hand.
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		int x = contentX();
		int y = contentY();
		int w = contentWidth();
		for (Text line : MenuKit.wrap(this.textRenderer, hostName + "'s world uses mods that change the world. Without them you'd be turned away"
				+ " or things would be missing.", MenuKit::ui, w)) {
			MenuKit.text(context, this.textRenderer, line, x, y, MenuKit.TEXT_2);
			y += 11;
		}
		y += 6;
		int listBottom = contentBottom() - 34;
		for (WorldMods.Mod mod : diff.missing()) {
			if (y + 12 > listBottom) {
				break;
			}
			VeloDraw.fillCircle(context, x + 4f, y + 4f, 2.5f, 0xFFFF5A5F);
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui((mod.name() == null ? mod.id() : mod.name()) + " "
					+ mod.version() + " - you don't have it"), w - 12), x + 12, y, MenuKit.TEXT);
			y += 13;
		}
		for (String[] mod : diff.different()) {
			if (y + 12 > listBottom) {
				break;
			}
			VeloDraw.fillCircle(context, x + 4f, y + 4f, 2.5f, MenuKit.GOLD);
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(mod[0] + " - you have " + mod[1] + ", the world uses " + mod[2]),
					w - 12), x + 12, y, MenuKit.TEXT);
			y += 13;
		}
		boolean canRelaunch = WorldMods.launchedByVelo();
		if (!canRelaunch) {
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.small("START VELO CLIENT FROM THE VELO LAUNCHER TO RELAUNCH WITH THEM"),
					w), x, listBottom + 2, MenuKit.TEXT_3);
		}
		if (!status.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(status), w), x, listBottom + 12, MenuKit.RED_TEXT);
		}
		int by = contentBottom() - 20;
		int bx = x + w;
		bx -= 64;
		button(context, bx, by, 64, 20, "Cancel", false, mouseX, mouseY, this::requestClose);
		bx -= 98;
		button(context, bx, by, 92, 20, "Join anyway", false, mouseX, mouseY, () -> WorldHosting.join(worldId, true, error -> status = error == null ? "" : error));
		if (canRelaunch) {
			bx -= 178;
			button(context, bx, by, 172, 20, "Relaunch with matching mods", true, mouseX, mouseY, () -> {
				try {
					WorldHosting.relaunchFor(worldId, hostName, version, needed);
				} catch (Exception e) {
					status = "Couldn't relaunch: " + e.getMessage();
				}
			});
		}
	}

	private void button(DrawContext context, int x, int y, int w, int h, String label, boolean primary, int mouseX, int mouseY, Runnable action) {
		boolean hovered = MenuKit.inside(mouseX, mouseY, x, y, w, h);
		VeloDraw.fillRounded(context, x, y, w, h, 7, primary ? MenuKit.accent() : hovered ? 0x1AFFFFFF : 0x0AFFFFFF);
		if (!primary) {
			VeloDraw.strokeRounded(context, x, y, w, h, 7, hovered ? 0x38FFFFFF : 0x17FFFFFF);
		}
		Text text = MenuKit.uiBold(label);
		MenuKit.text(context, this.textRenderer, text, x + (w - this.textRenderer.getWidth(text)) / 2, y + (h - 8) / 2, primary ? MenuKit.onAccent() : MenuKit.TEXT);
		hits.add(new Hit(x, y, w, h, action));
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (Hit hit : List.copyOf(hits)) {
			if (click.x() >= hit.x() && click.x() < hit.x() + hit.w() && click.y() >= hit.y() && click.y() < hit.y() + hit.h()) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}
}
