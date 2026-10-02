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
 * needed (live data after garbage collection, see {@code MemoryStatsRecorder}). The heap is set
 * to that peak plus headroom, and grows if the game spent noticeable time collecting garbage.</li>
 * <li><b>Estimated</b> - before that: a baseline plus extra for each mod beyond the bundled ones,
 * render distance above 12, resource pack size and installed shader packs.</li>
 * <li><b>Clamped to the PC</b> - never more than a safe share of total RAM, always leaving room
 * for the OS, and never more than what's actually free right now (so the game doesn't push
 * itself into swap - the classic cause of "more RAM made it worse").</li>
 * </ol>
 *
 * <p>Free memory is checked for EVERY mode (manual and fixed amounts too), against the whole game
 * process, not just its heap: mods, the GPU driver and native buffers add roughly 1-2.5 GB on top
 * (a real 87-mod session sat at ~3 GB resident with ~1 GB of heap). And the heap is committed
 * up front ({@code AlwaysPreTouch}), so world loads and resource reloads never have to grab more
 * memory mid-game - on a PC with little swap, that grab is what froze/crashed the whole system.
 * More is not better past a point: a bigger heap only makes each garbage collection longer.
 */
public final class MemoryPlanner {

	private static final int BASELINE_MB = 1600;
	private static final int FLOOR_MB = 2048;
	private static final int CEILING_MB = 12288;
	private static final int BUNDLED_MODS = 20;
	/** Kept free for the OS, the launcher and short spikes, on top of the whole game process. */
	private static final int SAFETY_MB = 900;
	private static final int MIN_HEAP_MB = 1536;

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
		boolean automatic;
		if (profileOverride) {
			maxMb = instance.ramMaxMb();
			automatic = false;
			reasons.add("Set manually for this profile");
		} else if (settings.memoryMode == LauncherSettings.MemoryMode.FIXED) {
			maxMb = settings.fixedMemoryMb;
			automatic = false;
			reasons.add("Fixed amount from launcher settings");
		} else {
			automatic = true;
			maxMb = automaticMb(instance, totalMb, availableMb, reasons);
		}

		// Whatever the mode: heap + the game's native memory must fit into what's free right now.
		if (availableMb > 0) {
			int overhead = nativeOverheadMb(instance);
			long budget = availableMb - overhead - SAFETY_MB;
			if (maxMb > budget) {
				int fitted = (int) Math.max(MIN_HEAP_MB, Math.round(budget / 256.0) * 256);
				if (fitted < maxMb) {
					reasons.add(String.format(Locale.ROOT, "Lowered from %.1f GB to fit free memory (%.1f GB free, ~%.1f GB needed outside the heap)",
							maxMb / 1024.0, availableMb / 1024.0, overhead / 1024.0));
					maxMb = fitted;
				}
			}
			if (budget < 2048) {
				warning = String.format(Locale.ROOT, "Only %.1f GB is free right now - close other programs (browser, Discord...) before playing.",
						availableMb / 1024.0);
			}
		}

		Gc gc = switch (settings.gc) {
			case G1 -> Gc.G1;
			case ZGC -> Gc.ZGC;
			default -> Runtime.version().feature() >= 21 && maxMb >= 8192 && Runtime.getRuntime().availableProcessors() >= 8 ? Gc.ZGC : Gc.G1;
		};
		// Always the full heap from the start (see class doc: memory is claimed at launch, never mid-game).
		int minMb = maxMb;
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

	private static int automaticMb(Instance instance, long totalMb, long availableMb, List<String> reasons) {
		int target;
		// The least this profile should run with. plan() still caps the final value to free memory:
		// on PCs with little swap, swapping freezes the whole system, which is worse than a small heap.
		int minimumNeed;
		Learned learned = instance == null ? null : readLearned(instance);
		if (learned != null) {
			double headroom = learned.gcPercent > 3 ? 2.2 : 1.8;
			target = (int) (learned.peakLiveMb * headroom) + 256;
			minimumNeed = (int) (learned.peakLiveMb * 1.3) + 256;
			reasons.add(String.format(Locale.ROOT, "Last sessions needed up to %.1f GB%s", learned.peakLiveMb / 1024.0,
					learned.gcPercent > 3 ? String.format(Locale.ROOT, " and spent %.0f%% of the time collecting garbage", learned.gcPercent) : ""));
		} else {
			int need = BASELINE_MB;
			if (instance != null) {
				int mods = countJars(InstancePaths.modsDir(instance.id()));
				if (mods > BUNDLED_MODS) {
					need += (mods - BUNDLED_MODS) * 30;
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
			minimumNeed = (int) (need * 1.15);
			if (reasons.isEmpty()) {
				reasons.add("Standard setup");
			}
		}

		long shareCap = (long) (totalMb * (totalMb <= 8192 ? 0.40 : totalMb <= 16384 ? 0.45 : 0.40));
		long osCap = totalMb - 2560;
		long cap = Math.max(1024, Math.min(CEILING_MB, Math.min(shareCap, osCap)));
		long floor = Math.min(FLOOR_MB, cap);
		long result = Math.max(floor, Math.min(cap, target));
		if (availableMb > 0 && result > availableMb - 768) {
			long fits = Math.max(Math.max(floor, Math.min(cap, minimumNeed)), availableMb - 768);
			if (fits < result) {
				result = fits;
				reasons.add("Limited by free memory right now");
			}
		}
		if (result == cap && target > cap) {
			reasons.add(String.format(Locale.ROOT, "Capped to keep your %.0f GB PC responsive", totalMb / 1024.0));
		}
		return (int) (Math.round(result / 256.0) * 256);
	}

	/**
	 * Memory the game uses outside its heap: class metadata and JIT code for every mod, native
	 * buffers (Sodium, LWJGL), the GPU driver's own allocations, thread stacks.
	 */
	public static int nativeOverheadMb(Instance instance) {
		int overhead = 950;
		if (instance != null) {
			overhead += countJars(InstancePaths.modsDir(instance.id())) * 12;
			if (countFiles(InstancePaths.shaderPacksDir(instance.id())) > 0) {
				overhead += 350;
			}
		}
		return overhead;
	}

	/** The JVM memory/GC flags for {@code plan} on this launcher's own runtime (the game runs on it too). */
	public static List<String> jvmArgs(Plan plan) {
		List<String> args = new ArrayList<>();
		args.add("-Xmx" + plan.maxMb() + "m");
		args.add("-Xms" + plan.minMb() + "m");
		// Claim the whole heap at startup instead of page by page during world loads / resource
		// reloads - those spikes are exactly when a nearly-full PC used to start swapping and freeze.
		args.add("-XX:+AlwaysPreTouch");
		int feature = Runtime.version().feature();
		if (plan.gc() == Gc.ZGC) {
			// Generational ZGC: pauses well under a millisecond regardless of heap size. The heap
			// starts at half and may grow to the max, but ZGC hands unused memory back, and
			// SoftMaxHeapSize keeps it aiming lower unless the game genuinely needs more.
			args.add("-XX:+UseZGC");
			if (feature >= 21 && feature <= 22) {
				args.add("-XX:+ZGenerational");
			}
			args.add("-XX:SoftMaxHeapSize=" + (int) (plan.maxMb() * 0.85) + "m");
			// Keep the claimed memory instead of handing it back and re-grabbing it under load.
			args.add("-XX:-ZUncommit");
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
		}
		if (isLinux() && plan.gc() == Gc.G1) {
			// Transparent huge pages (only where the kernel allows them, "madvise" mode): fewer TLB
			// misses walking a multi-GB heap. Linux-only flag - other OSes would refuse to start.
			args.add("-XX:+UseTransparentHugePages");
		}
		return args;
	}

	// ---- Inputs ----

	private record Learned(long peakLiveMb, double gcPercent) {
	}

	private static Learned readLearned(Instance instance) {
		Path file = InstancePaths.gameDir(instance.id()).resolve("velo").resolve("memory-stats.json");
		try {
			if (!Files.exists(file)) {
				return null;
			}
			JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			long peak = json.get("peakLiveAfterGcMb").getAsLong();
			double gc = json.has("gcTimePercent") ? json.get("gcTimePercent").getAsDouble() : 0;
			return peak > 256 ? new Learned(peak, gc) : null;
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
