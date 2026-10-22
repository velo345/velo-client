package net.veloclient.server;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Velo+ World Hosting: a member's singleplayer world, playable by their friends from anywhere - no port forwarding.
 *
 * <p>The world itself runs on the host's PC (opened to LAN there). Both sides only ever connect out, to this relay on
 * {@link #port}: the host keeps one control connection ({@code HOST <key>}); a guest who got a ticket from the REST API
 * connects with {@code JOIN <ticket>}, the relay tells the host {@code OPEN <stream>}, the host opens a data connection
 * ({@code DATA <key> <stream>}) to its own LAN port, and from then on the relay just copies bytes both ways. Minecraft's
 * own login (online mode, encrypted) runs end to end on top, so the relay never sees anything but ciphertext after the
 * handshake.
 *
 * <p>Who may join is decided here, before a ticket is handed out: players the host invited (friends, or the host's
 * party), all their friends when they turned that on, or anyone the host let in after they "knocked" with the world's
 * join code. Guests don't need Velo+, only the host does.
 */
final class WorldHostService {

	static final int MAX_GUESTS = 10;
	private static final long TICKET_TTL_MILLIS = 60_000;
	private static final long STREAM_WAIT_MILLIS = 15_000;

	/** An open world. {@code allowed}: invited players; {@code friendsCanJoin}: every friend of the host may. */
	static final class World {
		final String id;
		final String hostUuid;
		final String hostName;
		final String hostKey;
		volatile String name;
		volatile String version;
		volatile boolean friendsCanJoin;
		final Set<String> allowed = ConcurrentHashMap.newKeySet();
		final Set<String> banned = ConcurrentHashMap.newKeySet();
		final long openedAt = System.currentTimeMillis();
		/** "friends" (invites / friends only), "public" (anyone may join, listed in Discover), "request" (listed, ask first). */
		volatile String visibility = "friends";
		volatile String description;
		volatile int maxPlayers = MAX_GUESTS;
		/** The host's world mods (guests compare theirs before joining). */
		volatile List<Map<String, Object>> mods = List.of();
		/** Share code ("knock" with it to ask to join); null when the host switched codes off. */
		volatile String code;
		volatile Socket control;
		final AtomicInteger guests = new AtomicInteger();
		final AtomicLong bytes = new AtomicLong();
		/** Guests' relay connections right now (closed when staff take the world down). */
		final Set<Socket> streams = ConcurrentHashMap.newKeySet();
		/** Everyone who joined it while it was open (staff see who was there). */
		final Set<String> visitors = ConcurrentHashMap.newKeySet();

		World(String id, String hostUuid, String hostName, String hostKey) {
			this.id = id;
			this.hostUuid = hostUuid;
			this.hostName = hostName;
			this.hostKey = hostKey;
		}

		boolean online() {
			Socket c = control;
			return c != null && !c.isClosed();
		}
	}

	private record Ticket(String worldId, String uuid, String username, long expires) {
	}

	private final SecureRandom random = new SecureRandom();
	private final Map<String, World> byId = new ConcurrentHashMap<>();
	private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<Socket>> waiting = new ConcurrentHashMap<>();
	private final SocialService social;
	private volatile java.util.function.Function<String, List<String>> partyMates = uuid -> List.of();
	private volatile int port;
	private final String publicHost;

	WorldHostService(SocialService social, int port, String publicHost) {
		this.social = social;
		this.port = port;
		this.publicHost = publicHost;
	}

	void setPartyLookup(java.util.function.Function<String, List<String>> lookup) {
		this.partyMates = lookup;
	}

	int port() {
		return port;
	}

	String publicHost() {
		return publicHost;
	}

	private String token(int bytes) {
		byte[] b = new byte[bytes];
		random.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	// ---- REST side ----

	/** Opens (or reopens) the host's world; one per host. */
	World open(String uuid, String username, String name, String version, boolean friendsCanJoin) {
		World old = worldOf(uuid);
		if (old != null) {
			old.name = clean(name, old.name);
			old.version = clean(version, old.version);
			old.friendsCanJoin = friendsCanJoin;
			return old;
		}
		World world = new World(token(6), uuid, username, token(24));
		world.name = clean(name, username + "'s world");
		world.version = clean(version, null);
		world.friendsCanJoin = friendsCanJoin;
		byId.put(world.id, world);
		return world;
	}

	void close(String uuid) {
		World world = worldOf(uuid);
		if (world != null) {
			byId.remove(world.id);
			closeQuietly(world.control);
		}
	}

	/** Staff: closes the world for everyone (host and guests disconnected); null when it isn't open. */
	World takedown(String id) {
		World world = byId.remove(id);
		if (world != null) {
			closeQuietly(world.control);
			world.streams.forEach(WorldHostService::closeQuietly);
		}
		return world;
	}

	/** Every open world (all visibilities), busiest first - staff monitoring. */
	List<World> all() {
		List<World> out = new ArrayList<>(byId.values());
		out.sort((a, b) -> Integer.compare(b.guests.get(), a.guests.get()));
		return out;
	}

	/** Relay bytes of every open world. */
	long totalBytes() {
		return byId.values().stream().mapToLong(w -> w.bytes.get()).sum();
	}

	World worldOf(String hostUuid) {
		for (World world : byId.values()) {
			if (world.hostUuid.equals(hostUuid)) {
				return world;
			}
		}
		return null;
	}

	World byId(String id) {
		return id == null ? null : byId.get(id);
	}

	boolean mayJoin(World world, String uuid) {
		if (world.banned.contains(uuid)) {
			return false;
		}
		return world.hostUuid.equals(uuid) || world.allowed.contains(uuid) || "public".equals(world.visibility)
				|| world.friendsCanJoin && social.friendsWith(world.hostUuid, uuid);
	}

	/** Public worlds for Discover (public and ask-to-join), busiest first. */
	List<World> discover() {
		List<World> out = new ArrayList<>();
		for (World world : byId.values()) {
			if (!"friends".equals(world.visibility) && world.online()) {
				out.add(world);
			}
		}
		out.sort((a, b) -> Integer.compare(b.guests.get(), a.guests.get()));
		return out.size() > 100 ? out.subList(0, 100) : out;
	}

	/** Keeps only the plain fields of each mod (id, version, name, file, sha1, library), at most 300 mods. */
	void mods(World world, List<Map<String, Object>> mods) {
		if (mods == null) {
			return;
		}
		List<Map<String, Object>> clean = new ArrayList<>();
		for (Map<String, Object> mod : mods.subList(0, Math.min(300, mods.size()))) {
			Map<String, Object> out = new java.util.LinkedHashMap<>();
			for (String key : new String[] {"id", "version", "name", "file"}) {
				Object value = mod.get(key);
				if (value instanceof String text && !text.isBlank()) {
					out.put(key, text.length() > 100 ? text.substring(0, 100) : text);
				}
			}
			if (mod.get("sha1") instanceof String sha1 && sha1.matches("[0-9a-f]{40}")) {
				out.put("sha1", sha1);
			}
			if (Boolean.TRUE.equals(mod.get("library"))) {
				out.put("library", true);
			}
			if (out.containsKey("id")) {
				clean.add(out);
			}
		}
		world.mods = List.copyOf(clean);
	}

	/** Visibility + listing text from the host. */
	void listing(World world, String visibility, String description, Integer maxPlayers) {
		if (visibility != null && List.of("friends", "public", "request").contains(visibility)) {
			world.visibility = visibility;
		}
		if (description != null) {
			world.description = CloudBackupService.clean(description, 90);
		}
		if (maxPlayers != null) {
			world.maxPlayers = Math.max(1, Math.min(MAX_GUESTS, maxPlayers));
		}
	}

	/** Worlds this player could join right now (a friend's open world, or one they're invited to). */
	List<World> joinableFor(String uuid) {
		List<World> out = new ArrayList<>();
		for (World world : byId.values()) {
			if (!world.hostUuid.equals(uuid) && world.online() && mayJoin(world, uuid)) {
				out.add(world);
			}
		}
		return out;
	}

	/** Invites {@code target} (must be the host's friend) and tells them. */
	void invite(World world, String target) throws ApiError {
		if (!social.friendsWith(world.hostUuid, target) && !partyMates.apply(world.hostUuid).contains(target)) {
			throw new ApiError(403, "You can only invite your Velo friends and your party");
		}
		let(world, target);
	}

	/** Everyone in the host's party gets in (and an invite). */
	int invitePartyOf(World world) {
		List<String> mates = partyMates.apply(world.hostUuid);
		mates.forEach(m -> let(world, m));
		return mates.size();
	}

	/** A short code to share anywhere: whoever "knocks" with it shows up on the host's screen to accept. */
	String newCode(World world) {
		String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
		StringBuilder sb = new StringBuilder(6);
		for (int i = 0; i < 6; i++) {
			sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		world.code = sb.toString();
		return world.code;
	}

	World byCode(String code) {
		if (code == null) {
			return null;
		}
		String wanted = code.trim().toUpperCase(java.util.Locale.ROOT).replace("VELO-", "");
		for (World world : byId.values()) {
			if (wanted.equals(world.code)) {
				return world;
			}
		}
		return null;
	}

	/** Someone with the code asks to join: the host gets a request to accept. */
	void knock(World world, String uuid, String username) throws ApiError {
		if (world.banned.contains(uuid)) {
			throw new ApiError(403, "The host doesn't let you in");
		}
		if (mayJoin(world, uuid)) {
			let(world, uuid);
			return;
		}
		social.pushEvent(world.hostUuid, "world_knock", Map.of("world", world.id, "uuid", uuid, "username", username));
	}

	/** Lets {@code target} in and tells them (an invite toast with Join). */
	void let(World world, String target) {
		world.banned.remove(target);
		world.allowed.add(target);
		social.pushEvent(target, "world_invite", Map.of("world", world.id, "name", world.name, "host", world.hostUuid,
				"hostName", world.hostName, "version", world.version == null ? "" : world.version));
	}

	/** Takes a player's access away (and the host kicks them in game). */
	void remove(World world, String target, boolean ban) {
		world.allowed.remove(target);
		if (ban) {
			world.banned.add(target);
		}
	}

	String ticket(World world, String uuid, String username) throws ApiError {
		if (!world.online()) {
			throw new ApiError(409, "The world isn't reachable right now - the host may have closed the game");
		}
		if (!mayJoin(world, uuid)) {
			throw new ApiError(403, "You're not invited to this world");
		}
		if (world.guests.get() >= world.maxPlayers) {
			throw new ApiError(409, "This world is full");
		}
		long now = System.currentTimeMillis();
		tickets.values().removeIf(t -> t.expires() < now);
		String ticket = token(24);
		tickets.put(ticket, new Ticket(world.id, uuid, username, now + TICKET_TTL_MILLIS));
		return ticket;
	}

	private static String clean(String text, String fallback) {
		String plain = CloudBackupService.clean(text, 48);
		return plain == null ? fallback : plain;
	}

	// ---- The relay ----

	/** Starts accepting relay connections (on its own threads). */
	void start() throws IOException {
		ServerSocket server = new ServerSocket();
		server.setReuseAddress(true);
		server.bind(new InetSocketAddress(port));
		port = server.getLocalPort();
		Thread.ofPlatform().daemon().name("velo-relay-accept").start(() -> {
			while (!server.isClosed()) {
				try {
					Socket socket = server.accept();
					socket.setTcpNoDelay(true);
					socket.setSoTimeout(15_000);
					Thread.ofVirtual().name("velo-relay").start(() -> handle(socket));
				} catch (IOException e) {
					System.out.println("[relay] accept failed: " + e.getMessage());
				}
			}
		});
		System.out.println("[relay] World hosting relay on port " + port);
	}

	private void handle(Socket socket) {
		try {
			InputStream in = new BufferedInputStream(socket.getInputStream());
			String line = readLine(in);
			if (line == null) {
				closeQuietly(socket);
				return;
			}
			String[] parts = line.split(" ");
			switch (parts[0]) {
				case "HOST" -> host(socket, in, parts.length > 1 ? parts[1] : "");
				case "DATA" -> data(socket, in, parts.length > 2 ? parts[1] : "", parts.length > 2 ? parts[2] : "");
				case "JOIN" -> join(socket, in, parts.length > 1 ? parts[1] : "");
				default -> closeQuietly(socket);
			}
		} catch (Exception e) {
			closeQuietly(socket);
		}
	}

	private World byKey(String key) {
		for (World world : byId.values()) {
			if (java.security.MessageDigest.isEqual(world.hostKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
				return world;
			}
		}
		return null;
	}

	/** The host's control connection: stays open, gets "OPEN <stream>" lines; the host sends "PING" now and then. */
	private void host(Socket socket, InputStream in, String key) throws IOException {
		World world = byKey(key);
		if (world == null) {
			write(socket, "ERR unknown world\n");
			closeQuietly(socket);
			return;
		}
		closeQuietly(world.control);
		world.control = socket;
		socket.setSoTimeout(90_000);
		write(socket, "OK " + world.id + "\n");
		try {
			while (readLine(in) != null) {
				// PING keeps it open (and tells us the host is still there).
			}
		} catch (IOException ignored) {
			// Gone.
		} finally {
			if (world.control == socket) {
				world.control = null;
			}
			closeQuietly(socket);
		}
	}

	private void join(Socket guest, InputStream in, String ticketId) throws Exception {
		Ticket ticket = tickets.remove(ticketId);
		World world = ticket == null ? null : byId.get(ticket.worldId());
		if (ticket == null || ticket.expires() < System.currentTimeMillis() || world == null || !world.online() || !mayJoin(world, ticket.uuid())) {
			System.out.println("[relay] join refused (" + (ticket == null ? "unknown ticket" : world == null ? "world closed"
					: !world.online() ? "host offline" : "not allowed") + ")");
			write(guest, "ERR not allowed\n");
			closeQuietly(guest);
			return;
		}
		if (world.guests.incrementAndGet() > MAX_GUESTS) {
			world.guests.decrementAndGet();
			write(guest, "ERR full\n");
			closeQuietly(guest);
			return;
		}
		String stream = token(12);
		CompletableFuture<Socket> hostSide = new CompletableFuture<>();
		waiting.put(stream, hostSide);
		try {
			synchronized (world) {
				write(world.control, "OPEN " + stream + " " + ticket.username() + "\n");
			}
			Socket host = hostSide.get(STREAM_WAIT_MILLIS, TimeUnit.MILLISECONDS);
			write(guest, "OK\n");
			world.streams.add(guest);
			world.streams.add(host);
			world.visitors.add(ticket.uuid());
			pipe(guest, in, host, world);
		} catch (Exception e) {
			System.out.println("[relay] host of " + world.id + " didn't open a stream for " + ticket.username() + ": " + e);
			write(guest, "ERR host didn't answer\n");
			closeQuietly(guest);
		} finally {
			waiting.remove(stream);
			world.guests.decrementAndGet();
			world.streams.remove(guest);
			world.streams.removeIf(Socket::isClosed);
		}
	}

	private void data(Socket socket, InputStream in, String key, String stream) {
		World world = byKey(key);
		CompletableFuture<Socket> pending = world == null ? null : waiting.remove(stream);
		if (pending == null) {
			closeQuietly(socket);
			return;
		}
		// The guest's thread pipes both ways - with this buffered stream, which may already hold the first bytes.
		pendingInputs.put(socket, in);
		pending.complete(socket);
	}

	private final Map<Socket, InputStream> pendingInputs = new ConcurrentHashMap<>();

	private void pipe(Socket guest, InputStream guestIn, Socket host, World world) throws IOException {
		guest.setSoTimeout(0);
		host.setSoTimeout(0);
		InputStream hostIn = pendingInputs.remove(host);
		if (hostIn == null) {
			hostIn = host.getInputStream();
		}
		InputStream fromHost = hostIn;
		Thread back = Thread.ofVirtual().start(() -> copy(fromHost, guest, world, host));
		copy(guestIn, host, world, guest);
		try {
			back.join(5000);
		} catch (InterruptedException ignored) {
			// Done anyway.
		}
	}

	private static void copy(InputStream in, Socket to, World world, Socket other) {
		byte[] buffer = new byte[32 * 1024];
		try {
			OutputStream out = to.getOutputStream();
			int read;
			while ((read = in.read(buffer)) > 0) {
				out.write(buffer, 0, read);
				out.flush();
				world.bytes.addAndGet(read);
			}
		} catch (IOException ignored) {
			// One side closed.
		} finally {
			closeQuietly(to);
			closeQuietly(other);
		}
	}

	/** One line (up to 256 bytes) without buffering past it - what follows is the raw Minecraft stream. */
	private static String readLine(InputStream in) throws IOException {
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

	private static void write(Socket socket, String text) throws IOException {
		if (socket == null) {
			throw new IOException("not connected");
		}
		OutputStream out = socket.getOutputStream();
		out.write(text.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private static void closeQuietly(Socket socket) {
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException ignored) {
				// Already closed.
			}
		}
	}

	/** What the host's screen shows about its world. */
	Map<String, Object> view(World world) {
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		out.put("id", world.id);
		out.put("name", world.name);
		out.put("version", world.version);
		out.put("host", world.hostUuid);
		out.put("hostName", world.hostName);
		out.put("friendsCanJoin", world.friendsCanJoin);
		out.put("online", world.online());
		out.put("guests", world.guests.get());
		Set<Map<String, String>> allowed = new LinkedHashSet<>();
		for (String uuid : world.allowed) {
			allowed.add(Map.of("uuid", uuid, "username", social.usernameOf(uuid)));
		}
		out.put("allowed", allowed);
		out.put("code", world.code);
		out.put("visibility", world.visibility);
		out.put("description", world.description);
		out.put("maxPlayers", world.maxPlayers);
		out.put("openedAt", world.openedAt);
		return out;
	}
}
