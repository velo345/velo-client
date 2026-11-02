package net.veloclient.bridge.internal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Runs code on the right thread everywhere: Folia's per-player / global region schedulers (also on Paper and Purpur,
 * which have the same API), Bukkit's scheduler on plain Spigot.
 */
public final class Tasks {

	private static final boolean REGIONS = hasRegions();
	private static Plugin plugin;

	private Tasks() {
	}

	public static void init(Plugin owner) {
		plugin = owner;
	}

	private static boolean hasRegions() {
		try {
			Player.class.getMethod("getScheduler");
			Bukkit.class.getMethod("getGlobalRegionScheduler");
			return true;
		} catch (NoSuchMethodException e) {
			return false;
		}
	}

	public static boolean folia() {
		try {
			Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	/** On the player's own thread (Folia region / main thread). */
	public static void forPlayer(Player player, Runnable task) {
		if (REGIONS) {
			player.getScheduler().run(plugin, t -> task.run(), null);
		} else {
			Bukkit.getScheduler().runTask(plugin, task);
		}
	}

	public static void global(Runnable task) {
		if (REGIONS) {
			Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
		} else {
			Bukkit.getScheduler().runTask(plugin, task);
		}
	}

	public static void globalRepeating(Runnable task, long delayTicks, long periodTicks) {
		if (REGIONS) {
			Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> task.run(), Math.max(1, delayTicks), periodTicks);
		} else {
			Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
		}
	}

	public static void async(Runnable task) {
		if (REGIONS) {
			Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
		} else {
			Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
		}
	}
}
