package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last look each Velo player had in the game (cloak, wings, aura, bodywear, pet - already checked against what they
 * own when it came in with a heartbeat), so other Velo players still see it while they play on another client - Lunar and
 * Dawn do the same. Saved to last-cosmetics.json at most every 2 minutes.
 */
final class LastCosmetics {

	record Look(String capeId, String wingsId, String auraId, String wear, String pet, long at) {
		boolean empty() {
			return capeId == null && wingsId == null && auraId == null && wear == null && pet == null;
		}
	}

	private static final Gson GSON = new Gson();
	private static final long SAVE_EVERY_MILLIS = 120_000;
	private final Path file;
	private final Map<String, Look> looks = new ConcurrentHashMap<>();
	private volatile boolean dirty;
	private volatile long savedAt;

	LastCosmetics(Path dataDir) {
		this.file = dataDir.resolve("last-cosmetics.json");
		try {
			if (Files.exists(file)) {
				Map<String, Look> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Map<String, Look>>() { }.getType());
				if (loaded != null) {
					looks.putAll(loaded);
				}
			}
		} catch (IOException | RuntimeException e) {
			System.err.println("[cosmetics] last-cosmetics.json unreadable: " + e);
		}
		Thread saver = Thread.ofPlatform().daemon().name("last-cosmetics-saver").start(() -> {
			while (true) {
				try {
					Thread.sleep(SAVE_EVERY_MILLIS);
					save();
				} catch (InterruptedException e) {
					return;
				}
			}
		});
		Runtime.getRuntime().addShutdownHook(new Thread(this::save));
	}

	/** What a game heartbeat accepted for this player. */
	void record(String uuid, String capeId, String wingsId, String auraId, String wear, String pet) {
		Look look = new Look(capeId, wingsId, auraId, wear, pet, System.currentTimeMillis());
		Look before = looks.get(uuid);
		if (before == null || !java.util.Objects.equals(before.capeId(), capeId) || !java.util.Objects.equals(before.wingsId(), wingsId)
				|| !java.util.Objects.equals(before.auraId(), auraId) || !java.util.Objects.equals(before.wear(), wear)
				|| !java.util.Objects.equals(before.pet(), pet)) {
			looks.put(uuid, look);
			dirty = true;
		}
	}

	Look get(String uuid) {
		return looks.get(uuid);
	}

	synchronized void save() {
		if (!dirty) {
			return;
		}
		try {
			Path temp = Files.createTempFile(file.getParent(), "last-cosmetics-", ".json.tmp");
			Files.writeString(temp, GSON.toJson(looks), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
			savedAt = System.currentTimeMillis();
		} catch (IOException e) {
			System.err.println("[cosmetics] couldn't save last-cosmetics.json: " + e);
		}
	}
}
