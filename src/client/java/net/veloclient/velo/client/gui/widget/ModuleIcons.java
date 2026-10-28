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
			"fps-counter", "ping-display", "coordinates", "clock", "armor-durability",
			"potion-timers", "held-item", "keystrokes", "actionbar-log", "session-stats", "waypoints",
			"toggle-sprint", "toggle-sneak", "zoom", "cape-cosmetics", "frame-time-graph", "memory-monitor",
			"gpu-utilization", "particle-limiter", "performance-boost", "polyblur",
			"full-bright", "fov", "mouse-buttons", "copy-coordinates", "entity-count-overlay", "chunk-border-overlay",
			"hitbox-visualizer", "world-border-visualizer", "light-level-overlay", "tps-tick-graph",
			"packet-traffic-monitor", "particle-debug-overlay", "sound-debug-overlay", "client-log-viewer",
			"resource-reload-hotkeys", "keybind-conflict-checker", "looking-at-inspector", "command-keybinds",
			"scoreboard-hud", "custom-crosshair", "minimap", "session-auto-fixer", "tnt-timer", "block-outline",
			"time-weather-fog", "small-caps", "kill-effects", "input-sampler", "background-queue", "friends", "totem-counter", "shader-cache",
			"hearts", "hunger", "armor-bar", "xp-bar", "hotbar", "world-map",
			"boss-bar", "shulker-preview", "better-f3", "spawn-radius", "simulation-distance",
			"old-animations", "hit-color", "hit-particles", "cooldowns", "health-indicator", "enemy-effects", "enemy-hunger",
			"target-hud", "combo-counter", "reach-display", "pot-counter", "anti-farm-lag", "item-position",
			"damage-numbers", "hit-sounds", "auto-gg", "height-limit", "speed-hud", "direction-hud", "arrow-counter",
			"low-health-warning", "low-fire", "clips", "fight-recap", "party-ping", "server-overlays", "player-levels", "mode-presets", "chat-tweaks", "hopper-speed", "fast-grow", "hud-style", "wings-cosmetics", "aura-cosmetics",
			// Core modules that showed the fallback grid before (tools/icons/module_icons.json)
			"attack-indicator", "auto-reconnect", "chunk-load-profiler", "entity-detail", "fly-speed", "free-look",
			"module-profiler", "nick-hider", "phantom", "render-culling", "velo-network", "screen-stretch",
			"hide-foliage", "container-animations", "emoji-chat", "anti-coords-leak", "anti-deepslate", "velo-shaders",
			"no-break-particles", "totem-pops", "emotes", "media-player");

	private ModuleIcons() {
	}

	/** Icons other jars (an add-on) bring for their own modules. */
	private static final java.util.Map<String, Identifier> EXTRA = new java.util.concurrent.ConcurrentHashMap<>();

	public static void register(String moduleId, Identifier texture) {
		EXTRA.put(moduleId, texture);
	}

	public static Identifier textureFor(String moduleId) {
		Identifier extra = EXTRA.get(moduleId);
		if (extra != null) {
			return extra;
		}
		return KNOWN_IDS.contains(moduleId) ? texture(moduleId) : DEFAULT;
	}

	private static Identifier texture(String id) {
		return Identifier.of("velo-client", "textures/icon/module/" + id + ".png");
	}
}
