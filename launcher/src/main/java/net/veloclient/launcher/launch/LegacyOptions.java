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
import java.util.stream.Stream;

/**
 * Settings old versions need to run on Java 25 (Ornithe). 1.8.9/1.9.4 ship Netty 4.0.23, whose native Linux transport
 * (epoll) can't read its buffers on today's Java ("Unable to access address of buffer", every packet) - so the game
 * uses Java's own transport instead ({@code useNativeTransport:false}). On 1.8.9 Sarcio brings a current Netty, so
 * there it stays as the player set it.
 */
public final class LegacyOptions {

	private static final String KEY = "useNativeTransport:";

	private LegacyOptions() {
	}

	/** Before a launch; best-effort. */
	public static void apply(String instanceId, GameVersion version, Path modsDir) {
		if (!version.ornithe() || hasSarcio(modsDir)) {
			return;
		}
		try {
			Path file = InstancePaths.gameDir(instanceId).resolve("options.txt");
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
			String want = KEY + "false";
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).startsWith(KEY)) {
					found = true;
					if (lines.get(i).equals(want)) {
						return;
					}
					lines.set(i, want);
				}
			}
			if (!found) {
				lines.add(want);
			}
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("options.txt.velo-tmp");
			Files.write(tmp, lines, StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LauncherLog.warn("Couldn't switch Minecraft " + version.id() + " to Java's network transport", e);
		}
	}

	private static boolean hasSarcio(Path modsDir) {
		try (Stream<Path> files = Files.isDirectory(modsDir) ? Files.list(modsDir) : Stream.empty()) {
			return files.anyMatch(p -> {
				String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
				return name.startsWith("sarcio") && name.endsWith(".jar");
			});
		} catch (IOException e) {
			return false;
		}
	}
}
