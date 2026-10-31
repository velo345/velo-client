package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The staff panel (staff.velo-client.com): its sign-in, its sessions, and the routes only it needs.
 *
 * <p><b>First sign-in.</b> A team member types their Minecraft name; their open launcher shows a code (the launcher
 * already proved who they are through Mojang). The code opens a short setup session in which they choose how to sign
 * in from then on: a password or an unlock pattern, each with an authenticator app code, and/or a passkey (fingerprint,
 * face, screen lock). Once set up, codes are refused for that account - nobody can spam the launcher with them - until
 * the member signs out of the staff panel in their launcher, which wipes the sign-in so it can be set up somewhere new.
 *
 * <p><b>Sessions.</b> One per account: signing in ends the previous one (and the log says where it was). A session is
 * tied to the address it signed in from - another network means signing in again - so a session can't be handed on.
 * Cookie only ({@code __Host-}, HttpOnly, SameSite=Strict); every change needs the session's CSRF token and the panel's
 * origin. Leaving the team ends the session on the next request.
 *
 * <p>With a panel session, the existing {@code /v1/staff/*} routes work as for that member (see
 * {@link SocialRoutes#route}), with their own permission checks. On top: the team page and sign-in log for managers,
 * every punishment on record, and Discord tickets through the bot.
 */
final class StaffPanel {

	static final String COOKIE = "__Host-velo_staff";
	private static final long SETUP_MILLIS = 30 * 60_000L;
	private static final long IDLE_MILLIS = 24 * 3_600_000L;
	private static final long MAX_MILLIS = 14 * 86_400_000L;
	private static final long CODE_MILLIS = 5 * 60_000L;
	private static final long CHALLENGE_MILLIS = 3 * 60_000L;
	private static final int MAX_LOG = 600;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static final RateLimiter.Window[] REQUEST_IP = {new RateLimiter.Window(3, 600_000), new RateLimiter.Window(10, 3_600_000)};
	private static final RateLimiter.Window[] REQUEST_TARGET = {new RateLimiter.Window(1, 120_000), new RateLimiter.Window(4, 3_600_000)};
	private static final RateLimiter.Window[] REDEEM_IP = {new RateLimiter.Window(6, 600_000)};
	private static final RateLimiter.Window[] LOGIN_IP = {new RateLimiter.Window(6, 900_000), new RateLimiter.Window(20, 86_400_000)};
	private static final RateLimiter.Window[] LOGIN_ACCOUNT = {new RateLimiter.Window(10, 900_000)};
	private static final RateLimiter.Window[] PASSKEY_IP = {new RateLimiter.Window(20, 600_000)};

	static final class Passkey {
		String id;
		String publicKey;
		int alg;
		long signCount;
		String name;
		long createdAt;
		long lastUsed;
	}

	static final class Account {
		String name;
		boolean enrolled;
		long enrolledAt;
		String enrolledIp;
		/** "salt:hash" - a password and/or an unlock pattern ("0-4-8-..." over a 3x3 grid). */
		String password;
		String pattern;
		String totp;
		long totpStep;
		List<Passkey> passkeys = new ArrayList<>();
		long lastSignInAt;
		String lastSignInIp;
		String lastAgent;
	}

	static final class Session {
		String uuid;
		/** "setup" (from a launcher code, only for choosing sign-in methods) or "full". */
		String kind;
		String csrf;
		String ip;
		String agent;
		long createdAt;
		long lastSeen;
		/** An authenticator secret shown during setup, kept until it's confirmed with a code. */
		String pendingTotp;
	}

	record LogEntry(long at, String uuid, String name, String ip, String event, String detail) {
	}

	private static final class Data {
		Map<String, Account> accounts = new LinkedHashMap<>();
		/** sha256(token) -> session. */
		Map<String, Session> sessions = new LinkedHashMap<>();
		List<LogEntry> log = new ArrayList<>();
	}

	private record Pending(String code, long expires, int[] attempts) {
	}

	private final Path file;
	private final String origin;
	private final String rpId;
	private final SocialService social;
	private final Function<String, String> levelOf;
	private final java.util.function.Predicate<String> owner;
	private final RateLimiter limiter = new RateLimiter();
	private final ProofOfWork pow = new ProofOfWork();
	/** Launcher codes asked for (uuid -> until) and handed to the launcher (uuid -> code). */
	private final Map<String, Long> requested = new ConcurrentHashMap<>();
	private final Map<String, Pending> codes = new ConcurrentHashMap<>();
	/**
	 * Launchers from before the staff panel don't know "staff_code": when one hasn't picked its code up after a few
	 * seconds, the code goes the website-code way instead (uuids here may take it from /v1/web/pending).
	 */
	private final Map<String, Long> oldLauncherFallback = new ConcurrentHashMap<>();
	private static final java.util.concurrent.ScheduledExecutorService LATER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "staff-code-fallback");
		t.setDaemon(true);
		return t;
	});
	/** Passkey challenges: challenge -> {uuid or "" for sign-in, expiry}. */
	private final Map<String, String[]> challenges = new ConcurrentHashMap<>();
	private Data data = new Data();

	private static volatile StaffPanel instance;

	StaffPanel(Path dataDir, String origin, SocialService social, Function<String, String> levelOf, java.util.function.Predicate<String> owner) {
		this.file = dataDir.resolve("staff-panel.json");
		this.origin = origin;
		this.rpId = URI.create(origin).getHost();
		this.social = social;
		this.levelOf = levelOf;
		this.owner = owner;
		try {
			if (Files.exists(file)) {
				Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
				if (loaded != null) {
					data = loaded;
				}
			}
		} catch (IOException | RuntimeException e) {
			System.out.println("[staff-panel] Couldn't read staff-panel.json: " + e.getMessage());
		}
		if (data.accounts == null) {
			data.accounts = new LinkedHashMap<>();
		}
		if (data.sessions == null) {
			data.sessions = new LinkedHashMap<>();
		}
		if (data.log == null) {
			data.log = new ArrayList<>();
		}
		instance = this;
	}

	private synchronized void save() {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = Files.createTempFile(file.getParent(), "staff-panel-", ".json.tmp");
			Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
			try {
				Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
			} catch (UnsupportedOperationException ignored) {
				// Not a POSIX file system.
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[staff-panel] Couldn't save staff-panel.json: " + e.getMessage());
		}
	}

	private synchronized void log(String uuid, String ip, String event, String detail) {
		data.log.add(new LogEntry(System.currentTimeMillis(), uuid, uuid == null ? "?" : social.usernameOf(uuid), ip, event, detail == null ? "" : detail));
		while (data.log.size() > MAX_LOG) {
			data.log.removeFirst();
		}
		save();
	}

	/** The player's name if the server knows one (it falls back to the uuid for players it never saw). */
	private String knownName(String uuid) {
		String name = social.usernameOf(uuid);
		return name == null || !name.matches("[A-Za-z0-9_]{1,16}") ? null : name;
	}

	private synchronized Account account(String uuid) {
		return data.accounts.computeIfAbsent(uuid, k -> {
			Account a = new Account();
			a.name = knownName(uuid);
			return a;
		});
	}

	/** Hands the asked-for code to this member's launcher (once), or null. */
	private String handOut(String uuid) {
		Long until = requested.remove(uuid);
		oldLauncherFallback.remove(uuid);
		if (until == null || until < System.currentTimeMillis() || levelOf.apply(uuid) == null) {
			return null;
		}
		String code = String.format("%06d", StaffCrypto.RANDOM.nextInt(1_000_000));
		codes.put(uuid, new Pending(code, System.currentTimeMillis() + CODE_MILLIS, new int[1]));
		return code;
	}

	/** For {@code /v1/web/pending}: a staff code for a launcher too old to ask for it itself, or null. */
	static String codeForOldLauncher(String uuid) {
		StaffPanel panel = instance;
		if (panel == null) {
			return null;
		}
		Long until = panel.oldLauncherFallback.get(uuid);
		return until != null && until > System.currentTimeMillis() ? panel.handOut(uuid) : null;
	}

	// ---- Sessions ----

	/** For {@link SocialRoutes#route}: the team member whose full panel session this request carries, or null. */
	static String authorize(HttpExchange ex) throws IOException {
		StaffPanel panel = instance;
		if (panel == null) {
			return null;
		}
		Session s = panel.session(ex, false);
		if (s == null || !"full".equals(s.kind)) {
			return null;
		}
		panel.checkChange(ex, s);
		return s.uuid;
	}

	/** The panel session of this request (refreshing it), or null: expired, another network, or no longer on the team. */
	private synchronized Session session(HttpExchange ex, boolean allowSetup) throws IOException {
		String token = cookie(ex, COOKIE);
		if (token == null) {
			return null;
		}
		String key = StaffCrypto.sha256Hex(token);
		Session s = data.sessions.get(key);
		if (s == null) {
			return null;
		}
		long now = System.currentTimeMillis();
		boolean setup = "setup".equals(s.kind);
		boolean expired = setup ? now - s.createdAt > SETUP_MILLIS : now - s.lastSeen > IDLE_MILLIS || now - s.createdAt > MAX_MILLIS;
		String ip = WebRoutes.clientIp(ex);
		String why = expired ? "session expired" : !ip.equals(s.ip) ? "network changed (" + s.ip + " -> " + ip + ")"
				: levelOf.apply(s.uuid) == null ? "no longer on the team" : null;
		if (why != null) {
			data.sessions.remove(key);
			save();
			if (!expired) {
				log(s.uuid, ip, "signed out", why);
			}
			return null;
		}
		if (setup && !allowSetup) {
			return null;
		}
		if (now - s.lastSeen > 60_000) {
			s.lastSeen = now;
			save();
		}
		return s;
	}

	private void checkChange(HttpExchange ex, Session s) throws IOException {
		if ("GET".equals(ex.getRequestMethod())) {
			return;
		}
		String requestOrigin = ex.getRequestHeaders().getFirst("Origin");
		if (requestOrigin != null && !requestOrigin.equals(origin)) {
			throw new IOException("Cross-site request refused");
		}
		String header = ex.getRequestHeaders().getFirst("X-CSRF-Token");
		if (header == null || !java.security.MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), s.csrf.getBytes(StandardCharsets.UTF_8))) {
			throw new IOException("Reload the page and try again (security token missing)");
		}
	}

	/** Opens a session and sets its cookie. A full session ends every other session of the account (one device at a time). */
	private synchronized void open(HttpExchange ex, String uuid, String kind) throws IOException {
		String ip = WebRoutes.clientIp(ex);
		long now = System.currentTimeMillis();
		List<String> ended = new ArrayList<>();
		data.sessions.values().removeIf(s -> {
			if (s.uuid.equals(uuid)) {
				if ("full".equals(s.kind) && !ip.equals(s.ip)) {
					ended.add(s.ip);
				}
				return true;
			}
			return false;
		});
		String token = StaffCrypto.randomToken(32);
		Session s = new Session();
		s.uuid = uuid;
		s.kind = kind;
		s.csrf = StaffCrypto.randomToken(24);
		s.ip = ip;
		String agent = ex.getRequestHeaders().getFirst("User-Agent");
		s.agent = agent == null ? "" : agent.substring(0, Math.min(agent.length(), 200));
		s.createdAt = now;
		s.lastSeen = now;
		data.sessions.put(StaffCrypto.sha256Hex(token), s);
		if ("full".equals(kind)) {
			Account a = account(uuid);
			a.lastSignInAt = now;
			a.lastSignInIp = ip;
			a.lastAgent = s.agent;
			String known = knownName(uuid);
			if (known != null) {
				a.name = known;
			}
		}
		save();
		if (!ended.isEmpty()) {
			log(uuid, ip, "ended other session", "previous device on " + String.join(", ", ended));
		}
		long maxAge = ("setup".equals(kind) ? SETUP_MILLIS : MAX_MILLIS) / 1000;
		ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; Max-Age=" + maxAge + "; Secure; HttpOnly; SameSite=Strict");
	}

	private synchronized void endSessions(String uuid) {
		data.sessions.values().removeIf(s -> s.uuid.equals(uuid));
		save();
	}

	private synchronized boolean hasFullSession(String uuid) {
		long now = System.currentTimeMillis();
		return data.sessions.values().stream().anyMatch(s -> s.uuid.equals(uuid) && "full".equals(s.kind)
				&& now - s.lastSeen < IDLE_MILLIS && now - s.createdAt < MAX_MILLIS);
	}

	private static String cookie(HttpExchange ex, String name) {
		List<String> headers = ex.getRequestHeaders().get("Cookie");
		if (headers == null) {
			return null;
		}
		for (String header : headers) {
			for (String part : header.split(";")) {
				String p = part.trim();
				if (p.startsWith(name + "=")) {
					String v = p.substring(name.length() + 1);
					return v.isEmpty() ? null : v;
				}
			}
		}
		return null;
	}

	// ---- Account helpers ----

	/** Whether the account can sign in on its own: a passkey, or a password/pattern together with an authenticator. */
	private static boolean complete(Account a) {
		return !a.passkeys.isEmpty() || a.totp != null && (a.password != null || a.pattern != null);
	}

	private static Map<String, Object> methods(Account a) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("password", a.password != null);
		m.put("pattern", a.pattern != null);
		m.put("totp", a.totp != null);
		List<Map<String, Object>> keys = new ArrayList<>();
		for (Passkey p : a.passkeys) {
			Map<String, Object> k = new LinkedHashMap<>();
			k.put("id", p.id);
			k.put("name", p.name);
			k.put("createdAt", p.createdAt);
			k.put("lastUsed", p.lastUsed);
			keys.add(k);
		}
		m.put("passkeys", keys);
		return m;
	}

	/** Wipes the account's sign-in (after signing out in the launcher, or a manager's reset). */
	private synchronized void reset(String uuid) {
		Account a = account(uuid);
		a.enrolled = false;
		a.password = null;
		a.pattern = null;
		a.totp = null;
		a.totpStep = 0;
		a.passkeys = new ArrayList<>();
		data.sessions.values().removeIf(s -> s.uuid.equals(uuid));
		codes.remove(uuid);
		requested.remove(uuid);
		save();
	}

	private static String validPattern(String pattern) throws ApiError {
		if (pattern == null || !pattern.matches("[0-8](-[0-8]){3,8}")) {
			throw new ApiError(400, "Connect at least 4 dots");
		}
		String[] dots = pattern.split("-");
		if (java.util.Arrays.stream(dots).distinct().count() != dots.length) {
			throw new ApiError(400, "Use each dot once");
		}
		return pattern;
	}

	private static void validPassword(String password) throws ApiError {
		if (password == null || password.length() < 10) {
			throw new ApiError(400, "Use at least 10 characters");
		}
		if (password.length() > 200) {
			throw new ApiError(400, "That password is too long");
		}
	}

	// ---- Passkey challenges ----

	private String challenge(String forUser) throws ApiError {
		long now = System.currentTimeMillis();
		challenges.values().removeIf(v -> Long.parseLong(v[1]) < now);
		if (challenges.size() > 500) {
			throw new ApiError(429, "Too many sign-in attempts right now - try again in a minute");
		}
		String c = StaffCrypto.randomToken(32);
		challenges.put(c, new String[] {forUser, String.valueOf(now + CHALLENGE_MILLIS)});
		return c;
	}

	private String consume(String challenge) throws ApiError {
		String[] v = challenge == null ? null : challenges.remove(challenge);
		if (v == null || Long.parseLong(v[1]) < System.currentTimeMillis()) {
			throw new ApiError(400, "That passkey request expired - try again");
		}
		return v[0];
	}

	// ---- Requests ----

	private record CodeRequest(String name, ProofOfWork.Solution pow) {
	}

	private record RedeemRequest(String name, String code, ProofOfWork.Solution pow) {
	}

	private record SecretRequest(String password, String pattern) {
	}

	private record CodeOnly(String code) {
	}

	private record LoginRequest(String name, String method, String secret, String code, ProofOfWork.Solution pow) {
	}

	private record PasskeyRegister(String name, String clientDataJSON, String attestationObject) {
	}

	private record PasskeyLogin(String id, String clientDataJSON, String authenticatorData, String signature) {
	}

	private record IdRequest(String id) {
	}

	private record RemoveMethod(String method) {
	}

	private record TeamTarget(String uuid) {
	}

	private record TeamLevel(String target, String level) {
	}

	private record TicketAction(String id, String text, String reason) {
	}

	interface Route {
		void handle(HttpExchange ex) throws Exception;
	}

	private void post(HttpServer server, String path, Route route) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
			String requestOrigin = ex.getRequestHeaders().getFirst("Origin");
			if (requestOrigin != null && !requestOrigin.equals(origin)) {
				throw new ApiError(403, "Cross-site request refused");
			}
			route.handle(ex);
		}));
	}

	/** A route for a signed-in (or, with {@code setup}, setting-up) panel session; POSTs need the CSRF token. */
	private void authed(HttpServer server, String path, String method, boolean setup, SessionRoute route) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, method, ex -> {
			Session s = session(ex, setup);
			if (s == null) {
				throw new ApiError(401, "Signed out");
			}
			try {
				checkChange(ex, s);
			} catch (IOException e) {
				throw new ApiError(403, e.getMessage());
			}
			route.handle(ex, s);
		}));
	}

	interface SessionRoute {
		void handle(HttpExchange ex, Session s) throws Exception;
	}

	interface LauncherRoute {
		void handle(HttpExchange ex, SessionRegistry.Session s) throws Exception;
	}

	private static void launcher(HttpServer server, SessionRegistry registry, String path, String method, LauncherRoute route) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, method, ex -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(ex));
			if (session == null || SessionRegistry.KIND_WEB.equals(session.kind())) {
				throw new ApiError(401, "Sign in to the Velo launcher first");
			}
			route.handle(ex, session);
		}));
	}

	void register(HttpServer server, SessionRegistry registry, StaffService staff, ModerationService moderation, DiscordLinks links,
			String botUrl, String adminToken) {
		BotBridge bot = new BotBridge(botUrl, adminToken);

		// ---- First sign-in: a code in the launcher ----
		server.createContext("/v1/staffpanel/challenge", exchange -> VeloServerApp.handle(exchange, "GET", ex -> JsonHttp.writeJson(ex, 200, pow.issue())));
		post(server, "/v1/staffpanel/request-code", ex -> {
			String ip = WebRoutes.clientIp(ex);
			if (limiter.tryAcquire(ip, "staff-code-request", REQUEST_IP) > 0) {
				throw new ApiError(429, "Too many requests - wait a few minutes");
			}
			CodeRequest r = JsonHttp.readBody(ex, CodeRequest.class);
			if (r == null || !pow.verify(r.pow())) {
				throw new ApiError(400, "The security check failed - reload the page and try again");
			}
			// The same answer whoever it is, so the page can't be used to find out who is on the team or set up.
			Map<String, Object> out = Map.of("ok", true, "expiresInSeconds", CODE_MILLIS / 1000);
			SocialService.PlayerRef ref;
			try {
				ref = social.resolvePlayer(r.name());
			} catch (SocialService.SocialException e) {
				JsonHttp.writeJson(ex, 200, out);
				return;
			}
			String uuid = ref.uuid();
			boolean eligible;
			synchronized (this) {
				Account a = data.accounts.get(uuid);
				eligible = levelOf.apply(uuid) != null && (a == null || !a.enrolled) && !hasFullSession(uuid);
			}
			if (eligible && limiter.tryAcquire(uuid, "staff-code-target", REQUEST_TARGET) == 0) {
				requested.put(uuid, System.currentTimeMillis() + CODE_MILLIS);
				social.pushEvent(uuid, "staff_code", Map.of());
				log(uuid, ip, "code requested", "");
				LATER.schedule(() -> {
					if (requested.containsKey(uuid)) {
						oldLauncherFallback.put(uuid, System.currentTimeMillis() + CODE_MILLIS);
						social.pushEvent(uuid, "web_code", Map.of());
					}
				}, 6, java.util.concurrent.TimeUnit.SECONDS);
			}
			JsonHttp.writeJson(ex, 200, out);
		});
		// The launcher picks the code up (it heard "staff_code", or checks after signing in).
		launcher(server, registry, "/v1/staffpanel/pending", "GET", (ex, session) -> {
			String code = handOut(session.uuid());
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("code", code);
			if (code != null) {
				out.put("expiresInSeconds", CODE_MILLIS / 1000);
			}
			JsonHttp.writeJson(ex, 200, out);
		});
		launcher(server, registry, "/v1/staffpanel/launcher-status", "GET", (ex, session) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			String level = levelOf.apply(session.uuid());
			out.put("team", level);
			synchronized (this) {
				Account a = data.accounts.get(session.uuid());
				out.put("enrolled", a != null && a.enrolled);
				out.put("signedIn", hasFullSession(session.uuid()));
				out.put("lastSignInAt", a == null ? 0 : a.lastSignInAt);
				out.put("lastSignInIp", a == null ? null : a.lastSignInIp);
				out.put("methods", a == null ? null : methods(a));
			}
			out.put("url", origin);
			JsonHttp.writeJson(ex, 200, out);
		});
		launcher(server, registry, "/v1/staffpanel/launcher-signout", "POST", (ex, session) -> {
			reset(session.uuid());
			log(session.uuid(), WebRoutes.clientIp(ex), "signed out in the launcher", "sign-in wiped - can be set up again");
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		post(server, "/v1/staffpanel/redeem", ex -> {
			String ip = WebRoutes.clientIp(ex);
			if (limiter.tryAcquire(ip, "staff-redeem", REDEEM_IP) > 0) {
				throw new ApiError(429, "Too many tries - wait a few minutes");
			}
			RedeemRequest r = JsonHttp.readBody(ex, RedeemRequest.class);
			if (r == null || !pow.verify(r.pow())) {
				throw new ApiError(400, "The security check failed - reload the page and try again");
			}
			String uuid;
			try {
				uuid = social.resolvePlayer(r.name()).uuid();
			} catch (SocialService.SocialException e) {
				throw new ApiError(401, "That code is wrong or expired");
			}
			Pending p = codes.get(uuid);
			String typed = r.code() == null ? "" : r.code().replaceAll("\\s", "");
			if (p == null || p.expires() < System.currentTimeMillis() || ++p.attempts()[0] > 5) {
				codes.remove(uuid);
				throw new ApiError(401, "That code is wrong or expired - ask for a new one");
			}
			if (!java.security.MessageDigest.isEqual(p.code().getBytes(StandardCharsets.US_ASCII), typed.getBytes(StandardCharsets.US_ASCII))) {
				throw new ApiError(401, "That code is wrong or expired");
			}
			codes.remove(uuid);
			if (levelOf.apply(uuid) == null || data.accounts.containsKey(uuid) && data.accounts.get(uuid).enrolled) {
				throw new ApiError(403, "This account is already set up - sign out of the staff panel in your launcher first");
			}
			synchronized (this) {
				Account a = account(uuid);
				a.password = null;
				a.pattern = null;
				a.totp = null;
				a.passkeys = new ArrayList<>();
			}
			open(ex, uuid, "setup");
			log(uuid, ip, "launcher code used", "setting up sign-in");
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});

		// ---- Who am I ----
		server.createContext("/v1/staffpanel/me", exchange -> VeloServerApp.handle(exchange, "GET", ex -> {
			Session s = session(ex, true);
			if (s == null) {
				throw new ApiError(401, "Signed out");
			}
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("uuid", s.uuid);
			out.put("name", social.usernameOf(s.uuid));
			out.put("role", levelOf.apply(s.uuid));
			out.put("kind", s.kind);
			out.put("csrf", s.csrf);
			out.put("ip", s.ip);
			out.put("since", s.createdAt);
			synchronized (this) {
				Account a = account(s.uuid);
				out.put("methods", methods(a));
				out.put("complete", complete(a));
			}
			JsonHttp.writeJson(ex, 200, out);
		}));

		// ---- Choosing sign-in methods (setup, or later from the account page) ----
		authed(server, "/v1/staffpanel/method/secret", "POST", true, (ex, s) -> {
			SecretRequest r = JsonHttp.readBody(ex, SecretRequest.class);
			synchronized (this) {
				Account a = account(s.uuid);
				if (r.password() != null) {
					validPassword(r.password());
					a.password = StaffCrypto.hashSecret(r.password());
				} else {
					a.pattern = StaffCrypto.hashSecret(validPattern(r.pattern()));
				}
				save();
			}
			log(s.uuid, s.ip, r.password() != null ? "password set" : "pattern set", "");
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		authed(server, "/v1/staffpanel/method/totp", "POST", true, (ex, s) -> {
			String secret = StaffCrypto.newTotpSecret();
			synchronized (this) {
				s.pendingTotp = secret;
			}
			String label = java.net.URLEncoder.encode("Velo Staff:" + social.usernameOf(s.uuid), StandardCharsets.UTF_8).replace("+", "%20");
			JsonHttp.writeJson(ex, 200, Map.of("secret", secret, "uri", "otpauth://totp/" + label + "?secret=" + secret + "&issuer=Velo%20Staff"));
		});
		authed(server, "/v1/staffpanel/method/totp/confirm", "POST", true, (ex, s) -> {
			CodeOnly r = JsonHttp.readBody(ex, CodeOnly.class);
			synchronized (this) {
				Long step = StaffCrypto.totpStep(s.pendingTotp, r.code(), System.currentTimeMillis());
				if (step == null) {
					throw new ApiError(400, "That code doesn't match - check your phone's time and try the next code");
				}
				Account a = account(s.uuid);
				a.totp = s.pendingTotp;
				a.totpStep = step;
				s.pendingTotp = null;
				save();
			}
			log(s.uuid, s.ip, "authenticator set", "");
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		authed(server, "/v1/staffpanel/method/remove", "POST", false, (ex, s) -> {
			RemoveMethod r = JsonHttp.readBody(ex, RemoveMethod.class);
			synchronized (this) {
				Account a = account(s.uuid);
				String before = a.password + "|" + a.pattern + "|" + a.totp;
				switch (r.method() == null ? "" : r.method()) {
					case "password" -> a.password = null;
					case "pattern" -> a.pattern = null;
					case "totp" -> a.totp = null;
					default -> throw new ApiError(400, "Unknown sign-in method");
				}
				if (!complete(a)) {
					String[] old = before.split("\\|", -1);
					a.password = "null".equals(old[0]) ? null : old[0];
					a.pattern = "null".equals(old[1]) ? null : old[1];
					a.totp = "null".equals(old[2]) ? null : old[2];
					throw new ApiError(400, "You'd have no way left to sign in - add a passkey or another method first");
				}
				save();
			}
			log(s.uuid, s.ip, r.method() + " removed", "");
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		authed(server, "/v1/staffpanel/passkey/register/options", "POST", true, (ex, s) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("challenge", challenge(s.uuid));
			out.put("rp", Map.of("id", rpId, "name", "Velo Staff"));
			String name = social.usernameOf(s.uuid);
			out.put("user", Map.of("id", StaffCrypto.B64URL.encodeToString(StaffCrypto.sha256(("velo-staff:" + s.uuid).getBytes(StandardCharsets.UTF_8))),
					"name", name, "displayName", name + " · Velo Staff"));
			out.put("pubKeyCredParams", List.of(Map.of("type", "public-key", "alg", -7), Map.of("type", "public-key", "alg", -257)));
			out.put("timeout", 120_000);
			out.put("attestation", "none");
			out.put("authenticatorSelection", Map.of("residentKey", "required", "requireResidentKey", true, "userVerification", "required"));
			synchronized (this) {
				out.put("exclude", account(s.uuid).passkeys.stream().map(p -> p.id).toList());
			}
			JsonHttp.writeJson(ex, 200, out);
		});
		authed(server, "/v1/staffpanel/passkey/register", "POST", true, (ex, s) -> {
			PasskeyRegister r = JsonHttp.readBody(ex, PasskeyRegister.class);
			byte[] clientData = StaffCrypto.B64URL_IN.decode(r.clientDataJSON());
			if (!s.uuid.equals(consume(StaffCrypto.clientData(clientData, "webauthn.create", origin)))) {
				throw new ApiError(400, "That passkey request belongs to another session");
			}
			StaffCrypto.NewPasskey key = StaffCrypto.register(StaffCrypto.B64URL_IN.decode(r.attestationObject()), rpId);
			synchronized (this) {
				for (Account other : data.accounts.values()) {
					other.passkeys.removeIf(p -> p.id.equals(key.id()));
				}
				Passkey p = new Passkey();
				p.id = key.id();
				p.publicKey = key.publicKey();
				p.alg = key.alg();
				p.signCount = key.signCount();
				String name = r.name() == null ? "" : r.name().strip();
				p.name = name.isEmpty() ? "Passkey" : name.substring(0, Math.min(40, name.length()));
				p.createdAt = System.currentTimeMillis();
				account(s.uuid).passkeys.add(p);
				save();
			}
			log(s.uuid, s.ip, "passkey added", r.name());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		authed(server, "/v1/staffpanel/passkey/delete", "POST", false, (ex, s) -> {
			IdRequest r = JsonHttp.readBody(ex, IdRequest.class);
			synchronized (this) {
				Account a = account(s.uuid);
				List<Passkey> before = new ArrayList<>(a.passkeys);
				a.passkeys.removeIf(p -> p.id.equals(r.id()));
				if (!complete(a)) {
					a.passkeys = before;
					throw new ApiError(400, "That's your only way to sign in - add another passkey or a password with an authenticator first");
				}
				save();
			}
			log(s.uuid, s.ip, "passkey removed", "");
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		authed(server, "/v1/staffpanel/setup/finish", "POST", true, (ex, s) -> {
			synchronized (this) {
				Account a = account(s.uuid);
				if (!complete(a)) {
					throw new ApiError(400, "Add a passkey, or a password or pattern together with an authenticator app");
				}
				if (!a.enrolled) {
					a.enrolled = true;
					a.enrolledAt = System.currentTimeMillis();
					a.enrolledIp = s.ip;
				}
				save();
			}
			log(s.uuid, s.ip, "sign-in set up", "");
			open(ex, s.uuid, "full");
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});

		// ---- Signing in ----
		post(server, "/v1/staffpanel/login", ex -> {
			String ip = WebRoutes.clientIp(ex);
			if (limiter.tryAcquire(ip, "staff-login", LOGIN_IP) > 0) {
				throw new ApiError(429, "Too many sign-ins from your network - try again later");
			}
			LoginRequest r = JsonHttp.readBody(ex, LoginRequest.class);
			if (r == null || !pow.verify(r.pow())) {
				throw new ApiError(400, "The security check failed - reload the page and try again");
			}
			String name = r.name() == null ? "" : r.name().strip();
			String uuid = null;
			Account a = null;
			synchronized (this) {
				for (Map.Entry<String, Account> e : data.accounts.entrySet()) {
					if (e.getValue().enrolled && name.equalsIgnoreCase(e.getValue().name)) {
						uuid = e.getKey();
						a = e.getValue();
					}
				}
			}
			if (uuid == null && name.matches("[A-Za-z0-9_]{1,16}")) {
				// Renamed since setting up: find the account by who the name belongs to now.
				try {
					String resolved = social.resolvePlayer(name).uuid();
					synchronized (this) {
						Account byUuid = data.accounts.get(resolved);
						if (byUuid != null && byUuid.enrolled) {
							uuid = resolved;
							a = byUuid;
						}
					}
				} catch (SocialService.SocialException ignored) {
					// Unknown name: same answer as a wrong password.
				}
			}
			if (uuid != null && limiter.tryAcquire(uuid, "staff-login-account", LOGIN_ACCOUNT) > 0) {
				log(uuid, ip, "sign-in refused", "account locked for 15 minutes");
				throw new ApiError(429, "This account is locked for 15 minutes after too many tries");
			}
			String stored = a == null ? null : "pattern".equals(r.method()) ? a.pattern : a.password;
			boolean ok = StaffCrypto.secretMatches(stored, r.secret());
			Long step = ok ? StaffCrypto.totpStep(a.totp, r.code(), System.currentTimeMillis()) : null;
			if (!ok || step == null || step <= a.totpStep || levelOf.apply(uuid) == null) {
				if (uuid != null) {
					log(uuid, ip, "sign-in failed", !ok ? "wrong " + ("pattern".equals(r.method()) ? "pattern" : "password")
							: step == null ? "wrong code" : step <= a.totpStep ? "code reused" : "not on the team");
				}
				throw new ApiError(401, "Wrong name, " + ("pattern".equals(r.method()) ? "pattern" : "password") + " or code");
			}
			synchronized (this) {
				a.totpStep = step;
			}
			open(ex, uuid, "full");
			log(uuid, ip, "signed in", "pattern".equals(r.method()) ? "pattern + code" : "password + code");
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		post(server, "/v1/staffpanel/passkey/login/options", ex -> {
			if (limiter.tryAcquire(WebRoutes.clientIp(ex), "staff-passkey", PASSKEY_IP) > 0) {
				throw new ApiError(429, "Too many tries - wait a few minutes");
			}
			JsonHttp.writeJson(ex, 200, Map.of("challenge", challenge(""), "rpId", rpId, "timeout", 120_000, "userVerification", "required"));
		});
		post(server, "/v1/staffpanel/passkey/login", ex -> {
			String ip = WebRoutes.clientIp(ex);
			if (limiter.tryAcquire(ip, "staff-login", LOGIN_IP) > 0) {
				throw new ApiError(429, "Too many sign-ins from your network - try again later");
			}
			PasskeyLogin r = JsonHttp.readBody(ex, PasskeyLogin.class);
			byte[] clientData = StaffCrypto.B64URL_IN.decode(r.clientDataJSON());
			consume(StaffCrypto.clientData(clientData, "webauthn.get", origin));
			byte[] authData = StaffCrypto.B64URL_IN.decode(r.authenticatorData());
			StaffCrypto.authData(authData, rpId, false);
			String uuid = null;
			Passkey key = null;
			synchronized (this) {
				for (Map.Entry<String, Account> e : data.accounts.entrySet()) {
					for (Passkey p : e.getValue().passkeys) {
						if (p.id.equals(r.id()) && e.getValue().enrolled) {
							uuid = e.getKey();
							key = p;
						}
					}
				}
			}
			if (key == null) {
				throw new ApiError(401, "This passkey isn't set up for the staff panel (any more)");
			}
			if (!StaffCrypto.verifySignature(key.publicKey, key.alg, authData, clientData, StaffCrypto.B64URL_IN.decode(r.signature()))) {
				log(uuid, ip, "sign-in failed", "bad passkey signature");
				throw new ApiError(401, "The passkey's signature didn't check out");
			}
			long count = StaffCrypto.signCount(authData);
			if (count != 0 && count <= key.signCount) {
				log(uuid, ip, "sign-in refused", "passkey counter went back - possibly copied");
				throw new ApiError(401, "This passkey may have been copied - remove it and add it again");
			}
			if (levelOf.apply(uuid) == null) {
				throw new ApiError(403, "You're not on the Velo team any more");
			}
			synchronized (this) {
				key.signCount = count;
				key.lastUsed = System.currentTimeMillis();
			}
			open(ex, uuid, "full");
			log(uuid, ip, "signed in", "passkey " + key.name);
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		server.createContext("/v1/staffpanel/logout", exchange -> VeloServerApp.handle(exchange, "POST", ex -> {
			String token = cookie(ex, COOKIE);
			if (token != null) {
				Session s;
				synchronized (this) {
					s = data.sessions.remove(StaffCrypto.sha256Hex(token));
					save();
				}
				if (s != null) {
					log(s.uuid, WebRoutes.clientIp(ex), "signed out", "");
				}
			}
			ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict");
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		}));

		// ---- Managers: the team, sign-ins, punishments ----
		SocialRoutes.route(server, registry, "/v1/staff/team", "GET", (ex, me) -> {
			requireRank(levelOf.apply(me), StaffService.MANAGER);
			List<Map<String, Object>> out = new ArrayList<>();
			for (Map.Entry<String, StaffService.Member> e : staff.staff().entrySet()) {
				out.add(teamRow(e.getKey(), e.getValue().level, e.getValue().discordId, e.getValue().since, links));
			}
			out.sort(Comparator.comparingInt((Map<String, Object> m) -> -StaffService.rank((String) m.get("level")))
					.thenComparing(m -> String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));
			JsonHttp.writeJson(ex, 200, Map.of("team", out, "botOnline", bot.online()));
		});
		SocialRoutes.route(server, registry, "/v1/staff/team/reset-signin", "POST", (ex, me) -> {
			String mine = levelOf.apply(me);
			requireRank(mine, StaffService.MANAGER);
			String uuid = VeloServerApp.normalizeUuid(JsonHttp.readBody(ex, TeamTarget.class).uuid());
			if (uuid == null) {
				throw new ApiError(400, "Unknown team member");
			}
			requireAbove(me, mine, uuid);
			reset(uuid);
			log(uuid, WebRoutes.clientIp(ex), "sign-in reset", "by " + social.usernameOf(me));
			moderation.log(social.usernameOf(me), "reset staff panel sign-in", social.usernameOf(uuid), null);
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		SocialRoutes.route(server, registry, "/v1/staff/team/end-session", "POST", (ex, me) -> {
			String mine = levelOf.apply(me);
			requireRank(mine, StaffService.MANAGER);
			String uuid = VeloServerApp.normalizeUuid(JsonHttp.readBody(ex, TeamTarget.class).uuid());
			if (uuid == null) {
				throw new ApiError(400, "Unknown team member");
			}
			requireAbove(me, mine, uuid);
			endSessions(uuid);
			log(uuid, WebRoutes.clientIp(ex), "signed out", "by " + social.usernameOf(me));
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		// Levels live in Discord roles (the bot syncs them here): changing one changes the member's role.
		SocialRoutes.route(server, registry, "/v1/staff/team/level", "POST", (ex, me) -> {
			String mine = levelOf.apply(me);
			requireRank(mine, StaffService.MANAGER);
			TeamLevel r = JsonHttp.readBody(ex, TeamLevel.class);
			SocialService.PlayerRef ref = social.resolvePlayer(r.target());
			String level = "none".equals(r.level()) ? null : StaffService.normalizeLevel(r.level());
			if (level == null && !"none".equals(r.level())) {
				throw new ApiError(400, "Pick helper, moderator, admin, manager or remove");
			}
			if (ref.uuid().equals(me)) {
				throw new ApiError(403, "You can't change your own level");
			}
			boolean owner = isOwner(me);
			if (!owner && (StaffService.rank(level) >= StaffService.rank(mine) || StaffService.rank(staff.level(ref.uuid())) >= StaffService.rank(mine))) {
				throw new ApiError(403, "Only the owner can give or take the manager level");
			}
			DiscordLinks.Link link = links.byUuid(ref.uuid());
			if (link == null) {
				throw new ApiError(409, ref.username() + " hasn't linked Discord yet - they need /link on the Velo Discord first");
			}
			bot.setLevel(link.discordId(), level, social.usernameOf(me));
			moderation.log(social.usernameOf(me), level == null ? "removed from the team" : "set staff level " + level, ref.username(), null);
			JsonHttp.writeJson(ex, 200, Map.of("ok", true, "note", "Discord role updated - the team list follows within a minute"));
		});
		SocialRoutes.route(server, registry, "/v1/staff/signins", "GET", (ex, me) -> {
			requireRank(levelOf.apply(me), StaffService.MANAGER);
			List<LogEntry> out;
			synchronized (this) {
				out = new ArrayList<>(data.log);
			}
			java.util.Collections.reverse(out);
			JsonHttp.writeJson(ex, 200, Map.of("log", out.subList(0, Math.min(300, out.size()))));
		});
		SocialRoutes.route(server, registry, "/v1/staff/punishments", "GET", (ex, me) -> {
			requireRank(levelOf.apply(me), StaffService.MANAGER);
			List<Map<String, Object>> bans = new ArrayList<>();
			for (Map.Entry<String, StaffService.Ban> e : staff.bans().entrySet()) {
				StaffService.Ban b = e.getValue();
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("uuid", e.getKey());
				row.put("username", social.usernameOf(e.getKey()));
				row.put("services", b.services);
				row.put("reason", b.reason);
				row.put("by", b.by);
				row.put("at", b.at);
				row.put("until", b.until);
				bans.add(row);
			}
			bans.sort(Comparator.comparingLong((Map<String, Object> m) -> (long) m.get("at")).reversed());
			List<Map<String, Object>> blocks = new ArrayList<>();
			for (String what : List.of(ModerationService.CLIP, ModerationService.WORLD, ModerationService.NOTE)) {
				for (Map.Entry<String, ModerationService.Block> e : moderation.blocks(what).entrySet()) {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("what", what);
					row.put("uuid", e.getKey());
					row.put("username", social.usernameOf(e.getKey()));
					row.put("reason", e.getValue().reason);
					row.put("by", e.getValue().by);
					row.put("at", e.getValue().at);
					row.put("until", e.getValue().until);
					blocks.add(row);
				}
			}
			List<Map<String, Object>> actioned = new ArrayList<>();
			for (StaffService.Report r : staff.reports("actioned")) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("id", r.id);
				row.put("target", r.targetName);
				row.put("category", r.category);
				row.put("by", r.handledBy);
				row.put("at", r.handledAt);
				row.put("note", r.note);
				actioned.add(row);
			}
			JsonHttp.writeJson(ex, 200, Map.of("bans", bans, "blocks", blocks, "reports", actioned, "log", moderation.log(300)));
		});

		// ---- Discord tickets (through the bot) ----
		SocialRoutes.route(server, registry, "/v1/staff/tickets", "GET", (ex, me) -> {
			requireRank(levelOf.apply(me), StaffService.HELPER);
			JsonHttp.writeBytes(ex, 200, "application/json", bot.get("/tickets"), "no-store");
		});
		SocialRoutes.route(server, registry, "/v1/staff/tickets/messages", "GET", (ex, me) -> {
			requireRank(levelOf.apply(me), StaffService.HELPER);
			String id = StoreRoutes.query(ex).getOrDefault("id", "");
			if (!id.matches("\\d{5,25}")) {
				throw new ApiError(400, "Unknown ticket");
			}
			JsonHttp.writeBytes(ex, 200, "application/json", bot.get("/tickets/messages?id=" + id), "no-store");
		});
		for (String action : List.of("reply", "claim", "close")) {
			SocialRoutes.route(server, registry, "/v1/staff/tickets/" + action, "POST", (ex, me) -> {
				requireRank(levelOf.apply(me), StaffService.HELPER);
				TicketAction r = JsonHttp.readBody(ex, TicketAction.class);
				if (r == null || r.id() == null || !r.id().matches("\\d{5,25}")) {
					throw new ApiError(400, "Unknown ticket");
				}
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("id", r.id());
				body.put("staffName", social.usernameOf(me));
				DiscordLinks.Link link = links.byUuid(me);
				body.put("staffDiscordId", link == null ? null : link.discordId());
				body.put("text", r.text() == null ? null : r.text().strip());
				body.put("reason", r.reason() == null ? null : r.reason().strip());
				if ("reply".equals(action) && (r.text() == null || r.text().isBlank() || r.text().length() > 1900)) {
					throw new ApiError(400, "Write a message (up to 1900 characters)");
				}
				byte[] result = bot.post("/tickets/" + action, body);
				if (!"reply".equals(action)) {
					moderation.log(social.usernameOf(me), action + "ed ticket", r.id(), r.reason());
				}
				JsonHttp.writeBytes(ex, 200, "application/json", result, "no-store");
			});
		}
	}

	private boolean isOwner(String uuid) {
		return owner.test(uuid);
	}

	private void requireAbove(String me, String mine, String target) throws ApiError {
		if (me.equals(target)) {
			return;
		}
		if (!isOwner(me) && StaffService.rank(levelOf.apply(target)) >= StaffService.rank(mine)) {
			throw new ApiError(403, "Only the owner can do that to another manager");
		}
	}

	private synchronized Map<String, Object> teamRow(String uuid, String level, String discordId, long since, DiscordLinks links) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("uuid", uuid);
		row.put("name", social.usernameOf(uuid));
		row.put("level", level);
		row.put("discordId", discordId);
		DiscordLinks.Link link = links.byUuid(uuid);
		row.put("discordName", link == null ? null : link.discordName());
		row.put("since", since);
		Account a = data.accounts.get(uuid);
		row.put("enrolled", a != null && a.enrolled);
		row.put("methods", a == null ? null : methods(a));
		row.put("lastSignInAt", a == null ? 0 : a.lastSignInAt);
		row.put("lastSignInIp", a == null ? null : a.lastSignInIp);
		row.put("lastAgent", a == null ? null : a.lastAgent);
		row.put("signedIn", hasFullSession(uuid));
		return row;
	}

	private static void requireRank(String level, String needed) throws ApiError {
		if (level == null || StaffService.rank(level) < StaffService.rank(needed)) {
			throw new ApiError(403, StaffService.MANAGER.equals(needed) ? "Managers only" : "Velo staff only");
		}
	}

	/** The Discord bot's local API (same machine): tickets and staff roles. */
	static final class BotBridge {
		private final String base;
		private final String token;
		private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

		BotBridge(String base, String token) {
			this.base = base == null ? null : base.replaceAll("/+$", "");
			this.token = token;
		}

		boolean online() {
			try {
				get("/health");
				return true;
			} catch (ApiError e) {
				return false;
			}
		}

		byte[] get(String path) throws ApiError {
			return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
		}

		byte[] post(String path, Object body) throws ApiError {
			return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body))));
		}

		void setLevel(String discordId, String level, String by) throws ApiError {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("discordId", discordId);
			body.put("level", level);
			body.put("by", by);
			post("/staff/level", body);
		}

		private byte[] send(HttpRequest.Builder request) throws ApiError {
			if (base == null || token == null) {
				throw new ApiError(503, "The Discord bot isn't connected to the server");
			}
			try {
				HttpResponse<byte[]> response = http.send(request.header("X-Velo-Admin-Token", token).timeout(Duration.ofSeconds(20)).build(),
						HttpResponse.BodyHandlers.ofByteArray());
				if (response.statusCode() >= 400) {
					String message = "The Discord bot refused that (HTTP " + response.statusCode() + ")";
					try {
						JsonElement e = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8));
						if (e.isJsonObject() && e.getAsJsonObject().has("error")) {
							message = e.getAsJsonObject().get("error").getAsString();
						}
					} catch (RuntimeException ignored) {
						// Not JSON.
					}
					throw new ApiError(response.statusCode() == 404 ? 404 : 502, message);
				}
				return response.body();
			} catch (IOException e) {
				throw new ApiError(503, "The Discord bot isn't answering - try again in a minute");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new ApiError(503, "Interrupted");
			}
		}
	}
}
