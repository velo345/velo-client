package net.veloclient.velo.client.gui.widget;

import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleCategory;

import java.util.List;
import java.util.Map;

/**
 * The short "good for" tag on each module card in the Velo menu ("PvP", "Survival", "Bed Wars"...) and the
 * groups the Velo+ Addons are shown in (Find & See, Combat, Survive, Automate, Move, Safety - each with its own
 * colour, matching the icons in tools/icons/addon_icons.json).
 */
public final class ModuleTags {

	public record AddonGroup(String name, String blurb, int color) {
	}

	public static final List<AddonGroup> ADDON_GROUPS = List.of(
			new AddonGroup("Find & See", "Spot what matters", 0xFF2DD4BF),
			new AddonGroup("Combat", "Hit harder, hit smarter", 0xFFFF4D5A),
			new AddonGroup("Survive", "Stay alive without thinking", 0xFF3DDC97),
			new AddonGroup("Automate", "Let the boring parts run", 0xFFF7C04A),
			new AddonGroup("Move", "Go where you want", 0xFF5AA9FF),
			new AddonGroup("Safety", "Always one key away", 0xFFA9A9B3));

	private static final Map<String, String> ADDON_GROUP_OF = Map.ofEntries(
			Map.entry("block-finder", "Find & See"), Map.entry("storage-finder", "Find & See"), Map.entry("entity-finder", "Find & See"),
			Map.entry("x-ray", "Find & See"), Map.entry("sound-debug-overlay", "Find & See"), Map.entry("enemy-armor", "Find & See"),
			Map.entry("cinematic-camera", "Find & See"),
			Map.entry("kill-aura", "Combat"), Map.entry("auto-trigger", "Combat"), Map.entry("criticals", "Combat"), Map.entry("reach", "Combat"),
			Map.entry("bow-aimbot", "Combat"), Map.entry("auto-crystal", "Combat"), Map.entry("auto-anchor", "Combat"), Map.entry("auto-mace", "Combat"),
			Map.entry("stun-slam", "Combat"), Map.entry("elytra-mace", "Combat"), Map.entry("firework-mace", "Combat"),
			Map.entry("shield-breaker", "Combat"), Map.entry("w-tap", "Combat"), Map.entry("anti-knockback", "Combat"),
			Map.entry("auto-cart", "Combat"), Map.entry("terminator", "Combat"), Map.entry("auto-clicker", "Combat"),
			Map.entry("auto-totem", "Survive"), Map.entry("auto-pot", "Survive"), Map.entry("auto-eat", "Survive"), Map.entry("auto-armor", "Survive"),
			Map.entry("auto-shield", "Survive"), Map.entry("auto-mend", "Survive"), Map.entry("auto-mlg", "Survive"),
			Map.entry("auto-extinguish", "Survive"), Map.entry("auto-dodge", "Survive"), Map.entry("no-fall", "Survive"),
			Map.entry("auto-tunnel-miner", "Automate"), Map.entry("auto-excavator", "Automate"), Map.entry("ender-chest-farmer", "Automate"),
			Map.entry("auto-craft", "Automate"), Map.entry("auto-trade", "Automate"), Map.entry("auto-fish", "Automate"),
			Map.entry("auto-write", "Automate"), Map.entry("fast-break", "Automate"), Map.entry("fast-place", "Automate"), Map.entry("scaffold", "Automate"),
			Map.entry("fly", "Move"), Map.entry("fly-boat", "Move"), Map.entry("speed", "Move"), Map.entry("jump-boost", "Move"),
			Map.entry("air-jump", "Move"), Map.entry("wall-jump", "Move"), Map.entry("spider", "Move"), Map.entry("jesus", "Move"),
			Map.entry("safe-walk", "Move"), Map.entry("no-slow", "Move"), Map.entry("inventory-move", "Move"), Map.entry("auto-walk", "Move"),
			Map.entry("auto-jump", "Move"),
			Map.entry("addons-kill-switch", "Safety"));

	private static final Map<String, String> TAGS = Map.ofEntries(
			Map.entry("keystrokes", "PvP"), Map.entry("mouse-buttons", "PvP"), Map.entry("armor-durability", "PvP"),
			Map.entry("potion-timers", "PvP"), Map.entry("target-hud", "PvP"), Map.entry("cooldowns", "PvP"), Map.entry("combo-counter", "PvP"),
			Map.entry("totem-counter", "Crystal PvP"), Map.entry("pot-counter", "Pot PvP"), Map.entry("arrow-counter", "Bow PvP"),
			Map.entry("coordinates", "Survival"), Map.entry("minimap", "Survival"), Map.entry("waypoints", "Survival"),
			Map.entry("world-map", "Survival"), Map.entry("shulker-preview", "Survival"), Map.entry("session-stats", "Everyday"),
			Map.entry("fps-counter", "Everyday"), Map.entry("ping-display", "Servers"), Map.entry("clock", "Everyday"),
			Map.entry("direction-hud", "Everyday"), Map.entry("speed-hud", "Parkour"), Map.entry("scoreboard-hud", "Servers"),
			Map.entry("boss-bar", "Servers"), Map.entry("server-overlays", "Bed Wars"), Map.entry("height-limit", "Bed Wars"),
			Map.entry("player-levels", "Minigames"), Map.entry("auto-gg", "Minigames"), Map.entry("mode-presets", "Minigames"),
			Map.entry("background-queue", "Queues"), Map.entry("clips", "Recording"), Map.entry("free-look", "Recording"),
			Map.entry("polyblur", "Recording"), Map.entry("time-weather-fog", "Recording"), Map.entry("nick-hider", "Streaming"),
			Map.entry("zoom", "Everyday"), Map.entry("toggle-sprint", "Everyday"), Map.entry("toggle-sneak", "Everyday"),
			Map.entry("chat-tweaks", "Servers"), Map.entry("friends", "Social"), Map.entry("party-ping", "Social"),
			Map.entry("hopper-speed", "Building"), Map.entry("fast-grow", "Building"), Map.entry("fly-speed", "Building"),
			Map.entry("phantom", "Building"), Map.entry("chunk-border-overlay", "Building"), Map.entry("spawn-radius", "Farms"),
			Map.entry("simulation-distance", "Farms"), Map.entry("light-level-overlay", "Farms"), Map.entry("anti-farm-lag", "Farms"),
			Map.entry("old-animations", "1.8 feel"), Map.entry("reach-display", "PvP"), Map.entry("fight-recap", "PvP"),
			Map.entry("fov", "PvP"), Map.entry("screen-stretch", "PvP"), Map.entry("hit-sounds", "PvP"), Map.entry("hit-color", "PvP"), Map.entry("hit-particles", "PvP"),
			Map.entry("custom-crosshair", "PvP"), Map.entry("attack-indicator", "PvP"), Map.entry("low-fire", "PvP"),
			Map.entry("low-health-warning", "PvP"), Map.entry("item-position", "PvP"), Map.entry("damage-numbers", "PvP"),
			Map.entry("enemy-effects", "PvP"), Map.entry("enemy-hunger", "PvP"), Map.entry("health-indicator", "PvP"),
			Map.entry("hearts", "Look"), Map.entry("hunger", "Look"), Map.entry("armor-bar", "Look"), Map.entry("xp-bar", "Look"),
			Map.entry("hotbar", "Look"), Map.entry("small-caps", "Look"), Map.entry("block-outline", "Look"),
			Map.entry("performance-boost", "More FPS"), Map.entry("render-culling", "More FPS"), Map.entry("entity-detail", "More FPS"),
			Map.entry("particle-limiter", "More FPS"), Map.entry("hide-foliage", "Clear view"), Map.entry("container-animations", "Style"),
			Map.entry("emoji-chat", "Chat"), Map.entry("shader-cache", "Faster start"), Map.entry("full-bright", "Caves"),
			Map.entry("tnt-timer", "TNT"), Map.entry("velo-network", "Social"), Map.entry("cape-cosmetics", "Look"),
			Map.entry("kill-effects", "Look"), Map.entry("hud-style", "Look"), Map.entry("wings-cosmetics", "Look"), Map.entry("aura-cosmetics", "Look"));

	private ModuleTags() {
	}

	/** The addon group a Velo+ Addon belongs to (Safety for one we don't know yet). */
	public static AddonGroup addonGroup(String moduleId) {
		String name = ADDON_GROUP_OF.getOrDefault(moduleId, "Safety");
		for (AddonGroup group : ADDON_GROUPS) {
			if (group.name().equals(name)) {
				return group;
			}
		}
		return ADDON_GROUPS.getLast();
	}

	/** What a module is good for, in a word or two. */
	public static String tag(Module module) {
		if (module.category() == ModuleCategory.ADDONS) {
			return addonGroup(module.id()).name();
		}
		String tag = TAGS.get(module.id());
		if (tag != null) {
			return tag;
		}
		return switch (module.category()) {
			case HUD, QOL -> "Everyday";
			case PVP -> "PvP";
			case PERFORMANCE -> "More FPS";
			case RENDERING -> "Look";
			case SERVER_TOOLS -> "Servers";
			case DEBUG -> "Dev tools";
			case COSMETICS -> "Look";
			case ADDONS -> "Velo+";
		};
	}
}
