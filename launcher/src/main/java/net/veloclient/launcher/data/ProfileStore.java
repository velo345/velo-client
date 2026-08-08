package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Reads/writes the same {@code ~/.velo-client/profiles/*.json} files the mod's {@code ProfileManager} uses. */
public final class ProfileStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ProfileStore() {
	}

	public static List<ProfileData> loadAll() {
		VeloPaths.ensureDirectories();
		List<ProfileData> profiles = new ArrayList<>();
		try (Stream<Path> files = Files.list(VeloPaths.profiles())) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					ProfileData profile = GSON.fromJson(reader, ProfileData.class);
					if (profile != null) {
						profiles.add(profile);
					}
				} catch (IOException | JsonParseException ignored) {
					// Skip one corrupt profile file rather than letting it abort the
					// whole loop - previously had no catch of its own at all, so a
					// single malformed file (JsonSyntaxException, not an
					// IOException) escaped past the outer catch too and discarded
					// every profile already loaded before it.
				}
			}
		} catch (IOException ignored) {
			// No profiles directory content yet.
		}
		return profiles;
	}

	public static void save(ProfileData profile) {
		VeloPaths.ensureDirectories();
		Path file = VeloPaths.profiles().resolve(sanitize(profile.name()) + ".json");
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(profile, writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save profile " + profile.name(), e);
		}
	}

	public static void delete(ProfileData profile) {
		Path file = VeloPaths.profiles().resolve(sanitize(profile.name()) + ".json");
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			throw new RuntimeException("Failed to delete profile " + profile.name(), e);
		}
	}

	private static String sanitize(String name) {
		return name.toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
	}
}
