package net.veloclient.panel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.AlgorithmParameters;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passkeys (WebAuthn) for the panel: sign in with the phone's fingerprint or face, or a security key, instead of the
 * password and authenticator code. A passkey is added from a signed-in session (which already passed password + code),
 * and every sign-in with it must have user verification (the biometric / PIN), so it stays two-factor.
 *
 * <p>Kept in their own file (the accounts file is root-owned and read-only for the panel). Only what's needed is
 * implemented: "none" attestation, ES256 and RS256 keys, a minimal CBOR reader for the authenticator's data.
 */
final class Passkeys {

	static final class Passkey {
		String username;
		String id;          // credential id, base64url
		String publicKey;   // X.509 SubjectPublicKeyInfo, base64
		int alg;            // COSE algorithm: -7 ES256, -257 RS256
		long signCount;
		String name;
		long createdAt;
		long lastUsed;
	}

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final long CHALLENGE_MILLIS = 3 * 60_000L;
	private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder B64URL_IN = Base64.getUrlDecoder();

	private final Path file;
	private final String origin;
	private final String rpId;
	/** Outstanding challenges: challenge -> (username for registration or "" for sign-in, expiry). */
	private final Map<String, String[]> challenges = new ConcurrentHashMap<>();

	Passkeys(Path file, String origin) {
		this.file = file;
		this.origin = origin;
		this.rpId = URI.create(origin).getHost();
	}

	// ---- Storage ----

	synchronized List<Passkey> all() {
		try {
			if (!Files.exists(file)) {
				return new ArrayList<>();
			}
			List<Passkey> list = Http.GSON.fromJson(Files.readString(file), new TypeToken<List<Passkey>>() { }.getType());
			return list == null ? new ArrayList<>() : new ArrayList<>(list);
		} catch (IOException e) {
			System.out.println("[panel] Can't read " + file + ": " + e.getMessage());
			return new ArrayList<>();
		}
	}

	private synchronized void save(List<Passkey> list) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, Http.GSON.toJson(list));
		try {
			Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException ignored) {
			// Not a POSIX file system.
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	List<Map<String, Object>> list(String username) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Passkey p : all()) {
			if (p.username.equalsIgnoreCase(username)) {
				out.add(Map.of("id", p.id, "name", p.name == null ? "Passkey" : p.name, "createdAt", p.createdAt, "lastUsed", p.lastUsed));
			}
		}
		return out;
	}

	boolean remove(String username, String id) throws IOException {
		List<Passkey> list = all();
		boolean removed = list.removeIf(p -> p.username.equalsIgnoreCase(username) && p.id.equals(id));
		if (removed) {
			save(list);
		}
		return removed;
	}

	// ---- Challenges ----

	private String challenge(String forUser) {
		long now = System.currentTimeMillis();
		challenges.values().removeIf(v -> Long.parseLong(v[1]) < now);
		if (challenges.size() > 500) {
			throw new Http.Fail(429, "Too many sign-in attempts right now - try again in a minute");
		}
		byte[] raw = new byte[32];
		RANDOM.nextBytes(raw);
		String c = B64URL.encodeToString(raw);
		challenges.put(c, new String[] {forUser, String.valueOf(now + CHALLENGE_MILLIS)});
		return c;
	}

	/** Takes a challenge back (once); returns who it was for, or throws. */
	private String consume(String challenge) {
		String[] v = challenge == null ? null : challenges.remove(challenge);
		if (v == null || Long.parseLong(v[1]) < System.currentTimeMillis()) {
			throw new Http.Fail(400, "That passkey request expired - try again");
		}
		return v[0];
	}

	// ---- Registration (signed in) ----

	Map<String, Object> registerOptions(String username) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("challenge", challenge(username));
		out.put("rp", Map.of("id", rpId, "name", "Velo Panel"));
		out.put("user", Map.of("id", B64URL.encodeToString(sha256(("velo-panel:" + username.toLowerCase()).getBytes(StandardCharsets.UTF_8))),
				"name", username, "displayName", username));
		out.put("pubKeyCredParams", List.of(Map.of("type", "public-key", "alg", -7), Map.of("type", "public-key", "alg", -257)));
		out.put("timeout", 120_000);
		out.put("attestation", "none");
		out.put("authenticatorSelection", Map.of("residentKey", "required", "requireResidentKey", true, "userVerification", "required"));
		out.put("exclude", all().stream().filter(p -> p.username.equalsIgnoreCase(username)).map(p -> p.id).toList());
		return out;
	}

	record RegisterRequest(String name, String clientDataJSON, String attestationObject) {
	}

	void register(String username, RegisterRequest r) throws Exception {
		byte[] clientData = B64URL_IN.decode(r.clientDataJSON());
		String forUser = checkClientData(clientData, "webauthn.create");
		if (!username.equals(forUser)) {
			throw new Http.Fail(400, "That passkey request belongs to another session");
		}
		Object att = new Cbor(B64URL_IN.decode(r.attestationObject())).read();
		if (!(att instanceof Map<?, ?> map) || !(map.get("authData") instanceof byte[] authData)) {
			throw new Http.Fail(400, "Unreadable passkey");
		}
		checkAuthData(authData, true);
		// Attested credential data: aaguid (16), id length (2), id, COSE key.
		int at = 37 + 16;
		int idLen = ((authData[at] & 0xff) << 8) | (authData[at + 1] & 0xff);
		byte[] credId = java.util.Arrays.copyOfRange(authData, at + 2, at + 2 + idLen);
		Cbor keyReader = new Cbor(authData, at + 2 + idLen);
		Object cose = keyReader.read();
		if (!(cose instanceof Map<?, ?> key)) {
			throw new Http.Fail(400, "Unreadable passkey key");
		}
		int alg = ((Number) key.get(3L)).intValue();
		PublicKey publicKey = coseKey(key, alg);
		Passkey p = new Passkey();
		p.username = username;
		p.id = B64URL.encodeToString(credId);
		p.publicKey = Base64.getEncoder().encodeToString(publicKey.getEncoded());
		p.alg = alg;
		p.signCount = signCount(authData);
		String name = r.name() == null ? "" : r.name().trim();
		p.name = name.isEmpty() ? "Passkey" : name.substring(0, Math.min(40, name.length()));
		p.createdAt = System.currentTimeMillis();
		List<Passkey> list = all();
		list.removeIf(x -> x.id.equals(p.id));
		list.add(p);
		save(list);
	}

	// ---- Sign-in (signed out) ----

	Map<String, Object> loginOptions() {
		return Map.of("challenge", challenge(""), "rpId", rpId, "timeout", 120_000, "userVerification", "required");
	}

	record LoginRequest(String id, String clientDataJSON, String authenticatorData, String signature) {
	}

	/** Verifies a passkey sign-in; returns the account it belongs to, or throws. */
	String login(LoginRequest r) throws Exception {
		byte[] clientData = B64URL_IN.decode(r.clientDataJSON());
		checkClientData(clientData, "webauthn.get");
		byte[] authData = B64URL_IN.decode(r.authenticatorData());
		checkAuthData(authData, false);
		List<Passkey> list = all();
		Passkey p = list.stream().filter(x -> x.id.equals(r.id())).findFirst().orElse(null);
		if (p == null) {
			throw new Http.Fail(401, "This passkey isn't known to the panel - sign in with your password and add it again");
		}
		PublicKey key = KeyFactory.getInstance(p.alg == -257 ? "RSA" : "EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(p.publicKey)));
		Signature sig = Signature.getInstance(p.alg == -257 ? "SHA256withRSA" : "SHA256withECDSA");
		sig.initVerify(key);
		sig.update(authData);
		sig.update(sha256(clientData));
		if (!sig.verify(B64URL_IN.decode(r.signature()))) {
			throw new Http.Fail(401, "The passkey's signature didn't check out");
		}
		long count = signCount(authData);
		if (count != 0 && count <= p.signCount) {
			throw new Http.Fail(401, "This passkey may have been copied - remove it and add it again");
		}
		p.signCount = count;
		p.lastUsed = System.currentTimeMillis();
		save(list);
		return p.username;
	}

	// ---- Checks ----

	private String checkClientData(byte[] clientData, String type) {
		JsonObject c = JsonParser.parseString(new String(clientData, StandardCharsets.UTF_8)).getAsJsonObject();
		if (!type.equals(c.get("type").getAsString())) {
			throw new Http.Fail(400, "Wrong passkey request type");
		}
		if (!origin.equals(c.get("origin").getAsString())) {
			throw new Http.Fail(403, "Passkey used on the wrong site");
		}
		return consume(c.get("challenge").getAsString());
	}

	private void checkAuthData(byte[] authData, boolean registering) {
		if (authData.length < 37) {
			throw new Http.Fail(400, "Unreadable passkey data");
		}
		byte[] rpHash = java.util.Arrays.copyOfRange(authData, 0, 32);
		if (!MessageDigest.isEqual(rpHash, sha256(rpId.getBytes(StandardCharsets.UTF_8)))) {
			throw new Http.Fail(403, "Passkey made for another site");
		}
		int flags = authData[32] & 0xff;
		if ((flags & 0x01) == 0 || (flags & 0x04) == 0) {
			throw new Http.Fail(401, "The passkey has to check it's you (fingerprint, face or PIN)");
		}
		if (registering && (flags & 0x40) == 0) {
			throw new Http.Fail(400, "The passkey didn't send its key");
		}
	}

	private static long signCount(byte[] authData) {
		return ((long) (authData[33] & 0xff) << 24) | ((authData[34] & 0xff) << 16) | ((authData[35] & 0xff) << 8) | (authData[36] & 0xff);
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
		throw new Http.Fail(400, "That kind of passkey isn't supported (algorithm " + alg + ")");
	}

	private static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** Just enough CBOR for WebAuthn: unsigned/negative ints, byte/text strings, arrays, maps, simple values. */
	static final class Cbor {
		private final byte[] in;
		private int pos;

		Cbor(byte[] in) {
			this(in, 0);
		}

		Cbor(byte[] in, int start) {
			this.in = in;
			this.pos = start;
		}

		Object read() {
			int b = in[pos++] & 0xff;
			int major = b >> 5, info = b & 31;
			long n = info < 24 ? info : switch (info) {
				case 24 -> in[pos++] & 0xffL;
				case 25 -> num(2);
				case 26 -> num(4);
				case 27 -> num(8);
				default -> throw new Http.Fail(400, "Unsupported passkey data");
			};
			return switch (major) {
				case 0 -> n;
				case 1 -> -1 - n;
				case 2 -> bytes((int) n);
				case 3 -> new String(bytes((int) n), StandardCharsets.UTF_8);
				case 4 -> {
					List<Object> list = new ArrayList<>();
					for (long i = 0; i < n; i++) {
						list.add(read());
					}
					yield list;
				}
				case 5 -> {
					Map<Object, Object> map = new LinkedHashMap<>();
					for (long i = 0; i < n; i++) {
						Object k = read();
						map.put(k, read());
					}
					yield map;
				}
				case 7 -> info == 20 ? Boolean.FALSE : info == 21 ? Boolean.TRUE : null;
				default -> throw new Http.Fail(400, "Unsupported passkey data");
			};
		}

		private long num(int bytes) {
			long v = 0;
			for (int i = 0; i < bytes; i++) {
				v = (v << 8) | (in[pos++] & 0xff);
			}
			return v;
		}

		private byte[] bytes(int n) {
			ByteArrayOutputStream out = new ByteArrayOutputStream(n);
			out.write(in, pos, n);
			pos += n;
			return out.toByteArray();
		}
	}
}
