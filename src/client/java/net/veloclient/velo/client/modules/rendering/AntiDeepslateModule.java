package net.veloclient.velo.client.modules.rendering;

import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anti Deepslate: deepslate's texture is turned by a seed from its position, and ores and bedrock form patterns tied
 * to the world seed - so a screenshot or stream is enough for tools to find your base. This draws those blocks as one
 * plain block (netherite by default, any block can be chosen), which has no rotation and no pattern. Only the look
 * changes - AntiDeepslateModelMixin swaps the model the chunk builder (vanilla's and Sodium's) gets; hitboxes, mining
 * and the real blocks stay.
 *
 * <p>Anti Coords Leak switches it on by itself while you screen share ({@link #setForced}).
 */
public final class AntiDeepslateModule extends AbstractModule implements Configurable {

	private static AntiDeepslateModule instance;
	/** Read on chunk-builder threads: plain flags and an immutable map swapped whole. */
	private static volatile boolean active;
	private static volatile boolean forced;
	/** Block -> replacement block state (or the block itself mapped to null: shown as is). */
	private static volatile Map<Object, Object> cache = new IdentityHashMap<>();
	private static volatile Object replacement;

	private boolean deepslate = true;
	private boolean ores = true;
	private boolean bedrock = true;
	private boolean stoneTypes = true;
	private boolean otherRotated = false;
	private String extra = "";
	private String replaceWith = "minecraft:netherite_block";

	/**
	 * The underground stone around deepslate: tuff, diorite, andesite, granite and gravel blobs are placed by the world
	 * seed, stone and cobbled deepslate turn their texture by position - each can point tools at your base.
	 */
	private static final List<String> STONE_TYPES = List.of("stone", "cobbled_deepslate", "tuff", "gravel", "diorite", "andesite", "granite",
			"calcite", "dripstone_block", "smooth_basalt");

	/** Blocks whose texture turns by position too (besides deepslate), for "Other Rotated Blocks". */
	private static final List<String> ROTATED = List.of("grass_block", "dirt", "coarse_dirt", "sand", "red_sand", "stone", "netherrack", "tuff",
			"calcite", "mycelium", "podzol", "end_stone", "suspicious_sand", "gravel", "rooted_dirt", "dirt_path", "farmland", "mud", "basalt",
			"blackstone", "magma_block", "soul_sand", "soul_soil", "snow_block", "packed_ice", "clay");

	public AntiDeepslateModule() {
		super("anti-deepslate", "Anti Deepslate",
				"Draws deepslate, stone types, gravel, ores and bedrock as one plain block (netherite by default) so their rotation and patterns can't reveal "
						+ "where your base is.",
				ModuleCategory.RENDERING, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
	}

	/** Anti Coords Leak turning it on while you share your screen (without changing your own setting). */
	public static void setForced(boolean on) {
		if (forced == on) {
			return;
		}
		forced = on;
		refresh();
	}

	private static void refresh() {
		boolean next = forced || instance != null && instance.isEnabled();
		cache = new IdentityHashMap<>();
		replacement = null;
		if (next != active || next) {
			active = next;
			PerformanceBoostModule.reloadRendering();
		}
	}

	/**
	 * Called by AntiDeepslateModelMixin for every block state the chunk builder draws - must stay cheap. The block
	 * state to draw instead, or null for "as it is".
	 */
	public static Object replace(Object state) {
		if (!active || instance == null) {
			return null;
		}
		Object block = net.veloclient.velo.client.redstone.RmWorld.blockOf(state);
		Map<Object, Object> map = cache;
		if (map.containsKey(block)) {
			return map.get(block);
		}
		String id = net.veloclient.velo.client.redstone.RmWorld.id(state);
		Object to = instance.matches(id) ? target() : null;
		if (to != null && net.veloclient.velo.client.redstone.RmWorld.blockOf(to) == block) {
			to = null; // replacing a block with itself
		}
		Map<Object, Object> next = new IdentityHashMap<>(map);
		next.put(block, to);
		cache = next;
		return to;
	}

	private static Object target() {
		Object r = replacement;
		if (r == null) {
			String id = instance.replaceWith.isBlank() ? "minecraft:netherite_block" : instance.replaceWith.strip();
			r = net.veloclient.velo.client.redstone.RmWorld.parse(id.contains(":") ? id : "minecraft:" + id, Map.of());
			if (r == null || net.veloclient.velo.client.redstone.RmWorld.isAir(r)) {
				r = net.veloclient.velo.client.redstone.RmWorld.parse("minecraft:netherite_block", Map.of());
			}
			replacement = r;
		}
		return r;
	}

	private boolean matches(String id) {
		String path = id.startsWith("minecraft:") ? id.substring(10) : id;
		if (deepslate && (path.equals("deepslate") || path.equals("reinforced_deepslate"))) {
			return true;
		}
		if (ores && (path.endsWith("_ore") || path.equals("ancient_debris") || path.startsWith("raw_") && path.endsWith("_block")
				|| path.equals("budding_amethyst"))) {
			return true;
		}
		if (bedrock && path.equals("bedrock")) {
			return true;
		}
		if (stoneTypes && STONE_TYPES.contains(path)) {
			return true;
		}
		if (otherRotated && ROTATED.contains(path)) {
			return true;
		}
		if (!extra.isBlank()) {
			for (String part : extra.split("[,\\s]+")) {
				String want = part.strip();
				if (!want.isEmpty() && (want.equals(id) || want.equals(path))) {
					return true;
				}
			}
		}
		return false;
	}

	private void changed() {
		if (active) {
			refresh();
		}
	}

	@Override
	public void onEnable() {
		refresh();
	}

	@Override
	public void onDisable() {
		refresh();
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.TextField("Replace With", "minecraft:netherite_block", () -> replaceWith, v -> {
					replaceWith = v;
					changed();
				}),
				new ConfigField.ToggleField("Deepslate", () -> deepslate, v -> { deepslate = v; changed(); }),
				new ConfigField.ToggleField("Ores & Ancient Debris", () -> ores, v -> { ores = v; changed(); }),
				new ConfigField.ToggleField("Bedrock", () -> bedrock, v -> { bedrock = v; changed(); }),
				new ConfigField.ToggleField("Stone Types & Gravel (tuff, diorite, andesite, granite...)", () -> stoneTypes, v -> {
					stoneTypes = v;
					changed();
				}),
				new ConfigField.ToggleField("Other Rotated Blocks (grass, dirt, sand, stone...)", () -> otherRotated, v -> {
					otherRotated = v;
					changed();
				}),
				new ConfigField.TextField("More Blocks", "e.g. tuff, minecraft:calcite", () -> extra, v -> {
					extra = v;
					changed();
				}));
	}
}
