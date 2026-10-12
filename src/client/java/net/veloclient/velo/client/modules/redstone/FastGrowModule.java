package net.veloclient.velo.client.modules.redstone;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Singleplayer: everything that grows on random ticks grows faster - crops, saplings, amethyst
 * buds, sugar cane, cactus, bamboo, kelp, vines, cocoa, berries - by scaling the random ticks each
 * chunk gets (vanilla 3 per section per tick) without touching the world's gamerule, so leaving it
 * off or closing the world leaves the save exactly as it was. Side effect: anything else on random
 * ticks (ice melting, leaves decaying, fire spreading, copper oxidising) speeds up too.
 */
public final class FastGrowModule extends AbstractModule implements Configurable {

	public static final KeyBinding TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.fast_grow", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.UNKNOWN, VeloKeybinds.CATEGORY));

	private static volatile int multiplier = 1;
	private double speed = 10;

	public FastGrowModule() {
		super("fast-grow", "Fast Grow", "Singleplayer: crops, amethyst, saplings and other growing blocks grow up to 100x faster.",
				ModuleCategory.REDSTONE, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (TOGGLE_KEY.wasPressed()) {
				setEnabled(!isEnabled());
				NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Fast Grow " + (isEnabled() ? "on" : "off"),
						isEnabled() ? Math.round(speed) + "x growth speed" : "Back to normal growth", 2000, null, null, null));
			}
			multiplier = isEnabled() && ClientCompat.isSingleplayer() ? (int) Math.max(1, Math.round(speed)) : 1;
		});
	}

	/** Server thread: the random tick count to use for a chunk this tick. */
	public static int scale(int vanilla) {
		int m = multiplier;
		return m <= 1 || vanilla <= 0 ? vanilla : (int) Math.min(4096, (long) vanilla * m);
	}

	@Override
	public void onDisable() {
		multiplier = 1;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Growth Speed", 2, 100, () -> speed, v -> speed = Math.round(v), v -> Math.round(v) + "x"),
				KeybindConfig.field("Toggle Key", TOGGLE_KEY));
	}
}
