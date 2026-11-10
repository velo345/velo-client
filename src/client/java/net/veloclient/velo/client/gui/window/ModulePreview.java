package net.veloclient.velo.client.gui.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleCategory;

/**
 * The live preview on a module's settings page (2026-11 redesign): every module that changes how something looks shows
 * it right there, updated as you change a setting.
 * <ul>
 * <li>HUD elements: the element itself, drawn by the module, scaled to fit;</li>
 * <li>the custom crosshair: the crosshair as it is drawn in game;</li>
 * <li>cosmetics: your player (wings, cloak, aura...);</li>
 * <li>everything that changes the world (rendering, HUD looks): a clear window onto the real game behind the menu - no
 * blur, no dimming there - so the change shows on the world itself.</li>
 * </ul>
 */
public final class ModulePreview {

	public enum Kind { NONE, HUD, CROSSHAIR, PLAYER, WORLD, SCENE }

	/** Modules outside Rendering/HUD/Cosmetics that still change what you see in the world (live view preview). */
	private static final java.util.Set<String> WORLD_LOOKS = java.util.Set.of(
			"attack-indicator", "hit-color", "hit-particles", "low-fire", "item-position", "old-animations", "fov", "free-look", "zoom",
			"damage-numbers", "health-indicator", "low-health-warning", "height-limit", "own-name-tag", "nick-hider", "small-caps",
			"chunk-border-overlay", "hitbox-visualizer", "light-level-overlay", "world-border-visualizer", "spawn-radius",
			"particle-debug-overlay", "polyblur", "particle-limiter", "enemy-effects", "enemy-hunger", "phantom");

	private ModulePreview() {
	}

	public static Kind kind(Module module) {
		if (ModuleScenes.has(module) && module instanceof HudModule) {
			return Kind.SCENE; // a HUD module whose own drawing shows nothing useful alone (input latency)
		}
		if (module instanceof HudModule) {
			return Kind.HUD;
		}
		if (module.id().equals("custom-crosshair") || module.id().equals("attack-indicator")) {
			return Kind.CROSSHAIR;
		}
		if (ModuleScenes.has(module)) {
			return Kind.SCENE;
		}
		if (WORLD_LOOKS.contains(module.id())) {
			return net.veloclient.velo.client.util.ClientCompat.dimensionId() != null ? Kind.WORLD : Kind.NONE;
		}
		ModuleCategory category = module.category();
		if (category == ModuleCategory.COSMETICS) {
			return Kind.PLAYER;
		}
		if (category == ModuleCategory.RENDERING || category == ModuleCategory.HUD) {
			return net.veloclient.velo.client.util.ClientCompat.dimensionId() != null ? Kind.WORLD : Kind.NONE;
		}
		return Kind.NONE;
	}

	/** The box behind a drawn preview: a calm sky-and-grass backdrop, like a bit of the game. */
	public static void backdrop(DrawContext context, int x, int y, int w, int h) {
		int horizon = y + h * 58 / 100;
		VeloDraw.fillRoundedGradient(context, x, y, w, horizon - y + 4, 8, 0xFF5B7FA8, 0xFF8FB0CF);
		context.fill(x, horizon, x + w, y + h - 8, 0xFF4F6B3A);
		VeloDraw.fillRoundedGradient(context, x, y + h - 12, w, 12, 8, 0xFF46612F, 0xFF3C522C);
		context.fill(x, horizon, x + w, horizon + 1, 0xFF5E7D45);
	}

	/** Draws a HUD module's element centred in the box, as large as fits (up to 2x). */
	public static void drawHud(DrawContext context, HudModule hud, int x, int y, int w, int h, float delta) {
		// sample content (like the HUD editor): empty elements show an example of what they do
		net.veloclient.velo.client.hud.HudSample.begin();
		try {
			drawHudSampled(context, hud, x, y, w, h, delta);
		} finally {
			net.veloclient.velo.client.hud.HudSample.end();
		}
	}

	private static void drawHudSampled(DrawContext context, HudModule hud, int x, int y, int w, int h, float delta) {
		int ew = hud.width();
		int eh = hud.height();
		var renderer = MinecraftClient.getInstance().textRenderer;
		if (ew <= 0 || eh <= 0) {
			MenuKit.text(context, renderer, MenuKit.ui("Shows when there is something to show"), x + 10, y + h / 2 - 4, 0xFFEDE6E6);
			return;
		}
		float scale = Math.min(1.5f, Math.min((w - 24) / (float) ew, (h - 24) / (float) eh));
		scale = Math.max(0.4f, scale);
		float ox = x + (w - ew * scale) / 2f;
		float oy = y + (h - eh * scale) / 2f;
		context.enableScissor(x, y, x + w, y + h);
		boolean smallCaps = !hud.id().equals("scoreboard-hud");
		if (smallCaps) {
			net.veloclient.velo.client.modules.qol.SmallCapsModule.setHudRenderActive(true);
		}
		net.veloclient.velo.client.gui.VeloFonts.beginHud();
		float previous = net.veloclient.velo.client.gui.VeloFonts.setHudPoseScale(scale);
		context.getMatrices().pushMatrix();
		try {
			context.getMatrices().translate(ox, oy);
			context.getMatrices().scale(scale, scale);
			hud.render(context, 0, 0, delta);
		} catch (RuntimeException e) {
			// a preview must never take the settings page down
		} finally {
			context.getMatrices().popMatrix();
			net.veloclient.velo.client.gui.VeloFonts.setHudPoseScale(previous);
			net.veloclient.velo.client.gui.VeloFonts.endHud();
			if (smallCaps) {
				net.veloclient.velo.client.modules.qol.SmallCapsModule.setHudRenderActive(false);
			}
			context.disableScissor();
		}
	}

	/** The crosshair as the game draws it, moved to the middle of the box. */
	public static void drawCrosshair(DrawContext context, int x, int y, int w, int h) {
		net.veloclient.velo.client.hud.HudSample.begin();
		try {
			drawCrosshairSampled(context, x, y, w, h);
		} finally {
			net.veloclient.velo.client.hud.HudSample.end();
		}
	}

	private static void drawCrosshairSampled(DrawContext context, int x, int y, int w, int h) {
		int sw = context.getScaledWindowWidth();
		int sh = context.getScaledWindowHeight();
		context.enableScissor(x, y, x + w, y + h);
		context.getMatrices().pushMatrix();
		try {
			float cx = x + w / 2f;
			float cy = y + h / 2f;
			context.getMatrices().translate(cx, cy);
			context.getMatrices().scale(2f, 2f);
			context.getMatrices().translate(-sw / 2f, -sh / 2f);
			boolean custom = net.veloclient.velo.module.ModuleRegistry.get("custom-crosshair").map(net.veloclient.velo.module.Module::isEnabled).orElse(false);
			if (custom) {
				net.veloclient.velo.client.modules.qol.CustomCrosshairModule.renderIfEquipped(context, sw, sh, false);
			} else {
				// no custom crosshair: a plain cross, so the attack indicator under it has something to sit under
				context.fill(sw / 2 - 4, sh / 2, sw / 2 + 5, sh / 2 + 1, 0xFFFFFFFF);
				context.fill(sw / 2, sh / 2 - 4, sw / 2 + 1, sh / 2 + 5, 0xFFFFFFFF);
			}
			net.veloclient.velo.client.modules.qol.AttackIndicatorModule.renderUnderCrosshair(context, sw, sh, 9);
		} catch (RuntimeException e) {
			// nothing equipped / not drawable here
		} finally {
			context.getMatrices().popMatrix();
			context.disableScissor();
		}
	}
}
