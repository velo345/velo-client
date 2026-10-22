package net.veloclient.velo.client.modules.cosmetics;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Auras: whose are drawn (yours, other players') and how many particles other players' use. Off, no auras are drawn
 * at all. Your own always show in the Locker and the Store, so previews keep working. Read by AuraFeatureRenderer.
 */
public final class AuraCosmeticsModule extends AbstractModule implements Configurable, net.veloclient.velo.client.gui.window.ModuleShortcut {

	private static AuraCosmeticsModule instance;

	private boolean showMine = true;
	private boolean showOthers = true;
	private boolean fewerOthers = false;

	public AuraCosmeticsModule() {
		super("aura-cosmetics", "Auras", "Show or hide auras - yours and other players' - and calm down other players' particles in busy fights.",
				ModuleCategory.COSMETICS, SafetyTag.COSMETIC_ONLY, true);
		instance = this;
	}

	/** Whether the aura on this player is drawn ({@code mine}: the local player). */
	public static boolean shows(boolean mine) {
		AuraCosmeticsModule m = instance;
		if (m == null) {
			return true;
		}
		if (!m.isEnabled()) {
			return previewing();
		}
		return mine ? m.showMine || previewing() : m.showOthers;
	}

	/** Whether other players' auras use half the particles. */
	public static boolean fewerForOthers() {
		return instance != null && instance.fewerOthers;
	}

	/** A Velo window (Locker, Store) is open: its previews always show your cosmetics. */
	static boolean previewing() {
		return net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.window.VeloWindow;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show My Aura", () -> showMine, v -> showMine = v),
				new ConfigField.ToggleField("Show Other Players' Auras", () -> showOthers, v -> showOthers = v),
				new ConfigField.ToggleField("Fewer Particles On Others", () -> fewerOthers, v -> fewerOthers = v));
	}

	@Override
	public String shortcutLabel() {
		return "Open Locker";
	}

	@Override
	public void openShortcut(net.minecraft.client.gui.screen.Screen parent) {
		MinecraftClient.getInstance().setScreen(net.veloclient.velo.client.gui.CapeEquipScreen.open(parent, 3));
	}
}
