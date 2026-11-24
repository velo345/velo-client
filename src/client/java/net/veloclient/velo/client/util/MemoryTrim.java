package net.veloclient.velo.client.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Gives the memory loading needed back to the system while the game sits in the menu.
 *
 * <p>Starting the game (and leaving a world) briefly needs far more heap than playing: G1 grows the heap for that and
 * keeps it, because its regular collections never shrink it - the title screen kept ~2.4 GB resident for ~0.5 GB of
 * live data (measured 2026-11-24, 137-mod profile). Five seconds after the first title screen, and five seconds after
 * leaving a world, one collection is asked for. The launcher starts the game with -XX:+ExplicitGCInvokesConcurrent, so
 * that collection runs next to the game (no pause) and hands the unused heap back at its end: 2.46 GB -> 1.48 GB.
 * Without that flag (an older launcher, another launcher) nothing is done - there it would be a full pause.
 */
public final class MemoryTrim {

	private static final long DELAY_NANOS = 5_000_000_000L;
	private static boolean wasInWorld = true; // the start counts as "just left a world"
	private static long idleSince;
	private static volatile boolean running;

	private MemoryTrim() {
	}

	public static void register() {
		if (Boolean.getBoolean("velo.noMemoryTrim") || !concurrentExplicitGc()) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			//? if <26.1 {
			boolean inWorld = client.world != null;
			//?} else {
			/*boolean inWorld = client.level != null;
			*///?}
			if (inWorld) {
				wasInWorld = true;
				idleSince = 0;
				return;
			}
			if (!wasInWorld || ClientCompat.reloadingResources()) {
				return;
			}
			long now = System.nanoTime();
			if (idleSince == 0) {
				idleSince = now;
				return;
			}
			if (now - idleSince < DELAY_NANOS || running) {
				return;
			}
			wasInWorld = false;
			idleSince = 0;
			running = true;
			Thread.ofPlatform().daemon().name("velo-memory-trim").priority(Thread.MIN_PRIORITY).start(MemoryTrim::trim);
		});
	}

	private static void trim() {
		try {
			Runtime rt = Runtime.getRuntime();
			long before = rt.totalMemory();
			long start = System.nanoTime();
			System.gc(); // concurrent (see the class comment) - this thread waits, the game doesn't
			net.veloclient.velo.VeloClient.LOGGER.info("[Velo] memory trim: heap {} MB -> {} MB committed ({} MB used) in {} ms",
					before >> 20, rt.totalMemory() >> 20, (rt.totalMemory() - rt.freeMemory()) >> 20, (System.nanoTime() - start) / 1_000_000);
		} catch (Throwable t) {
			net.veloclient.velo.VeloClient.LOGGER.debug("memory trim failed", t);
		} finally {
			running = false;
		}
	}

	private static boolean concurrentExplicitGc() {
		try {
			var bean = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
			if (bean == null) {
				return false;
			}
			if ("true".equals(bean.getVMOption("UseZGC").getValue())) {
				return true; // ZGC: an explicit collection is concurrent anyway and uncommits on its own
			}
			return "true".equals(bean.getVMOption("ExplicitGCInvokesConcurrent").getValue());
		} catch (Throwable t) {
			return false;
		}
	}
}
