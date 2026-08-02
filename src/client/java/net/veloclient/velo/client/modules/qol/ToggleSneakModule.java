package net.veloclient.velo.client.modules.qol;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Makes sneak a toggle instead of hold. Vanilla already has this built in
 * (Options &gt; Controls &gt; "Toggle Sneak") - this module previously tried
 * to reimplement it by forcing the sneak keybinding's pressed state every
 * tick, fighting vanilla's own input handling and the physical key's real
 * GLFW state, which is a losing battle: releasing the key fires vanilla's
 * own callback immediately and always wins the next frame. Flipping
 * vanilla's real toggle option instead means vanilla's own, already-correct
 * logic does the actual work - there's nothing left to fight.
 */
public final class ToggleSneakModule extends AbstractModule {

	private Boolean previousValue;

	public ToggleSneakModule() {
		super("toggle-sneak", "Toggle Sneak", "Press sneak once to toggle it on/off instead of holding it.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public void onEnable() {
		var option = MinecraftClient.getInstance().options.getSneakToggled();
		previousValue = option.getValue();
		option.setValue(true);
	}

	@Override
	public void onDisable() {
		if (previousValue != null) {
			MinecraftClient.getInstance().options.getSneakToggled().setValue(previousValue);
			previousValue = null;
		}
	}
}
