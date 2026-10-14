package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.veloclient.server.store.StoreService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Links a Minecraft account (proved by a Velo session) to a Discord account (proved by the bot):
 * the game or launcher asks for a short code, the player types {@code /link <code>} in the Velo
 * Discord, the bot redeems it here. One Discord account per Minecraft account and the other way
 * round. Linking unlocks {@link #REWARDS} (the Velo Neon cape). Persisted in {@code discord-links.json}.
 */
final class DiscordLinks {

	/** Cosmetics given for linking Discord. */
	static final List<String> REWARDS = List.of("cape_velo_neon");
	private static final long CODE_TTL_MILLIS = 15 * 60_000L;
	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

	record Link(String discordId, String discordName, String uuid, String username, long linkedAt) {
	}

	private record Code(String uuid, String username, long expiresAt) {
	}

	record Result(String uuid, String username, List<String> rewards) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file;
	private final StoreService store;
	private final SecureRandom random = new SecureRandom();
	private final Map<String, Code> codes = new HashMap<>();
	/** By Discord id. */
	private final Map<String, Link> links = new HashMap<>();

	DiscordLinks(Path dataDir, StoreService store) throws IOException {
		this.file = dataDir.resolve("discord-links.json");
		this.store = store;
		if (Files.exists(file)) {
			List<Link> saved = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Link>>() { }.getType());
			if (saved != null) {
				saved.forEach(l -> links.put(l.discordId(), l));
			}
		}
	}

	synchronized List<Link> all() {
		return new ArrayList<>(links.values());
	}

	synchronized Link byUuid(String uuid) {
		return links.values().stream().filter(l -> l.uuid().equals(uuid)).findFirst().orElse(null);
	}

	/** A fresh code for this player (any earlier one of theirs stops working). */
	synchronized String createCode(String uuid, String username) {
		long now = System.currentTimeMillis();
		codes.values().removeIf(c -> c.expiresAt() < now || c.uuid().equals(uuid));
		String code;
		do {
			StringBuilder sb = new StringBuilder("VELO-");
			for (int i = 0; i < 6; i++) {
				sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
			}
			code = sb.toString();
		} while (codes.containsKey(code));
		codes.put(code, new Code(uuid, username, now + CODE_TTL_MILLIS));
		return code;
	}

	static long codeTtlSeconds() {
		return CODE_TTL_MILLIS / 1000;
	}

	/** Called by the bot: links {@code discordId} to the player behind {@code code} and gives the rewards. */
	synchronized Result redeem(String rawCode, String discordId, String discordName) throws Exception {
		String code = rawCode == null ? "" : rawCode.trim().toUpperCase().replace(" ", "");
		if (!code.startsWith("VELO-") && code.length() == 6) {
			code = "VELO-" + code;
		}
		Code entry = codes.get(code);
		if (entry == null || entry.expiresAt() < System.currentTimeMillis()) {
			throw new StoreService.StoreException(404, "That code isn't valid (any more) - get a new one in Velo Client");
		}
		Link existingForPlayer = byUuid(entry.uuid());
		if (existingForPlayer != null && !existingForPlayer.discordId().equals(discordId)) {
			throw new StoreService.StoreException(409, entry.username() + " is already linked to another Discord account (" + existingForPlayer.discordName() + ")");
		}
		Link existingForDiscord = links.get(discordId);
		if (existingForDiscord != null && !existingForDiscord.uuid().equals(entry.uuid())) {
			throw new StoreService.StoreException(409, "Your Discord account is already linked to " + existingForDiscord.username() + " - use /unlink first");
		}
		codes.remove(code);
		links.put(discordId, new Link(discordId, discordName, entry.uuid(), entry.username(), System.currentTimeMillis()));
		save();
		List<String> given = new ArrayList<>();
		for (String item : REWARDS) {
			store.grantSystem(entry.uuid(), entry.username(), item, "discord:" + discordId);
			given.add(item);
		}
		return new Result(entry.uuid(), entry.username(), given);
	}

	/** Removes a link (and the rewards, when {@code revoke}). Returns false if there was none. */
	synchronized boolean unlink(String discordId, boolean revoke) throws Exception {
		Link link = links.remove(discordId);
		if (link == null) {
			return false;
		}
		save();
		if (revoke) {
			for (String item : REWARDS) {
				store.database().revokeItem(link.uuid(), item, "discord:" + discordId);
			}
		}
		return true;
	}

	private void save() throws IOException {
		Path tmp = file.resolveSibling("discord-links.json.tmp");
		Files.writeString(tmp, GSON.toJson(new ArrayList<>(links.values())), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
