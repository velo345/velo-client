package net.veloclient.velo.client.gui.widget;

import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleCategory;

import java.util.List;
import java.util.Map;

/**
 * The short "good for" tag on each module card in the Velo menu ("PvP", "Survival", "Bed Wars"...) and the groups an
 * add-on's modules are shown in - the add-on registers those itself ({@link #registerAddonGroups}).
 */
public final class ModuleTags {

	public record AddonGroup(String name, String blurb, int color) {
	}

	private static final AddonGroup OTHER = new AddonGroup("Other", "", 0xFFA9A9B3);
	private static volatile List<AddonGroup> addonGroups = List.of(OTHER);
	private static volatile Map<String, String> addonGroupOf = Map.of();

	/** An add-on's groups (in order) and which group each of its modules is in. */
	public static void registerAddonGroups(List<AddonGroup> groups, Map<String, String> groupOfModule) {
		addonGroups = List.copyOf(groups);
		addonGroupOf = Map.copyOf(groupOfModule);
	}

	public static List<AddonGroup> addonGroups() {
		return addonGroups;
	}


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
			Map.entry("polyblur", "Recording"), Map.entry("time-weather-fog", "Recording"), Map.entry("nick-hider", "Streaming"), Map.entry("anti-coords-leak", "Streaming"), Map.entry("anti-deepslate", "Streaming"),
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

	/** The group an add-on module belongs to (the last group for one it didn't name). */
	public static AddonGroup addonGroup(String moduleId) {
		List<AddonGroup> groups = addonGroups;
		String name = addonGroupOf.get(moduleId);
		for (AddonGroup group : groups) {
			if (group.name().equals(name)) {
				return group;
			}
		}
		return groups.getLast();
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
			case ADDONS -> "Addon";
		};
	}
}
