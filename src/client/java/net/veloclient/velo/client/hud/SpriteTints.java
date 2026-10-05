package net.veloclient.velo.client.hud;

import net.veloclient.velo.module.ModuleRegistry;

/**
 * Color multipliers for vanilla HUD sprites, applied where vanilla draws them (see SpriteTintMixin)
 * - the XP / locator bar and the hotbar. Each sprite is asked of the module that owns it; 0 means
 * "leave vanilla alone".
 */
public final class SpriteTints {

	private SpriteTints() {
	}

	/** Implemented by modules that recolor vanilla sprites. */
	public interface Provider {
		/** ARGB tint for a {@code minecraft:} sprite path like {@code hud/hotbar}, or 0 for none. */
		int tintFor(String spritePath);
	}

	/** Tint for a sprite id (namespace:path), or 0. Called for every HUD sprite draw, so it stays cheap. */
	public static int tintFor(String namespace, String path) {
		if (!"minecraft".equals(namespace) || !path.startsWith("hud/")) {
			return 0;
		}
		if (path.startsWith("hud/hotbar")) {
			return ask("hotbar", path);
		}
		if (path.startsWith("hud/experience_bar") || path.startsWith("hud/locator_bar")) {
			return ask("xp-bar", path);
		}
		return 0;
	}

	private static int ask(String moduleId, String path) {
		var module = ModuleRegistry.get(moduleId).orElse(null);
		return module instanceof Provider provider && module.isEnabled() ? provider.tintFor(path) : 0;
	}

	/** XP level number color override (0 = vanilla green). */
	public static int xpLevelColor() {
		var module = ModuleRegistry.get("xp-bar").orElse(null);
		return module instanceof net.veloclient.velo.client.modules.hud.XpBarModule xp && module.isEnabled() ? xp.levelColor() : 0;
	}
}
