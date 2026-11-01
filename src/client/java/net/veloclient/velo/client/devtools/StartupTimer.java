package net.veloclient.velo.client.devtools;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.VeloClient;

/**
 * Dev helpers for measuring runs from the command line:
 * <ul>
 * <li>logs once how long after JVM start the game was ready (loading screen gone) - always on, one line;</li>
 * <li>{@code -Dvelo.profileExitAfter=SECONDS} quits the game cleanly that long after it was ready. Killing the Gradle
 * run from outside left the game process orphaned with its output pipe gone, where it froze and later aborted.</li>
 * </ul>
 */
public final class StartupTimer {

	private static final int EXIT_AFTER = Integer.getInteger("velo.profileExitAfter", 0);
	private static long readyAt;
	private static boolean stopping;

	private StartupTimer() {
	}

	/** Every client tick. */
	public static void tick(MinecraftClient client) {
		if (readyAt == 0) {
			//? if >=26.2 {
			/*if (client.gui.overlay() != null) {
				return;
			}
			*///?} else {
			if (client.getOverlay() != null) {
				return;
			}
			//?}
			readyAt = System.currentTimeMillis();
			long start = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
			VeloClient.LOGGER.info("Velo startup: game ready {} ms after JVM start", readyAt - start);
			return;
		}
		if (EXIT_AFTER > 0 && !stopping && System.currentTimeMillis() - readyAt > EXIT_AFTER * 1000L) {
			stopping = true;
			VeloClient.LOGGER.info("Velo profile run: quitting after {} s", EXIT_AFTER);
			//? if >=26.1 {
			/*client.stop();
			*///?} else {
			client.scheduleStop();
			//?}
		}
	}
}
