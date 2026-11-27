package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Launcher-wide settings ({@code config/launcher-settings.json}): how memory is allocated by
 * default and which garbage collector to use. Profiles can still override memory individually.
 */
public final class LauncherSettings {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public enum MemoryMode { AUTO, FIXED }

	public enum GcMode { AUTO, G1, ZGC }

	public static final class Data {
		public MemoryMode memoryMode = MemoryMode.AUTO;
		public int fixedMemoryMb = 4096;
		public GcMode gc = GcMode.AUTO;
		/** Let the driver keep a bigger shader cache between launches (Linux, NVIDIA/Mesa). */
		public boolean driverShaderCache = true;
		/** Linux: stop a game before the PC runs out of RAM and freezes (see MemoryGuard). */
		public boolean memoryGuard = true;
		/** Stop when available RAM stays below this... */
		public int guardAvailableMb = 220;
		/** ...while free swap is also below this (no swap counts as below). */
		public int guardSwapMb = 160;
		/** ...for this long, in seconds. */
		public double guardSeconds = 2.0;
		/** Newest news post the player has seen (publish time) - for the sidebar's unread badge. */
		public long newsSeenAt;
		/** Crash reports already offered/sent (file names), so a crash is only brought up once. */
		public java.util.List<String> handledCrashes = new java.util.ArrayList<>();
		/** Opt-in: send crash reports (crash report, log, mods, system) automatically, no dialog click needed. */
		public boolean crashTelemetry;
		/**
		 * For weaker PCs: the launcher draws at 30 frames a second (from its next start), wings show without their
		 * extra effects (moving arms, glow, shine, particles) and previews without auras.
		 */
		public boolean performanceMode;
		/** Launcher updates download (and mod updates install) in the background; a small card offers "Restart to update". */
		public boolean autoUpdate = true;
		/** Minimize the launcher once a game has started (it then frees its memory - see idle mode). */
		public boolean minimizeOnLaunch = true;
		/**
		 * Instant Play (off by default): the game of the profile played last waits hidden at its title screen, so Play only
		 * shows it. Costs about 1.5 GB of RAM while it waits - see {@code InstantPlay}.
		 */
		public boolean instantPlay;
		/** AI Access: the game opens its local AI API (127.0.0.1) so AI agents can drive it - see the game's velo-rewind/AI.md. */
		public boolean aiAccess;
		/**
		 * Windows only, on by default: the game starts one priority step above normal and Windows is asked not to throttle it
		 * (power saving, timer resolution) - shorter loading and steadier frames next to a busy desktop, at the price of
		 * taking a little more CPU from other programs. See {@code WindowsProcessBoost}.
		 */
		public boolean processBoost = true;
		/** Linux: start the game in an invisible display (kwin_wayland --virtual or xvfb-run) while AI Access is on. */
		public boolean aiHidden;
		/** The API's token, the same for every profile and launch. */
		public String aiToken = "";
		/** Party leader: pressing Play also launches every member. */
		public boolean partyLaunchTogether = true;
		/** Party member: launch when the leader does (asks with a short countdown). */
		public boolean partyFollowLaunches = true;
		/** Home's Ridgeline backdrop moves (drifting hills, stars, moon). Off = a still picture, a little less CPU. */
		public boolean animateHomeBackground = true;
		/** The glass launcher's backdrop: a PixelScenes id, or "custom" (Velo+, the HomeBackground picture). */
		public String backdrop = "sunset";
		/** False until the player picks a background in Settings (the default can change until then). */
		public boolean backdropPicked;
		/** How dark the glass panels are (0.15 clear .. 0.85 almost solid). */
		public double glassDarkness = 0.45;
		/** Velo+ custom backdrop: blur and dim, 0..1. */
		public double customBlur = 0.3;
		public double customDim = 0.35;
		/** System check kinds (finding titles) that no longer interrupt a launch. */
		public java.util.List<String> silencedChecks = new java.util.ArrayList<>();
	}

	private static Data cached;

	private LauncherSettings() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("launcher-settings.json");
	}

	public static synchronized Data get() {
		if (cached == null) {
			try {
				if (Files.exists(file())) {
					cached = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Data.class);
				}
			} catch (IOException | RuntimeException ignored) {
				// Fall back to defaults.
			}
			if (cached == null) {
				cached = new Data();
			}
			if (cached.memoryMode == null) {
				cached.memoryMode = MemoryMode.AUTO;
			}
			if (cached.gc == null) {
				cached.gc = GcMode.AUTO;
			}
		}
		return cached;
	}

	/** A full independent copy - every field, so saving it never resets settings it didn't mean to touch. */
	public static Data copy(Data from) {
		return GSON.fromJson(GSON.toJson(from), Data.class);
	}

	public static synchronized void save(Data data) {
		cached = data;
		try {
			VeloPaths.ensureDirectories();
			Files.writeString(file(), GSON.toJson(data), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// Settings just won't persist this time.
		}
	}
}
