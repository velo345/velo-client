package net.veloclient.velo.client.redstone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.veloclient.velo.config.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redstone Mode's own settings ({@code config/redstone-mode.json}) - separate from Velo profiles
 * and modules, since Redstone Mode is its own mode rather than a module. Edited from the
 * overlay's tool panel and menus; panel positions come from Edit HUD Layout while in the mode.
 */
public final class RmSettings {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Persisted values. */
	public static final class Data {
		// Analysis
		public String dustWarnings = "Always";
		public boolean signalNumbers = true;
		public boolean clockLabels = true;
		public boolean qcMarkers = true;
		public boolean rateMeters = true;
		// Editing
		public boolean keepRunning = true;
		public int pasteSpeed = 20_000;
		/** Pastes, moves and stacks leave the world's blocks where the copy has air (faster); off = air clears them. */
		public boolean skipAir = true;
		/** Unused since the tool slot became its own slot 0; kept so older settings files still load. */
		public int toolSlot = 8;
		// Redstone wiring
		public String wireStyle = "Dust";
		public String wireBlock = "auto";
		public String pathMode = "Go Around";
		public boolean delayMode = false;
		public int delayTicks = 1;
		// Water wiring
		public String waterFloor = "auto";
		public String waterWall = "auto";
		public String waterSeparator = "auto";
		// Timeline
		public double playbackSpeed = 1;
		public int timelineFrames = 1200;
		public int keepRecordings = 5;
		// Overlay panel layout: id -> {x, y, scale, placed}
		public Map<String, float[]> panels = new LinkedHashMap<>();
	}

	private static Data data;

	private RmSettings() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("redstone-mode.json");
	}

	public static synchronized Data get() {
		if (data == null) {
			try {
				if (Files.exists(file())) {
					data = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Data.class);
				}
			} catch (Exception ignored) {
				// Defaults below.
			}
			if (data == null) {
				data = new Data();
			}
			if (data.panels == null) {
				data.panels = new LinkedHashMap<>();
			}
		}
		return data;
	}

	public static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(get()), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Settings just don't persist this time.
		}
	}
}
