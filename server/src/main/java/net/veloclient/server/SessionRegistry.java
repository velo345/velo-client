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

	record Challenge(String uuid, String username, String serverId, long expiresAtMillis) {
	}

	// Not actually mutated in place - heartbeat() replaces the whole record in
	// the map (see below), so the ConcurrentHashMap itself provides the
	// necessary visibility without needing volatile fields here (which
	// records can't have anyway).
	record Session(String uuid, String username, String capeId, long expiresAtMillis) {
	}

	record OnlineUser(String uuid, String username, String capeId) {
	}

	String createChallenge(String uuid, String username) {
		String serverId = randomHex(16);
		challengesByUuid.put(uuid, new Challenge(uuid, username, serverId, System.currentTimeMillis() + CHALLENGE_TTL_MILLIS));
		return serverId;
	}

	/** Consumes the pending challenge for {@code uuid} if {@code serverId} matches and it hasn't expired; null otherwise. */
	Challenge takeChallenge(String uuid, String serverId) {
		Challenge challenge = challengesByUuid.get(uuid);
		if (challenge == null || challenge.expiresAtMillis() < System.currentTimeMillis() || !challenge.serverId().equals(serverId)) {
			return null;
		}
		challengesByUuid.remove(uuid);
		return challenge;
	}

	String createSession(String uuid, String username) {
		String token = randomHex(32);
		sessionsByToken.put(token, new Session(uuid, username, null, System.currentTimeMillis() + SESSION_TTL_MILLIS));
		return token;
	}

	/** Refreshes the session's TTL and cape choice; returns false if the token is unknown/expired. */
	boolean heartbeat(String token, String capeId) {
		Session session = sessionsByToken.get(token);
		if (session == null || session.expiresAtMillis() < System.currentTimeMillis()) {
			sessionsByToken.remove(token);
			return false;
		}
		sessionsByToken.put(token, new Session(session.uuid(), session.username(), normalizeCapeId(capeId),
				System.currentTimeMillis() + SESSION_TTL_MILLIS));
		return true;
	}

	void endSession(String token) {
		sessionsByToken.remove(token);
	}

	List<OnlineUser> onlineUsers() {
		long now = System.currentTimeMillis();
		return sessionsByToken.values().stream()
				.filter(s -> s.expiresAtMillis() >= now)
				.map(s -> new OnlineUser(s.uuid(), s.username(), s.capeId()))
				.toList();
	}

	int onlineCount() {
		return onlineUsers().size();
	}

	/** Drops expired sessions/challenges - called periodically so memory doesn't grow with churn. Not required for correctness (both lookups already check expiry), just housekeeping. */
	void sweepExpired() {
		long now = System.currentTimeMillis();
		sessionsByToken.entrySet().removeIf(e -> e.getValue().expiresAtMillis() < now);
		challengesByUuid.entrySet().removeIf(e -> e.getValue().expiresAtMillis() < now);
	}

	private static String normalizeCapeId(String capeId) {
		if (capeId == null || capeId.isBlank()) {
			return null;
		}
		String trimmed = capeId.trim();
		return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
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
