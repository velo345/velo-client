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
import java.util.Set;

/**
 * Hide Foliage: grass, ferns, dry grass, bushes, seagrass, nether sprouts and roots, leaf litter and the like simply
 * aren't drawn (HideFoliageMixin reports them as invisible to the chunk builder - vanilla's and Sodium's). Clearer
 * views in PvP and builds, and fewer polygons. Their hitboxes and the water around seagrass stay.
 */
public final class HideFoliageModule extends AbstractModule implements Configurable {

	private static final Set<String> GRASS = Set.of("short_grass", "grass", "tall_grass", "fern", "large_fern", "bush", "firefly_bush");
	private static final Set<String> DRY = Set.of("short_dry_grass", "tall_dry_grass", "dead_bush");
	private static final Set<String> WATER = Set.of("seagrass", "tall_seagrass");
	private static final Set<String> KELP = Set.of("kelp", "kelp_plant");
	private static final Set<String> NETHER = Set.of("nether_sprouts", "warped_roots", "crimson_roots", "hanging_roots");
	private static final Set<String> GROUND = Set.of("leaf_litter", "pink_petals", "wildflowers");
	private static final Set<String> FLOWERS = Set.of("dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
			"white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "torchflower", "closed_eyeblossom", "open_eyeblossom",
			"sunflower", "lilac", "rose_bush", "peony", "pitcher_plant");

	private static HideFoliageModule instance;
	/** Read on chunk-builder threads: a plain flag and an immutable map swapped whole. */
	private static volatile boolean active;
	private static volatile Map<Object, Boolean> cache = new IdentityHashMap<>();

	private boolean grass = true;
	private boolean dry = true;
	private boolean water = true;
	private boolean kelp = false;
	private boolean nether = true;
	private boolean ground = true;
	private boolean flowers = false;

	public HideFoliageModule() {
		super("hide-foliage", "Hide Foliage",
				"Hides grass, ferns, dry grass, bushes, seagrass, nether sprouts and roots, leaf litter and more - clearer views and fewer polygons.",
				ModuleCategory.RENDERING, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
	}

	/** Called by HideFoliageMixin for every block state the chunk builder asks about - must stay cheap. */
	public static boolean hidden(Object block) {
		if (!active) {
			return false;
		}
		Map<Object, Boolean> map = cache;
		Boolean known = map.get(block);
		if (known != null) {
			return known;
		}
		String id = blockId(block);
		boolean hide = instance != null && id != null && instance.matches(id);
		Map<Object, Boolean> next = new IdentityHashMap<>(map);
		next.put(block, hide);
		cache = next;
		return hide;
	}

	private boolean matches(String id) {
		return grass && GRASS.contains(id) || dry && DRY.contains(id) || water && WATER.contains(id) || kelp && KELP.contains(id)
				|| nether && NETHER.contains(id) || ground && GROUND.contains(id) || flowers && FLOWERS.contains(id);
	}

	private static String blockId(Object block) {
		try {
			//? if <26.1 {
			return net.minecraft.registry.Registries.BLOCK.getId((net.minecraft.block.Block) block).getPath();
			//?} else {
			/*return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey((net.minecraft.world.level.block.Block) block).getPath();
			*///?}
		} catch (RuntimeException e) {
			return null;
		}
	}

	private void changed() {
		cache = new IdentityHashMap<>();
		if (isEnabled()) {
			PerformanceBoostModule.reloadRendering();
		}
	}

	@Override
	public void onEnable() {
		cache = new IdentityHashMap<>();
		active = true;
		PerformanceBoostModule.reloadRendering();
	}

	@Override
	public void onDisable() {
		active = false;
		PerformanceBoostModule.reloadRendering();
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Grass & ferns", () -> grass, v -> { grass = v; changed(); }),
				new ConfigField.ToggleField("Dry grass & dead bushes", () -> dry, v -> { dry = v; changed(); }),
				new ConfigField.ToggleField("Seagrass", () -> water, v -> { water = v; changed(); }),
				new ConfigField.ToggleField("Kelp", () -> kelp, v -> { kelp = v; changed(); }),
				new ConfigField.ToggleField("Nether sprouts & roots", () -> nether, v -> { nether = v; changed(); }),
				new ConfigField.ToggleField("Leaf litter & petals", () -> ground, v -> { ground = v; changed(); }),
				new ConfigField.ToggleField("Flowers", () -> flowers, v -> { flowers = v; changed(); }));
	}
}
