package net.veloclient.legacy.menu;

import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.gui.screen.Screen;
import net.veloclient.legacy.feature.LegacyModule;
import net.veloclient.legacy.feature.Modules;

/** Drag the 1.8.9 HUD elements where you want them. Escape (or Done) goes back to the menu. */
public final class HudEditScreen extends Screen {

	private final Screen parent;
	private LegacyModule dragging;
	private int grabX;
	private int grabY;
	private final java.util.Map<LegacyModule, int[]> rects = new java.util.HashMap<>();

	public HudEditScreen(Screen parent) {
		this.parent = parent;
	}

	@Override
	public boolean shouldPauseGame() {
		return false;
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		GuiElement.fill(0, 0, width, height, 0x30000000);
		rects.clear();
		for (LegacyModule m : Modules.all()) {
			if (!m.hud || !m.enabled()) {
				continue;
			}
			int[] r = Modules.drawAt(minecraft, m, width, height, true);
			int w = Math.max(r[2], 10);
			int h = Math.max(r[3], 9);
			rects.put(m, new int[] {r[0], r[1], w, h});
			int c = m == dragging ? VeloMenuScreen.ACCENT : 0x80FFFFFF;
			GuiElement.fill(r[0] - 1, r[1] - 1, r[0] + w + 1, r[1], c);
			GuiElement.fill(r[0] - 1, r[1] + h, r[0] + w + 1, r[1] + h + 1, c);
			GuiElement.fill(r[0] - 1, r[1], r[0], r[1] + h, c);
			GuiElement.fill(r[0] + w, r[1], r[0] + w + 1, r[1] + h, c);
		}
		String hint = "Drag the elements - Escape when done";
		textRenderer.drawWithShadow(hint, (width - textRenderer.getWidth(hint)) / 2, height / 2 - 4, 0xFFFFFFFF);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		for (var e : rects.entrySet()) {
			int[] r = e.getValue();
			if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
				dragging = e.getKey();
				grabX = mouseX - r[0];
				grabY = mouseY - r[1];
				return;
			}
		}
	}

	@Override
	protected void mouseDragged(int mouseX, int mouseY, int button, long duration) {
		if (dragging == null) {
			return;
		}
		int[] r = rects.get(dragging);
		int w = r == null ? 10 : r[2];
		int h = r == null ? 9 : r[3];
		int x = Math.max(0, Math.min(width - w, mouseX - grabX));
		int y = Math.max(0, Math.min(height - h, mouseY - grabY));
		dragging.hx = x / (float) width;
		dragging.hy = y / (float) height;
	}

	@Override
	protected void mouseReleased(int mouseX, int mouseY, int button) {
		if (dragging != null) {
			dragging = null;
			Modules.saveSoon();
		}
	}

	@Override
	protected void keyPressed(char chr, int key) {
		if (key == 1) {
			Modules.saveNow();
			minecraft.openScreen(parent);
			return;
		}
		super.keyPressed(chr, key);
	}
}
