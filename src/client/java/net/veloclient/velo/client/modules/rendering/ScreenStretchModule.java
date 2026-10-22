package net.veloclient.velo.client.modules.rendering;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Locale;

/**
 * Stretched resolution, like the Rescale mods: the world is drawn as if the screen had another shape (4:3, 5:4...)
 * and stretched to fill yours - wider players and blocks, a narrower view, the look competitive players run on
 * wide monitors. Only the 3D view changes: the HUD, chat and menus stay sharp and in place.
 */
public final class ScreenStretchModule extends AbstractModule implements Configurable {

	public static final KeyBinding TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.screen-stretch-toggle", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.UNKNOWN, VeloKeybinds.CATEGORY));

	private static final List<String> SHAPES = List.of("4:3", "5:4", "3:2", "16:10", "1:1", "Custom");

	private static ScreenStretchModule instance;

	private String shape = "4:3";
	private double customAspect = 1.5;
	/** How much of the stretch to apply (0 = none, 100 = the full shape) - a softer middle ground. */
	private double strength = 100;

	public ScreenStretchModule() {
		super("screen-stretch", "Screen Stretch",
				"Stretched resolution: draw the world as 4:3, 5:4, 16:10 or your own shape and stretch it across your monitor - "
						+ "the HUD stays sharp.", ModuleCategory.RENDERING, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (TOGGLE_KEY.wasPressed()) {
				setEnabled(!isEnabled());
			}
		});
	}

	private float targetAspect() {
		return switch (shape) {
			case "5:4" -> 5f / 4f;
			case "3:2" -> 3f / 2f;
			case "16:10" -> 16f / 10f;
			case "1:1" -> 1f;
			case "Custom" -> (float) customAspect;
			default -> 4f / 3f;
		};
	}

	/** The aspect the 3D view is projected with for a screen of {@code nativeAspect}, or that aspect itself when off. */
	public static float aspect(float nativeAspect) {
		ScreenStretchModule module = instance;
		if (module == null || !module.isEnabled() || !(nativeAspect > 0)
				|| net.minecraft.client.MinecraftClient.getInstance().player == null) {
			return nativeAspect;
		}
		float t = (float) (module.strength / 100.0);
		return nativeAspect + (module.targetAspect() - nativeAspect) * t;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Toggle On/Off", TOGGLE_KEY),
				new ConfigField.ChoiceField("Shape", SHAPES, () -> shape, v -> shape = v),
				new ConfigField.SliderField("Custom Shape (width : height)", 0.8, 2.4, () -> customAspect,
						v -> customAspect = Math.round(v * 100) / 100.0, v -> String.format(Locale.ROOT, "%.2f : 1", v)),
				new ConfigField.SliderField("Strength", 0, 100, () -> strength, v -> strength = Math.round(v),
						v -> String.format(Locale.ROOT, "%.0f%%", v)));
	}
}
