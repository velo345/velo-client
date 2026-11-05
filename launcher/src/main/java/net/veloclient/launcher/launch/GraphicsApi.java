package net.veloclient.launcher.launch;

import net.veloclient.launcher.instance.InstancePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Vulkan or OpenGL for a profile (Minecraft 26.2 and newer). The game keeps the choice in the profile's options.txt
 * ({@code preferredGraphicsBackend:"vulkan"}), so that file is the one source of truth: the launcher reads and writes
 * it, the in-game video setting shows the same value.
 */
public final class GraphicsApi {

	public static final String VULKAN = "vulkan";
	public static final String OPENGL = "opengl";
	public static final String DEFAULT = "default";
	private static final String KEY = "preferredGraphicsBackend:";

	private GraphicsApi() {
	}

	/** Only 26.2+ can choose; older versions always run OpenGL. */
	public static boolean supported(String mcVersion) {
		if (mcVersion == null) {
			return false;
		}
		String[] parts = mcVersion.split("[.-]");
		try {
			int major = Integer.parseInt(parts[0]);
			int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
			return major > 26 || major == 26 && minor >= 2;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static Path options(String instanceId) {
		return InstancePaths.gameDir(instanceId).resolve("options.txt");
	}

	/**
	 * What the profile really runs: {@link #VULKAN} only when the player picked it. The game's "default" tries OpenGL
	 * first (26.2/26.3 {@code PreferredGraphicsApi.getBackendsToTry}: GL then Vulkan for DEFAULT and OPENGL), so
	 * default counts as OpenGL.
	 */
	public static String effective(String instanceId) {
		return VULKAN.equals(read(instanceId)) ? VULKAN : OPENGL;
	}

	/** {@link #VULKAN}, {@link #OPENGL} or {@link #DEFAULT} as written in options.txt (default runs OpenGL, see {@link #effective}). */
	public static String read(String instanceId) {
		try {
			Path file = options(instanceId);
			if (Files.exists(file)) {
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					if (line.startsWith(KEY)) {
						return line.substring(KEY.length()).replace("\"", "").strip().toLowerCase(java.util.Locale.ROOT);
					}
				}
			}
		} catch (IOException ignored) {
			// Unreadable: the game's default.
		}
		return DEFAULT;
	}

	/** True when the profile actually runs on OpenGL - Iris and other GL-only mods need that. */
	public static boolean isOpenGl(String instanceId, String mcVersion) {
		return !supported(mcVersion) || OPENGL.equals(effective(instanceId));
	}

	private static Path pending(String instanceId) {
		return InstancePaths.dir(instanceId).resolve("graphics-api-pending.txt");
	}

	/**
	 * Writes the choice; the game reads it on its next start. A game that is still running writes its own value back
	 * when it closes, so the choice is also kept aside and put in again right before the next launch.
	 */
	public static void write(String instanceId, String value) throws IOException {
		writeOptions(instanceId, value);
		Files.writeString(pending(instanceId), value, StandardCharsets.UTF_8);
	}

	/** Right before a launch: a choice made while the game was open goes in now (once). */
	public static void applyPending(String instanceId) {
		try {
			Path file = pending(instanceId);
			if (Files.exists(file)) {
				writeOptions(instanceId, Files.readString(file, StandardCharsets.UTF_8).strip());
				Files.deleteIfExists(file);
			}
		} catch (IOException ignored) {
			// The game keeps the value it had.
		}
	}

	private static void writeOptions(String instanceId, String value) throws IOException {
		Path file = options(instanceId);
		Files.createDirectories(file.getParent());
		List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
		String line = KEY + "\"" + value + "\"";
		boolean replaced = false;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith(KEY)) {
				lines.set(i, line);
				replaced = true;
			}
		}
		if (!replaced) {
			lines.add(line);
		}
		Path tmp = file.resolveSibling("options.txt.velo-tmp");
		Files.write(tmp, lines, StandardCharsets.UTF_8);
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
	}
}
