package net.veloclient.velo.dev.storage;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Storage Memory (dev build): remembers what's in every chest, barrel, shulker box and your ender chest per server
 * (and per sub-server behind a proxy), plus villager trades and work stations. Y opens the Storage menu: search an
 * item to see every container that holds it (also inside shulker boxes and bundles) and get pointed there; items you
 * don't have show their recipe - locked recipes too - with where the ingredients are, and the shortest walk past
 * containers that together hold enough. Party Sync (opt-in) shares all of it with your Velo party.
 */
public final class StorageMemoryModule extends AbstractModule implements Configurable, net.veloclient.velo.client.gui.window.ModuleShortcut {

	public static final KeyBinding OPEN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.storage_memory", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.Y, VeloKeybinds.CATEGORY));

	private static StorageMemoryModule instance;
	private static boolean partySync;

	public StorageMemoryModule() {
		super("storage-memory", "Storage Memory",
				"Remembers what's in your chests, shulker boxes and ender chest per server, plus villager trades and work stations. Y opens the search.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
		StorageCapture.register();
		StorageHighlight.register();
		StoragePartySync.register();
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> StorageStore.flush(true));
	}

	public static boolean active() {
		return instance != null && instance.isEnabled();
	}

	/** Share storage with your Velo party and see theirs (off unless you turn it on). */
	public static boolean partySync() {
		return partySync;
	}

	public static void setPartySync(boolean value) {
		partySync = value;
	}

	private void onTick(MinecraftClient client) {
		while (OPEN_KEY.wasPressed()) {
			if (client.player != null && ClientCompat.currentScreen() == null) {
				client.setScreen(new StorageScreen(null));
			}
		}
	}

	@Override
	public String shortcutLabel() {
		return "Open Storage";
	}

	@Override
	public void openShortcut(net.minecraft.client.gui.screen.Screen parent) {
		MinecraftClient.getInstance().setScreen(new StorageScreen(parent));
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Storage Menu Key", OPEN_KEY),
				new ConfigField.ToggleField("Party Sync (share with your Velo party)", () -> partySync, v -> partySync = v),
				new ConfigField.ActionButtonField("Stop Pointing At Target", StorageHighlight::clear));
	}
}
