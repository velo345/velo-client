package net.veloclient.velo.client.gui.widget;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.HashMap;
import java.util.Map;

/** Maps each module id to a vanilla item whose icon reads as a recognizable stand-in - no custom texture assets needed. */
public final class ModuleIcons {

	private static final Map<String, Item> ICONS = new HashMap<>();

	static {
		ICONS.put("fps-counter", Items.REDSTONE);
		ICONS.put("ping-display", Items.LIGHTNING_ROD);
		ICONS.put("coordinates", Items.COMPASS);
		ICONS.put("clock", Items.CLOCK);
		ICONS.put("cps-counter", Items.STICK);
		ICONS.put("armor-durability", Items.DIAMOND_CHESTPLATE);
		ICONS.put("potion-timers", Items.POTION);
		ICONS.put("held-item", Items.ITEM_FRAME);
		ICONS.put("keystrokes", Items.LEVER);
		ICONS.put("actionbar-log", Items.PAPER);
		ICONS.put("session-stats", Items.EXPERIENCE_BOTTLE);
		ICONS.put("waypoints", Items.FILLED_MAP);
		ICONS.put("toggle-sprint", Items.RABBIT_FOOT);
		ICONS.put("toggle-sneak", Items.LEATHER_BOOTS);
		ICONS.put("zoom", Items.SPYGLASS);
		ICONS.put("cape-cosmetics", Items.ELYTRA);
		ICONS.put("frame-time-graph", Items.COMPARATOR);
		ICONS.put("memory-monitor", Items.CHEST);
		ICONS.put("gpu-utilization", Items.REDSTONE_BLOCK);
		ICONS.put("particle-limiter", Items.FIREWORK_ROCKET);
		ICONS.put("performance-boost", Items.NETHER_STAR);
		ICONS.put("polyblur", Items.ENDER_EYE);
		ICONS.put("optimization-mod-compat", Items.ANVIL);
		ICONS.put("full-bright", Items.GLOWSTONE);
		ICONS.put("fov", Items.GLASS_PANE);
		ICONS.put("mouse-buttons", Items.STICK);
		ICONS.put("copy-coordinates", Items.WRITABLE_BOOK);
		ICONS.put("entity-count-overlay", Items.SPAWNER);
		ICONS.put("chunk-border-overlay", Items.BARRIER);
		ICONS.put("hitbox-visualizer", Items.TARGET);
		ICONS.put("world-border-visualizer", Items.BEDROCK);
		ICONS.put("light-level-overlay", Items.TORCH);
		ICONS.put("tps-tick-graph", Items.REPEATER);
		ICONS.put("packet-traffic-monitor", Items.TRIPWIRE_HOOK);
		ICONS.put("particle-debug-overlay", Items.GLOW_INK_SAC);
		ICONS.put("sound-debug-overlay", Items.NOTE_BLOCK);
		ICONS.put("client-log-viewer", Items.WRITTEN_BOOK);
		ICONS.put("resource-reload-hotkeys", Items.ENCHANTED_BOOK);
		ICONS.put("keybind-conflict-checker", Items.OAK_BUTTON);
		ICONS.put("looking-at-inspector", Items.OBSERVER);
		ICONS.put("command-keybinds", Items.COMMAND_BLOCK);
	}

	private ModuleIcons() {
	}

	public static ItemStack stackFor(String moduleId) {
		return new ItemStack(ICONS.getOrDefault(moduleId, Items.PAPER));
	}
}
