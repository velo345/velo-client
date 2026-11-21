package net.veloclient.velo.client.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Bakes the glyphs of Velo's menu fonts and loads its menu icons ahead of time, a little per tick after joining a
 * world. The first R-Shift menu of a session took a 60+ ms frame (later ones under 1 ms): every letter of every Velo
 * font size it uses was rasterised, and hundreds of icons were read from the jar, in that one frame. Measured 2026-11-21 with the spike recorder (devtools scene "spikes").
 *
 * <p>Only the variants this window can use right now (the GUI scale's and the denser menu scale's), one font per tick,
 * redone when the GUI scale changes.
 */
public final class FontWarmup {

	private static final String[] FONTS = {"ui", "ui_bold", "ui_sm", "mono", "display", "display_sm", "display_lg", "title", "big", "hero"};
	/** Everything a menu shows in English plus the usual accents and symbols. */
	private static final String CHARS;

	static {
		StringBuilder b = new StringBuilder();
		for (char c = 32; c < 127; c++) {
			b.append(c);
		}
		b.append("äöüÄÖÜßéèêáàâíìóòúùñç·•…–—’“”€£°×→←↑↓✓");
		CHARS = b.toString();
	}

	private static final List<String> queue = new ArrayList<>();
	/** Menu icons (module cards, rail, keys): loaded from the jar on first draw - 400+ of them in the first menu frame. */
	private static final List<net.minecraft.util.Identifier> textures = new ArrayList<>();
	private static boolean texturesListed;
	private static int warmedForScale = -1;
	private static boolean worldSeen;

	private FontWarmup() {
	}

	public static void register() {
		if (Boolean.getBoolean("velo.noFontWarmup")) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(FontWarmup::tick);
	}

	private static void listTextures(MinecraftClient client) {
		for (String dir : new String[] {"textures/icon/nav", "textures/icon/module", "textures/icon/keys"}) {
			try {
				//? if <26.1 {
				var found = client.getResourceManager().findResources(dir, id -> id.getPath().endsWith(".png"));
				//?} else {
				/*var found = client.getResourceManager().listResources(dir, id -> id.getPath().endsWith(".png"));
				*///?}
				for (var id : found.keySet()) {
					if ("velo-client".equals(id.getNamespace())) {
						textures.add(id);
					}
				}
			} catch (RuntimeException ignored) {
				// none then
			}
		}
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null) {
			worldSeen = false;
			return;
		}
		int scale = ClientCompat.guiScale();
		if (!worldSeen || scale != warmedForScale) {
			worldSeen = true;
			warmedForScale = scale;
			queue.clear();
			int dense = net.veloclient.velo.client.gui.window.DenseGui.effectiveScale();
			for (int s : dense == scale ? new int[] {scale} : new int[] {dense, scale}) {
				int variant = Math.max(1, Math.min(6, s));
				for (String font : FONTS) {
					queue.add(font + "_" + variant);
				}
			}
			return;
		}
		if (!texturesListed) {
			texturesListed = true;
			listTextures(client);
		}
		// a dozen icons per tick: a few ms, spread over ~2 s after joining instead of all in the first menu frame
		for (int i = 0; i < 12 && !textures.isEmpty(); i++) {
			try {
				client.getTextureManager().getTexture(textures.remove(textures.size() - 1));
			} catch (RuntimeException ignored) {
				// skip it
			}
		}
		if (queue.isEmpty()) {
			return;
		}
		String font = queue.remove(0);
		try {
			// measuring each character fetches its glyph, which bakes it into the font's atlas
			var text = net.veloclient.velo.client.gui.title.MenuKit.inNamedFont(CHARS, font);
			client.textRenderer.getWidth(text);
		} catch (RuntimeException ignored) {
			// a font this version doesn't ship - fine
		}
	}
}
