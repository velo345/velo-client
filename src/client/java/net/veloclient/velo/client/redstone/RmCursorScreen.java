package net.veloclient.velo.client.redstone;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Tap Left Alt in Redstone Mode: the mouse is freed so the overlay can be clicked (tools, menus,
 * settings, schematics, the timeline slider). The world keeps running and stays visible - no
 * background dim, no pause. Alt or Escape goes back to playing.
 */
public final class RmCursorScreen extends Screen {

	public RmCursorScreen() {
		super(Text.literal("Redstone Mode"));
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	protected void init() {
		RmHud.cursor = true;
	}

	@Override
	public void removed() {
		RmHud.cursor = false;
		RmHud.closeMenu();
		RmHud.draggingSlider = false;
		super.removed();
	}

	// Fully transparent: the world and the overlay (drawn by the HUD) stay visible.
	//? if <26.1 {
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
	}
	//?} else {
	/*@Override
	public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
	}
	*///?}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		RmHud.mouseX = mouseX;
		RmHud.mouseY = mouseY;
		if (!RedstoneMode.active()) {
			this.close();
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		return RmHud.click(click.x(), click.y()) || velo$superMouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (RmHud.draggingSlider) {
			RmHud.drag(click.x());
			return true;
		}
		return velo$superMouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		RmHud.draggingSlider = false;
		return velo$superMouseReleased(click);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (input.key() == net.veloclient.velo.client.util.Keys.LEFT_ALT) {
			this.close();
			return true;
		}
		return velo$superKeyPressed(input);
	}

	// ---- 1.21.1: vanilla input still comes as numbers; the screens use 1.21.9's event objects ----
	private boolean velo$superKeyPressed(net.minecraft.client.input.KeyInput c) {
		//? if <1.21.2 {
		/*return super.keyPressed(c.key(), c.scancode(), c.modifiers());
		*///?} else {
		return super.keyPressed(c);
		//?}
	}

	private boolean velo$superMouseClicked(net.minecraft.client.gui.Click c, boolean d) {
		//? if <1.21.2 {
		/*return super.mouseClicked(c.x(), c.y(), c.button());
		*///?} else {
		return super.mouseClicked(c, d);
		//?}
	}

	private boolean velo$superMouseDragged(net.minecraft.client.gui.Click c, double dx, double dy) {
		//? if <1.21.2 {
		/*return super.mouseDragged(c.x(), c.y(), c.button(), dx, dy);
		*///?} else {
		return super.mouseDragged(c, dx, dy);
		//?}
	}

	private boolean velo$superMouseReleased(net.minecraft.client.gui.Click c) {
		//? if <1.21.2 {
		/*return super.mouseReleased(c.x(), c.y(), c.button());
		*///?} else {
		return super.mouseReleased(c);
		//?}
	}

	//? if <1.21.2 {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		return mouseClicked(new net.veloclient.velo.client.compat.Click(x, y, button), net.veloclient.velo.client.compat.Click.doubled(x, y, button));
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		return mouseReleased(new net.veloclient.velo.client.compat.Click(x, y, button));
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return mouseDragged(new net.veloclient.velo.client.compat.Click(x, y, button), dx, dy);
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return keyPressed(new net.veloclient.velo.client.compat.KeyInput(key, scancode, modifiers));
	}
	*///?}
}
