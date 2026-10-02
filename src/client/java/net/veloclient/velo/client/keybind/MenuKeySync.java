package net.veloclient.velo.client.keybind;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.config.ConfigManager;

/**
 * Keeps the Velo menu key (default Right Shift) in {@code config/menu-key.json} - shared with the
 * launcher's Settings, so it can be changed there, in Velo's own Settings tab, or in vanilla's
 * Controls screen, and every place shows the same key. The file wins at startup (that's how a
 * change made in the launcher arrives); after that, any change made in game is written back.
 * Keys use vanilla's own names ({@code key.keyboard.right.shift}), same as options.txt.
 */
public final class MenuKeySync {

	private static final String CONFIG_ID = "menu-key";

	private record Data(String key) {
	}

	private static String lastSynced;
	private static int ticks;

	private MenuKeySync() {
	}

	public static void register() {
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> applyFromFile());
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (++ticks % 100 == 0) {
				writeIfChanged();
			}
		});
	}

	private static void applyFromFile() {
		Data data = ConfigManager.load(CONFIG_ID, Data.class, null);
		KeyBinding binding = VeloKeybinds.OPEN_MOD_MENU;
		if (data == null || data.key() == null || data.key().isBlank()) {
			lastSynced = current(binding);
			ConfigManager.save(CONFIG_ID, new Data(lastSynced));
			return;
		}
		set(data.key());
	}

	/** Sets the menu key from its vanilla name (e.g. {@code key.keyboard.insert}); unknown names are ignored. */
	public static void set(String keyName) {
		KeyBinding binding = VeloKeybinds.OPEN_MOD_MENU;
		try {
			//? if <26.1 {
			binding.setBoundKey(InputUtil.fromTranslationKey(keyName));
			//?} else {
			/*binding.setKey(InputUtil.getKey(keyName));
			*///?}
			KeyBinding.updateKeysByCode();
			//? if <26.1 {
			MinecraftClient.getInstance().options.write();
			//?} else {
			/*MinecraftClient.getInstance().options.save();
			*///?}
		} catch (RuntimeException ignored) {
			return;
		}
		lastSynced = current(binding);
		ConfigManager.save(CONFIG_ID, new Data(lastSynced));
	}

	private static void writeIfChanged() {
		String now = current(VeloKeybinds.OPEN_MOD_MENU);
		if (!now.equals(lastSynced)) {
			lastSynced = now;
			ConfigManager.save(CONFIG_ID, new Data(now));
		}
	}

	private static String current(KeyBinding binding) {
		return binding.getBoundKeyTranslationKey();
	}
}
