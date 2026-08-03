package net.veloclient.velo.client.gui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

/**
 * A small Velo Client watermark on the vanilla title screen and pause menu
 * (design spec section 5's launcher branding, extended in-game) - added via
 * {@code ScreenEvents} rather than replacing either screen outright, so
 * vanilla's own layout/buttons/mod-compatibility aren't touched.
 */
public final class VeloBranding {

	private static final Identifier LOGO_TEXTURE = Identifier.of("velo-client", "textures/icon/logo.png");
	// The source PNG is 500x500 - drawTexture's regionWidth/regionHeight
	// must be set to that, not the on-screen draw size, or scaling it down
	// samples UV coordinates the same wrong way the crosshair icons did
	// before that bug was fixed (see CustomCrosshairModule/VeloCrosshairTile).
	private static final int LOGO_SOURCE_SIZE = 500;

	private static final String VERSION = FabricLoader.getInstance()
			.getModContainer("velo-client")
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("dev");

	private VeloBranding() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof TitleScreen || screen instanceof GameMenuScreen) {
				ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, delta) -> drawWatermark(context));
			}
		});
	}

	private static void drawWatermark(DrawContext context) {
		Theme theme = ThemeManager.active();
		int x = 8;
		int y = 8;
		int iconSize = 16;

		context.drawTexture(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, x, y, 0f, 0f,
				iconSize, iconSize, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE);

		var textRenderer = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
		String label = "Velo Client " + VERSION;
		context.drawTextWithShadow(textRenderer, label, x + iconSize + 6, y + (iconSize - textRenderer.fontHeight) / 2, theme.text());
	}
}
