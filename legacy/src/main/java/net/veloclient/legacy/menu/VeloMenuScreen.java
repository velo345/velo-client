package net.veloclient.legacy.menu;

import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.gui.screen.Screen;
import net.veloclient.legacy.feature.LegacyModule;
import net.veloclient.legacy.feature.Modules;
import net.veloclient.legacy.feature.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * Velo's R-Shift menu on 1.8.9: category tabs, a card per module (click to switch it on/off, the dots open its
 * settings), and "Edit HUD" to drag the HUD elements around. Same dark Velo look as the modern versions, kept simple.
 */
public final class VeloMenuScreen extends Screen {

	static final int ACCENT = 0xFF4F7CFF;
	private static final String[] TABS = {"All", "HUD", "PvP", "Utility", "Performance"};
	private static String tab = "All";
	private LegacyModule settingsOf;
	private int scroll;
	private Setting.Slider dragging;
	private int dragX;
	private int dragW;
	private final List<Runnable> clicks = new ArrayList<>();
	private final List<int[]> boxes = new ArrayList<>();

	private int px;
	private int py;
	private int pw;
	private int ph;

	@Override
	public boolean shouldPauseGame() {
		return false;
	}

	private void hit(int x, int y, int w, int h, Runnable action) {
		boxes.add(new int[] {x, y, w, h});
		clicks.add(action);
	}

	private static boolean over(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		boxes.clear();
		clicks.clear();
		fill(0, 0, width, height, 0x80000000);
		pw = Math.min(420, width - 20);
		ph = Math.min(270, height - 20);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		fill(px, py, px + pw, py + ph, 0xF0101016);
		fill(px, py, px + pw, py + 1, 0x30FFFFFF);
		textRenderer.drawWithShadow("Velo Client", px + 10, py + 9, 0xFFFFFFFF);
		textRenderer.drawWithShadow("1.8.9", px + 12 + textRenderer.getWidth("Velo Client"), py + 9, 0xFF8A8A95);
		// Edit HUD
		String edit = "Edit HUD";
		int ew = textRenderer.getWidth(edit) + 14;
		boolean eh = over(mouseX, mouseY, px + pw - ew - 8, py + 5, ew, 16);
		fill(px + pw - ew - 8, py + 5, px + pw - 8, py + 21, eh ? 0xFF6A90FF : ACCENT);
		textRenderer.drawWithShadow(edit, px + pw - ew - 1, py + 9, 0xFFFFFFFF);
		hit(px + pw - ew - 8, py + 5, ew, 16, () -> minecraft.openScreen(new HudEditScreen(this)));
		String locker = "Locker";
		int lw = textRenderer.getWidth(locker) + 14;
		int lx = px + pw - ew - 8 - 4 - lw;
		boolean lh = over(mouseX, mouseY, lx, py + 5, lw, 16);
		fill(lx, py + 5, lx + lw, py + 21, lh ? 0x40FFFFFF : 0x20FFFFFF);
		textRenderer.drawWithShadow(locker, lx + 7, py + 9, 0xFFFFFFFF);
		hit(lx, py + 5, lw, 16, () -> minecraft.openScreen(new LockerScreen(this)));
		// tabs
		int tx = px + 10;
		for (String t : TABS) {
			int tw = textRenderer.getWidth(t) + 12;
			boolean active = t.equals(tab);
			boolean hov = over(mouseX, mouseY, tx, py + 26, tw, 15);
			fill(tx, py + 26, tx + tw, py + 41, active ? 0x40FFFFFF : hov ? 0x20FFFFFF : 0x10FFFFFF);
			textRenderer.drawWithShadow(t, tx + 6, py + 30, active ? 0xFFFFFFFF : 0xFFB4B4C0);
			String chosen = t;
			hit(tx, py + 26, tw, 15, () -> {
				tab = chosen;
				scroll = 0;
				settingsOf = null;
			});
			tx += tw + 4;
		}
		int top = py + 48;
		int bottom = py + ph - 8;
		if (settingsOf != null) {
			renderSettings(mouseX, mouseY, top, bottom);
			return;
		}
		List<LegacyModule> shown = new ArrayList<>();
		for (LegacyModule m : Modules.all()) {
			if (tab.equals("All") || m.category.equals(tab)) {
				shown.add(m);
			}
		}
		int cols = 2;
		int gap = 6;
		int cw = (pw - 20 - gap) / cols;
		int ch = 40;
		int rows = (shown.size() + cols - 1) / cols;
		int maxScroll = Math.max(0, rows * (ch + gap) - (bottom - top));
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		enableScissor(px, top, pw, bottom - top);
		for (int i = 0; i < shown.size(); i++) {
			LegacyModule m = shown.get(i);
			int cx = px + 10 + (i % cols) * (cw + gap);
			int cy = top + (i / cols) * (ch + gap) - scroll;
			if (cy + ch < top || cy > bottom) {
				continue;
			}
			boolean hov = over(mouseX, mouseY, cx, cy, cw, ch) && mouseY >= top && mouseY < bottom;
			fill(cx, cy, cx + cw, cy + ch, hov ? 0xFF1E1E26 : 0xFF17171D);
			fill(cx, cy, cx + 2, cy + ch, m.enabled() ? ACCENT : 0xFF3A3A44);
			textRenderer.drawWithShadow(m.name, cx + 8, cy + 7, 0xFFFFFFFF);
			List<String> desc = textRenderer.split(m.description, cw - 50);
			if (!desc.isEmpty()) {
				textRenderer.draw(desc.get(0) + (desc.size() > 1 ? "..." : ""), cx + 8, cy + 21, 0xFF8A8A95, false);
			}
			// switch
			int sx = cx + cw - 30;
			int sy = cy + 7;
			fill(sx, sy, sx + 22, sy + 10, m.enabled() ? ACCENT : 0xFF3A3A44);
			fill(m.enabled() ? sx + 13 : sx + 1, sy + 1, m.enabled() ? sx + 21 : sx + 9, sy + 9, 0xFFFFFFFF);
			if (!m.settings.isEmpty()) {
				boolean dots = over(mouseX, mouseY, cx + cw - 22, cy + 24, 16, 12);
				textRenderer.drawWithShadow("...", cx + cw - 20, cy + 24, dots ? 0xFFFFFFFF : 0xFF8A8A95);
				if (cy + ch <= bottom && cy >= top) {
					hit(cx + cw - 22, cy + 22, 18, 14, () -> settingsOf = m);
				}
			}
			if (cy >= top && cy + ch <= bottom) {
				hit(cx, cy, cw, ch, () -> m.setEnabled(!m.enabled()));
			}
		}
		disableScissor();
		if (maxScroll > 0) {
			int barH = Math.max(20, (bottom - top) * (bottom - top) / (bottom - top + maxScroll));
			int barY = top + (int) ((bottom - top - barH) * (scroll / (double) maxScroll));
			fill(px + pw - 5, barY, px + pw - 3, barY + barH, 0x60FFFFFF);
		}
	}

	private void renderSettings(int mouseX, int mouseY, int top, int bottom) {
		LegacyModule m = settingsOf;
		boolean back = over(mouseX, mouseY, px + 10, top, 50, 14);
		textRenderer.drawWithShadow("< Back", px + 10, top + 3, back ? 0xFFFFFFFF : 0xFFB4B4C0);
		hit(px + 10, top, 50, 14, () -> settingsOf = null);
		textRenderer.drawWithShadow(m.name, px + 70, top + 3, 0xFFFFFFFF);
		int y = top + 22;
		for (Setting s : m.settings) {
			fill(px + 10, y, px + pw - 10, y + 22, 0xFF17171D);
			textRenderer.drawWithShadow(s.name, px + 18, y + 7, 0xFFFFFFFF);
			if (s instanceof Setting.Toggle t) {
				int sx = px + pw - 42;
				fill(sx, y + 6, sx + 22, y + 16, t.value ? ACCENT : 0xFF3A3A44);
				fill(t.value ? sx + 13 : sx + 1, y + 7, t.value ? sx + 21 : sx + 9, y + 15, 0xFFFFFFFF);
				hit(px + 10, y, pw - 20, 22, () -> {
					t.value = !t.value;
					Modules.saveSoon();
				});
			} else if (s instanceof Setting.Slider sl) {
				int bw = 120;
				int bx = px + pw - 18 - bw;
				String v = sl.shown();
				textRenderer.drawWithShadow(v, bx - 6 - textRenderer.getWidth(v), y + 7, 0xFFB4B4C0);
				fill(bx, y + 10, bx + bw, y + 12, 0xFF3A3A44);
				int knob = bx + (int) Math.round(bw * (sl.value - sl.min) / (sl.max - sl.min));
				fill(bx, y + 10, knob, y + 12, ACCENT);
				fill(knob - 2, y + 6, knob + 3, y + 16, 0xFFFFFFFF);
				hit(bx - 4, y + 2, bw + 8, 18, () -> {
					dragging = sl;
					dragX = bx;
					dragW = bw;
				});
			}
			y += 26;
		}
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		if (button != 0) {
			return;
		}
		for (int i = boxes.size() - 1; i >= 0; i--) {
			int[] b = boxes.get(i);
			if (over(mouseX, mouseY, b[0], b[1], b[2], b[3])) {
				clicks.get(i).run();
				if (dragging != null) {
					mouseDragged(mouseX, mouseY, button, 0);
				}
				return;
			}
		}
	}

	@Override
	protected void mouseDragged(int mouseX, int mouseY, int button, long duration) {
		if (dragging != null) {
			dragging.set(dragging.min + (dragging.max - dragging.min) * Math.max(0, Math.min(1, (mouseX - dragX) / (double) dragW)));
		}
	}

	@Override
	protected void mouseReleased(int mouseX, int mouseY, int button) {
		if (dragging != null) {
			dragging = null;
			Modules.saveSoon();
		}
	}

	@Override
	public void handleMouse() {
		int wheel = org.lwjgl.input.Mouse.getEventDWheel();
		if (wheel != 0) {
			scroll += wheel > 0 ? -20 : 20;
		}
		super.handleMouse();
	}

	@Override
	protected void keyPressed(char chr, int key) {
		if (key == 1 && settingsOf != null) {
			settingsOf = null;
			return;
		}
		if (key == org.lwjgl.input.Keyboard.KEY_RSHIFT) {
			minecraft.openScreen(null);
			minecraft.lockMouse();
			return;
		}
		super.keyPressed(chr, key);
	}

	@Override
	public void removed() {
		Modules.saveNow();
	}

	private void enableScissor(int x, int y, int w, int h) {
		net.minecraft.client.render.Window window = new net.minecraft.client.render.Window(minecraft);
		int s = window.getScale();
		org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
		org.lwjgl.opengl.GL11.glScissor(x * s, minecraft.height - (y + h) * s, w * s, h * s);
	}

	private void disableScissor() {
		org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
	}
}
