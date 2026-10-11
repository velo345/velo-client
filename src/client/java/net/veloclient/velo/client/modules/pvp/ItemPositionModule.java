package net.veloclient.velo.client.modules.pvp;

import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where and how big your held items sit in first person - position, rotation and scale for the
 * main hand, the off hand (or a mirror of the main hand), and a "lower shield" offset so a raised
 * shield blocks less of the screen, and swapping which side each hand is drawn on. Purely visual.
 */
public final class ItemPositionModule extends AbstractModule implements Configurable {

	private static ItemPositionModule instance;

	private double mainX;
	private double mainY;
	private double mainZ;
	private double mainScale = 1;
	private double mainRotX;
	private double mainRotY;
	private double mainRotZ;
	private boolean mirrorOffhand = true;
	private double offX;
	private double offY;
	private double offZ;
	private double offScale = 1;
	private double shieldLower;
	private boolean swapHands;

	public ItemPositionModule() {
		super("item-position", "Item Position", "Move, rotate and resize your held items in first person - per hand, plus lower shield.",
				ModuleCategory.PVP, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
	}

	/** Draw the main-hand item on the off-hand side and vice versa (first person only - the server still sees your real main hand). */
	public static boolean swapHands() {
		ItemPositionModule m = instance;
		return m != null && m.isEnabled() && m.swapHands;
	}

	/** Arm to draw a hand on: the real one, or the other side when hands are swapped. Works on Arm/HumanoidArm enums. */
	public static <E extends Enum<E>> E arm(E real) {
		if (!swapHands()) {
			return real;
		}
		E[] values = real.getDeclaringClass().getEnumConstants();
		return values[(real.ordinal() + 1) % values.length];
	}

	/** {x, y, z, scale, rotX, rotY, rotZ} for the hand being drawn, or null when nothing changes. */
	public static float[] transform(boolean mainHand, Object stack) {
		ItemPositionModule m = instance;
		if (m == null || !m.isEnabled()) {
			return null;
		}
		boolean useMain = mainHand || m.mirrorOffhand;
		float x = (float) (useMain ? m.mainX : m.offX);
		float y = (float) (useMain ? m.mainY : m.offY);
		float z = (float) (useMain ? m.mainZ : m.offZ);
		float scale = (float) (useMain ? m.mainScale : m.offScale);
		float rx = useMain ? (float) m.mainRotX : 0f;
		float ry = useMain ? (float) m.mainRotY : 0f;
		float rz = useMain ? (float) m.mainRotZ : 0f;
		if (stack instanceof net.minecraft.item.ItemStack itemStack && PvpCompat.isShield(itemStack)) {
			y -= (float) m.shieldLower;
		}
		if (x == 0f && y == 0f && z == 0f && scale == 1f && rx == 0f && ry == 0f && rz == 0f) {
			return null;
		}
		return new float[] {x, y, z, scale, rx, ry, rz};
	}

	private static ConfigField offset(String label, java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set) {
		return new ConfigField.SliderField(label, -0.6, 0.6, get, v -> set.accept(Math.round(v * 100) / 100.0),
				v -> String.format(Locale.ROOT, "%+.2f", v));
	}

	private static ConfigField angle(String label, java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set) {
		return new ConfigField.SliderField(label, -90, 90, get, v -> set.accept(Math.round(v)), v -> Math.round(v) + "°");
	}

	private static ConfigField scale(String label, java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set) {
		return new ConfigField.SliderField(label, 0.3, 1.8, get, v -> set.accept(Math.round(v * 100) / 100.0),
				v -> String.format(Locale.ROOT, "%.2fx", v));
	}

	@Override
	public List<ConfigField> configFields() {
		List<ConfigField> fields = new ArrayList<>();
		fields.add(new ConfigField.HeaderField("Hands"));
		fields.add(new ConfigField.ToggleField("Swap Main & Off Hand (Visual)", () -> swapHands, v -> swapHands = v));
		fields.add(new ConfigField.HeaderField("Main Hand"));
		fields.add(offset("X (Left / Right)", () -> mainX, v -> mainX = v));
		fields.add(offset("Y (Down / Up)", () -> mainY, v -> mainY = v));
		fields.add(offset("Z (Closer / Further)", () -> mainZ, v -> mainZ = v));
		fields.add(scale("Scale", () -> mainScale, v -> mainScale = v));
		fields.add(angle("Tilt (X)", () -> mainRotX, v -> mainRotX = v));
		fields.add(angle("Turn (Y)", () -> mainRotY, v -> mainRotY = v));
		fields.add(angle("Roll (Z)", () -> mainRotZ, v -> mainRotZ = v));
		fields.add(new ConfigField.HeaderField("Off Hand"));
		fields.add(new ConfigField.ToggleField("Mirror Main Hand", () -> mirrorOffhand, v -> mirrorOffhand = v));
		fields.add(offset("Off Hand X", () -> offX, v -> offX = v));
		fields.add(offset("Off Hand Y", () -> offY, v -> offY = v));
		fields.add(offset("Off Hand Z", () -> offZ, v -> offZ = v));
		fields.add(scale("Off Hand Scale", () -> offScale, v -> offScale = v));
		fields.add(new ConfigField.HeaderField("Shield"));
		fields.add(new ConfigField.SliderField("Lower Shield", 0, 0.6, () -> shieldLower, v -> shieldLower = Math.round(v * 100) / 100.0,
				v -> String.format(Locale.ROOT, "%.2f", v)));
		return fields;
	}
}
