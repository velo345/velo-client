package net.veloclient.launcher.social;

import com.google.gson.Gson;
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

	static String baseUrl() {
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
			default -> {
				return true;
			}
		}
	}

	private static void reloadState() throws Exception {
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
