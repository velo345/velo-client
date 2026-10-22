package net.veloclient.server;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A self-hosted, invisible CAPTCHA (the ALTCHA scheme): the server hands out {@code challenge = sha256(salt + n)}
 * for a secret random {@code n}, signed with a per-process key; the browser tries numbers until it finds {@code n}
 * (well under a second for a person, real work for a bot sending thousands of logins). No third party, no cookies,
 * nothing to agree to. Each solution works once and only within a few minutes.
 */
final class ProofOfWork {

	private static final int MAX_NUMBER = 60_000;
	private static final long TTL_MILLIS = 5 * 60_000L;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final byte[] key = new byte[32];
	private final Map<String, Long> used = new ConcurrentHashMap<>();

	ProofOfWork() {
		RANDOM.nextBytes(key);
	}

	record Challenge(String algorithm, String challenge, String salt, int maxnumber, String signature) {
	}

	record Solution(String salt, Integer number, String challenge, String signature) {
	}

	Challenge issue() {
		byte[] raw = new byte[12];
		RANDOM.nextBytes(raw);
		// The expiry rides in the salt, so it's covered by the hash and the signature.
		String salt = HexFormat.of().formatHex(raw) + "?expires=" + (System.currentTimeMillis() + TTL_MILLIS);
		int secret = RANDOM.nextInt(MAX_NUMBER);
		String challenge = sha256(salt + secret);
		return new Challenge("SHA-256", challenge, salt, MAX_NUMBER, hmac(challenge));
	}

	/** Whether this is a fresh, correctly solved challenge we issued (and marks it used). */
	boolean verify(Solution s) {
		if (s == null || s.salt() == null || s.number() == null || s.challenge() == null || s.signature() == null) {
			return false;
		}
		long now = System.currentTimeMillis();
		used.values().removeIf(expiry -> expiry < now);
		int at = s.salt().indexOf("?expires=");
		long expires;
		try {
			expires = at < 0 ? 0 : Long.parseLong(s.salt().substring(at + 9));
		} catch (NumberFormatException e) {
			return false;
		}
		if (expires < now || s.number() < 0 || s.number() > MAX_NUMBER) {
			return false;
		}
		boolean ok = MessageDigest.isEqual(hmac(s.challenge()).getBytes(StandardCharsets.US_ASCII), s.signature().getBytes(StandardCharsets.US_ASCII))
				&& MessageDigest.isEqual(sha256(s.salt() + s.number()).getBytes(StandardCharsets.US_ASCII), s.challenge().getBytes(StandardCharsets.US_ASCII));
		return ok && used.putIfAbsent(s.challenge(), expires) == null;
	}

	private String hmac(String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}
}
