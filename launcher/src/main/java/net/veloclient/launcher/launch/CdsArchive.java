package net.veloclient.launcher.launch;

import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Class Data Sharing for the game JVM: the JDK and library classes the game loaded last time are
 * kept in a memory-mapped archive per profile, so the next start skips parsing and verifying them
 * again. Measured on 26.2 (benchmark profile): about 1 s off every start after the first.
 *
 * <p>The per-profile ("dynamic") archive needs the runtime's own base archive, which the jlink'd
 * runtimes the launcher ships and downloads don't contain. It's created once per runtime in the
 * background ({@code -Xshare:dump}, with the same compact-headers and native-access flags the game
 * runs with, or the JVM refuses it) and used from the next start on. The JVM itself rebuilds a
 * profile's archive whenever it no longer matches (another Minecraft or Fabric version); a runtime
 * the user can't write to just runs without it.
 */
final class CdsArchive {

	private static final Set<Path> DUMPING = ConcurrentHashMap.newKeySet();

	private CdsArchive() {
	}

	static List<String> jvmArgs(JavaRuntimeProvider.JavaRuntime runtime, Instance instance, boolean compactHeaders) {
		if (runtime.feature() < 19) {
			return List.of(); // AutoCreateSharedArchive is JDK 19+
		}
		Path home = runtime.binary().toAbsolutePath().getParent().getParent();
		Path serverDir = home.resolve("lib").resolve("server");
		Path base = serverDir.resolve(compactHeaders ? "classes_coh.jsa" : "classes.jsa");
		if (!Files.exists(base)) {
			if (Files.isDirectory(serverDir) && Files.isWritable(serverDir)) {
				dumpBaseInBackground(runtime.binary(), base, compactHeaders);
			}
			return List.of();
		}
		Path archive = InstancePaths.dir(instance.id()).resolve("velo-cds-" + runtime.feature() + (compactHeaders ? "-coh" : "") + ".jsa");
		return List.of("-XX:SharedArchiveFile=" + archive.toAbsolutePath(), "-XX:+AutoCreateSharedArchive", "-Xlog:cds=off", "-Xlog:cds+dynamic=off");
	}

	private static void dumpBaseInBackground(Path java, Path base, boolean compactHeaders) {
		if (!DUMPING.add(base)) {
			return;
		}
		Thread thread = new Thread(() -> {
			List<String> command = new ArrayList<>(List.of(java.toAbsolutePath().toString()));
			if (compactHeaders) {
				command.add("-XX:+UseCompactObjectHeaders");
			}
			command.add("--enable-native-access=ALL-UNNAMED");
			command.add("-Xshare:dump");
			try {
				Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
				if (!process.waitFor(2, TimeUnit.MINUTES)) {
					process.destroyForcibly();
				}
				LauncherLog.info(Files.exists(base) ? "Created the Java class-sharing archive (faster game starts from the next launch)"
						: "Couldn't create the Java class-sharing archive - starting without it");
			} catch (Exception e) {
				LauncherLog.info("Couldn't create the Java class-sharing archive: " + e.getMessage());
			} finally {
				DUMPING.remove(base);
			}
		}, "velo-cds-dump");
		thread.setDaemon(true);
		thread.start();
	}
}
