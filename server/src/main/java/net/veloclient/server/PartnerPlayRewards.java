package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Partner rewards for playing on a partner server: LuckySMP gives its cloak and the Lucky aura to anyone who plays
 * {@link #MINUTES} minutes in a row on luckysmp.net with Velo (owner, 2026-11-06).
 *
 * <p>Checked here, not trusted from the client: the game reports which server it is on (friends presence) and keeps a
 * live game session with heartbeats; a stay starts when the presence first names the partner's address and counts while
 * both last. Leaving, switching server or the session ending starts it over. Each reward is given once per player and
 * remembered in {@code partner-play-rewards.json}.
 */
final class PartnerPlayRewards {

	static final int MINUTES = 10;

	/** Partner address (any subdomain counts) -> cosmetics it gives. */
	private static final Map<String, List<String>> PARTNERS = Map.of("luckysmp.net", List.of("cape_luckysmp", "aura_lucky"));

	private record Stay(String partner, long since) {
	}

	private static final Gson GSON = new Gson();

	private final Path file;
	private final net.veloclient.server.store.StoreService store;
	private final Map<String, Stay> stays = new ConcurrentHashMap<>();
	/** "uuid|partner" pairs already rewarded. */
	private final Set<String> rewarded;

	PartnerPlayRewards(Path dataDir, net.veloclient.server.store.StoreService store) throws IOException {
		this.file = dataDir.resolve("partner-play-rewards.json");
		this.store = store;
		Set<String> saved = Files.exists(file)
				? GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Set<String>>() { }.getType()) : null;
		this.rewarded = saved == null ? new HashSet<>() : new HashSet<>(saved);
	}

	/** On every game heartbeat, with what the game last reported (kind "server" + address, or anything else). */
	void onGameHeartbeat(String uuid, String username, String presenceKind, String presenceDetail) {
		String partner = "server".equals(presenceKind) ? partnerFor(presenceDetail) : null;
		if (partner == null) {
			stays.remove(uuid);
			return;
		}
		long now = System.currentTimeMillis();
		Stay stay = stays.get(uuid);
		if (stay == null || !stay.partner().equals(partner)) {
			stays.put(uuid, new Stay(partner, now));
			return;
		}
		if (now - stay.since() < MINUTES * 60_000L) {
			return;
		}
		String key = uuid + "|" + partner;
		synchronized (this) {
			if (!rewarded.add(key)) {
				return;
			}
			try {
				for (String item : PARTNERS.get(partner)) {
					store.grantSystem(uuid, username, item, "partner-play:" + partner);
				}
				save();
				System.out.println("[partner-rewards] " + username + " played " + MINUTES + " min on " + partner + " - rewarded " + PARTNERS.get(partner));
			} catch (Exception e) {
				rewarded.remove(key); // try again on the next heartbeat
				System.err.println("[partner-rewards] couldn't reward " + username + ": " + e);
			}
		}
	}

	/** Display names of the reward items, for the partner cards. */
	private static final Map<String, String> REWARD_TEXT = Map.of("luckysmp.net", "the LuckySMP cloak and the Lucky aura");

	/** {minutes, items, text} for a partner address, or null when playing there gives nothing. */
	static Map<String, Object> rewardView(String address) {
		String partner = partnerFor(address);
		if (partner == null) {
			return null;
		}
		return Map.of("minutes", MINUTES, "items", PARTNERS.get(partner),
				"text", "Play " + MINUTES + " minutes here with Velo and get " + REWARD_TEXT.getOrDefault(partner, "a free cosmetic") + " for free");
	}

	/** The partner an address belongs to ("play.luckysmp.net:25565" -> "luckysmp.net"), or null. */
	static String partnerFor(String address) {
		if (address == null) {
			return null;
		}
		String host = address.toLowerCase(Locale.ROOT).trim();
		int colon = host.lastIndexOf(':');
		if (colon > 0 && host.indexOf(':') == colon) {
			host = host.substring(0, colon);
		}
		while (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		for (String partner : PARTNERS.keySet()) {
			if (host.equals(partner) || host.endsWith("." + partner)) {
				return partner;
			}
		}
		return null;
	}

	private void save() throws IOException {
		Path tmp = file.resolveSibling("partner-play-rewards.json.tmp");
		Files.writeString(tmp, GSON.toJson(rewarded), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
