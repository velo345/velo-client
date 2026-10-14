package net.veloclient.launcher.social;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.VeloPaths;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The launcher's connection to the Velo server's friends &amp; messaging (server: {@code
 * SocialService}; the game has its own copy of this, {@code SocialClient}). The launcher signs in
 * as its own "launcher" session with the same Mojang join/hasJoined handshake the game uses - the
 * access token only ever goes to Mojang - so friends see you as "In the launcher" when you're not
 * playing, and the Friends tab works without the game running.
 *
 * <p>Listeners are always called on the JavaFX thread.
 */
public final class LauncherSocial {

	public static final String DEFAULT_SERVER_URL = "https://client.asteriasmp.net";

	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

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

	public record Event(String type, Friend friend, Request request, Message message, String fromName) {
	}

	private static volatile MinecraftSession session;
	private static volatile String token;
	private static volatile State state;
	private static volatile String status = "Not signed in";
	private static volatile Thread worker;
	private static volatile boolean running;
	private static final Map<String, List<Message>> CONVERSATIONS = new ConcurrentHashMap<>();
	private static final List<java.util.function.Consumer<Event>> LISTENERS = new CopyOnWriteArrayList<>();

	private LauncherSocial() {
	}

	public static void addListener(java.util.function.Consumer<Event> listener) {
		LISTENERS.add(listener);
	}

	public static void removeListener(java.util.function.Consumer<Event> listener) {
		LISTENERS.remove(listener);
	}

	private static volatile boolean demo;

	/** The launcher's Velo server session (null until signed in and verified). */
	public static String sessionToken() {
		return token;
	}

	public static boolean isDemo() {
		return demo;
	}

	/** Dev-only ({@code -Dvelo.launcherDemo=<png>}): shows {@code fake} without any server, for UI screenshots. */
	public static void loadDemo(State fake, Map<String, List<Message>> conversations) {
		demo = true;
		state = fake;
		CONVERSATIONS.putAll(conversations);
		status = "Demo";
	}

	public static State snapshot() {
		return state;
	}

	public static String status() {
		return status;
	}

	public static List<Message> conversation(String uuid) {
		return CONVERSATIONS.getOrDefault(uuid, List.of());
	}

	public static Friend friend(String uuid) {
		State current = state;
		if (current == null || uuid == null) {
			return null;
		}
		return current.friends.stream().filter(f -> f.uuid.equals(uuid)).findFirst().orElse(null);
	}

	public static int badgeCount() {
		State current = state;
		if (current == null) {
			return 0;
		}
		return current.incoming.size() + current.friends.stream().mapToInt(f -> f.unread).sum();
	}

	/** (Re)starts for {@code newSession} - call on sign-in/account switch; null signs out. */
	public static synchronized void setSession(MinecraftSession newSession) {
		if (demo) {
			session = newSession;
			return;
		}
		boolean changed = newSession == null || session == null || !newSession.uuid().equals(session.uuid());
		session = newSession;
		if (newSession != null && !changed) {
			return;
		}
		token = null;
		state = null;
		CONVERSATIONS.clear();
		if (newSession == null) {
			status = "Not signed in";
			stopWorker();
			publish(new Event("state", null, null, null, null));
			return;
		}
		if (worker == null) {
			running = true;
			worker = Thread.ofPlatform().daemon().name("velo-launcher-social").start(LauncherSocial::loop);
		}
	}

	private static void stopWorker() {
		running = false;
		Thread thread = worker;
		worker = null;
		if (thread != null) {
			thread.interrupt();
		}
	}

	public static synchronized void shutdown() {
		String current = token;
		stopWorker();
		if (current != null) {
			try {
				JsonObject body = new JsonObject();
				body.addProperty("sessionToken", current);
				postJson("/v1/session/end", body, null);
			} catch (Exception ignored) {
				// Expires on its own.
			}
		}
	}

	public static String baseUrl() {
		// Dev-only: -Dvelo.serverUrl=http://localhost:8787 points the launcher at a test server.
		String override = System.getProperty("velo.serverUrl");
		if (override != null && !override.isBlank()) {
			return override.replaceAll("/+$", "");
		}
		Path config = VeloPaths.config().resolve("network.json");
		try {
			if (Files.exists(config)) {
				JsonObject json = JsonParser.parseString(Files.readString(config, StandardCharsets.UTF_8)).getAsJsonObject();
				if (json.has("serverUrl") && !json.get("serverUrl").isJsonNull()) {
					String url = json.get("serverUrl").getAsString().trim();
					if (url.isEmpty()) {
						return null;
					}
					return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
				}
			}
		} catch (Exception ignored) {
			// Fall back to the default below.
		}
		return DEFAULT_SERVER_URL;
	}

	private static void loop() {
		long since = 0;
		long lastHeartbeat = 0;
		while (running) {
			try {
				if (baseUrl() == null) {
					status = "Velo Network is disabled in network.json";
					Thread.sleep(10_000);
					continue;
				}
				if (token == null) {
					status = "Connecting...";
					authenticate();
					since = 0;
					status = "Connected";
				}
				if (System.currentTimeMillis() - lastHeartbeat > 30_000) {
					JsonObject body = new JsonObject();
					body.addProperty("sessionToken", token);
					postJson("/v1/heartbeat", body, null);
					lastHeartbeat = System.currentTimeMillis();
				}
				if (state == null || since == 0) {
					reloadState();
					since = state.seq;
					publish(new Event("state", null, null, null, null));
				}
				JsonObject result = getJson("/v1/social/poll?since=" + since, Duration.ofSeconds(40));
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
				return;
			} catch (HttpStatus e) {
				if (e.code == 401) {
					token = null;
				}
				status = e.code >= 500 ? "The Velo server is down (HTTP " + e.code + ") - retrying" : "Velo server error (HTTP " + e.code + ")";
				state = null;
				publish(new Event("state", null, null, null, null));
				sleep(e.code >= 500 ? 15_000 : 5_000);
				since = 0;
			} catch (Exception e) {
				status = "Can't reach the Velo server - retrying";
				LauncherLog.info("Velo social: " + e);
				state = null;
				publish(new Event("state", null, null, null, null));
				sleep(15_000);
				since = 0;
			}
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			running = false;
		}
	}

	private static void authenticate() throws Exception {
		MinecraftSession current = session;
		if (current == null) {
			throw new IllegalStateException("not signed in");
		}
		if (current.isAccessTokenExpired()) {
			status = "Your sign-in expired - restart the launcher or sign in again";
			throw new IllegalStateException("access token expired");
		}
		String uuid = current.uuid().replace("-", "").toLowerCase();
		JsonObject challenge = new JsonObject();
		challenge.addProperty("uuid", uuid);
		challenge.addProperty("username", current.username());
		challenge.addProperty("kind", "launcher");
		String serverId = postJson("/v1/session/challenge", challenge, null).get("serverId").getAsString();

		JsonObject join = new JsonObject();
		join.addProperty("accessToken", current.minecraftAccessToken());
		join.addProperty("selectedProfile", uuid);
		join.addProperty("serverId", serverId);
		HttpResponse<String> joined = HTTP.send(HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/join"))
				.header("Content-Type", "application/json").timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(join.toString())).build(), HttpResponse.BodyHandlers.ofString());
		if (joined.statusCode() / 100 != 2) {
			throw new HttpStatus(joined.statusCode(), "Mojang rejected the sign-in");
		}

		JsonObject verify = new JsonObject();
		verify.addProperty("uuid", uuid);
		verify.addProperty("serverId", serverId);
		verify.addProperty("kind", "launcher");
		token = postJson("/v1/session/verify", verify, null).get("sessionToken").getAsString();
	}

	private static boolean handleEvent(JsonObject event) {
		String type = event.get("type").getAsString();
		JsonElement data = event.get("data");
		switch (type) {
			case "message" -> {
				JsonObject payload = data.getAsJsonObject();
				Message message = GSON.fromJson(payload.get("message"), Message.class);
				String fromName = payload.has("fromName") ? payload.get("fromName").getAsString() : message.from;
				String me = state != null && state.me != null ? state.me.uuid : "";
				String other = message.from.equals(me) ? message.to : message.from;
				CONVERSATIONS.compute(other, (k, list) -> {
					List<Message> copy = list == null ? new ArrayList<>() : new ArrayList<>(list);
					if (copy.stream().noneMatch(m -> m.id == message.id)) {
						copy.add(message);
					}
					return List.copyOf(copy);
				});
				publish(new Event("message", friend(other), null, message, fromName));
				return true;
			}
			case "friend_request" -> {
				publish(new Event(type, null, GSON.fromJson(data, Request.class), null, null));
				return true;
			}
			case "friend_online", "presence", "friend_added" -> {
				publish(new Event(type, GSON.fromJson(data, Friend.class), null, null, null));
				return true;
			}
			case "party", "party_invite", "party_chat", "party_launch", "party_ping", "party_waypoint" -> {
				onPartyEvent(type, data == null || !data.isJsonObject() ? new JsonObject() : data.getAsJsonObject());
				return false;
			}
			default -> {
				return true;
			}
		}
	}

	// ---- Party ----

	/** A party change: {@code type} is the server event ("party", "party_invite", "party_chat", "party_launch"...). */
	public record PartyEvent(String type, JsonObject data) {
	}

	private static final List<java.util.function.Consumer<PartyEvent>> PARTY_LISTENERS = new CopyOnWriteArrayList<>();
	private static volatile JsonObject party;
	private static volatile List<JsonObject> partyInvites = List.of();

	public static void addPartyListener(java.util.function.Consumer<PartyEvent> listener) {
		PARTY_LISTENERS.add(listener);
	}

	public static void removePartyListener(java.util.function.Consumer<PartyEvent> listener) {
		PARTY_LISTENERS.remove(listener);
	}

	/** The party you're in (id, leader, members, invited, chat, sync, launch), or null. */
	public static JsonObject party() {
		return party;
	}

	public static List<JsonObject> partyInvites() {
		return partyInvites;
	}

	public static String myUuid() {
		State current = state;
		return current != null && current.me != null ? current.me.uuid : null;
	}

	public static boolean isPartyLeader() {
		JsonObject p = party;
		String me = myUuid();
		return p != null && me != null && me.equals(p.get("leader").getAsString());
	}

	private static void reloadParty() {
		try {
			applyPartyState(getJson("/v1/party/state", Duration.ofSeconds(15)));
		} catch (Exception e) {
			// Older server without parties - the feature just stays empty.
		}
	}

	private static void applyPartyState(JsonObject stateJson) {
		party = stateJson.has("party") && stateJson.get("party").isJsonObject() ? stateJson.getAsJsonObject("party") : null;
		if (stateJson.has("invites") && stateJson.get("invites").isJsonArray()) {
			List<JsonObject> invites = new ArrayList<>();
			for (JsonElement e : stateJson.getAsJsonArray("invites")) {
				invites.add(e.getAsJsonObject());
			}
			partyInvites = List.copyOf(invites);
		}
	}

	private static void onPartyEvent(String type, JsonObject data) {
		switch (type) {
			case "party" -> {
				party = data.has("party") && data.get("party").isJsonObject() ? data.getAsJsonObject("party") : null;
				if (party != null) {
					String id = party.get("id").getAsString();
					partyInvites = partyInvites.stream().filter(i -> !id.equals(i.get("party").getAsString())).toList();
				}
			}
			case "party_invite" -> {
				List<JsonObject> invites = new ArrayList<>(partyInvites);
				invites.removeIf(i -> i.get("party").getAsString().equals(data.get("party").getAsString()));
				invites.add(data);
				partyInvites = List.copyOf(invites);
			}
			case "party_chat" -> {
				JsonObject current = party;
				if (current != null && current.has("chat")) {
					JsonArray chat = current.getAsJsonArray("chat");
					chat.add(data);
				}
			}
			default -> {
			}
		}
		Platform.runLater(() -> {
			for (var listener : PARTY_LISTENERS) {
				try {
					listener.accept(new PartyEvent(type, data));
				} catch (RuntimeException e) {
					LauncherLog.info("Party listener failed: " + e);
				}
			}
		});
	}

	private static CompletableFuture<String> partyAction(String path, JsonObject body) {
		return action(path, body).thenApply(error -> {
			reloadParty();
			onPartyEvent("party_refresh", new JsonObject());
			return error;
		});
	}

	public static CompletableFuture<String> partyCreate() {
		return partyAction("/v1/party/create", new JsonObject());
	}

	public static CompletableFuture<String> partyInvite(String target) {
		JsonObject body = new JsonObject();
		body.addProperty("target", target);
		return partyAction("/v1/party/invite", body);
	}

	public static CompletableFuture<String> partyRespond(String partyId, boolean accept) {
		JsonObject body = new JsonObject();
		body.addProperty("party", partyId);
		body.addProperty("accept", accept);
		return partyAction("/v1/party/respond", body);
	}

	public static CompletableFuture<String> partyLeave() {
		return partyAction("/v1/party/leave", new JsonObject());
	}

	public static CompletableFuture<String> partyKick(String uuid) {
		return partyAction("/v1/party/kick", uuidBody(uuid));
	}

	public static CompletableFuture<String> partyTransfer(String uuid) {
		return partyAction("/v1/party/transfer", uuidBody(uuid));
	}

	public static CompletableFuture<String> partyChat(String text) {
		JsonObject body = new JsonObject();
		body.addProperty("text", text);
		return action("/v1/party/chat", body);
	}

	/** Leader: share (or with null, stop sharing) your mod profile. */
	public static CompletableFuture<String> partySync(JsonObject sync) {
		JsonObject body = new JsonObject();
		body.add("sync", sync);
		return partyAction("/v1/party/sync", body);
	}

	/** Leader: tell everyone you're launching (and where to). */
	public static CompletableFuture<String> partyLaunch(String server, String mcVersion) {
		JsonObject body = new JsonObject();
		body.addProperty("server", server == null ? "" : server);
		body.addProperty("mcVersion", mcVersion);
		return action("/v1/party/launch", body);
	}

	private static void reloadState() throws Exception {
		reloadParty();
		State loaded = GSON.fromJson(getJson("/v1/social/state", Duration.ofSeconds(15)), State.class);
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
		rememberInvisible(loaded.me != null && loaded.me.invisible);
	}

	/** Mirrors "appear offline" into config/social.json - the same file the game reads. */
	private static void rememberInvisible(boolean invisible) {
		try {
			Files.writeString(VeloPaths.config().resolve("social.json"), "{\n  \"invisible\": " + invisible + "\n}\n", StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Only a display cache; the server keeps the real value.
		}
	}

	private static void publish(Event event) {
		Platform.runLater(() -> {
			for (var listener : LISTENERS) {
				try {
					listener.accept(event);
				} catch (RuntimeException e) {
					LauncherLog.info("Velo social listener failed: " + e);
				}
			}
		});
	}

	// ---- Actions: futures complete with an error message, or null on success ----

	public static CompletableFuture<String> sendRequest(String target) {
		JsonObject body = new JsonObject();
		body.addProperty("target", target);
		return action("/v1/social/request", body);
	}

	public static CompletableFuture<String> respond(String uuid, boolean accept) {
		JsonObject body = uuidBody(uuid);
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
		JsonObject body = new JsonObject();
		body.addProperty("invisible", invisible);
		rememberInvisible(invisible);
		return action("/v1/social/status", body);
	}

	public static CompletableFuture<String> sendMessage(String to, String text) {
		JsonObject body = new JsonObject();
		body.addProperty("to", to);
		body.addProperty("text", text);
		return action("/v1/social/message", body);
	}

	public static CompletableFuture<String> markRead(String uuid) {
		return action("/v1/social/read", uuidBody(uuid));
	}

	public static CompletableFuture<String> loadConversation(String uuid) {
		if (demo) {
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				JsonObject result = getJson("/v1/social/messages?with=" + URLEncoder.encode(uuid, StandardCharsets.UTF_8), Duration.ofSeconds(15));
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
				if (token == null) {
					return "Not connected to the Velo server";
				}
				postJson(path, body, token);
				return null;
			} catch (Exception e) {
				return errorText(e);
			}
		});
	}

	private static String errorText(Exception e) {
		if (e instanceof HttpStatus status) {
			try {
				JsonObject json = JsonParser.parseString(status.getMessage()).getAsJsonObject();
				if (json.has("error")) {
					return json.get("error").getAsString();
				}
			} catch (RuntimeException ignored) {
				// HTML error page from a proxy.
			}
			return status.code >= 500 ? "The Velo server is unavailable right now" : "Request failed (HTTP " + status.code + ")";
		}
		return "Couldn't reach the Velo server";
	}

	static final class HttpStatus extends Exception {
		final int code;

		HttpStatus(int code, String body) {
			super(body);
			this.code = code;
		}
	}

	private static JsonObject getJson(String path, Duration timeout) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
				.header("Authorization", "Bearer " + token).header("Accept", "application/json")
				.timeout(timeout).GET().build();
		return send(request);
	}

	private static JsonObject postJson(String path, JsonObject body, String bearer) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
				.header("Content-Type", "application/json").header("Accept", "application/json")
				.timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofString(body.toString()));
		if (bearer != null) {
			builder.header("Authorization", "Bearer " + bearer);
		}
		return send(builder.build());
	}

	private static JsonObject send(HttpRequest request) throws Exception {
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new HttpStatus(response.statusCode(), response.body());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}
}
