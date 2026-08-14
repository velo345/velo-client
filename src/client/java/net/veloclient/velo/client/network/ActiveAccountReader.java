package net.veloclient.velo.client.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.config.VeloPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads the currently-signed-in Velo account straight out of
 * {@code account.json} - the same file {@code VeloAccountAuth} and the
 * launcher's {@code MicrosoftAuth} maintain - without triggering a refresh.
 * {@link VeloServerClient} only needs to fall back to {@code
 * VeloAccountAuth#refreshActiveAccount()} (a full network round trip) when
 * the cached token here has actually expired, so this stays a cheap local
 * file read for the common case of "already have a live token".
 */
final class ActiveAccountReader {

	record Account(String uuid, String username, String accessToken, long expiresAtEpochMillis) {
		boolean tokenLikelyValid() {
			// A minute of slack so a call that starts right before expiry
			// doesn't get rejected mid-flight by Mojang.
			return accessToken != null && !accessToken.isBlank()
					&& expiresAtEpochMillis > System.currentTimeMillis() + 60_000;
		}
	}

	private ActiveAccountReader() {
	}

	static Optional<Account> read() {
		Path path = VeloPaths.config().resolve("account.json");
		if (!Files.exists(path)) {
			return Optional.empty();
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			if (!root.has("activeUuid") || root.get("activeUuid").isJsonNull() || !root.has("accounts")) {
				return Optional.empty();
			}
			String activeUuid = root.get("activeUuid").getAsString();
			JsonArray accounts = root.getAsJsonArray("accounts");
			for (JsonElement element : accounts) {
				JsonObject account = element.getAsJsonObject();
				if (!account.has("uuid") || !activeUuid.equals(account.get("uuid").getAsString())) {
					continue;
				}
				String username = account.has("username") ? account.get("username").getAsString() : null;
				String accessToken = account.has("minecraftAccessToken") && !account.get("minecraftAccessToken").isJsonNull()
						? account.get("minecraftAccessToken").getAsString() : null;
				long expiresAt = account.has("accessTokenExpiresAtEpochMillis")
						? account.get("accessTokenExpiresAtEpochMillis").getAsLong() : 0L;
				if (username == null) {
					return Optional.empty();
				}
				return Optional.of(new Account(activeUuid, username, accessToken, expiresAt));
			}
			return Optional.empty();
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}
}
