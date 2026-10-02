package net.veloclient.velo.client.modules.queue;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.util.ClientCompat;
//? if <26.1 {
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.chat.Component;
*///?}

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Background Queue: keeps a server connection alive as a "ghost" while you play somewhere else, so
 * you keep your place (a queue position, your spot on a server) and can switch back any time.
 *
 * <h2>How it works</h2>
 * <ul>
 * <li><b>Send to background</b> detaches the world exactly the way vanilla does when a proxy
 * switches you between servers ({@code enterReconfiguration}/{@code clearClientLevel}): your world
 * and HUD are cleared, but the connection stays open and the server still sees you connected. The
 * old version on 26.x closed the connection outright, and on 1.21.11 used a disconnect path that
 * marks the connection as finished, so the client silently ignored every packet afterwards -
 * including keep-alives - and the server timed the "ghost" out ~30s later. That's why switching
 * back always felt like rejoining from scratch.</li>
 * <li>While backgrounded, {@code GhostPacketGateMixin} routes that connection's packets through
 * {@link GhostPackets}: keep-alives and scoreboard updates apply normally, chat/action bar/titles/
 * tab header are captured live (shown in the session menu, the HUD card and optionally your chat),
 * and everything that changes the world is buffered in order.</li>
 * <li><b>Switch</b> hands the same connection back - no reconnect: the world you left is
 * re-attached and the buffered packets replayed through vanilla's own handlers, so you're exactly
 * where the server thinks you are. If the server moved you meanwhile (queue popped onto another
 * backend), the buffer starts at that new login, which replays as a normal join. Only if that's
 * impossible (the connection died, or the buffer overflowed on a very busy server) does it fall
 * back to a fresh reconnect - and says so.</li>
 * </ul>
 * Singleplayer can't stay open in the background (it's a whole embedded server), so leaving it saves
 * and keeps a "resume point" that reopens the world.
 *
 * <p>Auto Reconnect: {@link #consumeAutoReconnectSuppression()} keeps a Velo-initiated disconnect
 * from looking like a kick.
 */
public final class BackgroundQueueManager {

	private static final Identifier PROFILE_ICON = Identifier.of("velo-client", "textures/icon/logo.png");
	private static final int PROFILE_ICON_SOURCE_SIZE = 500;
	/** Beyond this many buffered world packets a clean hand-off isn't realistic any more (very busy server). */
	private static final int MAX_BUFFERED_PACKETS = 60_000;
	private static final int MAX_LOG_LINES = 120;

	/** One line of a background session's live feed. {@code kind}: chat, actionbar, title, system. */
	public record LogLine(long time, String kind, String text) {
	}

	public record SessionSummary(String key, String displayName, String address, boolean singleplayer,
			boolean poppedReady, QueueStatusParser.Status status, List<String> recentMessages,
			List<LogLine> log, String actionBar, long actionBarTime, String title, String subtitle, long titleTime,
			String tabHeader, String tabFooter, String endedReason, boolean switching, boolean canResume,
			long since, int bufferedPackets) {
	}

	private static final class Session {
		final Object lock = new Object();
		String key;
		String displayName = "";
		String address = "";
		String preset = "Generic";
		boolean singleplayer;
		String spFolder;
		long since = System.currentTimeMillis();
		//? if <26.1 {
		ClientConnection connection;
		ClientWorld world;
		ClientPlayerEntity player;
		ClientPlayerInteractionManager gameMode;
		ServerInfo serverInfo;
		//?} else {
		/*Connection connection;
		ClientLevel world;
		LocalPlayer player;
		MultiPlayerGameMode gameMode;
		ServerData serverInfo;
		*///?}
		/** True while backgrounded - flipped off (under {@link #lock}) the instant a switch starts. */
		boolean ghost;
		final ArrayDeque<Packet<?>> buffer = new ArrayDeque<>();
		boolean overflowed;
		/** A fresh login is first in the buffer, so the switch is a normal join (no old world needed). */
		boolean bufferStartsWithLogin;
		/** Mid server-switch (reconfiguring) - can't hand off until the new login arrives. */
		boolean reconfiguring;
		boolean popped;
		String endedReason;
		QueueStatusParser.Status status = QueueStatusParser.Status.EMPTY;
		final Deque<LogLine> log = new ArrayDeque<>();
		String actionBar;
		long actionBarTime;
		String title;
		String subtitle;
		long titleTime;
		String tabHeader;
		String tabFooter;
	}

	private static final Map<String, Session> SESSIONS = new LinkedHashMap<>();
	private static final Map<Object, Session> BY_CONNECTION = new ConcurrentHashMap<>();
	private static String peekedKey;
	private static long suppressAutoReconnectUntilMs;
	private static String parsePreset = "Auto-detect";
	private static String parseCustomRegex = "";
	private static String lastNotice = "";

	private static Consumer<String> onPoppedCallback = key -> {
	};
	private static BiConsumer<String, LogLine> onLineCallback = (key, line) -> {
	};

	private BackgroundQueueManager() {
	}

	public static void setParsingConfig(String preset, String customRegex) {
		parsePreset = preset;
		parseCustomRegex = customRegex;
	}

	public static void setOnPopped(Consumer<String> callback) {
		onPoppedCallback = callback == null ? key -> {
		} : callback;
	}

	public static void setOnLine(BiConsumer<String, LogLine> callback) {
		onLineCallback = callback == null ? (key, line) -> {
		} : callback;
	}

	/** The last thing that went wrong (or happened) worth telling the user - shown in the menu. */
	public static String lastNotice() {
		return lastNotice;
	}

	/**
	 * Whether the packet gate mixin is actually active. It's in the optional mixin config (so a
	 * Minecraft update that moves its target can't crash the game) - without it a ghost can't stay
	 * alive, so sending to background falls back to "remember and reconnect".
	 */
	public static boolean liveGhostsSupported() {
		try {
			//? if <26.1 {
			Class<?> target = ClientConnection.class;
			//?} else {
			/*Class<?> target = Connection.class;
			*///?}
			for (var method : target.getDeclaredMethods()) {
				if (method.getName().contains("velo$gateGhostPackets")) {
					return true;
				}
			}
		} catch (RuntimeException ignored) {
			// Fall through.
		}
		return false;
	}

	// ---- Queries ----

	public static List<SessionSummary> sessions() {
		List<SessionSummary> out = new ArrayList<>();
		for (Session session : List.copyOf(SESSIONS.values())) {
			out.add(toSummary(session));
		}
		return out;
	}

	public static boolean hasSessions() {
		return !SESSIONS.isEmpty();
	}

	public static SessionSummary summaryFor(String key) {
		Session session = SESSIONS.get(key);
		return session == null ? null : toSummary(session);
	}

	private static SessionSummary toSummary(Session s) {
		synchronized (s.lock) {
			List<String> recent = new ArrayList<>();
			int skip = Math.max(0, s.log.size() - 6);
			int i = 0;
			for (LogLine line : s.log) {
				if (i++ >= skip) {
					recent.add(line.text());
				}
			}
			boolean canResume = s.singleplayer || s.endedReason != null || !s.reconfiguring;
			return new SessionSummary(s.key, s.displayName, s.address, s.singleplayer, s.popped, s.status, recent,
					List.copyOf(s.log), s.actionBar, s.actionBarTime, s.title, s.subtitle, s.titleTime, s.tabHeader, s.tabFooter,
					s.endedReason, s.reconfiguring, canResume, s.since, s.buffer.size());
		}
	}

	/** The live scoreboard of a background session (null for singleplayer/ended ones). */
	public static net.minecraft.scoreboard.Scoreboard scoreboardFor(String key) {
		Session session = SESSIONS.get(key);
		if (session == null || session.connection == null || session.endedReason != null) {
			return null;
		}
		//? if <26.1 {
		return session.connection.getPacketListener() instanceof ClientPlayNetworkHandler handler ? handler.getScoreboard() : null;
		//?} else {
		/*return session.connection.getPacketListener() instanceof ClientPacketListener handler ? handler.scoreboard() : null;
		*///?}
	}

	public static Identifier sessionIcon() {
		return PROFILE_ICON;
	}

	public static int sessionIconSourceSize() {
		return PROFILE_ICON_SOURCE_SIZE;
	}

	public static void setPeeked(String key) {
		Session session = SESSIONS.get(key);
		peekedKey = session != null && !session.singleplayer ? key : null;
	}

	public static void clearPeeked() {
		peekedKey = null;
	}

	public static String peekedKey() {
		return peekedKey;
	}

	public static SessionSummary peekedSummary() {
		return peekedKey == null ? null : summaryFor(peekedKey);
	}

	public static boolean isPeeked(String key) {
		return key != null && key.equals(peekedKey);
	}

	/** Checked by {@code AutoReconnectModule} - true (and consumed) exactly once right after this class closed a connection on purpose. */
	public static boolean consumeAutoReconnectSuppression() {
		boolean suppressed = System.currentTimeMillis() < suppressAutoReconnectUntilMs;
		suppressAutoReconnectUntilMs = 0;
		return suppressed;
	}

	private static void markSuppressAutoReconnect() {
		suppressAutoReconnectUntilMs = System.currentTimeMillis() + 5000;
	}

	/** Dev-only (screenshot tour): a session with no connection behind it, just for showing the menu. */
	public static void addDemoSession(String name, String address, int queuePosition, List<String> chat, String actionBar) {
		Session session = new Session();
		session.key = uniqueKey(name);
		session.displayName = name;
		session.address = address;
		session.since = System.currentTimeMillis() - 7 * 60_000;
		session.status = QueueStatusParser.parse("Generic", "", "Position in queue: " + queuePosition);
		if (session.status == null) {
			session.status = QueueStatusParser.Status.EMPTY;
		}
		long time = System.currentTimeMillis() - chat.size() * 20_000L;
		for (String line : chat) {
			session.log.addLast(new LogLine(time, line.startsWith("> ") ? "you" : line.startsWith("! ") ? "system" : "chat",
					line.startsWith("! ") ? line.substring(2) : line));
			time += 20_000;
		}
		session.actionBar = actionBar;
		session.actionBarTime = System.currentTimeMillis();
		session.tabHeader = name;
		SESSIONS.put(session.key, session);
	}

	// ---- The packet gate (network thread) ----

	/** @return true when the packet was consumed for a ghost connection and vanilla must not handle it. */
	public static boolean interceptGhostPacket(Packet<?> packet, PacketListener listener) {
		if (BY_CONNECTION.isEmpty()) {
			return false;
		}
		Object connection = GhostPackets.connectionOf(listener);
		Session session = connection == null ? null : BY_CONNECTION.get(connection);
		if (session == null) {
			return false;
		}
		GhostPackets.Result result = GhostPackets.classify(packet, GhostPackets.isPlayListener(listener));
		synchronized (session.lock) {
			if (!session.ghost) {
				return false;
			}
			switch (result.kind()) {
				case PASS, CONFIG_START -> {
					return false;
				}
				case DROP -> {
					return true;
				}
				case BUFFER -> {
					buffer(session, packet);
					if (result.text() != null) {
						addLine(session, "chat", result.text());
					}
					return true;
				}
				case LOGIN -> {
					session.buffer.clear();
					session.overflowed = false;
					session.bufferStartsWithLogin = true;
					session.reconfiguring = false;
					session.buffer.add(packet);
					boolean firstPop = !session.popped;
					session.popped = true;
					addLine(session, "system", "Joined a new server - ready to switch!");
					if (firstPop) {
						MinecraftClient.getInstance().execute(() -> onPoppedCallback.accept(session.key));
					}
					return true;
				}
				case CHAT -> addLine(session, "chat", result.text());
				case OVERLAY -> {
					session.actionBar = result.text();
					session.actionBarTime = System.currentTimeMillis();
					parseStatus(session, result.text());
				}
				case TITLE -> {
					session.title = result.text();
					session.titleTime = System.currentTimeMillis();
					parseStatus(session, result.text());
				}
				case SUBTITLE -> {
					session.subtitle = result.text();
					session.titleTime = System.currentTimeMillis();
					parseStatus(session, result.text());
				}
				case CLEAR_TITLE -> {
					session.title = null;
					session.subtitle = null;
				}
				case TAB_LIST -> {
					session.tabHeader = result.text();
					session.tabFooter = result.extra();
					parseStatus(session, (result.text() + " " + result.extra()).trim());
				}
				case DISCONNECT -> {
					session.endedReason = result.text() == null || result.text().isBlank() ? "Disconnected by the server" : result.text();
					addLine(session, "system", "Disconnected: " + session.endedReason);
					return false;
				}
				case TRANSFER -> {
					session.endedReason = "The server moved you to another server";
					addLine(session, "system", session.endedReason);
					closeQuietly(session, "Velo: background session can't follow a transfer");
					return true;
				}
				default -> {
					return true;
				}
			}
			return true;
		}
	}

	private static void buffer(Session session, Packet<?> packet) {
		if (session.overflowed) {
			return;
		}
		if (session.buffer.size() >= MAX_BUFFERED_PACKETS) {
			// Free the memory now; switching back will reconnect instead of replaying.
			session.buffer.clear();
			session.overflowed = true;
			addLine(session, "system", "Lots happened on this server - switching back will rejoin it.");
			return;
		}
		session.buffer.add(packet);
	}

	private static void addLine(Session session, String kind, String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		LogLine line = new LogLine(System.currentTimeMillis(), kind, text);
		session.log.addLast(line);
		while (session.log.size() > MAX_LOG_LINES) {
			session.log.removeFirst();
		}
		parseStatus(session, text);
		String key = session.key;
		MinecraftClient.getInstance().execute(() -> onLineCallback.accept(key, line));
	}

	private static void parseStatus(Session session, String text) {
		if (text == null) {
			return;
		}
		String effectivePreset = "Custom".equals(parsePreset) ? "Custom" : session.preset;
		String regex = "Custom".equals(parsePreset) ? parseCustomRegex : "";
		QueueStatusParser.Status parsed = QueueStatusParser.parse(effectivePreset, regex, text);
		if (parsed != null) {
			session.status = parsed;
		}
	}

	//? if <26.1 {
	public static boolean isGhost(ClientPlayNetworkHandler handler) {
		return BY_CONNECTION.containsKey(handler.getConnection());
	}

	public static void onGhostReconfiguring(ClientPlayNetworkHandler handler) {
		markReconfiguring(BY_CONNECTION.get(handler.getConnection()));
	}
	//?} else {
	/*public static boolean isGhost(ClientPacketListener handler) {
		return BY_CONNECTION.containsKey(handler.getConnection());
	}

	public static void onGhostReconfiguring(ClientPacketListener handler) {
		markReconfiguring(BY_CONNECTION.get(handler.getConnection()));
	}
	*///?}

	private static void markReconfiguring(Session session) {
		if (session == null) {
			return;
		}
		synchronized (session.lock) {
			// The world we stashed belongs to the old backend - only the coming login is useful now.
			session.reconfiguring = true;
			session.buffer.clear();
			session.bufferStartsWithLogin = false;
			session.world = null;
			session.player = null;
			session.gameMode = null;
			addLine(session, "system", "The server is moving you (queue moving?) - hang on...");
		}
	}

	/** Every client tick: notice background connections that dropped. */
	public static void tick() {
		for (Session session : List.copyOf(SESSIONS.values())) {
			if (session.singleplayer || session.connection == null) {
				continue;
			}
			//? if <26.1 {
			boolean open = session.connection.isOpen();
			//?} else {
			/*boolean open = session.connection.isConnected();
			*///?}
			if (!open) {
				synchronized (session.lock) {
					if (session.endedReason == null) {
						session.endedReason = "Lost connection";
						addLine(session, "system", "Lost connection to the server.");
					}
					session.ghost = false;
					session.buffer.clear();
				}
				BY_CONNECTION.remove(session.connection);
			}
		}
	}

	// ---- Send to background ----

	/** Sends what you're playing to the background. Returns the session key, or null if there's nothing to capture. */
	public static String demote() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getNetworkHandler() == null) {
			return null;
		}
		if (ClientCompat.isSingleplayer()) {
			return captureSingleplayer(client);
		}
		return captureServer(client);
	}

	private static String captureSingleplayer(MinecraftClient client) {
		String folder = ClientCompat.singleplayerFolderName();
		String name = ClientCompat.singleplayerWorldName();
		if (folder == null) {
			return null;
		}
		Session session = new Session();
		session.singleplayer = true;
		session.spFolder = folder;
		session.displayName = name != null ? name : folder;
		session.address = "Singleplayer";
		session.key = uniqueKey("Singleplayer: " + session.displayName);
		addLine(session, "system", "Saved and closed - Resume reopens it.");
		SESSIONS.put(session.key, session);
		markSuppressAutoReconnect();
		client.disconnectWithSavingScreen();
		client.setScreen(new TitleScreen());
		return session.key;
	}

	private static String captureServer(MinecraftClient client) {
		var handler = client.getNetworkHandler();
		Session session = new Session();
		String address = ClientCompat.currentServerAddress();
		String name = ClientCompat.currentServerName();
		session.address = address != null ? address : "unknown";
		session.displayName = name != null ? name : session.address;
		session.preset = "Auto-detect".equals(parsePreset) ? QueueStatusParser.presetForHost(session.address) : parsePreset;
		session.key = uniqueKey(session.displayName);
		//? if <26.1 {
		session.serverInfo = client.getCurrentServerEntry();
		session.world = client.world;
		session.player = client.player;
		session.gameMode = client.interactionManager;
		//?} else {
		/*session.serverInfo = client.getCurrentServer();
		session.world = client.level;
		session.player = client.player;
		session.gameMode = client.gameMode;
		*///?}
		session.connection = handler.getConnection();

		if (!liveGhostsSupported()) {
			// Without the packet gate the connection can't survive in the background - remember it
			// and reconnect on switch, which at least keeps the server one click away.
			lastNotice = "Live background sessions aren't available on this setup - switching back will reconnect.";
			session.endedReason = "Will reconnect when you switch back";
			session.connection = null;
			SESSIONS.put(session.key, session);
			markSuppressAutoReconnect();
			//? if <26.1 {
			client.disconnect(Text.literal("Velo: moved to background"));
			//?} else {
			/*client.disconnectFromWorld(Component.literal("Velo: moved to background"));
			*///?}
			return session.key;
		}

		synchronized (session.lock) {
			session.ghost = true;
		}
		SESSIONS.put(session.key, session);
		BY_CONNECTION.put(session.connection, session);
		addLine(session, "system", "Running in the background - you're still connected.");
		try {
			// Let packets vanilla already queued for this frame apply while the world is still
			// attached - from here on, new ones go through the gate instead.
			//? if <26.1 {
			client.getPacketApplyBatcher().apply();
			client.enterReconfiguration(new MultiplayerScreen(new TitleScreen()));
			//?} else {
			/*client.packetProcessor().processQueuedPackets();
			client.clearClientLevel(new JoinMultiplayerScreen(new TitleScreen()));
			*///?}
		} catch (Exception e) {
			VeloClient.LOGGER.error("Velo background queue: detaching from the world failed", e);
			lastNotice = "Couldn't move this server to the background - " + e.getMessage();
			terminate(session.key);
			return null;
		}
		return session.key;
	}

	private static String uniqueKey(String base) {
		if (!SESSIONS.containsKey(base)) {
			return base;
		}
		int i = 2;
		while (SESSIONS.containsKey(base + " (" + i + ")")) {
			i++;
		}
		return base + " (" + i + ")";
	}

	// ---- Switch back ----

	/**
	 * Switches to a background session: backgrounds (or saves) whatever you're in now, then hands the
	 * live connection back - see the class doc for the details and the fallbacks.
	 */
	public static void promote(String key) {
		Session session = SESSIONS.get(key);
		if (session == null) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (!session.singleplayer && session.endedReason == null && session.reconfiguring) {
			lastNotice = "\"" + session.displayName + "\" is moving you between servers - try again in a moment.";
			return;
		}
		if (client.getNetworkHandler() != null) {
			demote();
		}
		if (session.singleplayer) {
			removeSession(key);
			resumeSingleplayer(client, session);
			return;
		}
		if (session.connection == null || session.endedReason != null) {
			removeSession(key);
			reconnect(client, session, session.endedReason == null ? null
					: "Rejoining \"" + session.displayName + "\" (" + session.endedReason + ")");
			return;
		}

		List<Packet<?>> replay;
		boolean fromLogin;
		synchronized (session.lock) {
			session.ghost = false;
			replay = new ArrayList<>(session.buffer);
			session.buffer.clear();
			fromLogin = session.bufferStartsWithLogin;
			if (session.overflowed || (!fromLogin && session.world == null)) {
				replay = null;
			}
		}
		BY_CONNECTION.remove(session.connection);
		removeSession(key);
		if (replay == null) {
			markSuppressAutoReconnect();
			closeQuietly(session, "Velo: rejoining");
			reconnect(client, session, "Rejoining \"" + session.displayName + "\" - too much happened there to switch back seamlessly.");
			return;
		}
		try {
			PacketListener listener = session.connection.getPacketListener();
			if (!fromLogin) {
				reattach(client, session, listener);
			}
			int failed = 0;
			for (Packet<?> packet : replay) {
				try {
					GhostPackets.apply(packet, listener);
				} catch (RuntimeException e) {
					// Same as vanilla would for one bad packet, minus the disconnect: log and carry on.
					failed++;
					VeloClient.LOGGER.warn("Velo background queue: a buffered packet failed to replay ({})", packet.getClass().getSimpleName(), e);
				}
			}
			lastNotice = failed == 0 ? "Back on \"" + session.displayName + "\" - you never left."
					: "Back on \"" + session.displayName + "\" (" + failed + " update(s) couldn't be replayed).";
		} catch (Exception e) {
			VeloClient.LOGGER.error("Velo background queue: switching back failed, reconnecting instead", e);
			markSuppressAutoReconnect();
			closeQuietly(session, "Velo: rejoining");
			reconnect(client, session, "Couldn't switch back seamlessly - rejoining instead.");
		}
	}

	/** Puts the world/player we stashed at "send to background" back in place, the way a normal join would. */
	private static void reattach(MinecraftClient client, Session session, PacketListener listener) {
		//? if <26.1 {
		ClientPlayNetworkHandler handler = (ClientPlayNetworkHandler) listener;
		((net.veloclient.velo.client.mixin.QueueHandlerAccessorMixin) handler).velo$setWorld(session.world);
		client.interactionManager = session.gameMode;
		client.joinWorld(session.world);
		client.player = session.player;
		client.setCameraEntity(session.player);
		// Sync the camera's own entity now rather than on the next render - other mods' camera hooks
		// (e.g. Flashback) read it before vanilla updates it and crashed on the stale value.
		client.gameRenderer.getCamera().update(session.world, session.player, false, false, 1f);
		client.setScreen(null);
		//?} else {
		/*ClientPacketListener handler = (ClientPacketListener) listener;
		((net.veloclient.velo.client.mixin.QueueHandlerAccessorMixin) handler).velo$setWorld(session.world);
		client.gameMode = session.gameMode;
		client.setLevel(session.world);
		client.player = session.player;
		client.setCameraEntity(session.player);
		client.setScreen(null);
		*///?}
	}

	private static void resumeSingleplayer(MinecraftClient client, Session session) {
		try {
			//? if <26.1 {
			client.createIntegratedServerLoader().start(session.spFolder, () -> client.setScreen(new TitleScreen()));
			//?} else {
			/*client.createWorldOpenFlows().openWorld(session.spFolder, () -> client.setScreen(new TitleScreen()));
			*///?}
		} catch (Exception e) {
			VeloClient.LOGGER.error("Velo background queue: reopening world '{}' failed", session.spFolder, e);
			lastNotice = "Couldn't reopen \"" + session.displayName + "\" - open it from the Singleplayer menu.";
		}
	}

	private static void reconnect(MinecraftClient client, Session session, String notice) {
		if (notice != null) {
			lastNotice = notice;
		}
		if (session.serverInfo == null) {
			ClientCompat.joinServer(session.address, session.displayName);
			return;
		}
		//? if <26.1 {
		ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()), client, ServerAddress.parse(session.address), session.serverInfo, false, null);
		//?} else {
		/*ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), client, ServerAddress.parseString(session.address),
				session.serverInfo, false, null);
		*///?}
	}

	/** Ends a background session for good (really leaves the server) or forgets a singleplayer resume point. */
	public static void terminate(String key) {
		Session session = removeSession(key);
		if (session == null || session.singleplayer || session.connection == null) {
			return;
		}
		synchronized (session.lock) {
			session.ghost = false;
			session.buffer.clear();
		}
		BY_CONNECTION.remove(session.connection);
		markSuppressAutoReconnect();
		closeQuietly(session, "Velo: background session ended");
	}

	private static void closeQuietly(Session session, String reason) {
		try {
			//? if <26.1 {
			session.connection.disconnect(Text.literal(reason));
			//?} else {
			/*session.connection.disconnect(Component.literal(reason));
			*///?}
		} catch (Exception ignored) {
			// Already gone.
		}
	}

	/** Sends a chat line or /command into a background session, as if typed there. */
	public static String sendChat(String key, String message) {
		Session session = SESSIONS.get(key);
		if (session == null || session.connection == null || session.endedReason != null) {
			return "That session isn't connected";
		}
		String trimmed = message.strip();
		if (trimmed.isEmpty()) {
			return null;
		}
		try {
			//? if <26.1 {
			if (!(session.connection.getPacketListener() instanceof ClientPlayNetworkHandler handler)) {
				return "That server is busy moving you - try again in a moment";
			}
			if (trimmed.startsWith("/")) {
				handler.sendChatCommand(trimmed.substring(1));
			} else {
				handler.sendChatMessage(trimmed);
			}
			//?} else {
			/*if (!(session.connection.getPacketListener() instanceof ClientPacketListener handler)) {
				return "That server is busy moving you - try again in a moment";
			}
			if (trimmed.startsWith("/")) {
				handler.sendCommand(trimmed.substring(1));
			} else {
				handler.sendChat(trimmed);
			}
			*///?}
			synchronized (session.lock) {
				addLine(session, "you", "> " + trimmed);
			}
			return null;
		} catch (Exception e) {
			return "Couldn't send: " + e.getMessage();
		}
	}

	private static Session removeSession(String key) {
		Session session = SESSIONS.remove(key);
		if (key != null && key.equals(peekedKey)) {
			peekedKey = null;
		}
		return session;
	}
}
