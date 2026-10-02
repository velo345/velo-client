package net.veloclient.velo.client.network;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.VeloClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The game's side of Velo friends &amp; messaging (server: {@code SocialService}). Rides on the
 * session {@link VeloServerClient} already authenticates, so it needs no login of its own: one
 * daemon thread long-polls {@code /v1/social/poll} (the server holds each request open until
 * something happens, so messages arrive within a second without hammering it), and every action
 * (send, accept, block...) is a fire-and-forget request whose result comes back as a future.
 *
 * <p>Everything the UI reads ({@link #snapshot()}, {@link #conversation}) is an immutable copy
 * replaced wholesale, so the render thread never sees a half-updated list.
 */
public final class SocialClient {

	private static final Gson GSON = new Gson();

	// ---- Wire shapes (match the server's records) ----

	public static final class Activity {
		public String kind;
		public String detail;
	}

	public static final class Friend {
		public String uuid;
		public String username;
		public boolean online;
		public Activity activity;
		public long lastSeen;
		public int unread;
	}

	public static final class Request {
		public String uuid;
		public String username;
		public long time;
	}

	public static final class PlayerRef {
		public String uuid;
		public String username;
	}

	public static final class Me {
		public String uuid;
		public String username;
		public boolean invisible;
	}

	public static final class Message {
		public long id;
		public String from;
		public String to;
		public String text;
		public long time;
		public String kind;
		public Map<String, Object> waypoint;

		public boolean isWaypoint() {
			return "waypoint".equals(kind) && waypoint != null;
		}
	}

	public static final class State {
		public Me me;
		public List<Friend> friends = List.of();
		public List<Request> incoming = List.of();
		public List<Request> outgoing = List.of();
		public List<PlayerRef> blocked = List.of();
		public long seq;
	}

	/** Something the notification popups / open screens should react to. */
	public record Event(String type, Friend friend, Request request, Message message, String fromName) {
	}

	private static volatile State state;
	private static volatile Thread pollThread;
	private static volatile boolean running;
	private static final Map<String, List<Message>> CONVERSATIONS = new ConcurrentHashMap<>();
	private static final List<Consumer<Event>> LISTENERS = new CopyOnWriteArrayList<>();
	private static volatile String lastPresenceFingerprint;
	private static volatile long lastPresenceSentMillis;

	private SocialClient() {
	}

	public static void addListener(Consumer<Event> listener) {
		LISTENERS.add(listener);
	}

	/** Latest known friends/requests state, or null before the first successful load. */
	public static State snapshot() {
		return state;
	}

	public static boolean isReady() {
		return state != null && (demo || VeloServerClient.sessionToken() != null);
	}

	private static volatile boolean demo;
	private static volatile String lastError;
	private static volatile String lastLoggedError;

	/** Why the friends list isn't loading right now (shown on the Friends screen), or null. */
	public static String lastError() {
		return lastError;
	}

	private static void fail(String message, Exception cause) {
		lastError = message;
		if (!message.equals(lastLoggedError)) {
			lastLoggedError = message;
			VeloClient.LOGGER.warn("Velo friends: {}", message, cause);
		}
	}

	/**
	 * Dev-only (screenshot tour / UI testing with {@code -Dvelo.demo=true}): shows {@code fake} as
	 * the friends state without any server. Never used in normal play.
	 */
	public static void loadDemo(State fake, Map<String, List<Message>> conversations) {
		demo = true;
		state = fake;
		CONVERSATIONS.putAll(conversations);
	}

	/** Dev-only: pretend an event arrived (drives the popups in the screenshot tour). */
	public static void publishDemo(Event event) {
		publish(event);
	}

	public static List<Message> conversation(String uuid) {
		return CONVERSATIONS.getOrDefault(uuid, List.of());
	}

	public static Friend friend(String uuid) {
		State current = state;
		if (current == null || uuid == null) {
			return null;
		}
		for (Friend friend : current.friends) {
			if (friend.uuid.equals(uuid)) {
				return friend;
			}
		}
		return null;
	}

	public static int totalUnread() {
		State current = state;
		if (current == null) {
			return 0;
		}
		int total = 0;
		for (Friend friend : current.friends) {
			total += friend.unread;
		}
		return total;
	}

	static synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		pollThread = Thread.ofPlatform().daemon().name("velo-social").start(SocialClient::pollLoop);
	}

	static synchronized void stop() {
		running = false;
		Thread thread = pollThread;
		pollThread = null;
		if (thread != null) {
			thread.interrupt();
		}
		state = null;
		CONVERSATIONS.clear();
		lastPresenceFingerprint = null;
	}

	private static void pollLoop() {
		long since = 0;
		while (running) {
			try {
				if (demo) {
					Thread.sleep(5000);
					continue;
				}
				String token = VeloServerClient.sessionToken();
				String base = VeloServerClient.serverBaseUrl();
				if (token == null || base == null) {
					state = null;
					since = 0;
					Thread.sleep(2000);
					continue;
				}
				if (state == null || since == 0) {
					reloadState();
					since = state.seq;
					if (lastError != null || lastLoggedError == null) {
						VeloClient.LOGGER.info("Velo friends: connected as {} ({} friends, {} requests)",
								state.me != null ? state.me.username : "?", state.friends.size(), state.incoming.size());
						lastLoggedError = "";
					}
					lastError = null;
					publish(new Event("state", null, null, null, null));
				}
				JsonObject result = get("/v1/social/poll?since=" + since, Duration.ofSeconds(40));
				if (result.has("reset") && result.get("reset").getAsBoolean()) {
					since = 0;
					continue;
				}
				since = result.get("seq").getAsLong();
				boolean reload = false;
				for (JsonElement element : result.getAsJsonArray("events")) {
					reload |= handleEvent(element.getAsJsonObject());
				}
				if (reload) {
					reloadState();
					publish(new Event("state", null, null, null, null));
				}
			} catch (InterruptedException e) {
				if (!running) {
					return;
				}
			} catch (VeloServerClient.HttpStatusException e) {
				if (e.status == 401) {
					VeloServerClient.invalidateSession(VeloServerClient.sessionToken());
				}
				fail("server answered HTTP " + e.status + (e.status == 404 ? " - the Velo server needs updating" : ""), e);
				since = 0;
				sleepQuietly(5000);
			} catch (Exception e) {
				fail(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
				since = 0;
				sleepQuietly(5000);
			}
		}
	}

	/** Cuts a retry wait short (Friends screen "Retry"). */
	static void wake() {
		Thread thread = pollThread;
		if (thread != null && state == null) {
			thread.interrupt();
		}
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ignored) {
			// Woken early (retry) or stopping - the loop checks which.
		}
	}

	/** @return whether the friends/requests list needs reloading because of this event. */
	private static boolean handleEvent(JsonObject event) {
		String type = event.get("type").getAsString();
		JsonElement data = event.get("data");
		switch (type) {
			case "message" -> {
				JsonObject payload = data.getAsJsonObject();
				Message message = GSON.fromJson(payload.get("message"), Message.class);
				String fromName = payload.has("fromName") ? payload.get("fromName").getAsString() : message.from;
				String other = message.from.equals(myUuid()) ? message.to : message.from;
				appendMessage(other, message);
				publish(new Event("message", friend(other), null, message, fromName));
				return true;
			}
			case "friend_request" -> {
				publish(new Event("friend_request", null, GSON.fromJson(data, Request.class), null, null));
				return true;
			}
			case "friend_online", "presence", "friend_added" -> {
				Friend friend = GSON.fromJson(data, Friend.class);
				replaceFriend(friend);
				publish(new Event(type, friend, null, null, null));
				return type.equals("friend_added");
			}
			case "friend_removed" -> {
				PlayerRef ref = GSON.fromJson(data, PlayerRef.class);
				CONVERSATIONS.remove(ref.uuid);
				return true;
			}
			default -> {
				// state_changed / read / anything newer than this client: just resync.
				return true;
			}
		}
	}

	private static void replaceFriend(Friend updated) {
		State current = state;
		if (current == null) {
			return;
		}
		List<Friend> friends = new ArrayList<>(current.friends);
		boolean found = false;
		for (int i = 0; i < friends.size(); i++) {
			if (friends.get(i).uuid.equals(updated.uuid)) {
				friends.set(i, updated);
				found = true;
			}
		}
		if (!found) {
			friends.add(updated);
		}
		State copy = copyOf(current);
		copy.friends = List.copyOf(friends);
		state = copy;
	}

	private static State copyOf(State source) {
		State copy = new State();
		copy.me = source.me;
		copy.friends = source.friends;
		copy.incoming = source.incoming;
		copy.outgoing = source.outgoing;
		copy.blocked = source.blocked;
		copy.seq = source.seq;
		return copy;
	}

	private static void appendMessage(String other, Message message) {
		CONVERSATIONS.compute(other, (key, existing) -> {
			List<Message> list = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
			if (list.stream().noneMatch(m -> m.id == message.id)) {
				list.add(message);
			}
			return List.copyOf(list);
		});
	}

	private static String myUuid() {
		State current = state;
		return current != null && current.me != null ? current.me.uuid : VeloServerClient.authenticatedUuid();
	}

	private static void reloadState() throws Exception {
		State loaded = GSON.fromJson(get("/v1/social/state", Duration.ofSeconds(15)), State.class);
		if (loaded.friends == null) {
			loaded.friends = List.of();
		}
		if (loaded.incoming == null) {
			loaded.incoming = List.of();
		}
		if (loaded.outgoing == null) {
			loaded.outgoing = List.of();
		}
		if (loaded.blocked == null) {
			loaded.blocked = List.of();
		}
		state = loaded;
		SocialStatusCache.remember(loaded.me != null && loaded.me.invisible);
	}

	private static void publish(Event event) {
		for (Consumer<Event> listener : LISTENERS) {
			try {
				listener.accept(event);
			} catch (RuntimeException e) {
				VeloClient.LOGGER.error("Velo social listener failed", e);
			}
		}
	}

	// ---- Actions (all async; the future completes with an error message, or null on success) ----

	public static CompletableFuture<String> sendRequest(String target) {
		JsonObject body = new JsonObject();
		body.addProperty("target", target);
		return action("/v1/social/request", body);
	}

	public static CompletableFuture<String> respond(String uuid, boolean accept) {
		JsonObject body = new JsonObject();
		body.addProperty("uuid", uuid);
		body.addProperty("accept", accept);
		return action("/v1/social/respond", body);
	}

	public static CompletableFuture<String> cancelRequest(String uuid) {
		return action("/v1/social/cancel", uuidBody(uuid));
	}

	public static CompletableFuture<String> unfriend(String uuid) {
		return action("/v1/social/unfriend", uuidBody(uuid));
	}

	public static CompletableFuture<String> block(String uuid) {
		JsonObject body = new JsonObject();
		body.addProperty("target", uuid);
		return action("/v1/social/block", body);
	}

	public static CompletableFuture<String> unblock(String uuid) {
		return action("/v1/social/unblock", uuidBody(uuid));
	}

	public static CompletableFuture<String> setInvisible(boolean invisible) {
		SocialStatusCache.remember(invisible);
		State current = state;
		if (current != null && current.me != null) {
			State copy = copyOf(current);
			Me me = new Me();
			me.uuid = current.me.uuid;
			me.username = current.me.username;
			me.invisible = invisible;
			copy.me = me;
			state = copy;
		}
		JsonObject body = new JsonObject();
		body.addProperty("invisible", invisible);
		return action("/v1/social/status", body);
	}

	public static CompletableFuture<String> sendMessage(String to, String text) {
		JsonObject body = new JsonObject();
		body.addProperty("to", to);
		body.addProperty("text", text);
		return action("/v1/social/message", body);
	}

	public static CompletableFuture<String> markRead(String uuid) {
		Friend friend = friend(uuid);
		if (friend != null && friend.unread > 0) {
			Friend copy = GSON.fromJson(GSON.toJson(friend), Friend.class);
			copy.unread = 0;
			replaceFriend(copy);
		}
		return action("/v1/social/read", uuidBody(uuid));
	}

	public static CompletableFuture<String> shareWaypoint(List<String> to, Map<String, Object> waypoint) {
		JsonObject body = new JsonObject();
		body.add("to", GSON.toJsonTree(to));
		body.add("waypoint", GSON.toJsonTree(waypoint));
		return action("/v1/social/share-waypoint", body);
	}

	/** Loads the latest messages with {@code uuid} into {@link #conversation}. */
	public static CompletableFuture<String> loadConversation(String uuid) {
		if (demo) {
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				JsonObject result = get("/v1/social/messages?with=" + URLEncoder.encode(uuid, StandardCharsets.UTF_8), Duration.ofSeconds(15));
				List<Message> messages = new ArrayList<>();
				for (JsonElement element : result.getAsJsonArray("messages")) {
					messages.add(GSON.fromJson(element, Message.class));
				}
				CONVERSATIONS.put(uuid, List.copyOf(messages));
				return null;
			} catch (Exception e) {
				return errorText(e);
			}
		});
	}

	/** Reports where this player is (server/singleplayer/realm/menu) - sent on change and refreshed every minute. */
	public static void reportPresence(String kind, String detail) {
		String fingerprint = kind + "\u0000" + detail;
		long now = System.currentTimeMillis();
		if (fingerprint.equals(lastPresenceFingerprint) && now - lastPresenceSentMillis < 60_000) {
			return;
		}
		if (VeloServerClient.sessionToken() == null) {
			return;
		}
		lastPresenceFingerprint = fingerprint;
		lastPresenceSentMillis = now;
		JsonObject body = new JsonObject();
		body.addProperty("kind", kind);
		if (detail != null) {
			body.addProperty("detail", detail);
		}
		action("/v1/social/presence", body).thenAccept(error -> {
			if (error != null) {
				lastPresenceFingerprint = null;
			}
		});
	}

	private static JsonObject uuidBody(String uuid) {
		JsonObject body = new JsonObject();
		body.addProperty("uuid", uuid);
		return body;
	}

	private static CompletableFuture<String> action(String path, JsonObject body) {
		if (demo) {
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				post(path, body);
				return null;
			} catch (Exception e) {
				return errorText(e);
			}
		});
	}

	private static String errorText(Exception e) {
		if (e instanceof VeloServerClient.HttpStatusException status) {
			String message = status.getMessage();
			int brace = message.indexOf('{');
			if (brace >= 0) {
				try {
					JsonObject json = JsonParser.parseString(message.substring(brace)).getAsJsonObject();
					if (json.has("error")) {
						return json.get("error").getAsString();
					}
				} catch (RuntimeException ignored) {
					// Not a JSON error body (e.g. a proxy's HTML 502 page).
				}
			}
			return status.status >= 500 ? "The Velo server is unavailable right now" : "Request failed (HTTP " + status.status + ")";
		}
		return VeloServerClient.sessionToken() == null ? "Not connected to the Velo server" : "Couldn't reach the Velo server";
	}

	private static JsonObject get(String path, Duration timeout) throws Exception {
		String token = VeloServerClient.sessionToken();
		String base = VeloServerClient.serverBaseUrl();
		if (token == null || base == null) {
			throw new java.io.IOException("Not connected");
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/json")
				.timeout(timeout)
				.GET()
				.build();
		return send(request);
	}

	private static JsonObject post(String path, JsonObject body) throws Exception {
		String token = VeloServerClient.sessionToken();
		String base = VeloServerClient.serverBaseUrl();
		if (token == null || base == null) {
			throw new java.io.IOException("Not connected");
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		return send(request);
	}

	private static JsonObject send(HttpRequest request) throws Exception {
		HttpResponse<String> response = VeloServerClient.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new VeloServerClient.HttpStatusException(response.statusCode(), response.body());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}
}
