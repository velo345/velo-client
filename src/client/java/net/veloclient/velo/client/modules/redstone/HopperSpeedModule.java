package net.veloclient.velo.client.modules.redstone;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Hopper speed for testing redstone in singleplayer (EasyHopper-style): items per transfer
 * (1-64), the transfer cooldown (vanilla 8 game ticks), turning hopper transfers off entirely,
 * whether hoppers pick up dropped items, and whether hopper minecarts follow too.
 *
 * <p>Every container behaves exactly like vanilla: "N items per transfer" runs vanilla's own
 * one-item hopper move N times, so crafters (also with a hopper underneath), composters,
 * furnaces, brewing stands, jukeboxes, shulker boxes, chiseled bookshelves, minecarts... keep
 * their own slot rules and nothing can duplicate. Only active in singleplayer - the settings live
 * on the integrated server ({@code HopperSpeedMixin}).
 */
public final class HopperSpeedModule extends AbstractModule implements Configurable {

	/** Read on the server thread by the mixin. */
	public static volatile boolean active;
	public static volatile int itemsPerTransfer = 1;
	public static volatile int cooldown = 8;
	public static volatile boolean transfersOff;
	public static volatile boolean pickUpItems = true;
	public static volatile boolean minecarts = true;
	/** Re-entry guard while a transfer repeats vanilla's single-item move (server thread only). */
	public static boolean repeating;

	private double items = 1;
	private double ticks = 8;
	private boolean off;
	private boolean pickup = true;
	private boolean carts = true;

	public HopperSpeedModule() {
		super("hopper-speed", "Hopper Speed", "Singleplayer: items per hopper transfer (1-64), transfer cooldown, or no hopper transfers at all.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(client -> sync());
	}

	private void sync() {
		active = isEnabled() && ClientCompat.isSingleplayer();
		itemsPerTransfer = (int) Math.max(1, Math.min(64, Math.round(items)));
		cooldown = (int) Math.max(0, Math.min(200, Math.round(ticks)));
		transfersOff = off;
		pickUpItems = pickup;
		minecarts = carts;
	}

	@Override
	public void onDisable() {
		active = false;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Items Per Transfer", 1, 64, () -> items, v -> items = Math.round(v),
						v -> String.valueOf((int) Math.round(v))),
				new ConfigField.SliderField("Transfer Cooldown (ticks)", 0, 40, () -> ticks, v -> ticks = Math.round(v),
						v -> Math.round(v) + (Math.round(v) == 8 ? " (vanilla)" : "")),
				new ConfigField.ToggleField("Disable All Hopper Transfers", () -> off, v -> off = v),
				new ConfigField.ToggleField("Hoppers Pick Up Dropped Items", () -> pickup, v -> pickup = v),
				new ConfigField.ToggleField("Hopper Minecarts Too", () -> carts, v -> carts = v));
	}
}
