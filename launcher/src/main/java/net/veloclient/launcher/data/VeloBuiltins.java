package net.veloclient.launcher.data;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Popular mods whose feature Velo Client already has built in - the mod browser shows a small
 * hint on them ("Built into Velo: World Map") so players don't install the same thing twice.
 * Matched by Modrinth slug, or by a distinctive word in the title for installed files.
 */
public final class VeloBuiltins {

	/** Modrinth slug -> Velo feature. */
	private static final Map<String, String> BY_SLUG = new LinkedHashMap<>();
	/** Lower-case title fragment -> Velo feature (fallback when there's no slug). */
	private static final Map<String, String> BY_TITLE = new LinkedHashMap<>();

	static {
		slug("World Map & Minimap", "xaeros-world-map", "xaeros-minimap", "journeymap", "voxelmap-updated", "voxelmap", "antique-atlas");
		slug("Auto Reconnect", "autoreconnect", "auto-reconnect", "autoreconnect-reforged", "auto-reconnect-mod");
		slug("Shulker Preview", "shulkerboxtooltip", "shulker-box-tooltip", "shulker-tooltip");
		slug("Better F3", "betterf3");
		slug("Zoom", "zoomify", "ok-zoomer", "logical-zoom", "wi-zoom");
		slug("Free Look", "freelook", "perspective-mod", "perspective", "f5-fix");
		slug("Keystrokes & CPS", "keystrokes", "cps-display", "mouse-buttons");
		slug("Full Bright", "fullbright", "gamma-utils", "brightness-slider");
		slug("Potion Effect Timers", "statuseffecttimer", "status-effect-timer", "effect-timer");
		slug("Armor Durability", "durability-viewer", "armor-hud", "durabilityviewer");
		slug("Toggle Sprint", "toggle-sprint-display", "togglesprint");
		slug("Custom Crosshair", "custom-crosshair-mod", "dynamic-crosshair");
		slug("Waypoints", "waypoints");
		slug("Hitbox Visualizer", "hitboxes");
		slug("Chunk Boundary Overlay", "chunk-borders");
		title("World Map & Minimap", "xaero's world map", "xaero's minimap", "journeymap", "voxelmap");
		title("Auto Reconnect", "autoreconnect", "auto reconnect");
		title("Shulker Preview", "shulkerboxtooltip", "shulker box tooltip", "shulker tooltip");
		title("Better F3", "betterf3", "better f3");
		title("Zoom", "zoomify", "ok zoomer");
		title("Free Look", "freelook", "free look");
	}

	private static void slug(String feature, String... slugs) {
		for (String slug : slugs) {
			BY_SLUG.put(slug, feature);
		}
	}

	private static void title(String feature, String... fragments) {
		for (String fragment : fragments) {
			BY_TITLE.put(fragment, feature);
		}
	}

	private VeloBuiltins() {
	}

	/** The Velo feature that already does what this mod does, or null. */
	public static String featureFor(String slug, String title) {
		if (slug != null) {
			String feature = BY_SLUG.get(slug.toLowerCase(Locale.ROOT));
			if (feature != null) {
				return feature;
			}
		}
		if (title != null) {
			String lower = title.toLowerCase(Locale.ROOT);
			for (Map.Entry<String, String> e : BY_TITLE.entrySet()) {
				if (lower.contains(e.getKey())) {
					return e.getValue();
				}
			}
		}
		return null;
	}
}
