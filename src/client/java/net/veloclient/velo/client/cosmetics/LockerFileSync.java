package net.veloclient.velo.client.cosmetics;

import net.veloclient.velo.config.VeloPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The launcher's Locker and the game share the same files: what one equips (or a cloak it adds) shows up in the other
 * within half a second, without a restart. Each manager's {@code reloadFromDisk} only reads - it never saves - so a
 * change can't bounce back and forth. The name style watches its own file ({@code NameStyles}).
 */
public final class LockerFileSync {

	private static final Map<String, Runnable> FILES = new LinkedHashMap<>();
	private static final Map<String, Long> STAMPS = new java.util.HashMap<>();
	private static String account;
	private static long capesStamp = Long.MIN_VALUE;
	private static int ticks;

	static {
		FILES.put("cosmetics-cape.json", CapeManager::reloadEquipped);
		FILES.put("cosmetics-wings.json", net.veloclient.velo.client.cosmetics.wings.WingsManager::reloadFromDisk);
		FILES.put("cosmetics-aura.json", net.veloclient.velo.client.cosmetics.aura.AuraManager::reloadFromDisk);
		FILES.put("cosmetics-wear.json", net.veloclient.velo.client.cosmetics.wear.WearManager::reloadFromDisk);
		FILES.put("cosmetics-killfx.json", net.veloclient.velo.client.cosmetics.killfx.KillFxManager::reloadFromDisk);
		FILES.put("cosmetics-emotes.json", net.veloclient.velo.client.emotes.EmoteCatalog::reloadFromDisk);
	}

	private LockerFileSync() {
	}

	/** Client tick: checks the files every 10 ticks (a few stat calls). */
	public static void tick() {
		if (++ticks % 10 != 0) {
			return;
		}
		boolean first = !java.util.Objects.equals(account, VeloPaths.account()) || capesStamp == Long.MIN_VALUE;
		account = VeloPaths.account();
		boolean changed = false;
		for (Map.Entry<String, Runnable> entry : FILES.entrySet()) {
			long stamp = stamp(VeloPaths.accountConfig(entry.getKey()));
			Long before = STAMPS.put(entry.getKey(), stamp);
			if (!first && (before == null || before != stamp)) {
				run(entry.getValue());
				changed = true;
			}
		}
		long capes = capesStamp();
		if (!first && capes != capesStamp) {
			run(CapeManager::reloadFromDisk);
			changed = true;
		}
		capesStamp = capes;
		if (changed) {
			net.veloclient.velo.client.network.VeloServerClient.syncSoon();
		}
	}

	private static void run(Runnable reload) {
		try {
			reload.run();
		} catch (RuntimeException e) {
			// Half-written by the launcher: the next check (new stamp) reads it again.
			net.veloclient.velo.VeloClient.LOGGER.debug("Locker sync reload failed", e);
		}
	}

	private static long stamp(Path file) {
		try {
			return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() ^ Files.size(file) << 40 : 0;
		} catch (Exception e) {
			return -1;
		}
	}

	/** Changes when a cloak bundle is added, removed or rewritten. */
	private static long capesStamp() {
		try (var files = Files.list(VeloPaths.capes())) {
			long[] sum = {0};
			files.filter(p -> p.toString().endsWith(".velocape")).forEach(p -> sum[0] = sum[0] * 31 + p.getFileName().hashCode() + stamp(p));
			return sum[0];
		} catch (Exception e) {
			return 0;
		}
	}
}
