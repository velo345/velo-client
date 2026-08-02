package net.veloclient.velo.client.modules.cosmetics;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.gui.CapeEquipScreen;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Master toggle + menu-opener for the cape cosmetic system (design spec
 * section 6.5). Purely client-rendered; see {@link net.veloclient.velo.client.cosmetics.render.CapeFeatureRenderer}.
 */
public final class CapeCosmeticsModule extends AbstractModule implements Configurable {

	public static final KeyBinding OPEN_CAPE_MENU = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.open_cape_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));

	public CapeCosmeticsModule() {
		super("cape-cosmetics", "Cosmetic Capes",
				"Equip a cloth-physics cape from your local library. Purely client-rendered.",
				ModuleCategory.COSMETICS, SafetyTag.COSMETIC_ONLY, true);
		CapeManager.loadLibrary();
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled()) {
			return;
		}
		while (OPEN_CAPE_MENU.wasPressed()) {
			if (client.currentScreen == null) {
				client.setScreen(new CapeEquipScreen(null));
			}
		}
	}

	@Override
	public void onDisable() {
		CapeManager.unequip();
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(KeybindConfig.field("Open Cape Menu Key", OPEN_CAPE_MENU));
	}
}
