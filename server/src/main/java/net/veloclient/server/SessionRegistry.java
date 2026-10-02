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

	/** Which app a session belongs to - the game and the launcher can each hold one session for the same account at once. */
	static final String KIND_GAME = "game";
	static final String KIND_LAUNCHER = "launcher";

	record Challenge(String uuid, String username, String kind, String serverId, long expiresAtMillis) {
	}

	// Not actually mutated in place - heartbeat() replaces the whole record in
	// the map (see below), so the ConcurrentHashMap itself provides the
	// necessary visibility without needing volatile fields here (which
	// records can't have anyway).
	record Session(String uuid, String username, String kind, String capeId, long expiresAtMillis) {
	}

	record OnlineUser(String uuid, String username, String capeId) {
	}

	/** Called with a uuid whenever one of its sessions ends or expires - drives friends' presence updates. */
	private volatile java.util.function.Consumer<String> onSessionGone = uuid -> {
	};

	void setOnSessionGone(java.util.function.Consumer<String> listener) {
		this.onSessionGone = listener;
	}

	static String normalizeKind(String kind) {
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
		sessionsByToken.values().removeIf(s -> s.uuid().equals(uuid) && s.kind().equals(normalizedKind));
		String token = randomHex(32);
		sessionsByToken.put(token, new Session(uuid, username, normalizedKind, null, System.currentTimeMillis() + SESSION_TTL_MILLIS));
		return token;
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
		Session refreshed = new Session(session.uuid(), session.username(), session.kind(), normalized,
				System.currentTimeMillis() + SESSION_TTL_MILLIS);
		sessionsByToken.put(token, refreshed);
		return refreshed;
	}

	/** Extends a live session's TTL without touching its cape - the social long-poll counts as "still here". */
	void touch(String token) {
		Session session = session(token);
		if (session != null) {
			sessionsByToken.put(token, new Session(session.uuid(), session.username(), session.kind(), session.capeId(),
					System.currentTimeMillis() + SESSION_TTL_MILLIS));
		}
	}

	void endSession(String token) {
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

	/** In-game players only (the badge/cape list) - a launcher session isn't "in game", and isn't listed twice. */
	List<OnlineUser> onlineUsers() {
		long now = System.currentTimeMillis();
		return sessionsByToken.values().stream()
				.filter(s -> s.expiresAtMillis() >= now && s.kind().equals(KIND_GAME))
				.map(s -> new OnlineUser(s.uuid(), s.username(), s.capeId()))
				.toList();
	}

	int onlineCount() {
		return onlineUsers().size();
	}

	/** Drops expired sessions/challenges - called periodically so memory doesn't grow with churn. Not required for correctness (both lookups already check expiry), just housekeeping. */
	void sweepExpired() {
		long now = System.currentTimeMillis();
		List<String> expired = new java.util.ArrayList<>();
		sessionsByToken.entrySet().removeIf(e -> {
			boolean gone = e.getValue().expiresAtMillis() < now;
			if (gone) {
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
