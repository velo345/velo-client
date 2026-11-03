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
import net.veloclient.velo.client.util.ModuleProfiler;

import java.util.List;

/**
 * Master toggle + menu-opener for the cape cosmetic system (design spec
 * section 6.5). Purely client-rendered; see {@link net.veloclient.velo.client.cosmetics.render.CapeFeatureRenderer}.
 */
public final class CapeCosmeticsModule extends AbstractModule implements Configurable, net.veloclient.velo.client.gui.window.ModuleShortcut {

	private static final String CLOTH = "Cloth physics";
	private static final String CLASSIC = "Classic";
	/** How cloaks are drawn (yours and everyone else's): real cloth, or vanilla's rigid cape and motion. */
	private static volatile String style = CLOTH;

	private static final String WAVEY = "Wavey Capes";
	/** With Wavey Capes installed: let it animate the cloaks (the old, only behaviour) instead of Velo. */
	private static volatile boolean useWavey = true;

	public static boolean clothPhysics() {
		return !CLASSIC.equals(style);
	}

	/** True when Wavey Capes draws the cloaks: installed, and (in a dev build) picked as the cloak style. */
	public static boolean waveyActive() {
		return net.veloclient.velo.client.cosmetics.WaveyCapesCompat.isLoaded() && (!net.veloclient.velo.client.Preview.ON || useWavey);
	}

	public static final KeyBinding OPEN_CAPE_MENU = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.open_cape_menu", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.UNKNOWN, VeloKeybinds.CATEGORY));

	public CapeCosmeticsModule() {
		super("cape-cosmetics", "Cosmetic Cloaks",
				"Equip a cloth-physics cloak from your local library. Purely client-rendered.",
				ModuleCategory.COSMETICS, SafetyTag.COSMETIC_ONLY, true);
		CapeManager.loadLibrary();
		ClientTickEvents.END_CLIENT_TICK.register(client ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.TICK, () -> onTick(client)));
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled()) {
			return;
		}
		while (OPEN_CAPE_MENU.wasPressed()) {
			//? if <26.1 {
			if (client.currentScreen == null) {
			//?} else if <26.2 {
			/*if (client.screen == null) {
			*///?} else {
			/*if (client.gui.screen() == null) {
			*///?}
				client.setScreen(new CapeEquipScreen(null));
			}
		}
	}

	@Override
	public void onDisable() {
		CapeManager.unequip();
	}

	@Override
	public String shortcutLabel() {
		return "Open Locker";
	}

	@Override
	public void openShortcut(net.minecraft.client.gui.screen.Screen parent) {
		net.minecraft.client.MinecraftClient.getInstance().setScreen(net.veloclient.velo.client.gui.CapeEquipScreen.open(parent, 1));
	}

	/** Cloth / Classic, plus Wavey Capes when it's installed (dev build). */
	private static ConfigField styleField() {
		boolean wavey = net.veloclient.velo.client.Preview.ON && net.veloclient.velo.client.cosmetics.WaveyCapesCompat.isLoaded();
		if (!wavey) {
			return new ConfigField.ChoiceField("Cloak Style", List.of(CLOTH, CLASSIC), () -> style, v -> style = v);
		}
		return new ConfigField.ChoiceField("Cloak Style", List.of(CLOTH, CLASSIC, WAVEY), () -> useWavey ? WAVEY : style, v -> {
			useWavey = WAVEY.equals(v);
			if (!useWavey) {
				style = v;
			}
		});
	}

	/** For the first-start guide's preview: "Cloth physics", "Classic" or "Wavey Capes". */
	public static String styleName() {
		return waveyActive() ? WAVEY : style;
	}

	public static void setStyleName(String value) {
		useWavey = WAVEY.equals(value);
		if (!useWavey && (CLOTH.equals(value) || CLASSIC.equals(value))) {
			style = value;
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				styleField(),
				KeybindConfig.field("Open Cloak Menu Key", OPEN_CAPE_MENU));
	}
}
