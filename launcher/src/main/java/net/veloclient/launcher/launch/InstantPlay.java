package net.veloclient.launcher.launch;

import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.LauncherSettings;
import net.veloclient.launcher.data.QuickLaunchStore;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.RunningInstanceManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Instant Play (Settings, off by default): the game of the profile you played last is started in the background -
 * window hidden from its birth, low priority - and rests at its title screen. Play then only has to show the window:
 * a few dozen milliseconds instead of the ten to twenty seconds of JVM, Fabric and resource reload.
 *
 * <p>Rules that keep it harmless:
 * <ul>
 *   <li>Only a plain Play (no server, no AI launch) of the same profile with the same account, the same mods and the
 *       same options.txt takes the waiting game; anything else discards it and launches the ordinary way, so Play is
 *       never slower or different than without Instant Play.</li>
 *   <li>It only starts while enough memory is free, nothing else of ours runs, the account's token is good for the
 *       game, and not again for ten minutes after a parked game died on its own.</li>
 *   <li>It is shut down when the launcher closes, when the setting is turned off, and by the game itself when the
 *       launcher is gone (the game checks its parent process).</li>
 *   <li>Versions with a GLFW window only (1.21.1 - 26.2); 26.3's SDL window and the Ornithe versions play as before.</li>
 * </ul>
 * The signal to show the window is a file ({@link GameLauncher#parkSignal}) the game polls: it works the same on every
 * OS, unlike a pipe into a Windows GUI process.
 */
public final class InstantPlay {

	private static final Set<String> VERSIONS = Set.of("1.21.1", "1.21.10", "1.21.11", "26.1", "26.2");
	/** What a parked game holds at its title screen, measured on the 26.x runs (a little over 1.5 GB). */
	private static final long PARKED_ESTIMATE_MB = 1536;
	private static final long RETRY_AFTER_DEATH_MS = 10 * 60_000L;

	private record Parked(Instance instance, MinecraftSession session, Process process, Path logFile, String runId, Path signal,
			String fingerprint) {
	}

	private static final Object LOCK = new Object();
	private static Parked parked;
	private static boolean parking;
	private static long diedAt;

	static {
		// A normal JVM exit (the launcher closing) takes the waiting game with it; a hard kill is the game's own watchdog.
		Runtime.getRuntime().addShutdownHook(new Thread(InstantPlay::shutdown, "instant-play-shutdown"));
	}

	private InstantPlay() {
	}

	private static boolean watching;
	private static long busyUntil;
	private static int staleTicks;
	private static String lastBlocker;

	/**
	 * The upkeep, started once by the launcher: every ten seconds the waiting game is compared with what a Play would
	 * start now (the profile played last, its settings, mods, options, the account). A change - a mod added, the Graphics
	 * API switched, another profile played, a new sign-in - is noticed after two quiet checks, the old game is closed and
	 * a fresh one starts with the new setup, so the next Play is instant again. Also starts the first one, and again 20 seconds
	 * after the last game of ours closed.
	 */
	public static void watch(java.util.function.Supplier<MinecraftSession> currentSession) {
		synchronized (LOCK) {
			if (watching) {
				return;
			}
			watching = true;
		}
		Thread thread = new Thread(() -> {
			while (true) {
				try {
					Thread.sleep(10_000);
					tick(currentSession.get());
				} catch (InterruptedException e) {
					return;
				} catch (Throwable e) {
					LauncherLog.warn("[instant-play] upkeep failed", e);
				}
			}
		}, "instant-play-watch-settings");
		thread.setDaemon(true);
		thread.start();
	}

	private static void tick(MinecraftSession session) throws IOException {
		long now = System.currentTimeMillis();
		if (!enabled()) {
			shutdown();
			return;
		}
		if (!RunningInstanceManager.running().isEmpty()) {
			busyUntil = now + 20_000;
			return;
		}
		if (session == null || now < busyUntil) {
			return;
		}
		Parked current;
		synchronized (LOCK) {
			current = parked;
		}
		if (current == null) {
			staleTicks = 0;
			park(session);
			return;
		}
		Instance wanted = lastPlayed();
		boolean stale = wanted == null || !supported(wanted)
				|| !wanted.equals(current.instance)
				|| !current.session.uuid().equals(session.uuid())
				|| !current.session.minecraftAccessToken().equals(session.minecraftAccessToken())
				|| !current.fingerprint.equals(fingerprint(wanted));
		staleTicks = stale ? staleTicks + 1 : 0;
		if (staleTicks >= 2) {   // two quiet checks in a row: an installation in progress is not a change yet
			staleTicks = 0;
			synchronized (LOCK) {
				if (parked != current) {
					return;
				}
				parked = null;
			}
			LauncherLog.info("[instant-play] setup changed - restarting the waiting game");
			discard(current);
			park(session);
		}
	}

	public static boolean enabled() {
		return LauncherSettings.get().instantPlay && !Boolean.getBoolean("velo.noInstantPlay");
	}

	/** Whether this profile's version can be parked (a GLFW window). */
	public static boolean supported(Instance instance) {
		GameVersion version;
		try {
			version = GameVersion.byId(instance.mcVersion());
		} catch (RuntimeException e) {
			return false;
		}
		return !version.ornithe() && VERSIONS.contains(version.id());
	}

	/**
	 * Starts a hidden game for the profile played last, if all conditions hold; returns at once (the work runs on its own
	 * thread after {@code delayMs}). Safe to call as often as convenient: a second call while one is waiting or starting
	 * does nothing.
	 */
	public static void parkLater(MinecraftSession session, long delayMs) {
		if (!enabled() || session == null) {
			return;
		}
		Thread thread = new Thread(() -> {
			try {
				Thread.sleep(delayMs);
				park(session);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Throwable e) {
				LauncherLog.warn("[instant-play] could not start the waiting game", e);
			}
		}, "instant-play-park");
		thread.setDaemon(true);
		thread.start();
	}

	private static void park(MinecraftSession session) throws IOException {
		Instance instance = lastPlayed();
		if (instance == null || !supported(instance)) {
			return;
		}
		String blocked = blocker(session);
		if (blocked != null) {
			if (!blocked.equals(lastBlocker)) {   // said once per reason, not every ten seconds
				lastBlocker = blocked;
				LauncherLog.info("[instant-play] not starting a waiting game: " + blocked);
			}
			return;
		}
		lastBlocker = null;
		synchronized (LOCK) {
			if (parked != null || parking) {
				return;
			}
			parking = true;
		}
		try {
			LauncherLog.info("[instant-play] starting " + instance.name() + " (" + instance.mcVersion() + ") hidden");
			GameLauncher.LaunchResult result = GameLauncher.launchParked(instance, session);
			Parked entry = new Parked(instance, session, result.process(), result.logFile(), result.runId(),
					GameLauncher.parkSignal(instance.id(), result.runId()), fingerprint(instance));
			synchronized (LOCK) {
				parked = entry;
			}
			Thread watcher = new Thread(() -> {
				try {
					entry.process.waitFor();
				} catch (InterruptedException e) {
					return;
				}
				synchronized (LOCK) {
					if (parked == entry) {
						parked = null;
						diedAt = System.currentTimeMillis();
						LauncherLog.info("[instant-play] the waiting game ended by itself (exit " + entry.process.exitValue() + ") - not retrying for 10 minutes");
					}
				}
			}, "instant-play-watch");
			watcher.setDaemon(true);
			watcher.start();
		} finally {
			synchronized (LOCK) {
				parking = false;
			}
		}
	}

	/** Why a waiting game is not started now; null when all conditions hold. */
	private static String blocker(MinecraftSession session) {
		if (session.tooShortForGame()) {
			return "the account's token is about to expire";
		}
		if (LauncherSettings.get().aiAccess) {
			return "AI Access is on";
		}
		if (!RunningInstanceManager.running().isEmpty()) {
			return "a game is running";
		}
		synchronized (LOCK) {
			if (diedAt != 0 && System.currentTimeMillis() - diedAt < RETRY_AFTER_DEATH_MS) {
				return "the last waiting game ended by itself a moment ago";
			}
		}
		try {
			var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
			long totalMb = os.getTotalMemorySize() >> 20;
			long freeMb = availableMb(os.getFreeMemorySize() >> 20);
			long floor = Math.max(2048, totalMb * 15 / 100);
			// 8 GB PCs and up; what is left after it must stay above the larger of 2 GB and 15 % of the PC's memory.
			if (totalMb < 7500) {
				return "less than 8 GB of memory";
			}
			if (freeMb - PARKED_ESTIMATE_MB < floor) {
				return "not enough free memory (" + freeMb + " MB free, " + (PARKED_ESTIMATE_MB + floor) + " MB needed)";
			}
			return null;
		} catch (Throwable e) {
			return "memory could not be read";
		}
	}

	/** Memory a new program can really get: Linux's MemAvailable (file cache is not "used"); elsewhere the OS's own free figure. */
	private static long availableMb(long osFreeMb) {
		try {
			Path meminfo = Path.of("/proc/meminfo");
			if (Files.isReadable(meminfo)) {
				for (String line : Files.readAllLines(meminfo)) {
					if (line.startsWith("MemAvailable:")) {
						return Long.parseLong(line.replaceAll("[^0-9]", "")) >> 10;
					}
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// fall through
		}
		return osFreeMb;
	}

	private static Instance lastPlayed() {
		List<Instance> all = InstanceStore.loadAll();
		Optional<QuickLaunchStore.Entry> latest = QuickLaunchStore.loadAll().stream()
				.max(Comparator.comparingLong(QuickLaunchStore.Entry::lastLaunchedEpochMillis));
		if (latest.isEmpty()) {
			return null;
		}
		return all.stream().filter(i -> i.id().equals(latest.get().instanceId())).findFirst().orElse(null);
	}

	/**
	 * Called by {@link GameLauncher#launch} on every Play. Returns the launch of the waiting game after telling it to show
	 * its window, or null when the ordinary launch has to run (in which case a waiting game is gone: its files may be about
	 * to change, and on Windows its jars are locked).
	 */
	static GameLauncher.LaunchResult adopt(Instance instance, MinecraftSession session, String quickPlayServerAddress) {
		Parked taken;
		synchronized (LOCK) {
			taken = parked;
			parked = null;
		}
		if (taken == null) {
			return null;
		}
		boolean same = quickPlayServerAddress == null
				&& taken.process.isAlive()
				&& taken.instance.equals(instance)
				&& taken.session.uuid().equals(session.uuid())
				&& taken.session.minecraftAccessToken().equals(session.minecraftAccessToken())
				&& !GameLauncher.aiPending(instance.id())
				&& taken.fingerprint.equals(fingerprint(instance));
		if (same) {
			try {
				Files.writeString(taken.signal, "show");
				// the game is ours to play now: the priority a hidden game waits at is lifted
				String boosted = WindowsProcessBoost.forPlaying(taken.process.pid(), true);
				LauncherLog.info("[instant-play] showing the waiting game (pid " + taken.process.pid() + (boosted.isEmpty() ? "" : ", " + boosted) + ")");
				return new GameLauncher.LaunchResult(taken.process, taken.logFile, taken.runId);
			} catch (IOException e) {
				LauncherLog.warn("[instant-play] could not signal the waiting game", e);
			}
		}
		LauncherLog.info("[instant-play] the waiting game does not match this Play - launching the ordinary way");
		discard(taken);
		return null;
	}

	/** Ends the waiting game if it is this profile's: its files (mods, jars) are about to change or go. A new one starts by itself. */
	public static void release(String instanceId) {
		Parked taken;
		synchronized (LOCK) {
			if (parked == null || !parked.instance.id().equals(instanceId)) {
				return;
			}
			taken = parked;
			parked = null;
		}
		LauncherLog.info("[instant-play] releasing the waiting game: " + taken.instance.name() + " is about to change");
		discard(taken);
	}

	/** Ends the waiting game, if any (launcher closing, setting turned off). */
	public static void shutdown() {
		Parked taken;
		synchronized (LOCK) {
			taken = parked;
			parked = null;
		}
		if (taken != null) {
			discard(taken);
		}
	}

	private static void discard(Parked entry) {
		try {
			entry.process.destroy();
			if (!entry.process.waitFor(3, TimeUnit.SECONDS)) {
				entry.process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			entry.process.destroyForcibly();
		} finally {
			try {
				Files.deleteIfExists(entry.signal);
			} catch (IOException ignored) {
				// a stray empty file in logs/
			}
		}
	}

	/** What the waiting game was started with that the launch command does not show: the mods and the options file. */
	private static String fingerprint(Instance instance) {
		StringBuilder out = new StringBuilder();
		// launcher settings that change what the game is started with (memory, collector, shader cache): another value = another game
		var settings = LauncherSettings.get();
		out.append(settings.memoryMode).append(':').append(settings.fixedMemoryMb).append(':').append(settings.gc).append(':')
				.append(settings.driverShaderCache).append(';');
		try (Stream<Path> mods = Files.list(InstancePaths.modsDir(instance.id()))) {
			mods.sorted().forEach(p -> {
				try {
					out.append(p.getFileName()).append(':').append(Files.size(p)).append(':').append(Files.getLastModifiedTime(p).toMillis()).append(';');
				} catch (IOException ignored) {
					// a file that went away: the fingerprint differs anyway
				}
			});
		} catch (IOException ignored) {
			// no mods folder
		}
		Path options = InstancePaths.gameDir(instance.id()).resolve("options.txt");
		try {
			if (Files.isRegularFile(options)) {
				out.append("options:").append(Files.size(options)).append(':').append(Files.getLastModifiedTime(options).toMillis());
			}
		} catch (IOException ignored) {
			// same
		}
		return out.toString();
	}
}
