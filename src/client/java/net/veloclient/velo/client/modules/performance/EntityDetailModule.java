package net.veloclient.velo.client.modules.performance;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Renderer experiment #1 - "entity level of detail": past a distance, mobs and players are drawn
 * without their extra layers (armor, held items, capes, saddles, overlays...). Those layers are
 * each extra draws per entity, so in crowded places (lobbies, farms, PvP) this cuts submitted
 * geometry a lot while the body still shows. Works the same on Vulkan and OpenGL - it only
 * changes what is submitted, not how. Off by default while it's being tested.
 */
public final class EntityDetailModule extends AbstractModule implements Configurable {

	private static volatile double mobDistanceSq = 32 * 32;
	private static volatile double playerDistanceSq = 64 * 64;
	private static volatile boolean active;

	public EntityDetailModule() {
		super("entity-detail", "Entity Detail Distance (Experimental)",
				"Far-away mobs and players skip armor, held items and other extra layers - big FPS gains in crowded areas.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public void onEnable() {
		active = true;
	}

	@Override
	public void onDisable() {
		active = false;
	}

	/** Whether an entity this far away should skip its extra layers. */
	public static boolean skipLayers(boolean player, double distanceSq) {
		return active && distanceSq > (player ? playerDistanceSq : mobDistanceSq);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Mob Detail Distance", 8, 128, () -> Math.sqrt(mobDistanceSq),
						v -> mobDistanceSq = v * v, v -> Math.round(v) + " blocks"),
				new ConfigField.SliderField("Player Detail Distance", 8, 128, () -> Math.sqrt(playerDistanceSq),
						v -> playerDistanceSq = v * v, v -> Math.round(v) + " blocks"));
	}
}
