package net.veloclient.bridge.internal;

import net.veloclient.bridge.api.VeloPlayer;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Who is playing with Velo Client right now (they said hello on the channel) and what they told us. */
public final class Players {

	private static final Map<UUID, VeloPlayer> ONLINE = new ConcurrentHashMap<>();

	private Players() {
	}

	public static boolean isVelo(UUID uuid) {
		return ONLINE.containsKey(uuid);
	}

	public static VeloPlayer get(UUID uuid) {
		return ONLINE.get(uuid);
	}

	public static void put(VeloPlayer player) {
		ONLINE.put(player.uuid(), player);
	}

	public static void remove(UUID uuid) {
		ONLINE.remove(uuid);
	}

	public static Collection<VeloPlayer> all() {
		return ONLINE.values();
	}
}
