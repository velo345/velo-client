package net.veloclient.velo.client.modules.cosmetics;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * 3D wings: whose are drawn (yours, other players') and whether they flap as you move. Off, no wings are drawn at
 * all. Your own always show in the Locker and the Store, so previews keep working. Read by WingsFeatureRenderer.
 */
public final class WingsCosmeticsModule extends AbstractModule implements Configurable, net.veloclient.velo.client.gui.window.ModuleShortcut {

	private static WingsCosmeticsModule instance;

	private boolean showMine = true;
	private boolean showOthers = true;
	private boolean flap = true;

	public WingsCosmeticsModule() {
		super("wings-cosmetics", "Wings", "Show or hide 3D wings - yours and other players' - and whether they flap as you move.",
				ModuleCategory.COSMETICS, SafetyTag.COSMETIC_ONLY, true);
		instance = this;
	}

	/** Whether wings on this player are drawn ({@code mine}: the local player). */
	public static boolean shows(boolean mine) {
		WingsCosmeticsModule m = instance;
		if (m == null) {
			return true;
		}
		if (!m.isEnabled()) {
			return previewing();
		}
		return mine ? m.showMine || previewing() : m.showOthers;
	}

	/** Whether wings move with the player (otherwise they rest). */
	public static boolean flaps() {
		return instance == null || instance.flap;
	}

	/** A Velo window (Locker, Store) is open: its previews always show your cosmetics. */
	static boolean previewing() {
		return net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.window.VeloWindow;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show My Wings", () -> showMine, v -> showMine = v),
				new ConfigField.ToggleField("Show Other Players' Wings", () -> showOthers, v -> showOthers = v),
				new ConfigField.ToggleField("Flap While Moving", () -> flap, v -> flap = v));
	}

	@Override
	public String shortcutLabel() {
		return "Open Locker";
	}

	@Override
	public void openShortcut(net.minecraft.client.gui.screen.Screen parent) {
		MinecraftClient.getInstance().setScreen(net.veloclient.velo.client.gui.CapeEquipScreen.open(parent, 2));
	}
}
