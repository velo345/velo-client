package net.veloclient.velo.client.modules.hud;

import net.veloclient.velo.client.hud.SpriteTints;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Colors the experience bar (filled part and the empty track), the locator bar that shows other
 * players' directions in its place, and the XP level number above it.
 */
public final class XpBarModule extends AbstractModule implements Configurable, SpriteTints.Provider {

	private int progressColor = 0xFF5BC0FF;
	private int backgroundColor = 0xFFFFFFFF;
	private int locatorColor = 0xFF5BC0FF;
	private int levelTextColor = 0xFF5BC0FF;
	private boolean colorLevelText = true;

	public XpBarModule() {
		super("xp-bar", "XP & Locator Bar", "Color the experience bar, the locator bar and the XP level number.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public int tintFor(String spritePath) {
		if (spritePath.startsWith("hud/experience_bar_progress")) {
			return progressColor;
		}
		if (spritePath.startsWith("hud/experience_bar_background")) {
			return backgroundColor;
		}
		if (spritePath.startsWith("hud/locator_bar_background")) {
			return locatorColor;
		}
		return 0;
	}

	/** Level number color, or 0 for vanilla's green. */
	public int levelColor() {
		return colorLevelText ? levelTextColor | 0xFF000000 : 0;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ColorField("XP Bar Fill", () -> progressColor, v -> progressColor = v, true),
				new ConfigField.ColorField("XP Bar Background", () -> backgroundColor, v -> backgroundColor = v, true),
				new ConfigField.ColorField("Locator Bar", () -> locatorColor, v -> locatorColor = v, true),
				new ConfigField.ToggleField("Color The Level Number", () -> colorLevelText, v -> colorLevelText = v),
				new ConfigField.ColorField("Level Number Color", () -> levelTextColor, v -> levelTextColor = v, false));
	}
}
