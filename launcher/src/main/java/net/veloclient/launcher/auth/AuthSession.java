package net.veloclient.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Persists the signed-in account's refresh token + last-known profile to
 * {@code ~/.velo-client/config/account.json}, and re-authenticates silently
 * on startup when possible so the user doesn't have to enter the device code
 * every launch. The Minecraft access token itself is short-lived and always
 * re-derived from the refresh token rather than cached long-term.
 */
public final class AuthSession {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String CONFIG_ID = "account.json";

	private AuthSession() {
	}

	public static Optional<MinecraftSession> loadCached() {
		Path file = VeloPaths.config().resolve(CONFIG_ID);
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			MinecraftSession session = GSON.fromJson(reader, MinecraftSession.class);
			return Optional.ofNullable(session);
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	public static void save(MinecraftSession session) {
		VeloPaths.ensureDirectories();
		Path file = VeloPaths.config().resolve(CONFIG_ID);
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(session, writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save account session", e);
		}
	}

	public static void clear() {
		Path file = VeloPaths.config().resolve(CONFIG_ID);
		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// Best-effort sign-out.
		}
	}
}
