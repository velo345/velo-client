package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Free Velo+ that comes with a Discord role (partners, creators): the bot sends everyone who holds such a role with
 * the staff sync (every 5 minutes and right after a role change); linked members count as Velo+ for exactly as long
 * as they keep the role - nothing is written into the paid membership table, so losing the role ends it at once and
 * nothing has to be taken back. Persisted in {@code role-plus.json} so a server restart doesn't drop anyone until
 * the next sync.
 */
final class RolePlus {

	private static final Gson GSON = new Gson();
	private final Path file;
	/** Minecraft uuid -> role name. */
	private volatile Map<String, String> members = Map.of();

	RolePlus(Path dataDir) {
		this.file = dataDir.resolve("role-plus.json");
		try {
			if (Files.exists(file)) {
				Map<String, String> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
						new TypeToken<LinkedHashMap<String, String>>() { }.getType());
				members = read == null ? Map.of() : Map.copyOf(read);
			}
		} catch (Exception e) {
			System.out.println("[role-plus] Couldn't read role-plus.json: " + e.getMessage());
		}
	}

	boolean has(String uuid) {
		return uuid != null && members.containsKey(uuid);
	}

	String roleOf(String uuid) {
		return members.get(uuid);
	}

	/** Replaces the whole list; returns {added, removed}. */
	synchronized int[] replace(Map<String, String> fresh) throws IOException {
		Set<String> before = members.keySet();
		int added = 0;
		int removed = 0;
		for (String uuid : fresh.keySet()) {
			if (!before.contains(uuid)) {
				added++;
			}
		}
		for (String uuid : before) {
			if (!fresh.containsKey(uuid)) {
				removed++;
			}
		}
		members = Map.copyOf(fresh);
		Path tmp = file.resolveSibling("role-plus.json.tmp");
		Files.writeString(tmp, GSON.toJson(new LinkedHashMap<>(fresh)), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		return new int[] {added, removed};
	}
}
