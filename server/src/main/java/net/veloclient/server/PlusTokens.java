package net.veloclient.server;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Signed proof of Velo+ ("this uuid is a member until then"), made with the server's Ed25519 key
 * ({@code VELO_PLUS_SIGNING_KEY}, PKCS#8 base64); the game checks it against the public key built into
 * the mod before it lets the Velo Client Addons modules load. Format: {@code v1.<payload>.<signature>}
 * (both base64url, payload = JSON {uuid, until, iat}). A token is valid for a week at most, so a lapsed
 * membership stops working even for someone offline.
 */
final class PlusTokens {

	static final long MAX_VALID_MILLIS = 7L * 24 * 3600 * 1000;

	private final PrivateKey key;

	PlusTokens(String privateKeyBase64) {
		PrivateKey parsed = null;
		if (privateKeyBase64 != null && !privateKeyBase64.isBlank()) {
			try {
				parsed = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64.trim())));
			} catch (Exception e) {
				System.out.println("[plus] VELO_PLUS_SIGNING_KEY is invalid: " + e.getMessage());
			}
		}
		this.key = parsed;
	}

	boolean enabled() {
		return key != null;
	}

	/** A token for {@code uuid}, valid until the membership ends or a week from now, whichever comes first. */
	String issue(String uuid, long membershipUntil) throws Exception {
		long now = System.currentTimeMillis();
		long until = Math.min(membershipUntil, now + MAX_VALID_MILLIS);
		JsonObject payload = new JsonObject();
		payload.addProperty("uuid", uuid);
		payload.addProperty("until", until);
		payload.addProperty("iat", now);
		String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
		Signature signature = Signature.getInstance("Ed25519");
		signature.initSign(key);
		signature.update(("v1." + body).getBytes(StandardCharsets.UTF_8));
		return "v1." + body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
	}

	long validUntil(long membershipUntil) {
		return Math.min(membershipUntil, System.currentTimeMillis() + MAX_VALID_MILLIS);
	}
}
