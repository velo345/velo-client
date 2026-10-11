package net.veloclient.velo.client.modules.pvp;

import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * 1.7 / 1.8 style animations, every part switchable on its own. Purely visual - nothing changes
 * what the server sees (no extra packets, attack timing stays the server's):
 * <ul>
 * <li><b>1.8 swing</b>: no "hand drops after every hit" attack-cooldown animation; the item stays
 * up like before 1.9.</li>
 * <li><b>Swing speed</b>: arm swing duration (vanilla 6 ticks).</li>
 * <li><b>1.7 sword blocking</b>: holding a sword while blocking with a shield (or, in "Use key"
 * mode, while holding right click) shows the classic sword-block pose, and the shield is hidden in
 * first person. Swinging while blocking gives the 1.7 "block-hit" look.</li>
 * <li><b>Swing while using items</b>: clicking while eating/drinking/blocking still swings your arm
 * (on your screen only, like 1.7).</li>
 * <li><b>Old rod / bow position</b>, <b>instant sneak camera</b> and the 1.8 sneak eye height,
 * <b>classic hurt camera shake</b> (always tilts the same way, like before 1.19.4 - newer versions
 * tilt away from wherever the hit came from). Item position/scale is its own module (Item Position).</li>
 * </ul>
 */
public final class OldAnimationsModule extends AbstractModule implements Configurable {

	private static final List<String> BLOCK_MODES = List.of("Off", "Shield In Offhand", "Use Key (Visual Only)");
	private static OldAnimationsModule instance;

	private boolean noCooldownDrop = true;
	private double swingTicks = 6;
	private String blockMode = "Shield In Offhand";
	private boolean hideShield = true;
	private boolean swingWhileUsing = true;
	private boolean oldRod = true;
	private boolean oldBow = true;
	private boolean instantSneak = false;
	private boolean oldSneakHeight = false;
	private boolean fixedHurtTilt = true;

	private static boolean blocking;
	private static boolean attackWasDown;

	public OldAnimationsModule() {
		super("old-animations", "Old Animations", "1.7 / 1.8 animations: no cooldown hand drop, sword blocking, block-hitting, old rod and more.",
				ModuleCategory.PVP, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
	}

	private static OldAnimationsModule active() {
		OldAnimationsModule m = instance;
		return m != null && m.isEnabled() ? m : null;
	}

	/** Runs at the start of every client tick (before vanilla handles input). */
	public static void tick() {
		OldAnimationsModule m = active();
		var player = PvpCompat.localPlayer();
		if (m == null || player == null) {
			blocking = false;
			return;
		}
		var main = PvpCompat.mainHand(player);
		var off = PvpCompat.offHand(player);
		boolean sword = PvpCompat.isSword(main);
		blocking = switch (m.blockMode) {
			case "Shield In Offhand" -> sword && PvpCompat.isShield(off) && player.isUsingItem() && PvpCompat.activeItem(player) == off;
			case "Use Key (Visual Only)" -> sword && OldAnimationsInput.useDown() && (!player.isUsingItem() || PvpCompat.isShield(PvpCompat.activeItem(player)));
			default -> false;
		};
		boolean attackDown = OldAnimationsInput.attackDown();
		if (m.swingWhileUsing && attackDown && !attackWasDown && (player.isUsingItem() || blocking)) {
			OldAnimationsInput.localSwing();
		}
		attackWasDown = attackDown;
	}

	public static boolean noCooldownDrop() {
		OldAnimationsModule m = active();
		return m != null && m.noCooldownDrop;
	}

	/** Multiplier for the local player's swing duration (1 = vanilla). */
	public static float swingScale() {
		OldAnimationsModule m = active();
		return m == null ? 1f : (float) (m.swingTicks / 6.0);
	}

	public static boolean swordBlocking() {
		return active() != null && blocking;
	}

	public static boolean hideShield() {
		OldAnimationsModule m = active();
		return m != null && blocking && m.hideShield;
	}

	public static boolean instantSneak() {
		OldAnimationsModule m = active();
		return m != null && m.instantSneak;
	}

	public static boolean oldSneakHeight() {
		OldAnimationsModule m = active();
		return m != null && m.oldSneakHeight;
	}

	public static boolean fixedHurtTilt() {
		OldAnimationsModule m = active();
		return m != null && m.fixedHurtTilt;
	}

	/** Old rod/bow pose offsets {x, y, z, yawDegrees} for {@code stack}, or null. */
	public static float[] oldItemPose(Object stack) {
		OldAnimationsModule m = active();
		if (m == null || !(stack instanceof net.minecraft.item.ItemStack itemStack)) {
			return null;
		}
		if (m.oldRod && PvpCompat.isFishingRod(itemStack)) {
			// 1.7/1.8 held the rod further out and pointing more forward.
			return new float[] {0.05f, 0.02f, -0.08f, -8f};
		}
		if (m.oldBow && PvpCompat.isBow(itemStack)) {
			// The old bow sat lower and closer to the center of the screen.
			return new float[] {-0.04f, -0.03f, 0f, 6f};
		}
		return null;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("1.8 Swing (No Cooldown Hand Drop)", () -> noCooldownDrop, v -> noCooldownDrop = v),
				new ConfigField.SliderField("Swing Duration (ticks)", 2, 16, () -> swingTicks, v -> swingTicks = Math.round(v),
						v -> (int) Math.round(v) + (Math.round(v) == 6 ? " (vanilla)" : "")),
				new ConfigField.ChoiceField("1.7 Sword Blocking", BLOCK_MODES, () -> blockMode, v -> blockMode = v),
				new ConfigField.ToggleField("Hide Shield While Sword-Blocking", () -> hideShield, v -> hideShield = v),
				new ConfigField.ToggleField("Swing While Using Items (Block-Hit)", () -> swingWhileUsing, v -> swingWhileUsing = v),
				new ConfigField.ToggleField("Old Fishing Rod Position", () -> oldRod, v -> oldRod = v),
				new ConfigField.ToggleField("Old Bow Position", () -> oldBow, v -> oldBow = v),
				new ConfigField.ToggleField("Instant Sneak Camera", () -> instantSneak, v -> instantSneak = v),
				new ConfigField.ToggleField("1.8 Sneak Eye Height", () -> oldSneakHeight, v -> oldSneakHeight = v),
				new ConfigField.ToggleField("Classic Hurt Shake (Same Side Every Hit)", () -> fixedHurtTilt, v -> fixedHurtTilt = v));
	}
}
