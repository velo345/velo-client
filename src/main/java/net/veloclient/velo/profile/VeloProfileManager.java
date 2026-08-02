package net.veloclient.velo.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.veloclient.velo.config.VeloPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Create/choose/rename/delete storage for named {@link VeloProfile}s, each a
 * standalone bundle of every module's settings and the HUD layout, plus
 * which one is currently active. Not to be confused with {@link ProfileManager}
 * / {@link ServerProfile}, the older per-server auto-load system that only
 * captures module on/off states and is matched by server address rather
 * than chosen manually - different JSON shape, different directory
 * ({@link VeloPaths#savedProfiles()} instead of {@link VeloPaths#profiles()}).
 */
public final class VeloProfileManager {

	public static final String DEFAULT_PROFILE_NAME = "Default";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private VeloProfileManager() {
	}

	private static Path fileFor(String name) {
		return VeloPaths.savedProfiles().resolve(sanitize(name) + ".json");
	}

	private static Path activeMarkerFile() {
		return VeloPaths.config().resolve("active-profile.json");
	}

	public static List<String> listProfileNames() {
		VeloPaths.ensureDirectories();
		List<String> names = new ArrayList<>();
		try (var files = Files.list(VeloPaths.savedProfiles())) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
				load(file).ifPresent(p -> names.add(p.name()));
			}
		} catch (IOException ignored) {
			// No saved-profiles directory content yet.
		}
		return names;
	}

	public static Optional<VeloProfile> loadByName(String name) {
		return load(fileFor(name));
	}

	private static Optional<VeloProfile> load(Path file) {
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			VeloProfile profile = GSON.fromJson(reader, VeloProfile.class);
			return Optional.ofNullable(profile);
		} catch (IOException | com.google.gson.JsonSyntaxException e) {
			return Optional.empty();
		}
	}

	public static void save(VeloProfile profile) {
		VeloPaths.ensureDirectories();
		try (Writer writer = Files.newBufferedWriter(fileFor(profile.name()), StandardCharsets.UTF_8)) {
			GSON.toJson(profile, writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save profile " + profile.name(), e);
		}
	}

	public static void delete(String name) {
		try {
			Files.deleteIfExists(fileFor(name));
		} catch (IOException ignored) {
			// Nothing else sensible to do - the file just stays until the next attempt.
		}
	}

	public static void rename(String oldName, String newName) {
		loadByName(oldName).ifPresent(profile -> {
			save(new VeloProfile(newName, profile.moduleStates(), profile.hudLayout()));
			delete(oldName);
			if (oldName.equals(activeName())) {
				setActiveName(newName);
			}
		});
	}

	public static String activeName() {
		VeloPaths.ensureDirectories();
		Path file = activeMarkerFile();
		if (!Files.exists(file)) {
			return DEFAULT_PROFILE_NAME;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			ActiveMarker marker = GSON.fromJson(reader, ActiveMarker.class);
			return marker != null && marker.name() != null ? marker.name() : DEFAULT_PROFILE_NAME;
		} catch (IOException | com.google.gson.JsonSyntaxException e) {
			return DEFAULT_PROFILE_NAME;
		}
	}

	public static void setActiveName(String name) {
		VeloPaths.ensureDirectories();
		try (Writer writer = Files.newBufferedWriter(activeMarkerFile(), StandardCharsets.UTF_8)) {
			GSON.toJson(new ActiveMarker(name), writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save active profile marker", e);
		}
	}

	private static String sanitize(String name) {
		String cleaned = name.toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
		return cleaned.isBlank() ? "profile" : cleaned;
	}

	private record ActiveMarker(String name) {
	}
}
