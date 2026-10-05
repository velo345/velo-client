package net.veloclient.velo.client.modules.hud;

import net.veloclient.velo.client.hud.SpriteTints;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Colors the in-game hotbar: the slot bar (and the offhand slot) and the outline around the
 * selected slot. Only the HUD hotbar - inventories and chests keep their normal look.
 */
public final class HotbarModule extends AbstractModule implements Configurable, SpriteTints.Provider {

	private int slotColor = 0xFFFF6B6B;
	private int selectionColor = 0xFFFFFFFF;
	private boolean colorSlots = true;
	private boolean colorSelection = true;

	public HotbarModule() {
		super("hotbar", "Hotbar Colors", "Color your hotbar's slots and the outline of the selected slot.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public int tintFor(String spritePath) {
		if (spritePath.equals("hud/hotbar_selection")) {
			return colorSelection ? selectionColor : 0;
		}
		if (spritePath.equals("hud/hotbar") || spritePath.startsWith("hud/hotbar_offhand")) {
			return colorSlots ? slotColor : 0;
		}
		return 0;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Color The Slots", () -> colorSlots, v -> colorSlots = v),
				new ConfigField.ColorField("Slot Color", () -> slotColor, v -> slotColor = v, true),
				new ConfigField.ToggleField("Color The Selected Slot Outline", () -> colorSelection, v -> colorSelection = v),
				new ConfigField.ColorField("Selection Outline Color", () -> selectionColor, v -> selectionColor = v, true));
	}
}
