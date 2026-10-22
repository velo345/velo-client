package net.veloclient.server;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All server state lives here, in memory - there's nothing worth persisting
 * to disk: "who's online right now" is only ever true for as long as a
 * client keeps heartbeating, so a restart legitimately means everyone has to
 * reconnect anyway (their own game clients will just re-challenge on the
 * next poll, see the mod's {@code VeloServerClient}).
 */
final class SessionRegistry {

	// How long a pending challenge (server handed out a serverId, waiting for
	// the client to prove it via Mojang's join+hasJoined) stays valid before
	// it has to be re-requested.
	private static final long CHALLENGE_TTL_MILLIS = 60_000;
	// A session survives this long without a heartbeat before it's dropped
	// from the online list - comfortably longer than the interval the mod
	// actually heartbeats at (see VeloServerClient), so one dropped/slow
	// request doesn't flicker a player's badge off.
	static final long SESSION_TTL_MILLIS = 150_000;
	static final long HEARTBEAT_INTERVAL_SECONDS = 45;

	private final SecureRandom random = new SecureRandom();
	private final Map<String, Challenge> challengesByUuid = new ConcurrentHashMap<>();
	private final Map<String, Session> sessionsByToken = new ConcurrentHashMap<>();
	/** Members who chose not to show their Velo+ badge (the game says so with every heartbeat). */
	private final java.util.Set<String> plusHidden = ConcurrentHashMap.newKeySet();
	/** Launcher sessions whose window has been in the background for a while: still connected, but not shown as online. */
	private final java.util.Set<String> idleTokens = ConcurrentHashMap.newKeySet();

	/** Which app a session belongs to - the game and the launcher can each hold one session for the same account at once. */
	static final String KIND_GAME = "game";
	static final String KIND_LAUNCHER = "launcher";
	/** velo-client.com, signed in with a code from the launcher - lives a week, never counts as online. */
	static final String KIND_WEB = "web";
	static final long WEB_SESSION_TTL_MILLIS = 14L * 24 * 60 * 60 * 1000;
	static final long WEB_CODE_TTL_MILLIS = 5 * 60 * 1000;

	/** A website sign-in code shown in the launcher: whose it is and until when it works. */
	private record WebCode(String uuid, String username, long expiresAtMillis) {
	}

	private final Map<String, WebCode> webCodes = new ConcurrentHashMap<>();

	record Challenge(String uuid, String username, String kind, String serverId, long expiresAtMillis) {
	}

	// Not actually mutated in place - heartbeat() replaces the whole record in
	// the map (see below), so the ConcurrentHashMap itself provides the
	// necessary visibility without needing volatile fields here (which
	// records can't have anyway).
	record Session(String uuid, String username, String kind, String capeId, String wingsId, String auraId, String nameStyle, long expiresAtMillis) {
	}

	/** {@code nameStyle}: a Velo+ member's animated name (see {@link #normalizeNameStyle}), null for none. */
	record OnlineUser(String uuid, String username, String capeId, String wingsId, String auraId, boolean plus, String nameStyle) {
	}

	/** Called with a uuid whenever one of its sessions ends or expires - drives friends' presence updates. */
	private volatile java.util.function.Consumer<String> onSessionGone = uuid -> {
	};

	void setOnSessionGone(java.util.function.Consumer<String> listener) {
		this.onSessionGone = listener;
	}

	static String normalizeKind(String kind) {
		if (KIND_WEB.equals(kind)) {
			return KIND_WEB;
		}
		return KIND_LAUNCHER.equals(kind) ? KIND_LAUNCHER : KIND_GAME;
	}

	String createChallenge(String uuid, String username, String kind) {
		String serverId = randomHex(16);
		// Keyed per uuid+kind, so the launcher and the game authenticating at the same moment
		// don't overwrite each other's pending challenge.
		challengesByUuid.put(uuid + "/" + normalizeKind(kind), new Challenge(uuid, username, normalizeKind(kind), serverId,
				System.currentTimeMillis() + CHALLENGE_TTL_MILLIS));
		return serverId;
	}

	/** Consumes the pending challenge for {@code uuid} if {@code serverId} matches and it hasn't expired; null otherwise. */
	Challenge takeChallenge(String uuid, String kind, String serverId) {
		String key = uuid + "/" + normalizeKind(kind);
		Challenge challenge = challengesByUuid.get(key);
		if (challenge == null || challenge.expiresAtMillis() < System.currentTimeMillis() || !challenge.serverId().equals(serverId)) {
			return null;
		}
		challengesByUuid.remove(key);
		return challenge;
	}

	String createSession(String uuid, String username, String kind) {
		// One live session per account: a client that re-authenticates (restart, dropped token,
		// switching between the Velo launcher and the vanilla one) used to leave its previous
		// session behind until it expired, so /v1/online listed the same player twice - possibly
		// with a stale cape - for up to SESSION_TTL_MILLIS.
		// The launcher and the game are separate apps that can both be open - each keeps its own
		// session, only a repeat of the same kind replaces the old one.
		String normalizedKind = normalizeKind(kind);
		// Web sessions are one per browser, so signing in on a phone doesn't sign the PC out.
		if (!KIND_WEB.equals(normalizedKind)) {
			sessionsByToken.values().removeIf(s -> s.uuid().equals(uuid) && s.kind().equals(normalizedKind));
		}
		String token = randomHex(32);
		sessionsByToken.put(token, new Session(uuid, username, normalizedKind, null, null, null, null, System.currentTimeMillis() + ttl(normalizedKind)));
		return token;
	}

	private static long ttl(String kind) {
		return KIND_WEB.equals(kind) ? WEB_SESSION_TTL_MILLIS : SESSION_TTL_MILLIS;
	}

	/** A fresh 6-digit website sign-in code for this account (any earlier one stops working). */
	String createWebCode(String uuid, String username) {
		long now = System.currentTimeMillis();
		webCodes.values().removeIf(c -> c.uuid().equals(uuid) || c.expiresAtMillis() < now);
		String code;
		do {
			code = String.format("%06d", random.nextInt(1_000_000));
		} while (webCodes.containsKey(code));
		webCodes.put(code, new WebCode(uuid, username, now + WEB_CODE_TTL_MILLIS));
		return code;
	}

	/** Sign-in requests from the website, per uuid: when they expire. The launcher picks them up and shows the code. */
	private final Map<String, Long> webRequests = new ConcurrentHashMap<>();

	/** The website asked for a code for this account; the next launcher poll within 5 minutes shows one. */
	void requestWebCode(String uuid) {
		webRequests.put(uuid, System.currentTimeMillis() + WEB_CODE_TTL_MILLIS);
	}

	/** A fresh code if the website asked for one (once), else null. */
	String takeRequestedWebCode(String uuid, String username) {
		Long until = webRequests.remove(uuid);
		if (until == null || until < System.currentTimeMillis()) {
			return null;
		}
		return createWebCode(uuid, username);
	}

	/** Swaps a website sign-in code (once) for a web session: {token, uuid, username}, or null if it's wrong or expired. */
	String[] redeemWebCode(String code) {
		if (code == null) {
			return null;
		}
		WebCode webCode = webCodes.remove(code.replaceAll("\\s", ""));
		if (webCode == null || webCode.expiresAtMillis() < System.currentTimeMillis()) {
			return null;
		}
		return new String[] {createSession(webCode.uuid(), webCode.username(), KIND_WEB), webCode.uuid(), webCode.username()};
	}


	/** The live session behind {@code token}, or null if it's unknown/expired. */
	Session session(String token) {
		if (token == null) {
			return null;
		}
		Session session = sessionsByToken.get(token);
		if (session == null || session.expiresAtMillis() < System.currentTimeMillis()) {
			return null;
		}
		return session;
	}

	/**
	 * Refreshes the session's TTL and cape choice; returns the updated session, or null if the token is unknown/expired.
	 * A {@code custom:<hash>} cape id is only accepted if it's the cape this same account uploaded
	 * ({@code ownCustomCapeHash}) - otherwise anyone could point their cape at any other player's
	 * upload, or at a hash an admin removed.
	 */
	Session heartbeat(String token, String capeId, String ownCustomCapeHash) {
		return heartbeat(token, capeId, ownCustomCapeHash, null);
	}

	/** Same, also recording the equipped 3D wings ({@code wingsId}, a bundled cosmetic id - or null for none). */
	Session heartbeat(String token, String capeId, String ownCustomCapeHash, String wingsId) {
		return heartbeat(token, capeId, ownCustomCapeHash, wingsId, null);
	}

	/** Same, also recording the equipped aura ({@code auraId}: "id;settings...", like wings). */
	Session heartbeat(String token, String capeId, String ownCustomCapeHash, String wingsId, String auraId) {
		return heartbeat(token, capeId, ownCustomCapeHash, wingsId, auraId, null);
	}

	/** Same, also recording the name style (already checked: only Velo+ members may have one). */
	Session heartbeat(String token, String capeId, String ownCustomCapeHash, String wingsId, String auraId, String nameStyle) {
		Session session = session(token);
		if (session == null) {
			sessionsByToken.remove(token);
			return null;
		}
		String normalized = normalizeCapeId(capeId);
		if (normalized != null && normalized.startsWith(CapeStore.CUSTOM_PREFIX)
				&& (ownCustomCapeHash == null || !normalized.equals(CapeStore.CUSTOM_PREFIX + ownCustomCapeHash))) {
			normalized = null;
		}
		Session refreshed = new Session(session.uuid(), session.username(), session.kind(), normalized, normalizeWingsId(wingsId),
				normalizeWingsId(auraId), normalizeNameStyle(nameStyle), System.currentTimeMillis() + SESSION_TTL_MILLIS);
		sessionsByToken.put(token, refreshed);
		return refreshed;
	}

	/** Extends a live session's TTL without touching its cape - the social long-poll counts as "still here". */
	void touch(String token) {
		Session session = session(token);
		if (session != null) {
			sessionsByToken.put(token, new Session(session.uuid(), session.username(), session.kind(), session.capeId(), session.wingsId(),
					session.auraId(), session.nameStyle(), System.currentTimeMillis() + ttl(session.kind())));
		}
	}

	/** The game's heartbeat carries whether this player wants their Velo+ badge shown. */
	void setPlusHidden(String uuid, boolean hidden) {
		if (hidden) {
			plusHidden.add(uuid);
		} else {
			plusHidden.remove(uuid);
		}
	}

	/** Marks a launcher session idle (window long in the background - the player may be in another client) or active again; true if that changed. */
	boolean setIdle(String token, boolean idle) {
		return idle ? idleTokens.add(token) : idleTokens.remove(token);
	}

	/**
	 * The launcher saw its game process end (a crash, a failed join that left a stale session behind):
	 * drops every game session of the account behind this launcher token right away instead of waiting out the TTL.
	 */
	void endGameSessions(String launcherToken) {
		Session launcher = session(launcherToken);
		if (launcher == null || !KIND_LAUNCHER.equals(launcher.kind())) {
			return;
		}
		boolean removed = sessionsByToken.values().removeIf(s -> s.uuid().equals(launcher.uuid()) && s.kind().equals(KIND_GAME));
		if (removed) {
			onSessionGone.accept(launcher.uuid());
		}
	}

	void endSession(String token) {
		idleTokens.remove(token);
		Session removed = sessionsByToken.remove(token);
		if (removed != null) {
			onSessionGone.accept(removed.uuid());
		}
	}

	/** Whether {@code uuid} has a live session of {@code kind}. */
	boolean hasLiveSession(String uuid, String kind) {
		long now = System.currentTimeMillis();
		return sessionsByToken.values().stream()
				.anyMatch(s -> s.uuid().equals(uuid) && s.kind().equals(kind) && s.expiresAtMillis() >= now);
	}

	/** Whether {@code uuid} has a launcher session that is live and not idle. */
	boolean hasActiveLauncherSession(String uuid) {
		long now = System.currentTimeMillis();
		return sessionsByToken.entrySet().stream().anyMatch(e -> e.getValue().uuid().equals(uuid) && e.getValue().kind().equals(KIND_LAUNCHER)
				&& e.getValue().expiresAtMillis() >= now && !idleTokens.contains(e.getKey()));
	}

	/** In-game players only (the badge/cape list) - a launcher session isn't "in game", and isn't listed twice. */
	/** Whether a member has Velo+ (the badge with the plus); set by the server app. */
	private volatile java.util.function.Predicate<String> plusLookup = uuid -> false;

	void setPlusLookup(java.util.function.Predicate<String> lookup) {
		this.plusLookup = lookup;
	}

	List<OnlineUser> onlineUsers() {
		long now = System.currentTimeMillis();
		return sessionsByToken.values().stream()
				.filter(s -> s.expiresAtMillis() >= now && s.kind().equals(KIND_GAME))
				.map(s -> new OnlineUser(s.uuid(), s.username(), s.capeId(), s.wingsId(), s.auraId(), plusLookup.test(s.uuid()) && !plusHidden.contains(s.uuid()),
						plusLookup.test(s.uuid()) ? s.nameStyle() : null))
				.toList();
	}

	int onlineCount() {
		return onlineUsers().size();
	}

	/** Every live session, any app - for the owner panel. Idle launcher sessions are marked. */
	List<java.util.Map<String, Object>> liveSessions() {
		long now = System.currentTimeMillis();
		List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
		sessionsByToken.forEach((token, s) -> {
			if (s.expiresAtMillis() < now) {
				return;
			}
			java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
			row.put("uuid", s.uuid());
			row.put("username", s.username());
			row.put("kind", s.kind());
			row.put("idle", idleTokens.contains(token));
			row.put("capeId", s.capeId());
			row.put("wingsId", s.wingsId());
			row.put("auraId", s.auraId());
			row.put("nameStyle", s.nameStyle());
			row.put("plus", plusLookup.test(s.uuid()));
			out.add(row);
		});
		return out;
	}

	/** Ends every session of an account (a network ban takes effect at once). */
	void endSessionsOf(String uuid) {
		boolean[] any = {false};
		sessionsByToken.entrySet().removeIf(e -> {
			boolean match = e.getValue().uuid().equals(uuid);
			if (match) {
				idleTokens.remove(e.getKey());
				any[0] = true;
			}
			return match;
		});
		if (any[0]) {
			onSessionGone.accept(uuid);
		}
	}

	/** Live sessions per app: {game, launcher, web}. */
	int[] countsByKind() {
		long now = System.currentTimeMillis();
		int[] counts = new int[3];
		for (Session s : sessionsByToken.values()) {
			if (s.expiresAtMillis() >= now) {
				counts[KIND_GAME.equals(s.kind()) ? 0 : KIND_LAUNCHER.equals(s.kind()) ? 1 : 2]++;
			}
		}
		return counts;
	}

	/** Drops expired sessions/challenges - called periodically so memory doesn't grow with churn. Not required for correctness (both lookups already check expiry), just housekeeping. */
	void sweepExpired() {
		long now = System.currentTimeMillis();
		List<String> expired = new java.util.ArrayList<>();
		sessionsByToken.entrySet().removeIf(e -> {
			boolean gone = e.getValue().expiresAtMillis() < now;
			if (gone) {
				idleTokens.remove(e.getKey());
				expired.add(e.getValue().uuid());
			}
			return gone;
		});
		expired.forEach(onSessionGone);
		challengesByUuid.entrySet().removeIf(e -> e.getValue().expiresAtMillis() < now);
	}

	private static String normalizeCapeId(String capeId) {
		if (capeId == null || capeId.isBlank()) {
			return null;
		}
		String trimmed = capeId.trim();
		// custom:<64 hex chars> is 71 long, so the cap has to leave room for it.
		return trimmed.length() > 96 ? trimmed.substring(0, 96) : trimmed;
	}

	/**
	 * Wings are bundled with every client and picked by id, followed by the wearer's settings as
	 * numbers ("phoenix;100;0;0;10;120;90") - only that short plain form is passed on.
	 */
	static String normalizeWingsId(String wingsId) {
		if (wingsId == null || !wingsId.matches("[a-z0-9_-]{1,32}(;-?[0-9]{1,4}){0,8}")) {
			return null;
		}
		return wingsId;
	}

	/**
	 * A name style as the game sends it: {@code 1|effect|speed|flags|colors|icon|where}, e.g.
	 * {@code 1|wave|12|bg|ff4d4d,ffb84d,ffe14d|3|ctn} - effect name, speed in tenths, flags (b bold, i italic,
	 * u underline, g glow, r reverse, a digit for the spread, a capital letter for the font), 1 to 6 colours, an icon number (0 none) and where it shows (chat, tab, name tag).
	 * Anything else is dropped, so a client can't send other players arbitrary text.
	 */
	static String normalizeNameStyle(String style) {
		if (style == null || style.length() > 96
				|| !style.matches("1\\|[a-z]{1,12}\\|[0-9]{1,2}\\|[biugr1-9A-I]{0,7}\\|[0-9a-f]{6}(,[0-9a-f]{6}){0,5}\\|[0-9]{1,2}\\|[ctn]{0,3}")) {
			return null;
		}
		return style;
	}

	private String randomHex(int bytes) {
		byte[] buffer = new byte[bytes];
		random.nextBytes(buffer);
		StringBuilder sb = new StringBuilder(bytes * 2);
		for (byte b : buffer) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}
}
