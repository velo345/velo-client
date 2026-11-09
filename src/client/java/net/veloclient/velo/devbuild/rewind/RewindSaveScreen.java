package net.veloclient.velo.devbuild.rewind;

/**
 * After a recording stops it is already saved (named after its date) - this asks for a better name, or throws it away.
 * Opened in game when you stop with F6, or on the title / server list right after you leave a world while recording.
 */
//? if <26.2 {
public final class RewindSaveScreen {

	private RewindSaveScreen() {
	}

	public static void pump() {
	}
}
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

public final class RewindSaveScreen extends Screen {

	private static Path pending;

	private final Path file;
	private final Screen parent;
	private final boolean renaming;
	private net.minecraft.client.gui.components.EditBox name;

	private RewindSaveScreen(Path file, Screen parent, boolean renaming) {
		super(Component.literal("Save Velo Rewind"));
		this.file = file;
		this.parent = parent;
		this.renaming = renaming;
	}

	// The library's rename.
	public static Screen rename(Path file, Screen parent) {
		return new RewindSaveScreen(file, parent, true);
	}

	// Saved: ask for its name now if a screen can show it, otherwise as soon as one can (leaving a world goes through a few
	// loading screens first).
	public static void open(Path file) {
		pending = file;
		pump();
	}

	public static void pump() {
		if (pending == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Screen screen = mc.gui.screen();
		boolean inGame = mc.level != null && mc.player != null;
		boolean settled = inGame ? screen == null || screen instanceof net.minecraft.client.gui.screens.PauseScreen
				: screen instanceof net.minecraft.client.gui.screens.TitleScreen
						|| screen instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
						|| screen instanceof net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
						|| screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen;
		if (!settled) {
			return;
		}
		Path file = pending;
		pending = null;
		if (Files.exists(file)) {
			mc.setScreen(new RewindSaveScreen(file, screen, false));
		}
	}

	private record Hit(int x, int y, int w, int h, Runnable action) {
	}

	private final java.util.List<Hit> hits = new java.util.ArrayList<>();
	private static final int CARD_W = 280;
	private static final int CARD_H = 132;

	@Override
	protected void init() {
		int x = (width - CARD_W) / 2;
		int y = (height - CARD_H) / 2;
		name = new net.minecraft.client.gui.components.EditBox(font, x + 22, y + 64, CARD_W - 44, 12, Component.literal("Name"));
		name.setBordered(false);
		name.setMaxLength(80);
		name.setValue(file.getFileName().toString().replaceFirst("\\.vrewind$", ""));
		addRenderableWidget(name);
		setInitialFocus(name);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		g.fill(0, 0, width, height, 0x9A0A0A0D);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		int x = (width - CARD_W) / 2;
		int y = (height - CARD_H) / 2;
		net.veloclient.velo.client.gui.title.MenuKit.panel(g, x, y, CARD_W, CARD_H, 12);
		// title row: a red record dot (or the pencil of a rename) and the heading
		net.veloclient.velo.client.gui.widget.VeloDraw.fillCircle(g, x + 22f, y + 22f, 9f, renaming ? 0x1AFFFFFF : 0x2EFF5A5F);
		net.veloclient.velo.client.gui.title.MenuKit.icon(g, renaming ? "settings" : "record_start", x + 18, y + 18, 8, renaming ? 0xFFC9C9D1 : 0xFFFF5A5F);
		net.veloclient.velo.client.gui.title.MenuKit.text(g, font, net.veloclient.velo.client.gui.title.MenuKit.display(renaming ? "Rename recording" : "Recording saved"),
				x + 38, y + 14, net.veloclient.velo.client.gui.title.MenuKit.TEXT);
		String info = sizeInfo();
		net.veloclient.velo.client.gui.title.MenuKit.text(g, font, net.veloclient.velo.client.gui.title.MenuKit.mono(info), x + 38, y + 27,
				net.veloclient.velo.client.gui.title.MenuKit.TEXT_3);
		net.veloclient.velo.client.gui.title.MenuKit.text(g, font, net.veloclient.velo.client.gui.title.MenuKit.ui("Name"), x + 16, y + 46,
				net.veloclient.velo.client.gui.title.MenuKit.TEXT_2);
		// the name field
		boolean focused = name.isFocused();
		net.veloclient.velo.client.gui.widget.VeloDraw.fillRounded(g, x + 14, y + 57, CARD_W - 28, 24, 7, 0x0FFFFFFF);
		net.veloclient.velo.client.gui.widget.VeloDraw.strokeRounded(g, x + 14, y + 57, CARD_W - 28, 24, 7,
				focused ? net.veloclient.velo.client.gui.title.MenuKit.accent() : net.veloclient.velo.client.gui.title.MenuKit.HAIRLINE);
		super.extractRenderState(g, mouseX, mouseY, delta);
		// buttons
		hits.clear();
		int by = y + CARD_H - 34;
		int bw = (CARD_W - 28 - 8) / 2;
		button(g, x + 14, by, bw, renaming ? "Cancel" : "Delete", renaming ? "close" : "trash", false, !renaming, mouseX, mouseY,
				renaming ? this::onClose : this::delete);
		button(g, x + 14 + bw + 8, by, bw, "Save", "download", true, false, mouseX, mouseY, this::save);
	}

	private String sizeInfo() {
		try {
			long bytes = Files.size(file);
			return String.format("%.1f MB  ·  velo-rewind/", bytes / 1048576.0);
		} catch (Exception e) {
			return "velo-rewind/";
		}
	}

	private void button(GuiGraphicsExtractor g, int x, int y, int w, String text, String icon, boolean primary, boolean danger, int mouseX,
			int mouseY, Runnable action) {
		int h = 22;
		float hover = net.veloclient.velo.client.gui.title.MenuKit.hover("rewind-save-" + text,
				net.veloclient.velo.client.gui.title.MenuKit.inside(mouseX, mouseY, x, y, w, h));
		int accent = net.veloclient.velo.client.gui.title.MenuKit.accent();
		if (primary) {
			net.veloclient.velo.client.gui.widget.VeloDraw.shadow(g, x, y, w, h, 7, 8, 2, (accent & 0xFFFFFF) | 0x40000000);
			net.veloclient.velo.client.gui.widget.VeloDraw.fillRounded(g, x, y, w, h, 7,
					net.veloclient.velo.client.gui.widget.VeloAnim.lerpArgb(accent, net.veloclient.velo.client.gui.widget.VeloAnim.lerpArgb(accent, 0xFFFFFFFF, 0.14f), hover));
		} else {
			net.veloclient.velo.client.gui.widget.VeloDraw.fillRounded(g, x, y, w, h, 7,
					net.veloclient.velo.client.gui.widget.VeloAnim.lerpArgb(0x10FFFFFF, danger ? 0x33FF5A5F : 0x24FFFFFF, hover));
			net.veloclient.velo.client.gui.widget.VeloDraw.strokeRounded(g, x, y, w, h, 7,
					net.veloclient.velo.client.gui.widget.VeloAnim.lerpArgb(net.veloclient.velo.client.gui.title.MenuKit.HAIRLINE, danger ? 0xFFFF5A5F : 0x60FFFFFF, hover));
		}
		Component label = net.veloclient.velo.client.gui.title.MenuKit.uiBold(text);
		int lw = net.veloclient.velo.client.gui.title.MenuKit.width(font, label) + 12;
		int lx = x + (w - lw) / 2;
		int color = primary ? net.veloclient.velo.client.gui.title.MenuKit.onAccent() : danger ? net.veloclient.velo.client.gui.title.MenuKit.RED_TEXT
				: net.veloclient.velo.client.gui.title.MenuKit.TEXT;
		net.veloclient.velo.client.gui.title.MenuKit.icon(g, icon, lx, y + 7, 8, color);
		net.veloclient.velo.client.gui.title.MenuKit.text(g, font, label, lx + 12, y + (h - net.veloclient.velo.client.gui.title.MenuKit.H_UI) / 2, color);
		hits.add(new Hit(x, y, w, h, action));
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		for (Hit h : java.util.List.copyOf(hits)) {
			if (event.x() >= h.x() && event.x() < h.x() + h.w() && event.y() >= h.y() && event.y() < h.y() + h.h()) {
				h.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (event.key() == net.veloclient.velo.client.util.Keys.ENTER) {
			save();
			return true;
		}
		return super.keyPressed(event);
	}

	private void save() {
		String wanted = name.getValue().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
		Path target = file;
		if (!wanted.isEmpty() && !(wanted + ".vrewind").equals(file.getFileName().toString())) {
			target = file.resolveSibling(wanted + ".vrewind");
			for (int i = 2; Files.exists(target); i++) {
				target = file.resolveSibling(wanted + " (" + i + ").vrewind");
			}
			try {
				Files.move(file, target);
			} catch (Exception e) {
				target = file;
			}
		}
		say("Velo Rewind saved: " + target.getFileName());
		onClose();
	}

	private void delete() {
		try {
			Files.deleteIfExists(file);
		} catch (Exception ignored) {
		}
		say("Velo Rewind recording deleted");
		onClose();
	}

	private static void say(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal(text));
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
*///?}
