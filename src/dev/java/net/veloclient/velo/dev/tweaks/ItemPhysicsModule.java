package net.veloclient.velo.dev.tweaks;

import net.minecraft.util.math.Box;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Item Physics (dev build): dropped items lie flat on the ground instead of floating and spinning, each turned its own
 * way, and tumble while they fly through the air. Blocks sit on the ground. Only how they're drawn changes - the item
 * entity itself (and where you pick it up) is vanilla.
 */
public final class ItemPhysicsModule extends AbstractModule implements Configurable {

	private static ItemPhysicsModule instance;

	private double spinSpeed = 1.0;

	public ItemPhysicsModule() {
		super("item-physics", "Item Physics", "Dropped items lie flat on the ground and tumble through the air instead of floating and spinning.",
				ModuleCategory.RENDERING, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
	}

	public static boolean active() {
		ItemPhysicsModule m = instance;
		return m != null && m.isEnabled();
	}

	/**
	 * The pose for one dropped item, relative to the entity's position: replaces vanilla's bob + spin.
	 *
	 * @param grounded on the ground or in water
	 * @param age      the entity's age in ticks (with the partial tick)
	 * @param seed     the entity's own random offset (0..2pi), keeps every item turned its own way
	 * @param box      the item model's bounds (already in its "ground" display transform)
	 * @param count    how many copies vanilla draws for the stack
	 */
	public static Matrix4f pose(boolean grounded, float age, float seed, Box box, int count) {
		float cx = (float) (box.minX + box.maxX) / 2f;
		float cy = (float) (box.minY + box.maxY) / 2f;
		float cz = (float) (box.minZ + box.maxZ) / 2f;
		float depth = (float) (box.maxZ - box.minZ);
		boolean flat = depth <= 0.0625f;
		Matrix4f m = new Matrix4f();
		if (!grounded) {
			float size = (float) Math.max(box.maxX - box.minX, Math.max(box.maxY - box.minY, depth));
			float spin = age * 0.3f * (float) instance.spinSpeed;
			return m.translate(0f, size / 2f + 0.1f, 0f).rotateY(seed).rotateX(spin).translate(-cx, -cy, -cz);
		}
		if (flat) {
			// Vanilla spreads a flat stack's copies along z (1.5x the depth apart) - lying down, that's height.
			float spread = count > 1 ? depth * 1.5f * (count - 1) / 2f : 0f;
			return m.translate(0f, depth / 2f + spread + 0.005f, 0f).rotateY(seed).rotateX((float) (Math.PI / 2)).translate(-cx, -cy, -cz);
		}
		return m.rotateY(seed).translate(-cx, (float) -box.minY, -cz);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(new ConfigField.SliderField("Tumble Speed In The Air", 0, 3, () -> spinSpeed, v -> spinSpeed = Math.round(v * 10) / 10.0,
				v -> String.format("%.1fx", v)));
	}
}
