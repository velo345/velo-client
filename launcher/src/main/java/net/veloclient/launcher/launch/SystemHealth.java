package net.veloclient.launcher.launch;

import com.sun.management.OperatingSystemMXBean;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.RunningInstanceManager;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A quick look at the PC before the game starts - the problems behind most "my PC froze" and
 * "the game crashed on start" reports: not enough free memory for the planned RAM, no swap/page
 * file as a safety net, a nearly full disk, other Minecraft clients already eating memory, a
 * manual RAM amount too big for the machine. Runs before every launch (only warnings interrupt
 * it) and on demand from Settings, where every finding is listed.
 */
public final class SystemHealth {

	public enum Level { OK, INFO, WARN }

	public record Finding(Level level, String title, String detail) {
	}

	private SystemHealth() {
	}

	public static List<Finding> check(Instance instance) {
		List<Finding> findings = new ArrayList<>();
		long totalMb = MemoryPlanner.totalSystemMb();
		long freeMb = MemoryPlanner.availableSystemMb();

		MemoryPlanner.Plan plan = MemoryPlanner.plan(instance);
		if (plan.warning() != null) {
			findings.add(new Finding(Level.WARN, "Memory", plan.warning()));
		} else {
			findings.add(new Finding(Level.OK, "Memory", String.format(Locale.ROOT, "%.1f GB for the game, %s GB free of %.0f GB.",
					plan.maxMb() / 1024.0, freeMb > 0 ? String.format(Locale.ROOT, "%.1f", freeMb / 1024.0) : "?", totalMb / 1024.0)));
		}

		// Swap / page file: without it a full RAM doesn't slow things down, it freezes the PC.
		long swapMb = totalSwapMb();
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		if (swapMb >= 0) {
			if (swapMb < 512 && totalMb < 24 * 1024) {
				// Only worth interrupting a launch when memory is also tight right now.
				boolean tight = freeMb > 0 && freeMb < plan.maxMb() + MemoryPlanner.nativeOverheadMb(instance) + 2048;
				findings.add(new Finding(tight ? Level.WARN : Level.INFO, windows ? "Page file" : "Swap",
						(windows ? "The Windows page file is off or tiny" : "This system has no swap")
								+ " - when RAM runs out the PC freezes instead of slowing down. "
								+ (windows ? "Set the page file to \"System managed\" (System > Advanced > Performance)."
										: "Adding a 4-8 GB swap file prevents hard freezes.")));
			} else {
				findings.add(new Finding(Level.OK, windows ? "Page file" : "Swap", String.format(Locale.ROOT, "%.1f GB available as a safety net.", swapMb / 1024.0)));
			}
		}

		// Disk: worlds, logs and the shader cache need room; a full disk corrupts saves.
		long diskMb = freeDiskMb(instance != null ? InstancePaths.dir(instance.id()) : VeloPaths.root());
		if (diskMb >= 0) {
			if (diskMb < 1024) {
				findings.add(new Finding(Level.WARN, "Disk space", String.format(Locale.ROOT,
						"Only %.1f GB free - worlds can get corrupted when the disk fills up while saving. Free some space first.", diskMb / 1024.0)));
			} else if (diskMb < 4096) {
				findings.add(new Finding(Level.INFO, "Disk space", String.format(Locale.ROOT, "%.1f GB free - getting low.", diskMb / 1024.0)));
			} else {
				findings.add(new Finding(Level.OK, "Disk space", String.format(Locale.ROOT, "%.0f GB free.", diskMb / 1024.0)));
			}
		}

		// Games already running - ours and other launchers'.
		int ours = RunningInstanceManager.running().size();
		int others = otherMinecraftProcesses();
		if (ours + others > 0) {
			String who = ours > 0 && others > 0 ? ours + " from Velo and " + others + " from other launchers"
					: ours > 0 ? ours + " from Velo" : others + " from another launcher";
			boolean tight = freeMb > 0 && freeMb < plan.maxMb() + MemoryPlanner.nativeOverheadMb(instance) + 1024;
			findings.add(new Finding(tight ? Level.WARN : Level.INFO, "Already running",
					"Minecraft is already running (" + who + ")." + (tight ? " There isn't enough free memory for another one - close it first." : "")));
		}

		int cores = Runtime.getRuntime().availableProcessors();
		if (cores <= 2) {
			findings.add(new Finding(Level.INFO, "CPU", cores + " CPU threads - pick the Performance mode in-game for steadier FPS."));
		}
		return findings;
	}

	/** Only the findings worth interrupting a launch for (minus the ones the player silenced). */
	public static List<Finding> warnings(List<Finding> findings) {
		List<String> silenced = net.veloclient.launcher.data.LauncherSettings.get().silencedChecks;
		return findings.stream().filter(f -> f.level() == Level.WARN && (silenced == null || !silenced.contains(f.title()))).toList();
	}

	/** "Don't warn me again" for these findings' kinds (still listed in Settings > System check). */
	public static void silence(List<Finding> findings) {
		var settings = net.veloclient.launcher.data.LauncherSettings.get();
		if (settings.silencedChecks == null) {
			settings.silencedChecks = new ArrayList<>();
		}
		for (Finding finding : findings) {
			if (!settings.silencedChecks.contains(finding.title())) {
				settings.silencedChecks.add(finding.title());
			}
		}
		net.veloclient.launcher.data.LauncherSettings.save(settings);
	}

	private static long totalSwapMb() {
		try {
			if (MemoryPlanner.isLinux()) {
				for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
					if (line.startsWith("SwapTotal:")) {
						return Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
					}
				}
			}
			if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
				// On Windows this is the commit limit (RAM + page file).
				OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
				return Math.max(0, (os.getTotalSwapSpaceSize() - os.getTotalMemorySize()) / (1024 * 1024));
			}
		} catch (Exception ignored) {
			// Unknown.
		}
		return -1;
	}

	private static long freeDiskMb(Path path) {
		try {
			Path existing = path;
			while (existing != null && !Files.exists(existing)) {
				existing = existing.getParent();
			}
			return existing == null ? -1 : Files.getFileStore(existing).getUsableSpace() / (1024 * 1024);
		} catch (Exception e) {
			return -1;
		}
	}

	/** Java processes running Minecraft that this launcher didn't start. */
	private static int otherMinecraftProcesses() {
		java.util.Set<Long> ours = new java.util.HashSet<>();
		for (RunningInstanceManager.RunningInstance running : RunningInstanceManager.running()) {
			try {
				ours.add(running.process().pid());
			} catch (Exception ignored) {
				// Not available.
			}
		}
		long self = ProcessHandle.current().pid();
		return (int) ProcessHandle.allProcesses().filter(p -> p.pid() != self && !ours.contains(p.pid())).filter(p -> {
			var info = p.info();
			String command = info.command().orElse("").toLowerCase(Locale.ROOT);
			if (!command.contains("java")) {
				return false;
			}
			String args = info.arguments().map(a -> String.join(" ", a)).orElse(info.commandLine().orElse(""));
			return args.contains("net.minecraft.client.main.Main") || args.contains("net.fabricmc.loader.impl.launch.knot.KnotClient")
					|| args.contains("cpw.mods.bootstraplauncher");
		}).count();
	}
}
