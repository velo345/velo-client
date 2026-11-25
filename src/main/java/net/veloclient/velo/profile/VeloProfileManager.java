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

	/**
	 * Which saved profile this game uses. A launcher profile made since 1.1.1 has its own marker in its folder
	 * ({@code velo/active-profile.json}, written by the Velo launcher), so a new profile starts with Velo's defaults
	 * instead of taking over another profile's modules (recoloured hearts, 1.8 animations...). Older profiles have
	 * none and keep sharing the global one, as before.
	 */
	private static Path activeMarkerFile() {
		try {
			Path local = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("velo").resolve("active-profile.json");
			if (Files.exists(local)) {
				return local;
			}
		} catch (RuntimeException | LinkageError ignored) {
			// no loader (tools, tests): the global marker
		}
		return VeloPaths.config().resolve("active-profile.json");
	}

	/** file -> {last modified, name}: menus list the profiles every frame - a whole profile was parsed for each name. */
	private static final java.util.Map<Path, Object[]> NAME_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
	private static List<String> namesCached;
	private static long namesAt;

	public static List<String> listProfileNames() {
		long now = System.currentTimeMillis();
		if (namesCached != null && now - namesAt < 500) {
			return namesCached;
		}
		VeloPaths.ensureDirectories();
		List<String> names = new ArrayList<>();
		try (var files = Files.list(VeloPaths.savedProfiles())) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
				long modified = Files.getLastModifiedTime(file).toMillis();
				Object[] cached = NAME_CACHE.get(file);
				if (cached == null || (long) cached[0] != modified) {
					String name = load(file).map(VeloProfile::name).orElse(null);
					cached = new Object[] {modified, name};
					NAME_CACHE.put(file, cached);
				}
				if (cached[1] != null) {
					names.add((String) cached[1]);
				}
			}
		} catch (IOException ignored) {
			// No saved-profiles directory content yet.
		}
		namesCached = java.util.List.copyOf(names);
		namesAt = now;
		return namesCached;
	}

	public static Optional<VeloProfile> loadByName(String name) {
		flushPending(); // a save still on its way must land before the file is read
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
		namesAt = 0;
		VeloPaths.ensureDirectories();
		Path target = fileFor(profile.name());
		// written next to it and moved over: a crash or a full disk mid-write used to leave a cut-off profile
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		try {
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(profile, writer);
			}
			try {
				Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to save profile " + profile.name(), e);
		}
	}

	private static final java.util.concurrent.atomic.AtomicReference<VeloProfile> PENDING = new java.util.concurrent.atomic.AtomicReference<>();
	private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "velo-profile-save");
		t.setDaemon(true);
		return t;
	});

	/**
	 * {@link #save} on a background thread: the caller (the render thread - module toggles, HUD moves, settings) only
	 * hands over the captured profile. Several saves in a row write once, the newest. Serialising and writing every
	 * module's settings there was a hitch on slow disks / with antivirus scanning each write.
	 */
	public static void saveLater(VeloProfile profile) {
		if (PENDING.getAndSet(profile) == null) {
			WRITER.execute(VeloProfileManager::writePending);
		}
	}

	static {
		// the writer is a daemon thread: whatever is still waiting when the JVM ends is written first
		Runtime.getRuntime().addShutdownHook(new Thread(VeloProfileManager::flushPending, "velo-profile-flush"));
	}

	private static synchronized void writePending() {
		VeloProfile profile = PENDING.getAndSet(null);
		if (profile == null) {
			return;
		}
		try {
			save(profile);
		} catch (RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger("velo-client").warn("Couldn't save the Velo profile", e);
		}
	}

	/** Game closing: whatever is still waiting is written now (on this thread). */
	public static void flushPending() {
		writePending();
	}

	public static void delete(String name) {
		namesAt = 0;
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

	private static String activeCached;
	private static long activeAt;

	public static String activeName() {
		// asked every frame by the menus: read the marker at most every 250 ms (setActiveName updates it at once)
		long now = System.currentTimeMillis();
		if (activeCached != null && now - activeAt < 250) {
			return activeCached;
		}
		activeCached = readActiveName();
		activeAt = now;
		return activeCached;
	}

	private static String readActiveName() {
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
		activeCached = name;
		activeAt = System.currentTimeMillis();
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
