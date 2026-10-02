package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.util.ClientCompat;
//? if <26.1 {
import net.minecraft.text.StyleSpriteSource;
//?} else {
/*import net.minecraft.network.chat.FontDescription;
*///?}

/**
 * Makes every Velo menu render in Inter instead of the blocky vanilla pixel font, and keeps the
 * custom fonts crisp.
 *
 * Why per-scale fonts: vanilla samples glyph atlases with NEAREST filtering, so a TTF rasterized
 * at 8x oversample and drawn at GUI scale 2-3 gets shrunk by dropping texels - that's what made
 * the old Poppins/Anta text look jagged. Each font is therefore defined once per GUI scale
 * ({@code ui_1..ui_6}, oversample = scale), and lookups pick the one whose texels map 1:1 onto
 * screen pixels.
 *
 * Scope: Velo's own font ids are always remapped; the vanilla default font only while a Velo
 * screen (or the themed title screen) is open, and never while the in-game HUD draws underneath
 * it (chat, hotbar and Velo's HUD modules keep their usual look). Glyph lookup happens when text
 * is drawn/measured (GuiRenderState prepares text eagerly), so a simple flag is exact.
 */
public final class VeloFonts {

	private static final int MAX_SCALE = 6;

	//? if <26.1 {
	private static final StyleSpriteSource[] UI = fonts("ui");
	private static final StyleSpriteSource[] UI_BOLD = fonts("ui_bold");
	private static final StyleSpriteSource[] TITLE = fonts("title");
	//?} else {
	/*private static final FontDescription[] UI = fonts("ui");
	private static final FontDescription[] UI_BOLD = fonts("ui_bold");
	private static final FontDescription[] TITLE = fonts("title");
	*///?}

	private static int hudDepth;

	private VeloFonts() {
	}

	/** In-game HUD rendering brackets itself with these so it keeps the vanilla font under a Velo menu. */
	public static void beginHud() {
		hudDepth++;
	}

	public static void endHud() {
		hudDepth = Math.max(0, hudDepth - 1);
	}

	/** True while the default font should be swapped for Inter. */
	public static boolean menuScope() {
		if (hudDepth > 0) {
			return false;
		}
		Screen screen = ClientCompat.currentScreen();
		return screen instanceof VeloWindow || screen instanceof HudEditScreen || screen instanceof TitleScreen
				|| screen instanceof GameMenuScreen;
	}

	private static int scaleIndex() {
		int scale = ClientCompat.guiScale();
		return Math.max(1, Math.min(MAX_SCALE, scale)) - 1;
	}

	//? if <26.1 {
	private static StyleSpriteSource[] fonts(String base) {
		StyleSpriteSource[] out = new StyleSpriteSource[MAX_SCALE];
		for (int i = 0; i < MAX_SCALE; i++) {
			out[i] = new StyleSpriteSource.Font(Identifier.of("velo-client", base + "_" + (i + 1)));
		}
		return out;
	}

	public static StyleSpriteSource remap(StyleSpriteSource source) {
		if (source == StyleSpriteSource.DEFAULT || StyleSpriteSource.DEFAULT.equals(source)) {
			return menuScope() ? UI[scaleIndex()] : source;
		}
		if (source instanceof StyleSpriteSource.Font font) {
			return remapVelo(font.id(), source);
		}
		return source;
	}

	private static StyleSpriteSource remapVelo(Identifier id, StyleSpriteSource fallback) {
	//?} else {
	/*private static FontDescription[] fonts(String base) {
		FontDescription[] out = new FontDescription[MAX_SCALE];
		for (int i = 0; i < MAX_SCALE; i++) {
			out[i] = new FontDescription.Resource(Identifier.of("velo-client", base + "_" + (i + 1)));
		}
		return out;
	}

	public static FontDescription remap(FontDescription source) {
		if (source == FontDescription.DEFAULT || FontDescription.DEFAULT.equals(source)) {
			return menuScope() ? UI[scaleIndex()] : source;
		}
		if (source instanceof FontDescription.Resource font) {
			return remapVelo(font.id(), source);
		}
		return source;
	}

	private static FontDescription remapVelo(Identifier id, FontDescription fallback) {
	*///?}
		if (!"velo-client".equals(id.getNamespace())) {
			return fallback;
		}
		return switch (id.getPath()) {
			case "body", "ui" -> UI[scaleIndex()];
			case "tile", "ui_bold" -> UI_BOLD[scaleIndex()];
			case "title" -> TITLE[scaleIndex()];
			default -> fallback;
		};
	}
}
