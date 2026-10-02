package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Friends, friend requests, blocks, direct messages and shared waypoints - everything behind the
 * launcher's Friends tab and the in-game Friends screen.
 *
 * <p>Two kinds of state, deliberately kept apart:
 * <ul>
 * <li><b>Persistent</b> ({@link Data}, written to {@code social.json} in the data dir): who knows
 * whom, pending requests, blocks, message history (last {@link #MAX_MESSAGES_PER_CONVERSATION}
 * per conversation, so offline friends still get them), read markers, and each player's chosen
 * visibility ("appear offline"), which has to survive restarts of both this server and the
 * player's launcher.</li>
 * <li><b>Live</b> (memory only): where each player currently is (presence reported by the game) and
 * the per-player event queues the long-poll endpoint drains. "Online right now" is only true while
 * a session heartbeats, same reasoning as {@link SessionRegistry}.</li>
 * </ul>
 *
 * <p>Every mutation runs under one lock - this is a small community server, and a single lock makes
 * the friend/request/block invariants trivially consistent. Long-polls wait on a {@link Condition}
 * rather than {@code Object.wait()} so they never pin a virtual thread's carrier on JDK 21.
 */
final class SocialService {

	static final int MAX_MESSAGE_LENGTH = 500;
	private static final int MAX_MESSAGES_PER_CONVERSATION = 300;
	private static final int MAX_PENDING_OUTGOING = 100;
	private static final int MAX_EVENTS_PER_USER = 300;
	private static final int MAX_FRIENDS = 300;
	private static final long POLL_TIMEOUT_MILLIS = 25_000;
	/** After a declined request, the same sender can't ask that player again for this long. */
	static final long DECLINE_COOLDOWN_MILLIS = 30 * 60_000L;

	// Anti-spam limits per player (see RateLimiter): a short burst window plus a longer cap.
	private static final RateLimiter.Window[] REQUEST_LIMIT = {
			new RateLimiter.Window(5, 60_000), new RateLimiter.Window(30, 3_600_000)};
	private static final RateLimiter.Window[] MESSAGE_LIMIT = {
			new RateLimiter.Window(8, 5_000), new RateLimiter.Window(60, 60_000), new RateLimiter.Window(600, 3_600_000)};
	private static final RateLimiter.Window[] SHARE_LIMIT = {
			new RateLimiter.Window(10, 60_000), new RateLimiter.Window(60, 3_600_000)};
	private static final RateLimiter.Window[] MANAGE_LIMIT = {new RateLimiter.Window(30, 60_000)};
	private static final RateLimiter.Window[] STATUS_LIMIT = {new RateLimiter.Window(10, 60_000)};
	private static final RateLimiter.Window[] PRESENCE_LIMIT = {new RateLimiter.Window(30, 60_000)};

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	/** Thrown for a request the caller made wrong (unknown player, not friends...) - becomes a 4xx with this message. */
	static final class SocialException extends Exception {
		final int status;

		SocialException(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	// ---- Persistent shape (social.json) ----

	static final class UserRecord {
		String username;
		long lastSeen;
		boolean invisible;
	}

	static final class Message {
		long id;
		String from;
		String to;
		String text;
		long time;
		/** "text" or "waypoint". */
		String kind;
		Map<String, Object> waypoint;
	}

	static final class Data {
		Map<String, UserRecord> users = new HashMap<>();
		/** "a|b" with a < b. */
		Set<String> friendships = new LinkedHashSet<>();
		/** "from>to" -> time sent. */
		Map<String, Long> requests = new LinkedHashMap<>();
		/** uuid -> uuids that uuid has blocked. */
		Map<String, Set<String>> blocks = new HashMap<>();
		/** "a|b" -> messages, oldest first. */
		Map<String, List<Message>> conversations = new HashMap<>();
		/** reader -> (other -> newest message time read). */
		Map<String, Map<String, Long>> lastRead = new HashMap<>();
		long nextMessageId = 1;
	}

	// ---- API shapes ----

	record Activity(String kind, String detail) {
	}

	record FriendView(String uuid, String username, boolean online, Activity activity, long lastSeen, int unread) {
	}

	record PlayerRef(String uuid, String username) {
	}

	record RequestView(String uuid, String username, long time) {
	}

	record MessageView(long id, String from, String to, String text, long time, String kind, Map<String, Object> waypoint) {
	}

	record Me(String uuid, String username, boolean invisible) {
	}

	record State(Me me, List<FriendView> friends, List<RequestView> incoming, List<RequestView> outgoing,
			List<PlayerRef> blocked, long seq) {
	}

	record Event(long seq, String type, Object data) {
	}

	record PollResult(boolean reset, long seq, List<Event> events) {
	}

	private final Path file;
	private final SessionRegistry sessions;
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition eventsChanged = lock.newCondition();
	private final Data data;
	// Starts at the wall clock so sequence numbers keep increasing across restarts; anything a client
	// holds from before startSeq belongs to a previous run whose queued events are gone.
	private final long startSeq = System.currentTimeMillis();
	private final AtomicLong seq = new AtomicLong(startSeq);
	private final Map<String, Deque<Event>> events = new HashMap<>();
	/** Game-reported presence, per uuid - only meaningful while that uuid has a live game session. */
	private final Map<String, Activity> gamePresence = new ConcurrentHashMap<>();
	/** The last visible presence each player's friends were told about, so only real changes fan out. */
	private final Map<String, String> lastAnnouncedPresence = new HashMap<>();
	private volatile boolean dirty;
	private final RateLimiter limiter = new RateLimiter();
	/** "from>to" -> when to declined from's request (memory only - see DECLINE_COOLDOWN_MILLIS). */
	private final Map<String, Long> declinedAt = new ConcurrentHashMap<>();

	SocialService(Path dataDirectory, SessionRegistry sessions) throws IOException {
		this.file = dataDirectory.resolve("social.json");
		this.sessions = sessions;
		Files.createDirectories(dataDirectory);
		Data loaded = null;
		if (Files.exists(file)) {
			loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
		}
		this.data = loaded != null ? loaded : new Data();
		sessions.setOnSessionGone(this::refreshPresence);
	}

	// ---- Persistence ----

	/** Called periodically; writes social.json atomically if anything changed since the last save. */
	void saveIfDirty() {
		if (!dirty) {
			return;
		}
		String json;
		lock.lock();
		try {
			dirty = false;
			json = GSON.toJson(data);
		} finally {
			lock.unlock();
		}
		try {
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, json, StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			dirty = true;
			System.err.println("Failed to save social.json: " + e);
		}
	}

	private void limit(String uuid, String action, RateLimiter.Window... windows) throws SocialException {
		long wait = limiter.tryAcquire(uuid, action, windows);
		if (wait > 0) {
			throw new SocialException(429, "Slow down - try again in " + RateLimiter.describeWait(wait));
		}
	}

	/** Periodic housekeeping for the in-memory limiter/cooldown maps. */
	void sweepLimits() {
		limiter.sweep(3_600_000);
		long cutoff = System.currentTimeMillis() - DECLINE_COOLDOWN_MILLIS;
		declinedAt.values().removeIf(time -> time < cutoff);
	}

	// ---- Helpers (callers hold the lock) ----

	private static String pair(String a, String b) {
		return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
	}

	private boolean areFriends(String a, String b) {
		return data.friendships.contains(pair(a, b));
	}

	private boolean hasBlocked(String blocker, String target) {
		Set<String> blocked = data.blocks.get(blocker);
		return blocked != null && blocked.contains(target);
	}

	private UserRecord user(String uuid) {
		return data.users.computeIfAbsent(uuid, u -> new UserRecord());
	}

	private String nameOf(String uuid) {
		UserRecord record = data.users.get(uuid);
		return record != null && record.username != null ? record.username : uuid;
	}

	private List<String> friendsOf(String uuid) {
		List<String> out = new ArrayList<>();
		for (String friendship : data.friendships) {
			int bar = friendship.indexOf('|');
			String a = friendship.substring(0, bar);
			String b = friendship.substring(bar + 1);
			if (a.equals(uuid)) {
				out.add(b);
			} else if (b.equals(uuid)) {
				out.add(a);
			}
		}
		return out;
	}

	/** What {@code viewer} is allowed to see of {@code uuid}'s whereabouts - null activity means offline. */
	private Activity visibleActivity(String uuid) {
		UserRecord record = data.users.get(uuid);
		if (record != null && record.invisible) {
			return null;
		}
		if (sessions.hasLiveSession(uuid, SessionRegistry.KIND_GAME)) {
			Activity presence = gamePresence.get(uuid);
			return presence != null ? presence : new Activity("menu", null);
		}
		if (sessions.hasLiveSession(uuid, SessionRegistry.KIND_LAUNCHER)) {
			return new Activity("launcher", null);
		}
		return null;
	}

	private int unreadFrom(String reader, String other) {
		List<Message> conversation = data.conversations.get(pair(reader, other));
		if (conversation == null) {
			return 0;
		}
		long readUpTo = data.lastRead.getOrDefault(reader, Map.of()).getOrDefault(other, 0L);
		int count = 0;
		for (int i = conversation.size() - 1; i >= 0; i--) {
			Message message = conversation.get(i);
			if (message.time <= readUpTo) {
				break;
			}
			if (message.from.equals(other)) {
				count++;
			}
		}
		return count;
	}

	private FriendView friendView(String viewer, String uuid) {
		Activity activity = visibleActivity(uuid);
		UserRecord record = data.users.get(uuid);
		return new FriendView(uuid, nameOf(uuid), activity != null, activity, record != null ? record.lastSeen : 0,
				unreadFrom(viewer, uuid));
	}

	private void push(String uuid, String type, Object payload) {
		Deque<Event> queue = events.computeIfAbsent(uuid, u -> new ArrayDeque<>());
		queue.addLast(new Event(seq.incrementAndGet(), type, payload));
		while (queue.size() > MAX_EVENTS_PER_USER) {
			droppedUpTo.put(uuid, queue.removeFirst().seq());
		}
		eventsChanged.signalAll();
	}

	private static MessageView view(Message m) {
		return new MessageView(m.id, m.from, m.to, m.text, m.time, m.kind, m.waypoint);
	}

	// ---- Identity ----

	/** Records/refreshes the username for a freshly verified account so friends can find them by name. */
	void onAuthenticated(String uuid, String username) {
		lock.lock();
		try {
			UserRecord record = user(uuid);
			if (!username.equals(record.username)) {
				record.username = username;
				dirty = true;
			}
		} finally {
			lock.unlock();
		}
		refreshPresence(uuid);
	}

	/**
	 * Accepts a dashed/dashless uuid or a username. Names are matched against everyone who's ever
	 * signed in here first, then Mojang's public profile API (so you can add a friend who hasn't
	 * installed Velo yet - they'll see the request the first time they sign in).
	 */
	private PlayerRef resolve(String query) throws SocialException {
		if (query == null || query.isBlank()) {
			throw new SocialException(400, "Enter a username or UUID");
		}
		String trimmed = query.trim();
		String dashless = trimmed.replace("-", "").toLowerCase(Locale.ROOT);
		if (dashless.matches("[0-9a-f]{32}")) {
			lock.lock();
			try {
				return new PlayerRef(dashless, nameOf(dashless));
			} finally {
				lock.unlock();
			}
		}
		if (!trimmed.matches("[a-zA-Z0-9_]{1,16}")) {
			throw new SocialException(400, "That isn't a valid Minecraft username");
		}
		lock.lock();
		try {
			for (Map.Entry<String, UserRecord> entry : data.users.entrySet()) {
				if (trimmed.equalsIgnoreCase(entry.getValue().username)) {
					return new PlayerRef(entry.getKey(), entry.getValue().username);
				}
			}
		} finally {
			lock.unlock();
		}
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/"
					+ URLEncoder.encode(trimmed, StandardCharsets.UTF_8))).timeout(Duration.ofSeconds(8)).GET().build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 200) {
				JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
				String uuid = json.get("id").getAsString().toLowerCase(Locale.ROOT);
				String name = json.get("name").getAsString();
				lock.lock();
				try {
					UserRecord record = user(uuid);
					if (record.username == null) {
						record.username = name;
						dirty = true;
					}
				} finally {
					lock.unlock();
				}
				return new PlayerRef(uuid, name);
			}
		} catch (IOException | RuntimeException e) {
			throw new SocialException(502, "Couldn't look up that player right now - try their UUID instead");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SocialException(503, "Interrupted");
		}
		throw new SocialException(404, "No Minecraft player is called \"" + trimmed + "\"");
	}

	// ---- Queries ----

	State state(String me) {
		lock.lock();
		try {
			UserRecord self = user(me);
			List<FriendView> friends = new ArrayList<>();
			for (String friend : friendsOf(me)) {
				friends.add(friendView(me, friend));
			}
			friends.sort(Comparator.comparing((FriendView f) -> !f.online()).thenComparing(f -> f.username().toLowerCase(Locale.ROOT)));
			List<RequestView> incoming = new ArrayList<>();
			List<RequestView> outgoing = new ArrayList<>();
			for (Map.Entry<String, Long> request : data.requests.entrySet()) {
				int arrow = request.getKey().indexOf('>');
				String from = request.getKey().substring(0, arrow);
				String to = request.getKey().substring(arrow + 1);
				if (to.equals(me) && !hasBlocked(me, from)) {
					incoming.add(new RequestView(from, nameOf(from), request.getValue()));
				} else if (from.equals(me)) {
					outgoing.add(new RequestView(to, nameOf(to), request.getValue()));
				}
			}
			List<PlayerRef> blocked = new ArrayList<>();
			for (String uuid : data.blocks.getOrDefault(me, Set.of())) {
				blocked.add(new PlayerRef(uuid, nameOf(uuid)));
			}
			return new State(new Me(me, nameOf(me), self.invisible), friends, incoming, outgoing, blocked, seq.get());
		} finally {
			lock.unlock();
		}
	}

	List<MessageView> messages(String me, String other, long before) throws SocialException {
		lock.lock();
		try {
			if (!areFriends(me, other) && !data.conversations.containsKey(pair(me, other))) {
				throw new SocialException(403, "You aren't friends with that player");
			}
			List<Message> conversation = data.conversations.getOrDefault(pair(me, other), List.of());
			List<MessageView> out = new ArrayList<>();
			for (int i = conversation.size() - 1; i >= 0 && out.size() < 60; i--) {
				Message message = conversation.get(i);
				if (before > 0 && message.time >= before) {
					continue;
				}
				out.add(view(message));
			}
			java.util.Collections.reverse(out);
			return out;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Waits up to {@link #POLL_TIMEOUT_MILLIS} for events newer than {@code since}. A {@code since}
	 * older than what's still queued (or 0) answers {@code reset} so the client reloads {@link #state}
	 * instead of silently missing events.
	 */
	PollResult poll(String me, long since) throws InterruptedException {
		long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MILLIS;
		lock.lock();
		try {
			while (true) {
				Deque<Event> queue = events.getOrDefault(me, new ArrayDeque<>());
				if (since < startSeq || since > seq.get() || isTruncated(me, since)) {
					return new PollResult(true, seq.get(), List.of());
				}
				List<Event> fresh = new ArrayList<>();
				for (Event event : queue) {
					if (event.seq() > since) {
						fresh.add(event);
					}
				}
				if (!fresh.isEmpty()) {
					return new PollResult(false, fresh.get(fresh.size() - 1).seq(), fresh);
				}
				long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0) {
					return new PollResult(false, since, List.of());
				}
				eventsChanged.await(remaining, TimeUnit.MILLISECONDS);
			}
		} finally {
			lock.unlock();
		}
	}

	private final Map<String, Long> droppedUpTo = new HashMap<>();

	/** True when events {@code since} would have returned were already dropped from the bounded queue. */
	private boolean isTruncated(String me, long since) {
		Long dropped = droppedUpTo.get(me);
		return dropped != null && since < dropped;
	}

	// ---- Mutations ----

	PlayerRef sendRequest(String me, String target) throws SocialException {
		limit(me, "request", REQUEST_LIMIT);
		PlayerRef ref = resolve(target);
		lock.lock();
		try {
			if (ref.uuid().equals(me)) {
				throw new SocialException(400, "You can't add yourself");
			}
			if (areFriends(me, ref.uuid())) {
				throw new SocialException(409, ref.username() + " is already your friend");
			}
			if (hasBlocked(me, ref.uuid())) {
				throw new SocialException(409, "Unblock " + ref.username() + " first");
			}
			if (data.requests.containsKey(me + ">" + ref.uuid())) {
				throw new SocialException(409, "You already sent " + ref.username() + " a request");
			}
			if (data.requests.containsKey(ref.uuid() + ">" + me)) {
				// They asked first - adding them back just accepts.
				acceptLocked(me, ref.uuid());
				return ref;
			}
			Long declined = declinedAt.get(me + ">" + ref.uuid());
			if (declined != null && System.currentTimeMillis() - declined < DECLINE_COOLDOWN_MILLIS) {
				throw new SocialException(429, ref.username() + " declined your last request - try again in "
						+ RateLimiter.describeWait(declined + DECLINE_COOLDOWN_MILLIS - System.currentTimeMillis()));
			}
			long pending = data.requests.keySet().stream().filter(k -> k.startsWith(me + ">")).count();
			if (pending >= MAX_PENDING_OUTGOING) {
				throw new SocialException(429, "Too many pending requests - cancel some first");
			}
			if (friendsOf(me).size() >= MAX_FRIENDS) {
				throw new SocialException(409, "Your friends list is full");
			}
			user(ref.uuid());
			long now = System.currentTimeMillis();
			data.requests.put(me + ">" + ref.uuid(), now);
			dirty = true;
			// A player who blocked you never hears about it - the request just stays pending on your side.
			if (!hasBlocked(ref.uuid(), me)) {
				push(ref.uuid(), "friend_request", new RequestView(me, nameOf(me), now));
			}
			push(me, "state_changed", null);
			return ref;
		} finally {
			lock.unlock();
		}
	}

	void respond(String me, String from, boolean accept) throws SocialException {
		limit(me, "manage", MANAGE_LIMIT);
		lock.lock();
		try {
			if (!data.requests.containsKey(from + ">" + me)) {
				throw new SocialException(404, "That request no longer exists");
			}
			if (accept) {
				acceptLocked(me, from);
			} else {
				data.requests.remove(from + ">" + me);
				declinedAt.put(from + ">" + me, System.currentTimeMillis());
				dirty = true;
				push(me, "state_changed", null);
				push(from, "state_changed", null);
			}
		} finally {
			lock.unlock();
		}
	}

	private void acceptLocked(String me, String other) {
		data.requests.remove(other + ">" + me);
		data.requests.remove(me + ">" + other);
		data.friendships.add(pair(me, other));
		dirty = true;
		push(me, "friend_added", friendView(me, other));
		push(other, "friend_added", friendView(other, me));
		lastAnnouncedPresence.remove(me);
		lastAnnouncedPresence.remove(other);
	}

	void cancelRequest(String me, String to) throws SocialException {
		limit(me, "manage", MANAGE_LIMIT);
		lock.lock();
		try {
			if (data.requests.remove(me + ">" + to) != null) {
				dirty = true;
				push(me, "state_changed", null);
				push(to, "state_changed", null);
			}
		} finally {
			lock.unlock();
		}
	}

	void unfriend(String me, String other) throws SocialException {
		limit(me, "manage", MANAGE_LIMIT);
		lock.lock();
		try {
			if (data.friendships.remove(pair(me, other))) {
				dirty = true;
				push(me, "friend_removed", new PlayerRef(other, nameOf(other)));
				push(other, "friend_removed", new PlayerRef(me, nameOf(me)));
			}
		} finally {
			lock.unlock();
		}
	}

	void block(String me, String target) throws SocialException {
		limit(me, "manage", MANAGE_LIMIT);
		PlayerRef ref = resolve(target);
		lock.lock();
		try {
			if (ref.uuid().equals(me)) {
				throw new SocialException(400, "You can't block yourself");
			}
			data.blocks.computeIfAbsent(me, u -> new LinkedHashSet<>()).add(ref.uuid());
			boolean wereFriends = data.friendships.remove(pair(me, ref.uuid()));
			data.requests.remove(me + ">" + ref.uuid());
			data.requests.remove(ref.uuid() + ">" + me);
			dirty = true;
			push(me, "state_changed", null);
			if (wereFriends) {
				// From their side this looks exactly like an unfriend - a block is never announced.
				push(ref.uuid(), "friend_removed", new PlayerRef(me, nameOf(me)));
			}
		} finally {
			lock.unlock();
		}
	}

	void unblock(String me, String target) throws SocialException {
		limit(me, "manage", MANAGE_LIMIT);
		lock.lock();
		try {
			Set<String> blocked = data.blocks.get(me);
			if (blocked != null && blocked.remove(target)) {
				dirty = true;
				push(me, "state_changed", null);
			}
		} finally {
			lock.unlock();
		}
	}

	void setInvisible(String me, boolean invisible) throws SocialException {
		limit(me, "status", STATUS_LIMIT);
		lock.lock();
		try {
			UserRecord record = user(me);
			if (record.invisible != invisible) {
				record.invisible = invisible;
				dirty = true;
			}
			push(me, "state_changed", null);
		} finally {
			lock.unlock();
		}
		refreshPresence(me);
	}

	void setPresence(String me, String kind, String detail) throws SocialException {
		limit(me, "presence", PRESENCE_LIMIT);
		String safeKind = switch (kind == null ? "" : kind) {
			case "server", "singleplayer", "realm", "menu" -> kind;
			default -> "menu";
		};
		String safeDetail = detail == null || detail.isBlank() ? null : detail.length() > 80 ? detail.substring(0, 80) : detail;
		gamePresence.put(me, new Activity(safeKind, safeDetail));
		refreshPresence(me);
	}

	/** Re-evaluates what {@code uuid}'s friends should see and tells them only if it actually changed. */
	void refreshPresence(String uuid) {
		lock.lock();
		try {
			if (!sessions.hasLiveSession(uuid, SessionRegistry.KIND_GAME)) {
				gamePresence.remove(uuid);
			}
			UserRecord record = data.users.get(uuid);
			Activity activity = visibleActivity(uuid);
			if (record != null && (activity != null || sessions.hasLiveSession(uuid, SessionRegistry.KIND_GAME)
					|| sessions.hasLiveSession(uuid, SessionRegistry.KIND_LAUNCHER))) {
				record.lastSeen = System.currentTimeMillis();
			}
			String fingerprint = activity == null ? "offline" : activity.kind() + "\u0000" + activity.detail();
			if (fingerprint.equals(lastAnnouncedPresence.get(uuid))) {
				return;
			}
			String previous = lastAnnouncedPresence.put(uuid, fingerprint);
			boolean cameOnline = activity != null && (previous == null || previous.equals("offline"));
			for (String friend : friendsOf(uuid)) {
				FriendView view = friendView(friend, uuid);
				push(friend, cameOnline ? "friend_online" : "presence", view);
			}
		} finally {
			lock.unlock();
		}
	}

	MessageView sendMessage(String me, String to, String text) throws SocialException {
		if (text == null || text.isBlank()) {
			throw new SocialException(400, "Message is empty");
		}
		String trimmed = text.strip();
		if (trimmed.length() > MAX_MESSAGE_LENGTH) {
			throw new SocialException(400, "Messages are limited to " + MAX_MESSAGE_LENGTH + " characters");
		}
		limit(me, "message", MESSAGE_LIMIT);
		return storeMessage(me, to, "text", trimmed, null);
	}

	List<MessageView> shareWaypoint(String me, List<String> to, Map<String, Object> waypoint) throws SocialException {
		if (waypoint == null || !waypoint.containsKey("name")) {
			throw new SocialException(400, "Missing waypoint");
		}
		if (to.size() > 20) {
			throw new SocialException(400, "Share with at most 20 friends at once");
		}
		limit(me, "share", SHARE_LIMIT);
		Map<String, Object> clean = new LinkedHashMap<>();
		for (String key : List.of("name", "x", "y", "z", "dimension", "world", "color", "icon")) {
			Object value = waypoint.get(key);
			if (value instanceof String s && s.length() > 120) {
				value = s.substring(0, 120);
			}
			if (value != null) {
				clean.put(key, value);
			}
		}
		List<MessageView> sent = new ArrayList<>();
		for (String friend : to) {
			sent.add(storeMessage(me, friend, "waypoint", "Shared a waypoint: " + clean.get("name"), clean));
		}
		return sent;
	}

	private MessageView storeMessage(String me, String to, String kind, String text, Map<String, Object> waypoint) throws SocialException {
		lock.lock();
		try {
			if (!areFriends(me, to)) {
				throw new SocialException(403, "You can only message friends");
			}
			if (hasBlocked(to, me) || hasBlocked(me, to)) {
				throw new SocialException(403, "This player isn't accepting messages from you");
			}
			Message message = new Message();
			message.id = data.nextMessageId++;
			message.from = me;
			message.to = to;
			message.text = text;
			message.time = Math.max(System.currentTimeMillis(), lastMessageTime(me, to) + 1);
			message.kind = kind;
			message.waypoint = waypoint;
			List<Message> conversation = data.conversations.computeIfAbsent(pair(me, to), k -> new ArrayList<>());
			conversation.add(message);
			while (conversation.size() > MAX_MESSAGES_PER_CONVERSATION) {
				conversation.remove(0);
			}
			data.lastRead.computeIfAbsent(me, k -> new HashMap<>()).put(to, message.time);
			dirty = true;
			MessageView view = view(message);
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("message", view);
			payload.put("fromName", nameOf(me));
			push(to, "message", payload);
			push(me, "message", payload);
			return view;
		} finally {
			lock.unlock();
		}
	}

	private long lastMessageTime(String a, String b) {
		List<Message> conversation = data.conversations.get(pair(a, b));
		return conversation == null || conversation.isEmpty() ? 0 : conversation.get(conversation.size() - 1).time;
	}

	void markRead(String me, String other) {
		lock.lock();
		try {
			data.lastRead.computeIfAbsent(me, k -> new HashMap<>()).put(other, Math.max(System.currentTimeMillis(), lastMessageTime(me, other)));
			dirty = true;
			push(me, "read", new PlayerRef(other, nameOf(other)));
		} finally {
			lock.unlock();
		}
	}

	/** Test hook: the persisted data, for assertions. */
	Data dataForTests() {
		return data;
	}
}
