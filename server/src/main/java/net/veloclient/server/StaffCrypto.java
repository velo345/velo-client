package net.veloclient.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The staff panel's sign-in building blocks: salted PBKDF2 for passwords and unlock patterns, TOTP (authenticator app
 * codes, RFC 6238) and WebAuthn passkeys ("none" attestation, ES256 + RS256, user verification required). Only what
 * the panel needs - a minimal CBOR reader included.
 */
final class StaffCrypto {

	static final SecureRandom RANDOM = new SecureRandom();
	static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
	static final Base64.Decoder B64URL_IN = Base64.getUrlDecoder();
	static final int ITERATIONS = 310_000;

	private StaffCrypto() {
	}

	static String randomToken(int bytes) {
		byte[] raw = new byte[bytes];
		RANDOM.nextBytes(raw);
		return B64URL.encodeToString(raw);
	}

	static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	static String sha256Hex(String value) {
		return HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
	}

	// ---- Secrets (password, pattern) ----

	/** "salt:hash" (base64) of a new secret. */
	static String hashSecret(String secret) {
		byte[] salt = new byte[16];
		RANDOM.nextBytes(salt);
		return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(pbkdf2(secret.toCharArray(), salt));
	}

	static boolean secretMatches(String stored, String secret) {
		if (stored == null || secret == null) {
			// Same work either way, so timing doesn't tell whether a secret is set.
			pbkdf2("x".toCharArray(), new byte[16]);
			return false;
		}
		String[] parts = stored.split(":");
		try {
			return MessageDigest.isEqual(Base64.getDecoder().decode(parts[1]), pbkdf2(secret.toCharArray(), Base64.getDecoder().decode(parts[0])));
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static byte[] pbkdf2(char[] secret, byte[] salt) {
		try {
			return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(secret, salt, ITERATIONS, 256)).getEncoded();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	// ---- TOTP ----

	static String newTotpSecret() {
		byte[] raw = new byte[20];
		RANDOM.nextBytes(raw);
		return base32(raw);
	}

	/** The 30-second step the code belongs to (one step either side for clock drift), or null. */
	static Long totpStep(String secret, String code, long now) {
		if (code == null || secret == null) {
			return null;
		}
		String digits = code.replaceAll("\\s", "");
		if (!digits.matches("\\d{6}")) {
			return null;
		}
		byte[] key = unbase32(secret);
		long step = now / 30_000L;
		for (long s = step - 1; s <= step + 1; s++) {
			if (MessageDigest.isEqual(totp(key, s).getBytes(StandardCharsets.US_ASCII), digits.getBytes(StandardCharsets.US_ASCII))) {
				return s;
			}
		}
		return null;
	}

	static String totp(byte[] key, long step) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(key, "HmacSHA1"));
			byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
			int offset = h[h.length - 1] & 0x0f;
			int binary = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16) | ((h[offset + 2] & 0xff) << 8) | (h[offset + 3] & 0xff);
			return String.format("%06d", binary % 1_000_000);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

	static String base32(byte[] data) {
		StringBuilder out = new StringBuilder();
		int buffer = 0;
		int bits = 0;
		for (byte b : data) {
			buffer = (buffer << 8) | (b & 0xff);
			bits += 8;
			while (bits >= 5) {
				out.append(B32.charAt((buffer >> (bits - 5)) & 31));
				bits -= 5;
			}
		}
		if (bits > 0) {
			out.append(B32.charAt((buffer << (5 - bits)) & 31));
		}
		return out.toString();
	}

	static byte[] unbase32(String text) {
		String s = text.toUpperCase().replaceAll("[^A-Z2-7]", "");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int buffer = 0;
		int bits = 0;
		for (char c : s.toCharArray()) {
			buffer = (buffer << 5) | B32.indexOf(c);
			bits += 5;
			if (bits >= 8) {
				out.write((buffer >> (bits - 8)) & 0xff);
				bits -= 8;
			}
		}
		return out.toByteArray();
	}

	// ---- WebAuthn ----

	/** A verified new passkey. */
	record NewPasskey(String id, String publicKey, int alg, long signCount) {
	}

	/** Checks the browser's client data; returns the challenge it signed. */
	static String clientData(byte[] clientData, String type, String origin) throws ApiError {
		JsonObject c = JsonParser.parseString(new String(clientData, StandardCharsets.UTF_8)).getAsJsonObject();
		if (!type.equals(c.get("type").getAsString())) {
			throw new ApiError(400, "Wrong passkey request type");
		}
		if (!origin.equals(c.get("origin").getAsString())) {
			throw new ApiError(403, "Passkey used on the wrong site");
		}
		return c.get("challenge").getAsString();
	}

	static void authData(byte[] authData, String rpId, boolean registering) throws ApiError {
		if (authData.length < 37) {
			throw new ApiError(400, "Unreadable passkey data");
		}
		if (!MessageDigest.isEqual(Arrays.copyOfRange(authData, 0, 32), sha256(rpId.getBytes(StandardCharsets.UTF_8)))) {
			throw new ApiError(403, "Passkey made for another site");
		}
		int flags = authData[32] & 0xff;
		if ((flags & 0x01) == 0 || (flags & 0x04) == 0) {
			throw new ApiError(401, "The passkey has to check it's you (fingerprint, face or PIN)");
		}
		if (registering && (flags & 0x40) == 0) {
			throw new ApiError(400, "The passkey didn't send its key");
		}
	}

	static long signCount(byte[] authData) {
		return ((long) (authData[33] & 0xff) << 24) | ((authData[34] & 0xff) << 16) | ((authData[35] & 0xff) << 8) | (authData[36] & 0xff);
	}

	/** Reads the key out of a registration's attestation object (after the client data was checked). */
	static NewPasskey register(byte[] attestationObject, String rpId) throws Exception {
		Object att = new Cbor(attestationObject, 0).read();
		if (!(att instanceof Map<?, ?> map) || !(map.get("authData") instanceof byte[] authData)) {
			throw new ApiError(400, "Unreadable passkey");
		}
		authData(authData, rpId, true);
		int at = 37 + 16;
		int idLen = ((authData[at] & 0xff) << 8) | (authData[at + 1] & 0xff);
		byte[] credId = Arrays.copyOfRange(authData, at + 2, at + 2 + idLen);
		Object cose = new Cbor(authData, at + 2 + idLen).read();
		if (!(cose instanceof Map<?, ?> key)) {
			throw new ApiError(400, "Unreadable passkey key");
		}
		int alg = ((Number) key.get(3L)).intValue();
		PublicKey publicKey = coseKey(key, alg);
		return new NewPasskey(B64URL.encodeToString(credId), Base64.getEncoder().encodeToString(publicKey.getEncoded()), alg, signCount(authData));
	}

	static boolean verifySignature(String publicKey, int alg, byte[] authData, byte[] clientData, byte[] signature) throws GeneralSecurityException {
		PublicKey key = KeyFactory.getInstance(alg == -257 ? "RSA" : "EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
		Signature sig = Signature.getInstance(alg == -257 ? "SHA256withRSA" : "SHA256withECDSA");
		sig.initVerify(key);
		sig.update(authData);
		sig.update(sha256(clientData));
		return sig.verify(signature);
	}

	private static PublicKey coseKey(Map<?, ?> key, int alg) throws Exception {
		if (alg == -7) {
			byte[] x = (byte[]) key.get(-2L), y = (byte[]) key.get(-3L);
			AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
			params.init(new ECGenParameterSpec("secp256r1"));
			ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
			return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), spec));
		}
		if (alg == -257) {
			byte[] n = (byte[]) key.get(-1L), e = (byte[]) key.get(-2L);
			return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e)));
		}
		throw new ApiError(400, "That kind of passkey isn't supported (algorithm " + alg + ")");
	}

	/** Just enough CBOR for WebAuthn: ints, byte/text strings, arrays, maps, simple values. */
	static final class Cbor {
		private final byte[] in;
		private int pos;

		Cbor(byte[] in, int start) {
			this.in = in;
			this.pos = start;
		}

		Object read() throws ApiError {
			int b = in[pos++] & 0xff;
			int major = b >> 5, info = b & 31;
			long n;
			if (info < 24) {
				n = info;
			} else if (info == 24) {
				n = in[pos++] & 0xffL;
			} else if (info >= 25 && info <= 27) {
				n = num(1 << (info - 24));
			} else {
				throw new ApiError(400, "Unsupported passkey data");
			}
			switch (major) {
				case 0:
					return n;
				case 1:
					return -1 - n;
				case 2:
					return bytes((int) n);
				case 3:
					return new String(bytes((int) n), StandardCharsets.UTF_8);
				case 4: {
					List<Object> list = new ArrayList<>();
					for (long i = 0; i < n; i++) {
						list.add(read());
					}
					return list;
				}
				case 5: {
					Map<Object, Object> map = new LinkedHashMap<>();
					for (long i = 0; i < n; i++) {
						Object k = read();
						map.put(k, read());
					}
					return map;
				}
				case 7:
					return info == 20 ? Boolean.FALSE : info == 21 ? Boolean.TRUE : null;
				default:
					throw new ApiError(400, "Unsupported passkey data");
			}
		}

		private long num(int bytes) {
			long v = 0;
			for (int i = 0; i < bytes; i++) {
				v = (v << 8) | (in[pos++] & 0xff);
			}
			return v;
		}

		private byte[] bytes(int n) {
			byte[] out = Arrays.copyOfRange(in, pos, pos + n);
			pos += n;
			return out;
		}
	}
}
