package net.veloclient.velo.client.plus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.network.VeloServerClient;
import net.veloclient.velo.config.VeloPaths;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;

/**
 * Whether this player has Velo+ - what the Velo Client Addons check before their modules load.
 * The server hands members a signed proof ({@code v1.<payload>.<signature>}, Ed25519, valid for a week
 * at most); it's saved in {@code config/velo-plus.json} and checked here against the public key built
 * into the mod: right signature, this account's UUID, not expired. The proof is fetched each time you
 * start the game (and by the launcher), so a membership that ends stops unlocking the Addons within
 * a week even if you never go online.
 */
public final class PlusAccess {

	private static volatile String reason = "";

	private PlusAccess() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("velo-plus.json");
	}

	/** True if a valid proof for the account the game runs as is on disk. */
	public static boolean allowed() {
		try {
			Path file = file();
			if (!Files.exists(file)) {
				reason = "No Velo+ proof yet - open Velo Client once while signed in with Velo+.";
				return false;
			}
			JsonObject saved = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			String token = saved.get("token").getAsString();
			String[] parts = token.split("\\.");
			if (parts.length != 3 || !parts[0].equals("v1")) {
				reason = "Unreadable Velo+ proof.";
				return false;
			}
			Signature verify = Signature.getInstance("Ed25519");
			verify.initVerify(publicKey());
			verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
			if (!verify.verify(Base64.getUrlDecoder().decode(parts[2]))) {
				reason = "The Velo+ proof isn't genuine.";
				return false;
			}
			JsonObject payload = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
			String me = VeloServerClient.sessionUuid();
			if (me == null || !me.equalsIgnoreCase(payload.get("uuid").getAsString().replace("-", ""))) {
				reason = "The Velo+ proof is for another account.";
				return false;
			}
			if (payload.get("until").getAsLong() < System.currentTimeMillis()) {
				reason = "Your Velo+ proof has expired - go online with Velo Client to renew it (or subscribe again).";
				return false;
			}
			reason = "";
			return true;
		} catch (Exception e) {
			reason = "Couldn't check Velo+: " + e.getMessage();
			return false;
		}
	}

	/** Why {@link #allowed()} last said no (empty when it said yes). */
	public static String reason() {
		return reason;
	}

	private static PublicKey publicKey() throws Exception {
		try (InputStream in = PlusAccess.class.getResourceAsStream("/assets/velo-client/plus/public.key")) {
			byte[] der = Base64.getDecoder().decode(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
			return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
		}
	}

	/** Fetches a fresh proof from the server (members only) and saves it; in the background. */
	public static void refresh() {
		String base = VeloServerClient.serverBaseUrl();
		String session = VeloServerClient.sessionToken();
		if (base == null || session == null) {
			return;
		}
		Thread.ofVirtual().name("velo-plus-token").start(() -> {
			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/plus/token")).timeout(Duration.ofSeconds(15))
						.header("Authorization", "Bearer " + session).GET().build();
				HttpResponse<String> response = VeloServerClient.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() != 200) {
					return;
				}
				JsonObject answer = JsonParser.parseString(response.body()).getAsJsonObject();
				JsonObject out = new JsonObject();
				out.addProperty("token", answer.get("token").getAsString());
				Files.createDirectories(file().getParent());
				Files.writeString(file(), out.toString(), StandardCharsets.UTF_8);
			} catch (Exception e) {
				VeloClient.LOGGER.debug("Couldn't refresh the Velo+ proof: {}", e.toString());
			}
		});
	}
}
