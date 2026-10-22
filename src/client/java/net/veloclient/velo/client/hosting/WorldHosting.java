package net.veloclient.velo.client.hosting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.network.VeloServerClient;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.config.VeloPaths;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Velo+ World Hosting in the game: your singleplayer world for your friends, from anywhere, no port forwarding.
 *
 * <p><b>Hosting</b> opens the world to LAN on a local port, makes a backup of it first, registers it with the Velo
 * server and keeps a control connection to the relay. Each guest the relay lets in becomes one extra connection from
 * here to the relay, piped to the LAN port - so the guest talks to your world as if they were in your living room.
 *
 * <p><b>Joining</b> (free, no Velo+ needed): asks the server for a ticket, opens a local port on 127.0.0.1 that
 * pipes to the relay, and connects Minecraft to it.
 *
 * <p>Managing members, rules and players runs on your own server with commands; who may connect at all is checked by
 * the Velo server before it hands out a ticket.
 */
public final class WorldHosting {

	/** What the screens show about the world you host. */
	/** {@code visibility}: "friends", "public" (anyone joins, listed in Discover) or "request" (listed, they ask first). */
	public record Hosted(String id, String name, boolean friendsCanJoin, boolean online, int guests, String code, List<String[]> allowed,
			String visibility, int maxPlayers) {
	}

	/** A public world in Discover; {@code ask}: you request to join instead of joining straight away. */
	public record Listed(String id, String name, String description, String hostUuid, String hostName, String version, int guests,
			int maxPlayers, boolean ask) {
	}

	/** A world you can join. */
	public record Joinable(String id, String name, String hostUuid, String hostName, String version, int guests) {
	}

	/** Someone knocked with your code. */
	public record Knock(String uuid, String username, long at) {
	}

	/** This Velo Client's version - the server turns away World Hosting from versions before the relay fixes. */
	private static final String CLIENT = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("velo-client")
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString().replaceAll("\\+.*", "")).orElse("0");

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
	private static final int KEEP_BACKUPS = 5;

	private static volatile Hosted hosted;
	private static volatile String hostKey;
	private static volatile String relayHost;
	private static volatile int relayPort;
	private static volatile int lanPort;
	private static volatile Socket control;
	private static volatile boolean hosting;
	private static final List<Knock> KNOCKS = new java.util.concurrent.CopyOnWriteArrayList<>();
	private static volatile String lastStatus = "";

	private WorldHosting() {
	}

	public static boolean hosting() {
		return hosting;
	}

	public static Hosted hosted() {
		return hosted;
	}

	public static String status() {
		return lastStatus;
	}

	public static List<Knock> knocks() {
		return List.copyOf(KNOCKS);
	}

	/** Whether there's a singleplayer world running that could be hosted. */
	public static boolean canHost() {
		return HostCompat.server() != null;
	}

	public static List<String> players() {
		return HostCompat.players();
	}

	// ---- REST ----

	private static HttpRequest.Builder request(String path) {
		String base = VeloServerClient.serverBaseUrl();
		String token = VeloServerClient.sessionToken();
		if (base == null || token == null) {
			throw new IllegalStateException("Not connected to the Velo Network yet");
		}
		return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token);
	}

	private static JsonObject call(String path, JsonObject body) throws Exception {
		HttpRequest.Builder builder = request(path);
		HttpRequest request = body == null ? builder.GET().build()
				: builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
		HttpResponse<String> response = VeloServerClient.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		JsonObject json = response.body().isBlank() ? new JsonObject() : JsonParser.parseString(response.body()).getAsJsonObject();
		if (response.statusCode() / 100 != 2) {
			throw new IOException(json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode());
		}
		return json;
	}

	private static Hosted parse(JsonObject view) {
		List<String[]> allowed = new ArrayList<>();
		if (view.has("allowed")) {
			for (JsonElement e : view.getAsJsonArray("allowed")) {
				JsonObject o = e.getAsJsonObject();
				allowed.add(new String[] {o.get("uuid").getAsString(), o.get("username").getAsString()});
			}
		}
		return new Hosted(view.get("id").getAsString(), view.get("name").getAsString(), view.get("friendsCanJoin").getAsBoolean(),
				view.get("online").getAsBoolean(), view.get("guests").getAsInt(),
				view.has("code") && !view.get("code").isJsonNull() ? view.get("code").getAsString() : null, allowed,
				view.has("visibility") ? view.get("visibility").getAsString() : "friends", view.has("maxPlayers") ? view.get("maxPlayers").getAsInt() : 10);
	}

	/** Runs {@code task} off the render thread; {@code done} gets null or the error, on the render thread. */
	private static void async(Task task, Consumer<String> done) {
		Thread.ofVirtual().name("velo-world-hosting").start(() -> {
			String error = null;
			try {
				task.run();
			} catch (Exception e) {
				error = e.getMessage() == null ? e.toString() : e.getMessage();
			}
			String result = error;
			MinecraftClient.getInstance().execute(() -> done.accept(result));
		});
	}

	@FunctionalInterface
	private interface Task {
		void run() throws Exception;
	}

	// ---- Hosting ----

	/** Opens the running singleplayer world to your friends. */
	public static void start(boolean friendsCanJoin, Consumer<String> done) {
		start(friendsCanJoin, "friends", done);
	}

	/** Opens the running singleplayer world: to friends, or publicly ({@code visibility} "public" / "request"). */
	public static void start(boolean friendsCanJoin, String visibility, Consumer<String> done) {
		if (HostCompat.server() == null) {
			done.accept("Open a singleplayer world first");
			return;
		}
		if (!net.veloclient.velo.client.namestyle.NameStyles.plus()) {
			done.accept("Hosting worlds is part of Velo+ (joining is free)");
			return;
		}
		String name = HostCompat.worldName();
		async(() -> {
			if (!HostCompat.published()) {
				int port;
				try (ServerSocket probe = new ServerSocket(0)) {
					port = probe.getLocalPort();
				}
				CompletableFuture<Boolean> opened = new CompletableFuture<>();
				int chosen = port;
				MinecraftClient.getInstance().execute(() -> opened.complete(HostCompat.open(chosen, false)));
				if (!opened.get()) {
					throw new IOException("Couldn't open the world");
				}
				lanPort = port;
			} else {
				// Already open to LAN (Open to LAN, or another mod's hosting): use the port it's really on.
				lanPort = HostCompat.port();
			}
			if (!reachable(lanPort)) {
				throw new IOException("The world is open to LAN, but not on a port Velo can reach - turn off other world hosting (like Essential's) and try again");
			}
			backup();
			JsonObject body = new JsonObject();
			body.addProperty("name", name);
			body.addProperty("version", net.veloclient.velo.client.util.ClientCompat.minecraftVersion());
			body.addProperty("friendsCanJoin", friendsCanJoin);
			body.addProperty("visibility", visibility);
			// The mods guests need too (they get a warning and "Relaunch with matching mods" if theirs differ).
			body.add("mods", WorldMods.toJson(WorldMods.content()));
			body.addProperty("client", CLIENT);
			JsonObject answer = call("/v1/worlds/open", body);
			hostKey = answer.get("hostKey").getAsString();
			relayHost = answer.get("relayHost").getAsString();
			relayPort = answer.get("relayPort").getAsInt();
			hosted = parse(answer);
			hosting = true;
			Thread.ofPlatform().daemon().name("velo-world-control").start(WorldHosting::controlLoop);
		}, done);
	}

	/** Stops letting people in (the world stays open on LAN until you leave it; anyone in it is sent away). */
	public static void stop(Consumer<String> done) {
		hosting = false;
		closeQuietly(control);
		for (String player : HostCompat.players()) {
			if (!player.equals(HostCompat.selfName())) {
				HostCompat.command("kick " + player + " The host closed the world");
			}
		}
		hosted = null;
		KNOCKS.clear();
		async(() -> call("/v1/worlds/close", new JsonObject()), done);
	}

	/** Left the world: hosting ends with it. */
	public static void onWorldLeft() {
		if (hosting) {
			hosting = false;
			closeQuietly(control);
			hosted = null;
			try {
				Thread.ofVirtual().start(() -> {
					try {
						call("/v1/worlds/close", new JsonObject());
					} catch (Exception ignored) {
						// Times out on the server anyway.
					}
				});
			} catch (Exception ignored) {
				// Shutting down.
			}
		}
	}

	/** The control connection: reconnects while hosting; every "OPEN" is a guest to pipe to the world. */
	private static void controlLoop() {
		long backoff = 1000;
		while (hosting) {
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress(relayHost, relayPort), 10_000);
				socket.setTcpNoDelay(true);
				control = socket;
				OutputStream out = socket.getOutputStream();
				out.write(("HOST " + hostKey + "\n").getBytes(StandardCharsets.UTF_8));
				out.flush();
				InputStream in = new BufferedInputStream(socket.getInputStream());
				String hello = readLine(in);
				if (hello == null || !hello.startsWith("OK")) {
					throw new IOException("Relay said: " + hello);
				}
				lastStatus = "";
				backoff = 1000;
				socket.setSoTimeout(30_000);
				Thread pinger = Thread.ofVirtual().start(() -> {
					try {
						while (!socket.isClosed()) {
							Thread.sleep(20_000);
							synchronized (out) {
								out.write("PING\n".getBytes(StandardCharsets.UTF_8));
								out.flush();
							}
						}
					} catch (Exception ignored) {
						// Socket closed.
					}
				});
				String line;
				while (hosting && (line = readLineKeepAlive(in, socket)) != null) {
					if (line.startsWith("OPEN ")) {
						String[] parts = line.split(" ");
						String stream = parts[1];
						String who = parts.length > 2 ? parts[2] : "A friend";
						Thread.ofVirtual().name("velo-world-guest").start(() -> openGuest(stream, who));
					}
				}
				pinger.interrupt();
			} catch (Exception e) {
				if (hosting) {
					lastStatus = "Reconnecting to the Velo relay... (" + e.getMessage() + ")";
					VeloClient.LOGGER.debug("World Hosting relay link dropped", e);
				}
			}
			if (hosting) {
				try {
					Thread.sleep(backoff);
				} catch (InterruptedException ignored) {
					return;
				}
				backoff = Math.min(30_000, backoff * 2);
			}
		}
	}

	private static String readLineKeepAlive(InputStream in, Socket socket) throws IOException {
		while (true) {
			try {
				return readLine(in);
			} catch (java.net.SocketTimeoutException timeout) {
				if (socket.isClosed() || !hosting) {
					return null;
				}
			}
		}
	}

	/** Whether something accepts connections on this PC's {@code port}. */
	private static boolean reachable(int port) {
		if (port <= 0 || port > 65535) {
			return false;
		}
		try (Socket probe = new Socket()) {
			probe.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/** One guest: the world's LAN port first (so a dead port never reaches the relay), then a data connection piped to it. */
	private static void openGuest(String stream, String who) {
		Socket relay = new Socket();
		Socket world = new Socket();
		try {
			int port = HostCompat.published() && HostCompat.port() > 0 ? HostCompat.port() : lanPort;
			world.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000);
			world.setTcpNoDelay(true);
			relay.connect(new InetSocketAddress(relayHost, relayPort), 10_000);
			relay.setTcpNoDelay(true);
			relay.getOutputStream().write(("DATA " + hostKey + " " + stream + "\n").getBytes(StandardCharsets.UTF_8));
			relay.getOutputStream().flush();
			toast("Joining your world", who + " is connecting");
			pipe(relay, relay.getInputStream(), world);
		} catch (IOException e) {
			VeloClient.LOGGER.warn("World Hosting: couldn't let {} in", who, e);
			toast("Couldn't let " + who + " in", e.getMessage() == null ? "Connection failed" : e.getMessage());
			closeQuietly(relay);
			closeQuietly(world);
		}
	}

	/** Copies both ways until either side closes. */
	static void pipe(Socket a, InputStream aIn, Socket b) throws IOException {
		InputStream bIn = b.getInputStream();
		Thread back = Thread.ofVirtual().start(() -> copy(bIn, a, b));
		copy(aIn, b, a);
		try {
			back.join(5000);
		} catch (InterruptedException ignored) {
			// Done.
		}
	}

	private static void copy(InputStream in, Socket to, Socket other) {
		byte[] buffer = new byte[32 * 1024];
		try {
			OutputStream out = to.getOutputStream();
			int read;
			while ((read = in.read(buffer)) > 0) {
				out.write(buffer, 0, read);
				out.flush();
			}
		} catch (IOException ignored) {
			// Closed.
		} finally {
			closeQuietly(to);
			closeQuietly(other);
		}
	}

	static String readLine(InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		int c;
		while ((c = in.read()) != -1) {
			if (c == '\n') {
				return sb.toString().trim();
			}
			if (sb.length() > 256) {
				throw new IOException("line too long");
			}
			sb.append((char) c);
		}
		return sb.isEmpty() ? null : sb.toString();
	}

	static void closeQuietly(Socket socket) {
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException ignored) {
				// Already closed.
			}
		}
	}

	// ---- Backups ----

	public static Path backupDir() {
		return VeloPaths.root().resolve("world-backups");
	}

	/** Zips the world (saved and paused writing first) before anyone else gets in; keeps the newest few per world. */
	private static void backup() {
		Path folder = HostCompat.worldFolder();
		String name = HostCompat.worldName();
		if (folder == null || name == null) {
			return;
		}
		try {
			HostCompat.command("save-off");
			HostCompat.command("save-all flush");
			Thread.sleep(1500);
			String safe = name.replaceAll("[^A-Za-z0-9_-]+", "-");
			Files.createDirectories(backupDir());
			Path zip = backupDir().resolve(safe + "_" + STAMP.format(LocalDateTime.now()) + ".zip");
			Path root = folder.toAbsolutePath().normalize();
			try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip)); Stream<Path> files = Files.walk(root)) {
				for (Path file : files.filter(Files::isRegularFile).toList()) {
					String entry = root.relativize(file).toString().replace('\\', '/');
					if (entry.equals("session.lock")) {
						continue;
					}
					out.putNextEntry(new ZipEntry(safe + "/" + entry));
					try {
						Files.copy(file, out);
					} catch (IOException skipped) {
						// A file in use: the rest of the world is still saved.
					}
					out.closeEntry();
				}
			}
			try (Stream<Path> old = Files.list(backupDir())) {
				List<Path> mine = old.filter(p -> p.getFileName().toString().startsWith(safe + "_")).sorted(Comparator.reverseOrder()).toList();
				for (int i = KEEP_BACKUPS; i < mine.size(); i++) {
					Files.deleteIfExists(mine.get(i));
				}
			}
		} catch (Exception e) {
			VeloClient.LOGGER.warn("World backup before hosting failed", e);
		} finally {
			HostCompat.command("save-on");
		}
	}

	// ---- Members ----

	public static void refresh(Consumer<String> done) {
		async(() -> {
			JsonObject json = call("/v1/worlds/mine", null);
			if (json.has("world") && json.get("world").isJsonObject()) {
				hosted = parse(json.getAsJsonObject("world"));
			}
		}, done);
	}

	public static void invite(String uuid, Consumer<String> done) {
		JsonObject body = new JsonObject();
		body.addProperty("uuid", uuid);
		async(() -> hosted = parse(call("/v1/worlds/invite", body)), done);
	}

	public static void inviteParty(Consumer<String> done) {
		async(() -> hosted = parse(call("/v1/worlds/invite-party", new JsonObject())), done);
	}

	/** Removes access (and kicks them if they're in); {@code ban} keeps them out even if they're your friend. */
	public static void remove(String uuid, String name, boolean ban, Consumer<String> done) {
		JsonObject body = new JsonObject();
		body.addProperty("uuid", uuid);
		body.addProperty("ban", ban);
		if (name != null) {
			HostCompat.command("kick " + name + (ban ? " Banned by the host" : " Removed by the host"));
		}
		async(() -> hosted = parse(call("/v1/worlds/remove", body)), done);
	}

	public static void setFriendsCanJoin(boolean value, Consumer<String> done) {
		JsonObject body = new JsonObject();
		body.addProperty("friendsCanJoin", value);
		async(() -> hosted = parse(call("/v1/worlds/settings", body)), done);
	}

	/** Who can find and enter the world: "friends", "public" or "request". */
	public static void setVisibility(String visibility, Consumer<String> done) {
		JsonObject body = new JsonObject();
		body.addProperty("visibility", visibility);
		async(() -> hosted = parse(call("/v1/worlds/settings", body)), done);
	}

	public static void setMaxPlayers(int max, Consumer<String> done) {
		JsonObject body = new JsonObject();
		body.addProperty("maxPlayers", max);
		async(() -> hosted = parse(call("/v1/worlds/settings", body)), done);
	}

	/** Discover: everyone's public worlds, busiest first. */
	public static void discover(Consumer<List<Listed>> done) {
		Thread.ofVirtual().start(() -> {
			List<Listed> out = new ArrayList<>();
			try {
				String base = VeloServerClient.serverBaseUrl();
				HttpResponse<String> response = VeloServerClient.HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/worlds/public"))
						.timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
				for (JsonElement e : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("worlds")) {
					JsonObject o = e.getAsJsonObject();
					out.add(new Listed(o.get("id").getAsString(), o.get("name").getAsString(),
							o.has("description") && !o.get("description").isJsonNull() ? o.get("description").getAsString() : "",
							o.get("host").getAsString(), o.get("hostName").getAsString(), o.get("version").getAsString(), o.get("guests").getAsInt(),
							o.get("maxPlayers").getAsInt(), o.get("ask").getAsBoolean()));
				}
			} catch (Exception e) {
				VeloClient.LOGGER.debug("Couldn't load Discover", e);
			}
			MinecraftClient.getInstance().execute(() -> done.accept(out));
		});
	}

	/**
	 * A partner server the Velo team lists on the title screen and in Discover (joined directly by address).
	 * {@code online}/{@code players}/{@code max} and the icon come from the Velo server's own ping of it; {@code iconUrl}
	 * is null when it has none.
	 */
	public record Partner(String id, String name, String address, String description, String versions, int accent, boolean online, int players,
			int max, String iconUrl) {
	}

	private static volatile List<Partner> partnerCache = List.of();
	private static volatile long partnersFetchedAt;
	private static volatile boolean partnersLoading;

	/** The partner list as last fetched (refetched in the background every two minutes while something asks). */
	public static List<Partner> partnersCached() {
		long now = System.currentTimeMillis();
		if (!partnersLoading && now - partnersFetchedAt > 120_000) {
			partnersLoading = true;
			partnersFetchedAt = now;
			partners(list -> partnersLoading = false);
		}
		return partnerCache;
	}

	public static void partners(Consumer<List<Partner>> done) {
		Thread.ofVirtual().start(() -> {
			List<Partner> out = new ArrayList<>();
			try {
				String base = VeloServerClient.serverBaseUrl();
				HttpResponse<String> response = VeloServerClient.HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/partners"))
						.timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
				for (JsonElement e : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("partners")) {
					JsonObject o = e.getAsJsonObject();
					String accent = o.has("accent") && !o.get("accent").isJsonNull() ? o.get("accent").getAsString() : null;
					String id = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsString() : "";
					String icon = o.has("icon") && !o.get("icon").isJsonNull() ? base + "/v1/partners/icon/" + id + "?v=" + o.get("icon").getAsString() : null;
					out.add(new Partner(id, o.get("name").getAsString(), o.get("address").getAsString(),
							o.has("description") && !o.get("description").isJsonNull() ? o.get("description").getAsString() : "",
							o.has("versions") && !o.get("versions").isJsonNull() ? o.get("versions").getAsString() : "",
							accent == null ? 0 : 0xFF000000 | Integer.parseInt(accent.substring(1), 16),
							o.has("online") && o.get("online").getAsBoolean(), o.has("players") ? o.get("players").getAsInt() : 0,
							o.has("max") ? o.get("max").getAsInt() : 0, icon));
				}
				partnerCache = List.copyOf(out);
				partnersFetchedAt = System.currentTimeMillis();
			} catch (Exception e) {
				VeloClient.LOGGER.debug("Couldn't load partner servers", e);
			}
			MinecraftClient.getInstance().execute(() -> done.accept(out));
		});
	}

	/** Asks the host of a public world to let you in (you get a Join toast when they do). */
	public static void ask(String worldId, Consumer<String> done) {
		JsonObject body = new JsonObject();
		body.addProperty("world", worldId);
		async(() -> {
			if (call("/v1/worlds/ask", body).get("allowed").getAsBoolean()) {
				MinecraftClient.getInstance().execute(() -> join(worldId, error -> {
				}));
			}
		}, done);
	}

	/** A new share code ({@code enabled}) or none. */
	public static void code(boolean enabled, Consumer<String> done) {
		JsonObject body = new JsonObject();
		body.addProperty("enabled", enabled);
		async(() -> hosted = parse(call("/v1/worlds/code", body)), done);
	}

	public static void accept(Knock knock, Consumer<String> done) {
		JsonObject body = new JsonObject();
		body.addProperty("uuid", knock.uuid());
		KNOCKS.remove(knock);
		async(() -> hosted = parse(call("/v1/worlds/accept", body)), done);
	}

	public static void decline(Knock knock) {
		KNOCKS.remove(knock);
	}


	/** In-world player actions (your own server, full permission). */
	public static void playerAction(String player, String action) {
		String self = HostCompat.selfName();
		switch (action) {
			case "op" -> HostCompat.command("op " + player);
			case "deop" -> HostCompat.command("deop " + player);
			case "survival", "creative", "adventure", "spectator" -> HostCompat.command("gamemode " + action + " " + player);
			case "tp-here" -> HostCompat.command("tp " + player + " " + self);
			case "tp-to" -> HostCompat.command("tp " + self + " " + player);
			case "kick" -> HostCompat.command("kick " + player + " Kicked by the host");
			default -> {
			}
		}
	}

	/** World rules from the host screen: each sends the rule under its old and its new (1.21.11+) name. */
	public static void rule(String rule, String value) {
		switch (rule) {
			case "keepInventory" -> gamerule(value, "keepInventory", "keep_inventory");
			case "pvp" -> gamerule(value, "pvp");
			case "sleep" -> gamerule(Boolean.parseBoolean(value) ? "0" : "100", "playersSleepingPercentage", "players_sleeping_percentage");
			case "mobGriefing" -> gamerule(value, "mobGriefing", "mob_griefing");
			case "daylight" -> gamerule(value, "doDaylightCycle", "advance_time");
			case "difficulty" -> HostCompat.command("difficulty " + value);
			default -> {
			}
		}
	}

	private static void gamerule(String value, String... names) {
		for (String name : names) {
			HostCompat.command("gamerule " + name + " " + value);
		}
	}

	// ---- Joining (free) ----

	public static void joinable(Consumer<List<Joinable>> done) {
		Thread.ofVirtual().start(() -> {
			List<Joinable> out = new ArrayList<>();
			try {
				for (JsonElement e : call("/v1/worlds/joinable", null).getAsJsonArray("worlds")) {
					JsonObject o = e.getAsJsonObject();
					out.add(new Joinable(o.get("id").getAsString(), o.get("name").getAsString(), o.get("host").getAsString(),
							o.get("hostName").getAsString(), o.get("version").getAsString(), o.get("guests").getAsInt()));
				}
			} catch (Exception e) {
				VeloClient.LOGGER.debug("Couldn't list joinable worlds", e);
			}
			MinecraftClient.getInstance().execute(() -> done.accept(out));
		});
	}

	/** Knocks with a share code: you're in right away if the host already allows you, else the host is asked. */
	public static void knock(String code, Consumer<String> done) {
		JsonObject body = new JsonObject();
		body.addProperty("code", code);
		async(() -> {
			JsonObject answer = call("/v1/worlds/knock", body);
			if (answer.get("allowed").getAsBoolean()) {
				String world = answer.get("world").getAsString();
				MinecraftClient.getInstance().execute(() -> join(world, error -> {
				}));
			}
		}, done);
	}

	/** Joins a friend's world through the relay: a local port that pipes to it, then a normal connect. */
	public static void join(String worldId, Consumer<String> done) {
		join(worldId, false, done);
	}

	/** {@code anyway}: join even though your mods differ from the host's (otherwise the warning opens instead). */
	public static void join(String worldId, boolean anyway, Consumer<String> done) {
		JsonObject body = new JsonObject();
		body.addProperty("world", worldId);
		body.addProperty("client", CLIENT);
		async(() -> {
			JsonObject answer = call("/v1/worlds/join", body);
			if (!anyway && answer.has("mods") && answer.get("mods").isJsonArray()) {
				List<WorldMods.Mod> needed = WorldMods.fromJson(answer.getAsJsonArray("mods"));
				WorldMods.Diff diff = WorldMods.compare(needed);
				if (!diff.matches()) {
					String hostName = answer.get("hostName").getAsString();
					String version = answer.get("version").getAsString();
					MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new net.veloclient.velo.client.gui.WorldModsScreen(
							net.veloclient.velo.client.util.ClientCompat.currentScreen(), worldId, hostName, version, needed, diff)));
					return;
				}
			}
			String version = answer.get("version").getAsString();
			String mine = net.veloclient.velo.client.util.ClientCompat.minecraftVersion();
			if (!version.isEmpty() && !version.equals(mine)) {
				throw new IOException("That world runs Minecraft " + version + " - start that version to join (you have " + mine + ")");
			}
			String ticket = answer.get("ticket").getAsString();
			String host = answer.get("relayHost").getAsString();
			int port = answer.get("relayPort").getAsInt();
			// Through the relay to the host's world first: any problem shows up as a clear message here instead of
			// Minecraft's bare "Failed to connect". Only then does the game connect to the local end of the pipe.
			Socket relay = new Socket();
			InputStream relayIn;
			try {
				relay.connect(new InetSocketAddress(host, port), 10_000);
				relay.setTcpNoDelay(true);
				relay.getOutputStream().write(("JOIN " + ticket + "\n").getBytes(StandardCharsets.UTF_8));
				relay.getOutputStream().flush();
				relay.setSoTimeout(25_000);
				relayIn = new BufferedInputStream(relay.getInputStream());
				String hello = readLine(relayIn);
				if (hello == null || !hello.startsWith("OK")) {
					throw new IOException(relayError(hello));
				}
				relay.setSoTimeout(0);
			} catch (java.net.SocketTimeoutException e) {
				closeQuietly(relay);
				throw new IOException("The host's game didn't answer - ask them to check that their world is still hosted");
			} catch (java.net.ConnectException e) {
				closeQuietly(relay);
				throw new IOException("Couldn't reach the Velo relay (" + host + ":" + port + ") - a firewall may block it");
			} catch (IOException e) {
				closeQuietly(relay);
				throw e;
			}
			ServerSocket local = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
			local.setSoTimeout(30_000);
			Thread.ofVirtual().name("velo-world-join").start(() -> {
				try (local) {
					Socket game = local.accept();
					game.setTcpNoDelay(true);
					pipe(relay, relayIn, game);
				} catch (IOException e) {
					closeQuietly(relay);
					VeloClient.LOGGER.warn("Joining a hosted world failed", e);
				}
			});
			String label = answer.get("hostName").getAsString() + "'s world";
			MinecraftClient.getInstance().execute(() -> net.veloclient.velo.client.util.ClientCompat.joinServer("127.0.0.1:" + local.getLocalPort(), label));
		}, done);
	}

	/** Velo Network events: a friend invited you (Join / Later), or someone knocked on your world (Let in / Decline). */
	public static void onEvent(String type, JsonObject data) {
		MinecraftClient.getInstance().execute(() -> {
			if (type.equals("world_invite")) {
				String world = data.get("world").getAsString();
				String host = data.get("hostName").getAsString();
				NotificationOverlay.show(new NotificationOverlay.Toast(data.get("host").getAsString(), host, host + " invited you",
						"to play in \"" + data.get("name").getAsString() + "\"", 20_000, null, () -> join(world, error -> {
							if (error != null) {
								toast("Couldn't join", error);
							}
						}), () -> {
						}).labels("Join", "Later"));
			} else if (type.equals("world_knock") && hosting) {
				String uuid = data.get("uuid").getAsString();
				String name = data.get("username").getAsString();
				KNOCKS.removeIf(k -> k.uuid().equals(uuid));
				Knock knock = new Knock(uuid, name, System.currentTimeMillis());
				KNOCKS.add(knock);
				NotificationOverlay.show(new NotificationOverlay.Toast(uuid, name, name + " wants to join", "They'd like to play in your world", 20_000, null,
						() -> accept(knock, error -> {
						}), () -> decline(knock)).labels("Let in", "Decline"));
			}
		});
	}

	private static int pendingCheck;
	private static volatile boolean relaunching;

	/**
	 * "Relaunch with matching mods": leaves a note for the launcher (the world, its Minecraft version and the host's
	 * mods) and closes the game; the launcher waits for it to exit, makes a matching profile from Modrinth and starts
	 * it, and that game joins.
	 */
	public static void relaunchFor(String worldId, String hostName, String version, List<WorldMods.Mod> mods) throws IOException {
		JsonObject note = new JsonObject();
		note.addProperty("world", worldId);
		note.addProperty("at", System.currentTimeMillis());
		note.addProperty("relaunch", true);
		note.addProperty("pid", ProcessHandle.current().pid());
		note.addProperty("version", version);
		note.addProperty("hostName", hostName);
		note.add("mods", WorldMods.toJson(mods));
		relaunching = true;
		Files.createDirectories(VeloPaths.config());
		Files.writeString(VeloPaths.config().resolve("pending-world-join.json"), note.toString(), StandardCharsets.UTF_8);
		//? if <26.1 {
		MinecraftClient.getInstance().scheduleStop();
		//?} else {
		/*MinecraftClient.getInstance().stop();
		*///?}
	}

	/**
	 * The launcher's "Join" on a hosted world leaves {@code config/pending-world-join.json} and starts the game: once
	 * the game is signed in to the Velo Network (and not in a world), it joins. Notes older than 10 minutes are dropped.
	 */
	public static void tickPending() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (relaunching || ++pendingCheck % 40 != 0 || VeloServerClient.sessionToken() == null || client.world != null) {
			return;
		}
		Path note = VeloPaths.config().resolve("pending-world-join.json");
		if (!Files.exists(note)) {
			return;
		}
		try {
			JsonObject json = JsonParser.parseString(Files.readString(note, StandardCharsets.UTF_8)).getAsJsonObject();
			if (json.has("relaunch") && json.get("relaunch").getAsBoolean()) {
				return; // still waiting for the launcher to set up the matching profile
			}
			Files.deleteIfExists(note);
			if (System.currentTimeMillis() - json.get("at").getAsLong() < 10 * 60_000) {
				join(json.get("world").getAsString(), error -> {
					if (error != null) {
						toast("Couldn't join", error);
					}
				});
			}
		} catch (Exception e) {
			VeloClient.LOGGER.debug("Bad pending world join", e);
		}
	}

	/** The relay's "ERR ..." answer, in words. */
	private static String relayError(String hello) {
		if (hello == null) {
			return "The relay closed the connection";
		}
		return switch (hello.replace("ERR ", "")) {
			case "not allowed" -> "The host doesn't let you in (or the invite expired) - try again";
			case "full" -> "The world is full";
			case "host didn't answer" -> "The host's game didn't answer - ask them to check that their world is still hosted";
			default -> hello.replace("ERR ", "");
		};
	}

	static void toast(String title, String text) {
		MinecraftClient.getInstance().execute(() -> NotificationOverlay.show(new NotificationOverlay.Toast(null, null, title, text, 6000, null, null, null)));
	}
}
