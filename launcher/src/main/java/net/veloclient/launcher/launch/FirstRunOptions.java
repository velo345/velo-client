package net.veloclient.launcher.launch;

import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.instance.InstancePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Velo's starting video settings for a profile the game has never run in.
 *
 * <p>Vanilla starts every new game with VSync on and a 120 FPS limit. On a fast PC that caps the frame rate long before
 * the GPU or CPU is busy - the "GPU at 30 %" a lot of high-end players see - and Velo's uncapped rendering (decoupled
 * present, Vulkan) only runs with VSync off. So a profile's first launch gets an unlimited frame rate (vanilla's
 * {@code maxFps:260} means "Unlimited") and VSync off, like other PvP clients ship.
 *
 * <p>Only the very first time and only for keys the game hasn't written itself: as soon as options.txt has a
 * {@code maxFps} line the game has run (or the player imported settings), and nothing here is touched again.
 * A marker file makes sure a player who turns VSync back on keeps it.
 */
public final class FirstRunOptions {

	private static final String MARKER = "velo-first-run-options.done";

	private FirstRunOptions() {
	}

	/** Before a launch, ahead of anything else that writes options.txt; best-effort. */
	public static void apply(String instanceId) {
		Path marker = InstancePaths.dir(instanceId).resolve(MARKER);
		if (Files.exists(marker)) {
			return;
		}
		try {
			Path file = InstancePaths.gameDir(instanceId).resolve("options.txt");
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
			boolean ranBefore = lines.stream().anyMatch(l -> l.startsWith("maxFps:"));
			if (!ranBefore) {
				lines.add("maxFps:260");
				if (lines.stream().noneMatch(l -> l.startsWith("enableVsync:"))) {
					lines.add("enableVsync:false");
				}
				Files.createDirectories(file.getParent());
				Path tmp = file.resolveSibling("options.txt.velo-tmp");
				Files.write(tmp, lines, StandardCharsets.UTF_8);
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				LauncherLog.info("New profile: unlimited FPS and VSync off as starting video settings");
			}
			Files.createDirectories(marker.getParent());
			Files.writeString(marker, "1", StandardCharsets.UTF_8);
		} catch (IOException e) {
			LauncherLog.warn("Couldn't write the starting video settings", e);
		}
	}
}
