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
