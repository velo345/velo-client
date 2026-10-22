package net.veloclient.velo.client.modules.hud;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * One look for every Velo HUD element (0.9.8): the font (Inter, Sora, Space Grotesk, Poppins, IBM Plex Sans, JetBrains Mono,
 * Audiowide or the Minecraft font), what's behind the text
 * (nothing, frosted glass or a solid panel), an accent colour and the text shadow. Read through
 * {@link net.veloclient.velo.client.hud.HudLook}. Switched off, the HUD looks exactly as it did before 0.9.8 -
 * Minecraft font, no panels. It's a module so it rides along with profiles like every other setting.
 */
public final class HudStyleModule extends AbstractModule implements Configurable {

	public static final List<String> FONTS = List.of("Inter", "Sora", "Space Grotesk", "Poppins", "IBM Plex Sans", "JetBrains Mono", "Audiowide", "Minecraft");
	public static final List<String> BACKGROUNDS = List.of("None", "Glass", "Solid");

	private static HudStyleModule instance;

	private String font = "Inter";
	private String background = "None";
	private int accent = 0xFFE0262E;
	private boolean shadow = true;
	private boolean rounded = true;

	public HudStyleModule() {
		super("hud-style", "HUD Style", "One font, background and accent colour for every HUD element - Inter, Sora, Space Grotesk, Poppins and more, "
				+ "on glass, solid or nothing.", ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
	}

	public static HudStyleModule get() {
		return instance;
	}

	public String font() {
		return font;
	}

	public String background() {
		return background;
	}

	public int accent() {
		return accent;
	}

	public boolean shadow() {
		return shadow;
	}

	public boolean rounded() {
		return rounded;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Font", FONTS, () -> font, v -> font = v),
				new ConfigField.ChoiceField("Background", BACKGROUNDS, () -> background, v -> background = v),
				new ConfigField.ColorField("Accent", () -> accent, v -> accent = v, false),
				new ConfigField.ToggleField("Text Shadow", () -> shadow, v -> shadow = v),
				new ConfigField.ToggleField("Rounded Corners", () -> rounded, v -> rounded = v));
	}
}
