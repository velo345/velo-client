package net.veloclient.velo.client.gui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

/**
 * A small Velo Client watermark on the vanilla title screen and pause menu
 * (design spec section 5's launcher branding, extended in-game) - added via
 * {@code ScreenEvents} rather than replacing either screen outright, so
 * vanilla's own layout/buttons/mod-compatibility aren't touched.
 */
public final class VeloBranding {

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
		int iconSize = 14;

		VeloDraw.fillRounded(context, x, y, iconSize, iconSize, 3, theme.accentStart());
		var textRenderer = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
		context.drawTextWithShadow(textRenderer, "V", x + (iconSize - textRenderer.getWidth("V")) / 2,
				y + (iconSize - textRenderer.fontHeight) / 2, 0xFFFFFFFF);

		String label = "VELO CLIENT  v" + VERSION;
		context.drawTextWithShadow(textRenderer, label, x + iconSize + 6, y + (iconSize - textRenderer.fontHeight) / 2, theme.text());
	}
}
