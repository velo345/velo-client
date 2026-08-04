package net.veloclient.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists every signed-in account's refresh token + last-known profile to
 * {@code ~/.velo-client/config/account.json} (multiple accounts, one marked
 * active), and re-authenticates the active one silently on startup when
 * possible so the user doesn't have to enter the device code every launch.
 * The Minecraft access token itself is short-lived and always re-derived
 * from the refresh token rather than cached long-term.
 *
 * <p>Transparently migrates the older single-account file format (a bare
 * {@code MinecraftSession} object, from before account switching existed)
 * into a one-account version of the new format the first time it's read.
 */
public final class AuthSession {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String CONFIG_ID = "account.json";

	private record AccountsFile(List<MinecraftSession> accounts, String activeUuid) {
	}

	private AuthSession() {
	}

	/** The currently-active account, if any are saved. */
	public static Optional<MinecraftSession> loadCached() {
		AccountsFile file = load();
		return file.accounts().stream().filter(a -> a.uuid().equals(file.activeUuid())).findFirst()
				.or(() -> file.accounts().stream().findFirst());
	}

	/** Every saved account, active one first. */
	public static List<MinecraftSession> loadAllAccounts() {
		AccountsFile file = load();
		List<MinecraftSession> ordered = new ArrayList<>(file.accounts());
		ordered.sort((a, b) -> Boolean.compare(!a.uuid().equals(file.activeUuid()), !b.uuid().equals(file.activeUuid())));
		return ordered;
	}

	/** Adds/updates an account by uuid (e.g. after a fresh sign-in or a token refresh) and marks it active. */
	public static void save(MinecraftSession session) {
		AccountsFile file = load();
		List<MinecraftSession> accounts = new ArrayList<>(file.accounts());
		accounts.removeIf(a -> a.uuid().equals(session.uuid()));
		accounts.add(session);
		write(new AccountsFile(accounts, session.uuid()));
	}

	/** Switches the active account to an already-saved one (the account switcher dropdown), without re-authenticating. */
	public static Optional<MinecraftSession> switchTo(String uuid) {
		AccountsFile file = load();
		Optional<MinecraftSession> match = file.accounts().stream().filter(a -> a.uuid().equals(uuid)).findFirst();
		match.ifPresent(m -> write(new AccountsFile(file.accounts(), uuid)));
		return match;
	}

	/**
	 * Signs out one account. If other saved accounts remain, the first of
	 * them becomes active and is returned so the caller can switch straight
	 * into it instead of dropping back to "not signed in".
	 */
	public static Optional<MinecraftSession> remove(String uuid) {
		AccountsFile file = load();
		List<MinecraftSession> remaining = file.accounts().stream().filter(a -> !a.uuid().equals(uuid)).toList();
		String newActive = remaining.isEmpty() ? null : remaining.get(0).uuid();
		write(new AccountsFile(remaining, newActive));
		return remaining.stream().findFirst();
	}

	/** Wipes every saved account. */
	public static void clear() {
		write(new AccountsFile(List.of(), null));
	}

	private static AccountsFile load() {
		Path path = VeloPaths.config().resolve(CONFIG_ID);
		if (!Files.exists(path)) {
			return new AccountsFile(List.of(), null);
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (root == null || root.isJsonNull()) {
				return new AccountsFile(List.of(), null);
			}
			if (root.isJsonObject() && root.getAsJsonObject().has("accounts")) {
				AccountsFile parsed = GSON.fromJson(root, AccountsFile.class);
				if (parsed == null) {
					return new AccountsFile(List.of(), null);
				}
				return new AccountsFile(parsed.accounts() != null ? parsed.accounts() : List.of(), parsed.activeUuid());
			}
			// Legacy pre-multi-account format: a bare MinecraftSession.
			MinecraftSession legacy = GSON.fromJson(root, MinecraftSession.class);
			return legacy == null ? new AccountsFile(List.of(), null) : new AccountsFile(List.of(legacy), legacy.uuid());
		} catch (IOException | JsonSyntaxException e) {
			return new AccountsFile(List.of(), null);
		}
	}

	private static void write(AccountsFile file) {
		VeloPaths.ensureDirectories();
		Path path = VeloPaths.config().resolve(CONFIG_ID);
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			GSON.toJson(file, writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save account session", e);
		}
	}
}
