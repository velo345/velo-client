package net.veloclient.velo.client.stats;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.veloclient.velo.config.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minutes played per day (in a world, any server or singleplayer), for the Statistics screen's "last 7 days" chart
 * - Minecraft itself only keeps totals. Counted locally from client ticks, saved about once a minute, last 60 days.
 */
public final class PlayTimeDays {

	private static final Gson GSON = new Gson();
	private static Map<String, Integer> seconds;
	private static int ticks;
	private static int unsaved;

	private PlayTimeDays() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null || System.getProperty("velo.screenshotTour") != null) {
				return;
			}
			if (++ticks < 20) {
				return;
			}
			ticks = 0;
			load().merge(LocalDate.now().toString(), 1, Integer::sum);
			if (++unsaved >= 60) {
				save();
			}
		});
	}

	private static Path file() {
		return VeloPaths.config().resolve("playtime-days.json");
	}

	private static synchronized Map<String, Integer> load() {
		if (seconds == null) {
			seconds = new TreeMap<>();
			try {
				if (Files.exists(file())) {
					Map<String, Integer> read = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), new TypeToken<TreeMap<String, Integer>>() {
					}.getType());
					if (read != null) {
						seconds.putAll(read);
					}
				}
			} catch (Exception ignored) {
				// Starts counting again.
			}
		}
		return seconds;
	}

	private static synchronized void save() {
		unsaved = 0;
		String cutoff = LocalDate.now().minusDays(60).toString();
		load().keySet().removeIf(day -> day.compareTo(cutoff) < 0);
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(seconds), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Kept in memory.
		}
	}

	/** Seconds played on each of the last {@code days} days, oldest first (today last). */
	public static synchronized List<Integer> lastDays(int days) {
		List<Integer> out = new ArrayList<>();
		LocalDate today = LocalDate.now();
		for (int i = days - 1; i >= 0; i--) {
			out.add(load().getOrDefault(today.minusDays(i).toString(), 0));
		}
		return out;
	}

	/** Whether anything was counted yet (the chart explains itself on the first day). */
	public static synchronized boolean hasData() {
		return !load().isEmpty();
	}
}
