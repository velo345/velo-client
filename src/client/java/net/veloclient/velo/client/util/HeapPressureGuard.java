package net.veloclient.velo.client.util;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.social.NotificationOverlay;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;

/**
 * Keeps a nearly full heap from ending in an out-of-memory crash.
 *
 * <p>Every 2 s it looks at the live data left after the last garbage collections (not raw "used",
 * which includes garbage). When that stays above 88% of the maximum heap, the game is about to run
 * out - the usual trigger is a render or simulation distance too large for the RAM the profile got.
 * It then frees Velo's own caches (menu thumbnails, idle animated capes), lowers render and
 * simulation distance by 2 (never below 6) and tells the player once, then waits 45 s before
 * judging again. The launcher reads the same pressure from {@code memory-stats.json} and gives the
 * profile more RAM next launch.
 */
public final class HeapPressureGuard {

	private static final double LIMIT = 0.88;
	private static final int MIN_DISTANCE = 6;
	private static final long COOLDOWN_NANOS = 45_000_000_000L;
	private static final List<MemoryPoolMXBean> HEAP_POOLS = ManagementFactory.getMemoryPoolMXBeans().stream()
			.filter(p -> p.getType() == MemoryType.HEAP && p.getCollectionUsage() != null).toList();

	private static int ticks;
	private static int strikes;
	private static long lastActionNanos;
	private static boolean toldPlayer;

	private HeapPressureGuard() {
	}

	public static void tick(MinecraftClient client) {
		if (++ticks % 40 != 0) {
			return;
		}
		long max = Runtime.getRuntime().maxMemory();
		long live = 0;
		for (MemoryPoolMXBean pool : HEAP_POOLS) {
			var usage = pool.getCollectionUsage();
			if (usage != null) {
				live += usage.getUsed();
			}
		}
		strikes = live > max * LIMIT ? strikes + 1 : 0;
		if (strikes < 2 || System.nanoTime() - lastActionNanos < COOLDOWN_NANOS) {
			return;
		}
		strikes = 0;
		lastActionNanos = System.nanoTime();
		net.veloclient.velo.client.gui.RemoteTextureLoader.releaseAll(client);
		net.veloclient.velo.client.cosmetics.AnimatedCapeAsset.releaseIdle(5_000);

		var options = client.options;
		int view = options.getViewDistance().getValue();
		int simulation = options.getSimulationDistance().getValue();
		boolean lowered = false;
		if (view > MIN_DISTANCE) {
			options.getViewDistance().setValue(Math.max(MIN_DISTANCE, view - 2));
			lowered = true;
		}
		if (simulation > MIN_DISTANCE) {
			options.getSimulationDistance().setValue(Math.max(MIN_DISTANCE, simulation - 2));
			lowered = true;
		}
		if (lowered) {
			//? if <26.1 {
			options.write();
			//?} else {
			/*options.save();
			*///?}
		}
		if (!toldPlayer || lowered) {
			toldPlayer = true;
			NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Memory almost full",
					(lowered ? "Render distance lowered to " + options.getViewDistance().getValue() + " so the game doesn't crash. "
							: "Freed Velo's caches so the game doesn't crash. ")
							+ "The launcher gives this profile more RAM next time (Automatic).", 9000, null, null, null));
		}
	}
}
