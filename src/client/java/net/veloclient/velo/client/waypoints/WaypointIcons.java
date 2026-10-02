package net.veloclient.velo.client.waypoints;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The icons a waypoint can wear - real item icons (rendered with the game's own item renderer), so
 * they're instantly recognizable, scale cleanly and need no extra art. Stored by key, never by
 * registry id, so a key keeps working across Minecraft versions.
 */
public final class WaypointIcons {

	public static final String NONE = "none";
	public static final String SKULL = "skull";

	private static final Map<String, Item> ICONS = new LinkedHashMap<>();

	static {
		ICONS.put("home", Items.OAK_DOOR);
		ICONS.put("chest", Items.CHEST);
		ICONS.put("diamond", Items.DIAMOND);
		ICONS.put("pickaxe", Items.DIAMOND_PICKAXE);
		ICONS.put("sword", Items.IRON_SWORD);
		ICONS.put("portal", Items.OBSIDIAN);
		ICONS.put("star", Items.NETHER_STAR);
		ICONS.put("farm", Items.WHEAT);
		ICONS.put("village", Items.EMERALD);
		ICONS.put("spawner", Items.SPAWNER);
		ICONS.put("beacon", Items.BEACON);
		ICONS.put("boat", Items.OAK_BOAT);
		ICONS.put("end", Items.ENDER_EYE);
		ICONS.put("map", Items.FILLED_MAP);
		ICONS.put("anvil", Items.ANVIL);
		ICONS.put("craft", Items.CRAFTING_TABLE);
		ICONS.put("tnt", Items.TNT);
		ICONS.put("fire", Items.CAMPFIRE);
		ICONS.put("gold", Items.GOLD_INGOT);
		ICONS.put("compass", Items.COMPASS);
		ICONS.put(SKULL, Items.SKELETON_SKULL);
	}

	private static final Map<String, ItemStack> STACKS = new LinkedHashMap<>();

	private WaypointIcons() {
	}

	/** Every selectable key, "none" (a plain colored marker) first. */
	public static List<String> keys() {
		List<String> keys = new java.util.ArrayList<>();
		keys.add(NONE);
		keys.addAll(ICONS.keySet());
		return keys;
	}

	/** The item to draw for {@code key}, or null for {@link #NONE}/unknown keys (draw a colored marker instead). */
	public static ItemStack stack(String key) {
		if (key == null || !ICONS.containsKey(key)) {
			return null;
		}
		return STACKS.computeIfAbsent(key, k -> new ItemStack(ICONS.get(k)));
	}
}
