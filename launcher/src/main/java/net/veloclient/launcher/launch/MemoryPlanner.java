package net.veloclient.launcher.launch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.management.OperatingSystemMXBean;
import net.veloclient.launcher.data.LauncherSettings;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Picks how much memory (and which garbage collector) a profile gets, instead of a flat 4 GB that
 * starves heavy modpacks on big PCs and makes small PCs swap.
 *
 * <p>How "Automatic" decides:
 * <ol>
 * <li><b>Learned</b> - after you've played a profile, the game records how much heap it really
 * needed (live data after garbage collection, see {@code MemoryStatsRecorder}) - the highest of
 * recent sessions, slowly forgetting old ones, so one short lobby session can't shrink the next
 * heavy one into an out-of-memory crash. Sessions that came close to running out get extra.</li>
 * <li><b>Estimated</b> - before that: a baseline plus extra for each mod beyond the bundled ones,
 * render distance above 12, resource pack size and installed shader packs.</li>
 * <li><b>Clamped to the PC</b> - a fixed tier per amount of installed RAM (never a big share of
 * it, the game needs 1.5-4 GB outside its heap too). Free memory right now can lower it, but
 * never below what the profile needs - a too-small heap crashes the game just as surely.</li>
 * </ol>
 *
 * <p>The heap is <b>not</b> claimed up front any more: {@code -Xms} starts small and G1 grows it
 * only as the game really needs, and hands unused memory back to the OS when it's idle. Claiming
 * the whole maximum at launch ({@code -Xms=-Xmx} + {@code AlwaysPreTouch}) made every session cost
 * its worst case from the first second, which is exactly what pushed PCs into swap and froze them;
 * on Windows it also reserved the whole heap against the page file (commit limit), so the GPU
 * driver's own allocations failed instead.
 */
public final class MemoryPlanner {

	private static final int BASELINE_MB = 1700;
	private static final int CEILING_MB = 10240;
	private static final int BUNDLED_MODS = 20;
	/** Kept free for the OS, the launcher and short spikes, on top of the whole game process. */
	private static final int SAFETY_MB = 1024;
	/** Below this the game can't even load a world with Velo's modules - lowering further only trades a swap for a crash. */
	private static final int ABSOLUTE_MIN_MB = 2048;

	public enum Gc { G1, ZGC }

	public record Plan(int maxMb, int minMb, Gc gc, boolean automatic, List<String> reasons, String warning) {
		public String summary() {
			return String.format(Locale.ROOT, "%.1f GB, %s", maxMb / 1024.0, gc == Gc.ZGC ? "ZGC (low-pause)" : "G1");
		}
	}

	private MemoryPlanner() {
	}

	public static Plan plan(Instance instance) {
		LauncherSettings.Data settings = LauncherSettings.get();
		long totalMb = totalSystemMb();
		long availableMb = availableSystemMb();
		List<String> reasons = new ArrayList<>();
		String warning = null;

		boolean profileOverride = instance != null && instance.ramMaxMb() != null && !isLegacyDefault(instance);
		int maxMb;
		int needMb;
		boolean automatic;
		if (profileOverride) {
			maxMb = instance.ramMaxMb();
			needMb = maxMb;
			automatic = false;
			reasons.add("Set manually for this profile");
		} else if (settings.memoryMode == LauncherSettings.MemoryMode.FIXED) {
			maxMb = settings.fixedMemoryMb;
			needMb = maxMb;
			automatic = false;
			reasons.add("Fixed amount from launcher settings");
		} else {
			automatic = true;
			int[] auto = automaticMb(instance, totalMb, reasons);
			maxMb = auto[0];
			needMb = auto[1];
		}

		// Heap + the game's native memory should fit into what's free right now. Automatic may give
		// up its headroom for that, but never what the profile needs; manual amounts are respected.
		int overhead = nativeOverheadMb(instance);
		if (availableMb > 0) {
			long budget = availableMb - overhead - SAFETY_MB;
			if (automatic && maxMb > budget) {
				int fitted = (int) Math.max(needMb, Math.round(budget / 256.0) * 256);
				if (fitted < maxMb) {
					reasons.add(String.format(Locale.ROOT, "Lowered from %.1f GB to fit free memory (%.1f GB free)", maxMb / 1024.0, availableMb / 1024.0));
					maxMb = fitted;
				}
			}
			if (maxMb > budget) {
				warning = String.format(Locale.ROOT, "Only %.1f GB is free right now but this profile needs about %.1f GB (game + safety margin) - close other programs (browser, Discord...) before playing.",
						availableMb / 1024.0, (maxMb + overhead + SAFETY_MB) / 1024.0);
			}
		}
		if (maxMb + overhead > totalMb - 1536) {
			warning = String.format(Locale.ROOT, "%.1f GB for the game is too much for this %.0f GB PC - it may freeze when memory runs out. Switch to Automatic.",
					maxMb / 1024.0, totalMb / 1024.0);
		}

		Gc gc = switch (settings.gc) {
			case ZGC -> Gc.ZGC;
			// G1 is the safe default: it's the most memory-efficient collector and returns unused heap.
			// ZGC needs noticeably more headroom for the same game and is only used when picked.
			default -> Gc.G1;
		};
		int minMb = Math.min(maxMb, Math.max(1024, Math.min(2048, maxMb / 3)));
		return new Plan(maxMb, minMb, gc, automatic, reasons, warning);
	}

	/**
	 * Old launcher versions saved exactly 4096/4096 whenever the settings dialog was confirmed,
	 * even without touching the sliders - indistinguishable from "never chosen", so treated as
	 * automatic. Any other value is a real choice and respected.
	 */
	public static boolean isLegacyDefault(Instance instance) {
		return instance.ramMaxMb() != null && instance.ramMaxMb() == 4096 && (instance.ramMinMb() == null || instance.ramMinMb() == 4096);
	}

	/** The most heap Automatic ever hands out on a PC with {@code totalMb} of RAM. */
	static int tierCapMb(long totalMb) {
		if (totalMb <= 4608) {
			return 1792;
		}
		if (totalMb <= 6656) {
			return 2560;
		}
		if (totalMb <= 8704) {
			return 3584;
		}
		if (totalMb <= 12800) {
			return 4608;
		}
		if (totalMb <= 16896) {
			return 6144;
		}
		if (totalMb <= 25088) {
			return 7168;
		}
		if (totalMb <= 33280) {
			return 8192;
		}
		return CEILING_MB;
	}

	/** @return {target heap, least heap this profile should run with} */
	private static int[] automaticMb(Instance instance, long totalMb, List<String> reasons) {
		int target;
		int minimumNeed;
		Learned learned = instance == null ? null : readLearned(instance);
		if (learned != null) {
			double headroom = learned.gcPercent > 3 || learned.nearOom ? 2.0 : 1.7;
			target = (int) (learned.peakLiveMb * headroom) + 384;
			minimumNeed = (int) (learned.peakLiveMb * (learned.nearOom ? 1.6 : 1.35)) + 384;
			reasons.add(String.format(Locale.ROOT, "Recent sessions needed up to %.1f GB%s", learned.peakLiveMb / 1024.0,
					learned.nearOom ? " and nearly ran out" : learned.gcPercent > 3
							? String.format(Locale.ROOT, " and spent %.0f%% of the time collecting garbage", learned.gcPercent) : ""));
		} else {
			int need = BASELINE_MB;
			if (instance != null) {
				int mods = countJars(InstancePaths.modsDir(instance.id()));
				if (mods > BUNDLED_MODS) {
					need += (mods - BUNDLED_MODS) * 35;
					reasons.add(mods + " mods installed");
				}
				int renderDistance = readRenderDistance(InstancePaths.gameDir(instance.id()));
				if (renderDistance > 12) {
					need += (renderDistance - 12) * 90;
					reasons.add("Render distance " + renderDistance);
				}
				long packsMb = folderSizeMb(InstancePaths.resourcePacksDir(instance.id()));
				if (packsMb > 50) {
					need += (int) Math.min(1536, packsMb * 0.8);
					reasons.add(packsMb + " MB of resource packs");
				}
				if (countFiles(InstancePaths.shaderPacksDir(instance.id())) > 0) {
					need += 700;
					reasons.add("Shader packs installed");
				}
			}
			target = (int) (need * 1.5);
			minimumNeed = (int) (need * 1.2);
			if (reasons.isEmpty()) {
				reasons.add("Standard setup");
			}
		}

		int cap = tierCapMb(totalMb);
		minimumNeed = Math.max(Math.min(ABSOLUTE_MIN_MB, cap), Math.min(minimumNeed, cap));
		int result = Math.max(minimumNeed, Math.min(cap, target));
		if (result == cap && target > cap) {
			reasons.add(String.format(Locale.ROOT, "Capped to keep your %.0f GB PC responsive", totalMb / 1024.0));
		}
		return new int[] {roundUp256(result), roundUp256(minimumNeed)};
	}

	private static int roundUp256(int mb) {
		return (mb + 255) / 256 * 256;
	}

	/**
	 * Memory the game uses outside its heap: class metadata and JIT code for every mod, native
	 * buffers (Sodium, LWJGL), the GPU driver's own allocations (Vulkan on 26.2 maps a lot of it
	 * into the process), thread stacks. Measured: 1.5-4 GB resident beyond the heap with big packs.
	 */
	public static int nativeOverheadMb(Instance instance) {
		int overhead = 1300;
		if (instance != null) {
			overhead += countJars(InstancePaths.modsDir(instance.id())) * 15;
			if (countFiles(InstancePaths.shaderPacksDir(instance.id())) > 0) {
				overhead += 450;
			}
		}
		return overhead;
	}

	/** The JVM memory/GC flags for {@code plan} on a game runtime of Java {@code javaFeature}. */
	public static List<String> jvmArgs(Plan plan, int javaFeature) {
		List<String> args = new ArrayList<>();
		args.add("-Xmx" + plan.maxMb() + "m");
		args.add("-Xms" + plan.minMb() + "m");
		if (plan.gc() == Gc.ZGC) {
			// Generational ZGC: pauses well under a millisecond regardless of heap size, and it
			// returns unused memory (ZUncommit, on by default). SoftMaxHeapSize keeps it aiming lower
			// unless the game genuinely needs more.
			args.add("-XX:+UseZGC");
			if (javaFeature >= 21 && javaFeature <= 22) {
				args.add("-XX:+ZGenerational");
			}
			args.add("-XX:SoftMaxHeapSize=" + (int) (plan.maxMb() * 0.8) + "m");
		} else {
			// G1 with a strict pause target and young-gen sizing tuned for Minecraft's huge
			// allocation rate of short-lived objects (the widely used Aikar-style recipe);
			// region size scales with the heap so humongous allocations stay rare.
			args.add("-XX:+UseG1GC");
			args.add("-XX:MaxGCPauseMillis=40");
			args.add("-XX:+UnlockExperimentalVMOptions");
			args.add("-XX:G1NewSizePercent=" + (plan.maxMb() >= 8192 ? 30 : 20));
			args.add("-XX:G1MaxNewSizePercent=" + (plan.maxMb() >= 8192 ? 40 : 50));
			args.add("-XX:G1ReservePercent=20");
			args.add("-XX:G1HeapRegionSize=" + (plan.maxMb() > 8192 ? "32M" : plan.maxMb() > 4096 ? "16M" : "8M"));
			args.add("-XX:G1MixedGCCountTarget=4");
			args.add("-XX:InitiatingHeapOccupancyPercent=15");
			args.add("-XX:G1RSetUpdatingPauseTimePercent=5");
			args.add("-XX:+ParallelRefProcEnabled");
			// Give memory back: a periodic concurrent cycle when no GC ran for a while (menus, AFK)
			// lets G1 shrink the heap, and the free-ratio bounds stop it keeping lots of empty heap.
			args.add("-XX:G1PeriodicGCInterval=30000");
			args.add("-XX:MinHeapFreeRatio=10");
			args.add("-XX:MaxHeapFreeRatio=30");
		}
		return args;
	}

	// ---- Inputs ----

	private record Learned(long peakLiveMb, double gcPercent, boolean nearOom) {
	}

	private static Learned readLearned(Instance instance) {
		Path file = InstancePaths.gameDir(instance.id()).resolve("velo").resolve("memory-stats.json");
		try {
			if (!Files.exists(file)) {
				return null;
			}
			JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			long peak = json.get("peakLiveAfterGcMb").getAsLong();
			// Highest of recent sessions (decays slowly), so one light session can't undersize the next.
			if (json.has("recentPeakMb")) {
				peak = Math.max(peak, json.get("recentPeakMb").getAsLong());
			}
			double gc = json.has("gcTimePercent") ? json.get("gcTimePercent").getAsDouble() : 0;
			boolean nearOom = json.has("nearOom") && json.get("nearOom").getAsBoolean();
			return peak > 256 ? new Learned(peak, gc, nearOom) : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static int readRenderDistance(Path gameDir) {
		try {
			Path options = gameDir.resolve("options.txt");
			if (!Files.exists(options)) {
				return 12;
			}
			for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
				if (line.startsWith("renderDistance:")) {
					return Integer.parseInt(line.substring("renderDistance:".length()).trim());
				}
			}
		} catch (Exception ignored) {
			// Default below.
		}
		return 12;
	}

	private static int countJars(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			return (int) files.filter(p -> p.toString().endsWith(".jar")).count();
		} catch (Exception e) {
			return 0;
		}
	}

	private static int countFiles(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			return (int) files.count();
		} catch (Exception e) {
			return 0;
		}
	}

	private static long folderSizeMb(Path dir) {
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum() / (1024 * 1024);
		} catch (Exception e) {
			return 0;
		}
	}

	public static long totalSystemMb() {
		try {
			return ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize() / (1024 * 1024);
		} catch (Exception e) {
			return 8192;
		}
	}

	/** Memory the OS can hand out right now (Linux: MemAvailable, which counts reclaimable cache), or -1. */
	public static long availableSystemMb() {
		try {
			if (isLinux()) {
				for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
					if (line.startsWith("MemAvailable:")) {
						return Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
					}
				}
			}
			if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
				// macOS counts file cache as used, so "free" is always tiny there - not a useful limit.
				return -1;
			}
			return ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getFreeMemorySize() / (1024 * 1024);
		} catch (Exception e) {
			return -1;
		}
	}

	public static boolean isLinux() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
	}
}
