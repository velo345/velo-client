package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.title.MenuKit;

import java.util.ArrayList;
import java.util.List;

/**
 * A right-click menu for any Velo window (2026-11, like the launcher): one click does the obvious thing, everything else
 * (edit, delete, customise, the Store) lives here. Open with {@link #open}; {@link net.veloclient.velo.client.gui.window.VeloWindow}
 * draws it on top and gives it every click first. A click outside or Escape closes it.
 */
public final class VeloContextMenu {

	public record Entry(String label, boolean danger, Runnable action) {
	}

	private static final int ROW = 16;
	private static final int PAD = 4;
	private static List<Entry> entries = List.of();
	private static int x;
	private static int y;
	private static int width;
	private static Object owner;

	private VeloContextMenu() {
	}

	public static void open(Object screen, double mouseX, double mouseY, List<Entry> items) {
		if (items.isEmpty()) {
			return;
		}
		var tr = MinecraftClient.getInstance().textRenderer;
		int w = 90;
		for (Entry e : items) {
			w = Math.max(w, MenuKit.width(tr, MenuKit.ui(e.label())) + 24);
		}
		entries = new ArrayList<>(items);
		width = w;
		int h = items.size() * ROW + PAD * 2;
		int sw = screen instanceof net.minecraft.client.gui.screen.Screen sc ? sc.width : 10_000;
		int sh = screen instanceof net.minecraft.client.gui.screen.Screen sc2 ? sc2.height : 10_000;
		x = (int) Math.min(mouseX, sw - w - 2);
		y = (int) Math.min(mouseY, sh - h - 2);
		owner = screen;
		net.veloclient.velo.client.sound.VeloUiSounds.click(1.1f);
	}

	public static boolean isOpen(Object screen) {
		return owner == screen && !entries.isEmpty();
	}

	public static void close() {
		entries = List.of();
		owner = null;
	}

	public static void render(Object screen, DrawContext context, int mouseX, int mouseY) {
		if (!isOpen(screen)) {
			return;
		}
		var tr = MinecraftClient.getInstance().textRenderer;
		int h = entries.size() * ROW + PAD * 2;
		VeloDraw.shadow(context, x, y, width, h, 6, 10, 3, 0x90000000);
		VeloDraw.fillRounded(context, x, y, width, h, 6, 0xFA141011);
		VeloDraw.strokeRounded(context, x, y, width, h, 6, 0x22FFFFFF);
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			int ry = y + PAD + i * ROW;
			boolean hover = mouseX >= x && mouseX < x + width && mouseY >= ry && mouseY < ry + ROW;
			if (hover) {
				VeloDraw.fillRounded(context, x + 3, ry, width - 6, ROW, 4, e.danger() ? 0x30E5484D : 0x1CFFFFFF);
			}
			MenuKit.text(context, tr, MenuKit.ui(e.label()), x + 10, ry + 4, e.danger() ? 0xFFFF6B6F : hover ? 0xFFFFFFFF : 0xFFEDE6E6);
		}
	}

	/** A press while the menu is open: runs the entry under the mouse (or just closes). Always consumed. */
	public static boolean click(Object screen, double mouseX, double mouseY) {
		if (!isOpen(screen)) {
			return false;
		}
		List<Entry> list = entries;
		close();
		for (int i = 0; i < list.size(); i++) {
			int ry = y + PAD + i * ROW;
			if (mouseX >= x && mouseX < x + width && mouseY >= ry && mouseY < ry + ROW) {
				net.veloclient.velo.client.sound.VeloUiSounds.click(1.0f);
				list.get(i).action().run();
				break;
			}
		}
		return true;
	}
}
