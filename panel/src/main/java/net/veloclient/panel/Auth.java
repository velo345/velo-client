package net.veloclient.panel;

import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may use the panel and how they prove it.
 *
 * <ul>
 * <li>Accounts live in a root-owned JSON file (password as PBKDF2-SHA256, 310 000 rounds, plus a TOTP secret) -
 * {@code java -jar velo-panel.jar adduser <name>} creates one. A second factor is always required.</li>
 * <li>Sign-in is rate limited per address (5 failures in 15 minutes) and per account (10 failures lock it for 15
 * minutes); a TOTP code works once.</li>
 * <li>Sessions are 256-bit random tokens in a {@code __Host-} cookie (Secure, HttpOnly, SameSite=Strict); only
 * their SHA-256 is kept. They end after 30 minutes without use or 12 hours at most.</li>
 * <li>Every change needs the session's CSRF token in {@code X-CSRF-Token} and, when the browser sends one, the
 * panel's own Origin.</li>
 * </ul>
 */
final class Auth {

	static final String COOKIE = "__Host-velo_panel";
	private static final long IDLE_MILLIS = 30 * 60_000L;
	private static final long MAX_MILLIS = 12 * 3_600_000L;
	private static final int ITERATIONS = 310_000;
	private static final SecureRandom RANDOM = new SecureRandom();

	static final class User {
		String username;
		String salt;
		String hash;
		int iterations;
		String totpSecret;
		long createdAt;
	}

	record Session(String username, String csrf, long createdAt, long lastSeen, String ip, String userAgent) {
	}

	private final Path usersFile;
	private final Audit audit;
	private final Map<String, Session> sessions = new ConcurrentHashMap<>();
	private final Map<String, List<Long>> failuresByIp = new ConcurrentHashMap<>();
	private final Map<String, List<Long>> failuresByUser = new ConcurrentHashMap<>();
	private final Map<String, Long> lastTotpStep = new ConcurrentHashMap<>();
	private volatile List<User> users;
	private volatile long usersModified;

	Auth(Path usersFile, Audit audit) {
		this.usersFile = usersFile;
		this.audit = audit;
	}

	// ---- Accounts ----

	private List<User> users() {
		try {
			long modified = Files.exists(usersFile) ? Files.getLastModifiedTime(usersFile).toMillis() : 0;
			if (users == null || modified != usersModified) {
				users = modified == 0 ? List.of()
						: Http.GSON.fromJson(Files.readString(usersFile), new TypeToken<List<User>>() { }.getType());
				usersModified = modified;
			}
		} catch (IOException e) {
			System.out.println("[panel] Can't read " + usersFile + ": " + e.getMessage());
			return users == null ? List.of() : users;
		}
		return users;
	}

	private User user(String username) {
		for (User u : users()) {
			if (u.username.equalsIgnoreCase(username)) {
				return u;
			}
		}
		return null;
	}

	/** Creates or replaces an account; returns its new TOTP secret. Used by the {@code adduser} command. */
	static String addUser(Path file, String username, char[] password) throws IOException, GeneralSecurityException {
		List<User> list = Files.exists(file)
				? new ArrayList<>(Http.GSON.fromJson(Files.readString(file), new TypeToken<List<User>>() { }.getType()))
				: new ArrayList<>();
		list.removeIf(u -> u.username.equalsIgnoreCase(username));
		User u = new User();
		u.username = username;
		byte[] salt = new byte[16];
		RANDOM.nextBytes(salt);
		u.salt = Base64.getEncoder().encodeToString(salt);
		u.iterations = ITERATIONS;
		u.hash = Base64.getEncoder().encodeToString(pbkdf2(password, salt, ITERATIONS));
		byte[] secret = new byte[20];
		RANDOM.nextBytes(secret);
		u.totpSecret = base32(secret);
		u.createdAt = System.currentTimeMillis();
		list.add(u);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, Http.GSON.toJson(list));
		try {
			Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r-----"));
		} catch (UnsupportedOperationException ignored) {
			// Not a POSIX file system (development on Windows).
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		return u.totpSecret;
	}

	// ---- Sign-in ----

	record LoginRequest(String username, String password, String code, ProofOfWork.Solution pow) {
	}

	/** Checks the credentials; returns the new session token (for the cookie) or throws a {@link Http.Fail}. */
	String login(HttpExchange ex, LoginRequest request) {
		String ip = Http.clientIp(ex);
		long now = System.currentTimeMillis();
		if (recent(failuresByIp, ip, now, 15 * 60_000L) >= 5) {
			throw new Http.Fail(429, "Too many failed sign-ins from your network - try again in 15 minutes");
		}
		String username = request.username() == null ? "" : request.username().trim();
		if (recent(failuresByUser, username.toLowerCase(), now, 15 * 60_000L) >= 10) {
			fail(ip, username, now, "locked");
			throw new Http.Fail(429, "This account is locked for 15 minutes after too many failed sign-ins");
		}
		User user = username.isEmpty() ? null : user(username);
		boolean ok = user != null && request.password() != null && passwordMatches(user, request.password().toCharArray());
		Long step = ok ? totpStep(user, request.code(), now) : null;
		if (!ok || step == null) {
			// Same answer and roughly the same time whether the name, the password or the code was wrong.
			if (user == null) {
				passwordMatches(DUMMY, (request.password() == null ? "" : request.password()).toCharArray());
			}
			fail(ip, username, now, ok ? "bad code" : "bad password");
			throw new Http.Fail(401, "Wrong name, password or code");
		}
		Long previous = lastTotpStep.get(user.username);
		if (previous != null && step <= previous) {
			fail(ip, username, now, "code reused");
			throw new Http.Fail(401, "That code was already used - wait for the next one");
		}
		lastTotpStep.put(user.username, step);
		failuresByUser.remove(username.toLowerCase());
		byte[] raw = new byte[32];
		RANDOM.nextBytes(raw);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		byte[] csrf = new byte[24];
		RANDOM.nextBytes(csrf);
		String agent = ex.getRequestHeaders().getFirst("User-Agent");
		sessions.put(sha256(token), new Session(user.username, Base64.getUrlEncoder().withoutPadding().encodeToString(csrf), now, now, ip,
				agent == null ? "" : agent.substring(0, Math.min(agent.length(), 200))));
		audit.log(user.username, ip, "sign-in", "");
		return token;
	}

	private static final User DUMMY = new User();

	static {
		DUMMY.username = "";
		DUMMY.salt = Base64.getEncoder().encodeToString(new byte[16]);
		DUMMY.iterations = ITERATIONS;
		DUMMY.hash = Base64.getEncoder().encodeToString(new byte[32]);
	}

	private void fail(String ip, String username, long now, String why) {
		failuresByIp.computeIfAbsent(ip, k -> new ArrayList<>()).add(now);
		if (!username.isEmpty()) {
			failuresByUser.computeIfAbsent(username.toLowerCase(), k -> new ArrayList<>()).add(now);
		}
		audit.log(username.isEmpty() ? "?" : username, ip, "sign-in failed", why);
	}

	private static int recent(Map<String, List<Long>> map, String key, long now, long window) {
		List<Long> list = map.get(key);
		if (list == null) {
			return 0;
		}
		synchronized (list) {
			list.removeIf(t -> t < now - window);
			return list.size();
		}
	}

	void logout(HttpExchange ex) {
		String token = Http.cookie(ex, COOKIE);
		if (token != null) {
			Session s = sessions.remove(sha256(token));
			if (s != null) {
				audit.log(s.username(), Http.clientIp(ex), "sign-out", "");
			}
		}
	}

	/** The signed-in session for this request (refreshing its idle timer), or null. */
	Session session(HttpExchange ex) {
		String token = Http.cookie(ex, COOKIE);
		if (token == null || token.length() > 100) {
			return null;
		}
		String key = sha256(token);
		Session s = sessions.get(key);
		long now = System.currentTimeMillis();
		if (s == null) {
			return null;
		}
		if (now - s.lastSeen() > IDLE_MILLIS || now - s.createdAt() > MAX_MILLIS || user(s.username()) == null) {
			sessions.remove(key);
			return null;
		}
		Session refreshed = new Session(s.username(), s.csrf(), s.createdAt(), now, s.ip(), s.userAgent());
		sessions.put(key, refreshed);
		return refreshed;
	}

	/** A change (anything but GET/HEAD) must carry the CSRF token and come from the panel's own page. */
	static void checkChange(HttpExchange ex, Session s, String origin) {
		String method = ex.getRequestMethod();
		if ("GET".equals(method) || "HEAD".equals(method)) {
			return;
		}
		String header = ex.getRequestHeaders().getFirst("X-CSRF-Token");
		if (header == null || !MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), s.csrf().getBytes(StandardCharsets.UTF_8))) {
			throw new Http.Fail(403, "Missing or wrong CSRF token - reload the page");
		}
		String requestOrigin = ex.getRequestHeaders().getFirst("Origin");
		if (origin != null && requestOrigin != null && !requestOrigin.equals(origin)) {
			throw new Http.Fail(403, "Cross-site request refused");
		}
	}

	String setCookie(String token) {
		return COOKIE + "=" + token + "; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=" + (MAX_MILLIS / 1000);
	}

	static String clearCookie() {
		return COOKIE + "=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0";
	}

	List<Map<String, Object>> sessionsOf(String username) {
		List<Map<String, Object>> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Session s : sessions.values()) {
			if (s.username().equals(username) && now - s.lastSeen() <= IDLE_MILLIS) {
				out.add(Map.of("ip", s.ip(), "userAgent", s.userAgent(), "createdAt", s.createdAt(), "lastSeen", s.lastSeen()));
			}
		}
		return out;
	}

	/** Signs out every session of this account (e.g. after a password change). */
	int logoutAll(String username) {
		int before = sessions.size();
		sessions.values().removeIf(s -> s.username().equals(username));
		return before - sessions.size();
	}

	// ---- Crypto ----

	private static boolean passwordMatches(User user, char[] password) {
		try {
			byte[] expected = Base64.getDecoder().decode(user.hash);
			byte[] actual = pbkdf2(password, Base64.getDecoder().decode(user.salt), user.iterations > 0 ? user.iterations : ITERATIONS);
			return MessageDigest.isEqual(expected, actual);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			return false;
		}
	}

	private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
		return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password, salt, iterations, 256)).getEncoded();
	}

	/** The 30-second step the code belongs to (one step either side allowed for clock drift), or null. */
	static Long totpStep(User user, String code, long now) {
		if (code == null || user.totpSecret == null) {
			return null;
		}
		String digits = code.replaceAll("\\s", "");
		if (!digits.matches("\\d{6}")) {
			return null;
		}
		byte[] key = unbase32(user.totpSecret);
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
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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

	static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}
}
