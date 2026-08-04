package net.veloclient.velo.client.gui.widget;

import net.minecraft.util.Identifier;

import java.util.Set;

/**
 * Maps each module id to a custom Lucide-derived icon texture (see
 * NOTICE.md) instead of a vanilla item stand-in - a uniform, modern icon set
 * that recolors live with the active theme (the source PNGs are white
 * strokes on transparent, tinted at draw time by {@link VeloModuleTile}) or
 * {@link #DEFAULT} if a module id has none registered.
 */
public final class ModuleIcons {

	// A real dedicated fallback asset - not a reference to one of the named
	// module icons above, which previously pointed at a filename ("target")
	// that only ever existed under its own module id ("custom-crosshair.png"),
	// making the fallback itself resolve to a missing texture.
	public static final Identifier DEFAULT = texture("default");

	private static final Set<String> KNOWN_IDS = Set.of(
			"fps-counter", "ping-display", "coordinates", "clock", "cps-counter", "armor-durability",
			"potion-timers", "held-item", "keystrokes", "actionbar-log", "session-stats", "waypoints",
			"toggle-sprint", "toggle-sneak", "zoom", "cape-cosmetics", "frame-time-graph", "memory-monitor",
			"gpu-utilization", "particle-limiter", "performance-boost", "polyblur", "optimization-mod-compat",
			"full-bright", "fov", "mouse-buttons", "copy-coordinates", "entity-count-overlay", "chunk-border-overlay",
			"hitbox-visualizer", "world-border-visualizer", "light-level-overlay", "tps-tick-graph",
			"packet-traffic-monitor", "particle-debug-overlay", "sound-debug-overlay", "client-log-viewer",
			"resource-reload-hotkeys", "keybind-conflict-checker", "looking-at-inspector", "command-keybinds",
			"scoreboard-hud", "custom-crosshair", "minimap");

	private ModuleIcons() {
	}

	public static Identifier textureFor(String moduleId) {
		return KNOWN_IDS.contains(moduleId) ? texture(moduleId) : DEFAULT;
	}

	private static Identifier texture(String id) {
		return Identifier.of("velo-client", "textures/icon/module/" + id + ".png");
	}
}
