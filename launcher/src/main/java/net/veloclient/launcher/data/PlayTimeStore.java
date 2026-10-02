package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Time actually spent in game, per profile, recorded by the launcher whenever a game it started
 * exits (so it covers servers too, unlike the per-world stats Minecraft keeps itself).
 */
public final class PlayTimeStore {

	public static final class Data {
		public Map<String, Long> millisByProfile = new LinkedHashMap<>();
		public Map<String, Integer> sessionsByProfile = new LinkedHashMap<>();
		public long longestSessionMillis;
		public long firstRecordedEpochMillis;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Data cached;

	private PlayTimeStore() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("playtime.json");
	}

	public static synchronized Data get() {
		if (cached == null) {
			try {
				cached = Files.exists(file())
						? GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Data.class)
						: new Data();
			} catch (Exception e) {
				cached = new Data();
			}
			if (cached == null) {
				cached = new Data();
			}
		}
		return cached;
	}

	/** Adds one finished session; sessions under 10 seconds (crashes on start) aren't counted. */
	public static synchronized void record(String profileId, long millis) {
		if (millis < 10_000) {
			return;
		}
		Data data = get();
		data.millisByProfile.merge(profileId, millis, Long::sum);
		data.sessionsByProfile.merge(profileId, 1, Integer::sum);
		data.longestSessionMillis = Math.max(data.longestSessionMillis, millis);
		if (data.firstRecordedEpochMillis == 0) {
			data.firstRecordedEpochMillis = System.currentTimeMillis() - millis;
		}
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(data), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Best-effort - stats only.
		}
	}

	public static long totalMillis() {
		return get().millisByProfile.values().stream().mapToLong(Long::longValue).sum();
	}

	public static int totalSessions() {
		return get().sessionsByProfile.values().stream().mapToInt(Integer::intValue).sum();
	}
}
