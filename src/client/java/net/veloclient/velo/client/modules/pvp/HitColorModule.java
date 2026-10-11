package net.veloclient.velo.client.modules.pvp;

import net.veloclient.velo.client.pvp.HitColorTexture;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Custom color and strength for the flash when something gets hurt - separately for other players,
 * hostile mobs, passive mobs and yourself in third person - and on armor and armor trims too
 * (vanilla never tints armor; 1.8 did).
 *
 * <p>Bodies: vanilla tints hurt entities with one row of its small "overlay" texture. That texture
 * has spare rows, so each category gets its own row painted in your color, and the hurt check just
 * points at the right row - no extra rendering. Armor and trims use shaders without that overlay,
 * so for them the same color is multiplied into each layer's tint instead.
 */
public final class HitColorModule extends AbstractModule implements Configurable {

	public static final int SELF = 0;
	public static final int PLAYER = 1;
	public static final int HOSTILE = 2;
	public static final int PASSIVE = 3;
	/** Overlay texture row per category; row 3 stays vanilla red. Rows 0-7 are the "red" half vanilla samples for hurt. */
	private static final int[] ROWS = {0, 1, 2, 4};

	private static HitColorModule instance;

	private final boolean[] enabled = {true, true, true, true};
	private final int[] colors = {0xFF0000, 0xFF2A2A, 0xFF0000, 0xFF0000};
	/** 0-100 %; vanilla's red is about 30 %. */
	private final double[] strength = {30, 40, 30, 30};
	private boolean tintArmor = true;

	private boolean dirty = true;
	private boolean painted;

	public HitColorModule() {
		super("hit-color", "Hit Color", "Pick the hurt flash color and strength for players, mobs and yourself - and tint armor and trims too.",
				ModuleCategory.PVP, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
	}

	@Override
	public void onEnable() {
		dirty = true;
	}

	@Override
	public void onDisable() {
		if (painted && HitColorTexture.restore()) {
			painted = false;
		}
	}

	/** Applies pending color changes - every client tick (the renderer surely exists by then). */
	public static void tickAll() {
		HitColorModule m = instance;
		if (m == null || !m.isEnabled() || !m.dirty) {
			return;
		}
		int[] argb = new int[4];
		for (int i = 0; i < 4; i++) {
			argb[i] = (int) Math.round(m.strength[i] / 100.0 * 255) << 24 | (m.colors[i] & 0xFFFFFF);
		}
		if (HitColorTexture.paint(new int[] {ROWS[SELF], ROWS[PLAYER], ROWS[HOSTILE], ROWS[PASSIVE]}, argb)) {
			m.dirty = false;
			m.painted = true;
		}
	}

	/** The overlay texture row to use for a hurt entity of {@code category}, or -1 for vanilla. Render thread. */
	public static int rowFor(int category) {
		HitColorModule m = instance;
		if (m == null || !m.isEnabled() || category < 0 || !m.enabled[category]) {
			return -1;
		}
		return ROWS[category];
	}

	/**
	 * ARGB multiplier for a hurt entity's armor/trim layers, or -1 for none. Multiplying is weaker
	 * than the overlay mix, so the strength is boosted to look about the same as the body.
	 */
	public static int armorTint(int category) {
		HitColorModule m = instance;
		if (m == null || !m.isEnabled() || !m.tintArmor || category < 0 || !m.enabled[category]) {
			return -1;
		}
		double s = Math.min(1.0, m.strength[category] / 100.0 * 1.8);
		int c = m.colors[category];
		int r = (int) Math.round(255 * (1 - s) + ((c >> 16) & 0xFF) * s);
		int g = (int) Math.round(255 * (1 - s) + ((c >> 8) & 0xFF) * s);
		int b = (int) Math.round(255 * (1 - s) + (c & 0xFF) * s);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/** Multiplies two ARGB colors channel by channel. */
	public static int multiply(int a, int b) {
		int alpha = ((a >>> 24) * (b >>> 24)) / 255;
		int r = (((a >> 16) & 0xFF) * ((b >> 16) & 0xFF)) / 255;
		int g = (((a >> 8) & 0xFF) * ((b >> 8) & 0xFF)) / 255;
		int bl = ((a & 0xFF) * (b & 0xFF)) / 255;
		return alpha << 24 | r << 16 | g << 8 | bl;
	}

	/** Replaces the red-overlay row inside a packed overlay coordinate (u in the low 16 bits, v in the high). */
	public static int withRow(int packedOverlay, int row) {
		return (packedOverlay & 0xFFFF) | (row << 16);
	}

	private void section(List<ConfigField> fields, String title, int category) {
		fields.add(new ConfigField.HeaderField(title));
		fields.add(new ConfigField.ToggleField("Custom Color", () -> enabled[category], v -> enabled[category] = v));
		fields.add(new ConfigField.ColorField("Color", () -> 0xFF000000 | colors[category], v -> {
			colors[category] = v & 0xFFFFFF;
			dirty = true;
		}, false));
		fields.add(new ConfigField.SliderField("Strength", 5, 90, () -> strength[category], v -> {
			strength[category] = Math.round(v);
			dirty = true;
		}, v -> Math.round(v) + "%" + (Math.round(v) == 30 ? " (vanilla)" : "")));
	}

	@Override
	public List<ConfigField> configFields() {
		List<ConfigField> fields = new java.util.ArrayList<>();
		section(fields, "Other Players", PLAYER);
		section(fields, "Hostile Mobs", HOSTILE);
		section(fields, "Passive Mobs", PASSIVE);
		section(fields, "Yourself (Third Person)", SELF);
		fields.add(new ConfigField.HeaderField("Armor"));
		fields.add(new ConfigField.ToggleField("Tint Armor & Trims Too", () -> tintArmor, v -> tintArmor = v));
		return fields;
	}
}
