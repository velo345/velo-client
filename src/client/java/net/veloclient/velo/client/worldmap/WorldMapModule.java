package net.veloclient.velo.client.worldmap;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.gui.window.ModuleShortcut;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.ModuleRegistry;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Settings for the world map: whether it records at all, whether caves are recorded, and whether
 * coordinates and biomes are shown on the map (turn off when streaming so viewers can't find you).
 */
public final class WorldMapModule extends AbstractModule implements Configurable, ModuleShortcut {

	private boolean showCoordinates = true;
	private boolean showBiomes = true;
	private boolean caves = true;

	public WorldMapModule() {
		super("world-map", "World Map", "Maps everywhere you explore - open it with M. Turn off coordinates for streaming.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
	}

	private static WorldMapModule instance() {
		var module = ModuleRegistry.get("world-map").orElse(null);
		return module instanceof WorldMapModule map ? map : null;
	}

	static boolean recording() {
		WorldMapModule module = instance();
		return module == null || module.isEnabled();
	}

	static boolean recordCaves() {
		WorldMapModule module = instance();
		return module == null || module.caves;
	}

	static boolean showCoordinates() {
		WorldMapModule module = instance();
		return module == null || module.showCoordinates;
	}

	static boolean showBiomes() {
		WorldMapModule module = instance();
		return module == null || module.showBiomes;
	}

	@Override
	public String shortcutLabel() {
		return "Open World Map";
	}

	@Override
	public void openShortcut(net.minecraft.client.gui.screen.Screen parent) {
		MinecraftClient.getInstance().setScreen(new WorldMapScreen(parent));
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Open World Map Key", WorldMapKeys.OPEN_MAP),
				new ConfigField.ToggleField("Show Coordinates On The Map", () -> showCoordinates, v -> showCoordinates = v),
				new ConfigField.ToggleField("Show Biome Names On The Map", () -> showBiomes, v -> showBiomes = v),
				new ConfigField.ToggleField("Record Caves While Underground", () -> caves, v -> caves = v));
	}
}
