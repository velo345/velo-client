package net.veloclient.velo.client.modules.qol;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/** Settings for {@link ShulkerPreview}: Shift shows what's inside, Ctrl+Shift opens the edit panel. */
public final class ShulkerPreviewModule extends AbstractModule implements Configurable {

	private boolean bundles = true;
	private boolean editing = true;
	private boolean tint = true;

	public ShulkerPreviewModule() {
		super("shulker-preview", "Shulker Preview",
				"Hold Shift over a shulker box or bundle to see what's inside. Ctrl+Shift opens it next to your inventory so you can "
						+ "move items in and out (singleplayer and the Creative inventory).",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
	}

	boolean bundles() {
		return bundles;
	}

	boolean editing() {
		return editing;
	}

	boolean tint() {
		return tint;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Preview Bundles Too", () -> bundles, v -> bundles = v),
				new ConfigField.ToggleField("Ctrl+Shift To Edit", () -> editing, v -> editing = v),
				new ConfigField.ToggleField("Tint In The Box's Color", () -> tint, v -> tint = v));
	}
}
