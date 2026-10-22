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
 * the mod before it unlocks Velo+ features. Format: {@code v1.<payload>.<signature>} (both base64url,
 * payload = JSON {uuid, until, iat, plus, addons}). {@code addons} is only there for players the owner gave
 * the Addons to; the Addons jar checks it. A token is valid for a week at most, so a lapsed membership (or
 * taken-away access) stops working even for someone offline.
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

	/**
	 * A token for {@code uuid}, valid until the membership ends or a week from now, whichever comes first
	 * (a week for someone who only has the Addons).
	 */
	String issue(String uuid, long membershipUntil, boolean plus, boolean addons) throws Exception {
		long now = System.currentTimeMillis();
		long until = validUntil(plus ? membershipUntil : 0, addons);
		JsonObject payload = new JsonObject();
		payload.addProperty("uuid", uuid);
		payload.addProperty("until", until);
		payload.addProperty("iat", now);
		payload.addProperty("plus", plus);
		if (addons) {
			payload.addProperty("addons", true);
		}
		String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
		Signature signature = Signature.getInstance("Ed25519");
		signature.initSign(key);
		signature.update(("v1." + body).getBytes(StandardCharsets.UTF_8));
		return "v1." + body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
	}

	long validUntil(long membershipUntil, boolean addons) {
		long week = System.currentTimeMillis() + MAX_VALID_MILLIS;
		return addons ? week : Math.min(membershipUntil, week);
	}
}
