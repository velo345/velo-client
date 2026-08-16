package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Remembers the last several things launched from this launcher - a mod
 * profile on its own (Play), or a profile launched straight into a specific
 * server (My Servers' Connect / Quick Play) - so the sidebar can offer a
 * one-click "Quick Launch" shortcut back into them, most-recent first.
 * Persisted to {@code ~/.velo-client/config/quick_launch.json}.
 */
public final class QuickLaunchStore {

	/** @param serverAddress nullable - null means this entry launches the profile straight to the title screen, not into a server. */
	public record Entry(String instanceId, String serverAddress, long lastLaunchedEpochMillis) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "quick_launch.json";
	private static final int MAX_ENTRIES = 3;

	private QuickLaunchStore() {
	}

	/** Most-recently-launched first. */
	public static List<Entry> loadAll() {
		Path file = VeloPaths.config().resolve(FILE_NAME);
		if (!Files.exists(file)) {
			return new ArrayList<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			List<Entry> entries = GSON.fromJson(reader, new TypeToken<List<Entry>>() {
			}.getType());
			return entries != null ? entries : new ArrayList<>();
		} catch (IOException | JsonParseException e) {
			return new ArrayList<>();
		}
	}

	/** @param serverAddress nullable - see {@link Entry#serverAddress()}. */
	public static void recordLaunch(String instanceId, String serverAddress) {
		if (instanceId == null) {
			return;
		}
		List<Entry> entries = new ArrayList<>(loadAll());
		entries.removeIf(e -> e.instanceId().equals(instanceId) && java.util.Objects.equals(e.serverAddress(), serverAddress));
		entries.add(0, new Entry(instanceId, serverAddress, System.currentTimeMillis()));
		while (entries.size() > MAX_ENTRIES) {
			entries.remove(entries.size() - 1);
		}
		save(entries);
	}

	private static void save(List<Entry> entries) {
		VeloPaths.ensureDirectories();
		Path file = VeloPaths.config().resolve(FILE_NAME);
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(entries, writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save quick launch history", e);
		}
	}
}
