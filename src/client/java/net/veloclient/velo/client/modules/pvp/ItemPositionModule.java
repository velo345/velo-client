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
 * Where and how big your held items sit in first person - position, rotation and scale, for all items at once or
 * per item type (swords, axes, maces, blocks...), the empty hand included. The off hand mirrors the main hand or
 * gets its own offset, a raised shield can sit lower, and the hands can swap sides. Purely visual.
 */
public final class ItemPositionModule extends AbstractModule implements Configurable {

	/** The item types that can get their own position; index 0 is the shared one every other type falls back to. */
	public static final List<String> TYPES = List.of("All Items", "Empty Hand", "Swords", "Axes", "Maces", "Pickaxes & Tools",
			"Bows & Crossbows", "Tridents & Spears", "Shields", "Totems", "Blocks", "Food & Potions", "Pearls & Throwables", "Other Items");

	private static ItemPositionModule instance;

	/** One position per type: {x, y, z, scale, rotX, rotY, rotZ}; {@code own[i]} = that type uses its own values. */
	private final double[][] pos = new double[TYPES.size()][];
	private final boolean[] own = new boolean[TYPES.size()];
	private String editing = TYPES.get(0);

	private boolean mirrorOffhand = true;
	private double offX;
	private double offY;
	private double offZ;
	private double offScale = 1;
	private double shieldLower;
	private boolean swapHands;

	public ItemPositionModule() {
		super("item-position", "Item Position", "Move, rotate and resize your held items and hand in first person - for all items or per item type.",
				ModuleCategory.PVP, SafetyTag.COSMETIC_ONLY, false);
		for (int i = 0; i < pos.length; i++) {
			pos[i] = new double[] {0, 0, 0, 1, 0, 0, 0};
		}
		own[0] = true;
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

	/** Which of {@link #TYPES} an item stack belongs to. */
	static int typeOf(Object stack) {
		if (!(stack instanceof net.minecraft.item.ItemStack item) || item.isEmpty()) {
			return 1;
		}
		String id = PvpCompat.itemId(item);
		String path = id.substring(id.indexOf(':') + 1);
		if (path.endsWith("_sword")) return 2;
		if (path.endsWith("_axe")) return 3;
		if (path.equals("mace")) return 4;
		if (path.endsWith("_pickaxe") || path.endsWith("_shovel") || path.endsWith("_hoe") || path.equals("shears")
				|| path.equals("flint_and_steel") || path.equals("fishing_rod") || path.equals("brush")) return 5;
		if (path.equals("bow") || path.equals("crossbow")) return 6;
		if (path.equals("trident") || path.endsWith("_spear")) return 7;
		if (PvpCompat.isShield(item)) return 8;
		if (path.equals("totem_of_undying")) return 9;
		if (path.equals("ender_pearl") || path.equals("snowball") || path.equals("egg") || path.endsWith("_egg") && !path.endsWith("spawn_egg")
				|| path.equals("wind_charge") || path.endsWith("splash_potion") || path.endsWith("lingering_potion") || path.equals("experience_bottle")) return 12;
		if (PvpCompat.isBlockItem(item)) return 10;
		if (PvpCompat.food(item) != null || path.equals("potion") || path.equals("milk_bucket") || path.equals("honey_bottle")) return 11;
		return 13;
	}

	private double[] profileFor(Object stack) {
		int t = typeOf(stack);
		return own[t] ? pos[t] : pos[0];
	}

	/** {x, y, z, scale, rotX, rotY, rotZ} for the hand being drawn, or null when nothing changes. */
	public static float[] transform(boolean mainHand, Object stack) {
		ItemPositionModule m = instance;
		if (m == null || !m.isEnabled()) {
			return null;
		}
		double[] p = m.profileFor(stack);
		boolean useMain = mainHand || m.mirrorOffhand;
		float x = (float) (useMain ? p[0] : m.offX);
		float y = (float) (useMain ? p[1] : m.offY);
		float z = (float) (useMain ? p[2] : m.offZ);
		float scale = (float) (useMain ? p[3] : m.offScale);
		float rx = useMain ? (float) p[4] : 0f;
		float ry = useMain ? (float) p[5] : 0f;
		float rz = useMain ? (float) p[6] : 0f;
		if (stack instanceof net.minecraft.item.ItemStack itemStack && PvpCompat.isShield(itemStack)) {
			y -= (float) m.shieldLower;
		}
		if (x == 0f && y == 0f && z == 0f && scale == 1f && rx == 0f && ry == 0f && rz == 0f) {
			return null;
		}
		return new float[] {x, y, z, scale, rx, ry, rz};
	}

	/** The empty hand's offset {x, y, z} (it is drawn without an item, through a different path), or null. */
	public static float[] armOffset(boolean mainHand) {
		float[] t = transform(mainHand, null);
		return t == null || t[0] == 0f && t[1] == 0f && t[2] == 0f ? null : new float[] {t[0], t[1], t[2]};
	}

	// ---- settings: the sliders edit the type picked in "Edit Item Type" ----

	private int editIndex() {
		return Math.max(0, TYPES.indexOf(editing));
	}

	private double get(int k) {
		int i = editIndex();
		return (own[i] ? pos[i] : pos[0])[k];
	}

	private void set(int k, double v) {
		int i = editIndex();
		if (!own[i]) { // first change to a type: start from the shared values
			pos[i] = pos[0].clone();
			own[i] = true;
		}
		pos[i][k] = v;
	}

	private ConfigField offset(String label, int k) {
		return new ConfigField.SliderField(label, -1.5, 1.5, () -> get(k), v -> set(k, Math.round(v * 100) / 100.0),
				v -> String.format(Locale.ROOT, "%+.2f", v));
	}

	private ConfigField angle(String label, int k) {
		return new ConfigField.SliderField(label, -180, 180, () -> get(k), v -> set(k, Math.round(v)), v -> Math.round(v) + "°");
	}

	private static ConfigField offOffset(String label, java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set) {
		return new ConfigField.SliderField(label, -1.5, 1.5, get, v -> set.accept(Math.round(v * 100) / 100.0),
				v -> String.format(Locale.ROOT, "%+.2f", v));
	}

	@Override
	public List<ConfigField> configFields() {
		List<ConfigField> fields = new ArrayList<>();
		fields.add(new ConfigField.HeaderField("Hands"));
		fields.add(new ConfigField.ToggleField("Swap Main & Off Hand (Visual)", () -> swapHands, v -> swapHands = v));
		fields.add(new ConfigField.HeaderField("Main Hand"));
		fields.add(new ConfigField.ChoiceField("Edit Item Type", TYPES, () -> editing, v -> editing = v));
		fields.add(new ConfigField.ToggleField("Own Position For This Type", () -> own[editIndex()], v -> {
			int i = editIndex();
			if (i == 0) {
				return; // the shared one is always on
			}
			if (v && !own[i]) {
				pos[i] = pos[0].clone();
			}
			own[i] = v;
		}));
		fields.add(offset("X (Left / Right)", 0));
		fields.add(offset("Y (Down / Up)", 1));
		fields.add(offset("Z (Closer / Further)", 2));
		fields.add(new ConfigField.SliderField("Scale", 0.1, 3, () -> get(3), v -> set(3, Math.round(v * 100) / 100.0),
				v -> String.format(Locale.ROOT, "%.2fx", v)));
		fields.add(angle("Tilt (X)", 4));
		fields.add(angle("Turn (Y)", 5));
		fields.add(angle("Roll (Z)", 6));
		fields.add(new ConfigField.ActionButtonField("Reset This Type", () -> {
			int i = editIndex();
			pos[i] = new double[] {0, 0, 0, 1, 0, 0, 0};
			own[i] = i == 0;
		}));
		fields.add(new ConfigField.HeaderField("Off Hand"));
		fields.add(new ConfigField.ToggleField("Mirror Main Hand", () -> mirrorOffhand, v -> mirrorOffhand = v));
		fields.add(offOffset("Off Hand X", () -> offX, v -> offX = v));
		fields.add(offOffset("Off Hand Y", () -> offY, v -> offY = v));
		fields.add(offOffset("Off Hand Z", () -> offZ, v -> offZ = v));
		fields.add(new ConfigField.SliderField("Off Hand Scale", 0.1, 3, () -> offScale, v -> offScale = Math.round(v * 100) / 100.0,
				v -> String.format(Locale.ROOT, "%.2fx", v)));
		fields.add(new ConfigField.HeaderField("Shield"));
		fields.add(new ConfigField.SliderField("Lower Shield", 0, 0.8, () -> shieldLower, v -> shieldLower = Math.round(v * 100) / 100.0,
				v -> String.format(Locale.ROOT, "%.2f", v)));
		return fields;
	}

	/** Every type's values: "index:own:x,y,z,scale,rx,ry,rz;..." */
	@Override
	public String extraState() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < pos.length; i++) {
			if (!own[i]) {
				continue;
			}
			sb.append(i).append(':');
			for (int k = 0; k < 7; k++) {
				sb.append(k == 0 ? "" : ",").append(pos[i][k]);
			}
			sb.append(';');
		}
		return sb.toString();
	}

	@Override
	public void restoreExtraState(String state) {
		editing = TYPES.get(0);
		for (String part : state.split(";")) {
			try {
				int colon = part.indexOf(':');
				if (colon < 0) {
					continue;
				}
				int i = Integer.parseInt(part.substring(0, colon));
				String[] v = part.substring(colon + 1).split(",");
				if (i < 0 || i >= pos.length || v.length != 7) {
					continue;
				}
				for (int k = 0; k < 7; k++) {
					pos[i][k] = Double.parseDouble(v[k]);
				}
				own[i] = true;
			} catch (NumberFormatException ignored) {
				// one bad entry shouldn't lose the rest
			}
		}
	}
}
