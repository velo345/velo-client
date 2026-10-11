package net.veloclient.velo.client.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Records how much heap this game session actually needed - the live data still in use right
 * after each garbage collection (not the raw "used" number, which includes garbage waiting to be
 * collected), its peak, and how much time went into GC - to {@code velo/memory-stats.json} in the
 * game directory. The Velo launcher reads it on the next launch of the same profile to size the
 * memory allocation from real usage instead of a fixed guess ({@code MemoryPlanner}).
 *
 * <p>Also keeps {@code recentPeakMb}, the highest peak of recent sessions (each new session starts
 * from 92% of it, so it fades over a few weeks of lighter play): one short lobby session must not
 * teach the launcher a heap that the next singleplayer world then runs out of. {@code nearOom}
 * marks a session whose live data reached 85% of the heap - the launcher adds headroom for it.
 *
 * <p>Pure JDK management beans on a daemon thread every 20 s; no game hooks, no measurable cost.
 */
public final class MemoryStatsRecorder {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static long peakLiveMb;
	private static long samples;
	private static long carriedPeakMb;
	private static boolean nearOom;

	private record Stats(long maxHeapMb, long peakLiveAfterGcMb, long recentPeakMb, boolean nearOom, double gcTimePercent,
			long sessionMinutes, String gc, long updated) {
	}

	private MemoryStatsRecorder() {
	}

	public static void start() {
		try {
			Path file = statsFile();
			if (Files.exists(file)) {
				var json = com.google.gson.JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				long previous = json.has("peakLiveAfterGcMb") ? json.get("peakLiveAfterGcMb").getAsLong() : 0;
				if (json.has("recentPeakMb")) {
					previous = Math.max(previous, json.get("recentPeakMb").getAsLong());
				}
				carriedPeakMb = (long) (previous * 0.92);
			}
		} catch (Exception ignored) {
			// Fresh start.
		}
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "velo-memory-stats");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
		scheduler.scheduleWithFixedDelay(MemoryStatsRecorder::sample, 60, 20, TimeUnit.SECONDS);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> write());
	}

	private static synchronized void sample() {
		long liveBytes = 0;
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getType() == MemoryType.HEAP && pool.getCollectionUsage() != null) {
				liveBytes += pool.getCollectionUsage().getUsed();
			}
		}
		long liveMb = liveBytes / (1024 * 1024);
		peakLiveMb = Math.max(peakLiveMb, liveMb);
		boolean close = liveMb > Runtime.getRuntime().maxMemory() / (1024 * 1024) * 0.85;
		if (close && !nearOom) {
			nearOom = true;
			write();
		}
		if (++samples % 15 == 0) {
			write();
		}
	}

	private static synchronized void write() {
		try {
			long uptime = Math.max(1, ManagementFactory.getRuntimeMXBean().getUptime());
			long gcMillis = 0;
			StringBuilder collectors = new StringBuilder();
			for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
				gcMillis += Math.max(0, gc.getCollectionTime());
				if (!collectors.isEmpty()) {
					collectors.append(", ");
				}
				collectors.append(gc.getName());
			}
			long maxHeap = Runtime.getRuntime().maxMemory() / (1024 * 1024);
			Stats stats = new Stats(maxHeap, peakLiveMb, Math.max(peakLiveMb, carriedPeakMb), nearOom, 100.0 * gcMillis / uptime, uptime / 60_000, collectors.toString(),
					System.currentTimeMillis());
			// Too short a session hasn't loaded a world yet - don't let it teach the launcher anything.
			if (stats.sessionMinutes() < 2 || peakLiveMb == 0) {
				return;
			}
			Path file = statsFile();
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(stats), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Purely advisory.
		}
	}

	private static Path statsFile() {
		return FabricLoader.getInstance().getGameDir().resolve("velo").resolve("memory-stats.json");
	}
}
