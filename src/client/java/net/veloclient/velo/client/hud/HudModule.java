package net.veloclient.velo.client.hud;

import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.module.Module;

/**
 * A module that draws something on the HUD layer. Purely a local overlay over
 * data the client already has (design spec section 2) - never reads or infers
 * anything the server hasn't sent this client.
 */
public interface HudModule extends Module {

	HudPosition position();

	/** Renders this element's content with its top-left corner at (x, y), resolved from {@link #position()}. */
	void render(DrawContext context, int x, int y, float tickDelta);

	/** Rendered size in pixels, used to keep the element on-screen and for drag hit-testing. */
	int width();

	int height();
}
