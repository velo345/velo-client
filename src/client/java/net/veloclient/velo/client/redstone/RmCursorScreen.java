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
		return RmHud.click(click.x(), click.y()) || super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (RmHud.draggingSlider) {
			RmHud.drag(click.x());
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		RmHud.draggingSlider = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (input.key() == net.veloclient.velo.client.util.Keys.LEFT_ALT) {
			this.close();
			return true;
		}
		return super.keyPressed(input);
	}
}
