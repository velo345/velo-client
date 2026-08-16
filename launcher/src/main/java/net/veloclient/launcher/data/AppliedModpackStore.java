package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.veloclient.launcher.instance.InstancePaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Which modpack (if any) is currently applied to a profile - see {@link net.veloclient.launcher.modpack.ModpackInstaller}. */
public final class AppliedModpackStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private AppliedModpackStore() {
	}

	private static Path file(String instanceId) {
		return InstancePaths.dir(instanceId).resolve("applied-modpack.json");
	}

	public static Optional<AppliedModpack> load(String instanceId) {
		Path file = file(instanceId);
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return Optional.ofNullable(GSON.fromJson(reader, AppliedModpack.class));
		} catch (IOException | JsonParseException e) {
			return Optional.empty();
		}
	}

	public static void save(String instanceId, AppliedModpack modpack) {
		try {
			Files.createDirectories(InstancePaths.dir(instanceId));
			try (Writer writer = Files.newBufferedWriter(file(instanceId), StandardCharsets.UTF_8)) {
				GSON.toJson(modpack, writer);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to save applied modpack for instance " + instanceId, e);
		}
	}
}
