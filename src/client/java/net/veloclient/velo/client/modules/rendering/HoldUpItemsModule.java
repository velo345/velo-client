package net.veloclient.velo.client.modules.rendering;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Hold Up Items: in third person (and on other players) the arm holding an item is held straight out in front,
 * zombie-style - or both arms. Visual only, nothing is sent to the server. Applied by HoldUpItemsPoseMixin after
 * vanilla posed the model; a swing still plays normally.
 */
public final class HoldUpItemsModule extends AbstractModule implements Configurable {

	private static HoldUpItemsModule instance;

	private static final List<String> ARMS = List.of("Item Hand", "Both Arms");
	private static final List<String> WHO = List.of("Only Me", "Everyone");
	private String arms = "Item Hand";
	private String who = "Only Me";
	private boolean followLook = true;
	private boolean onlyWithItem = true;
	private double lift = 90;

	public HoldUpItemsModule() {
		super("hold-up-items", "Hold Up Items", "Holds your item arm (or both arms) straight out in front, zombie-style, in third person.",
				ModuleCategory.RENDERING, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
	}

	/**
	 * The new arm pitch (radians) for one arm, or NaN to leave vanilla's. {@code self}: the local player;
	 * {@code mainArm}: this is the main-hand arm; {@code itemHere}: this arm holds something; {@code anyItem}: either
	 * hand does; {@code headPitch} in radians; {@code swinging}: a swing is playing (left alone so hits still show).
	 */
	public static float armPitch(boolean self, boolean mainArm, boolean itemHere, boolean anyItem, float headPitch, boolean swinging) {
		HoldUpItemsModule m = instance;
		if (m == null || !m.isEnabled() || swinging || (!self && "Only Me".equals(m.who))) {
			return Float.NaN;
		}
		boolean raise = "Both Arms".equals(m.arms)
				? !m.onlyWithItem || anyItem
				: m.onlyWithItem ? itemHere : mainArm;
		if (!raise) {
			return Float.NaN;
		}
		float pitch = (float) -Math.toRadians(m.lift);
		return m.followLook ? pitch + headPitch * 0.8f : pitch;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Arms", ARMS, () -> arms, v -> arms = v),
				new ConfigField.ChoiceField("Show On", WHO, () -> who, v -> who = v),
				new ConfigField.ToggleField("Only While Holding An Item", () -> onlyWithItem, v -> onlyWithItem = v),
				new ConfigField.ToggleField("Arms Follow Where You Look", () -> followLook, v -> followLook = v),
				new ConfigField.SliderField("Arm Height", 45, 120, () -> lift, v -> lift = Math.round(v), v -> Math.round(v) + "°"));
	}
}
