package net.veloclient.velo.client.modules.queue;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.veloclient.velo.VeloClient;
//? if <26.1 {
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.world.WorldIcon;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.text.Text;
import net.veloclient.velo.client.mixin.QueueHandlerAccessorMixin;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.chat.Component;
import net.veloclient.velo.client.mixin.QueueHandlerAccessorMixin;
*///?}

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Holds one "ghost" background session per server you've demoted (plus,
 * optionally, one "resume point" for whatever singleplayer world you left
 * behind), so you can queue on several servers at once, keep a way back to
 * the world you were playing before you started queuing, and switch between
 * all of it - see {@link BackgroundQueueModule} and {@link
 * BackgroundQueueSessionsScreen} for the user-facing side of this; this
 * class is purely the state machine.
 *
 * <p><b>Why this doesn't need a second, from-scratch protocol client</b>:
 * {@code MinecraftClient.world}/{@code .player} are just pointers vanilla
 * itself reassigns at join/disconnect - a live {@code
 * ClientPlayNetworkHandler} keeps its own connection and keeps processing
 * real packets on the Netty IO thread regardless of what those pointers
 * currently point at. So, on 1.21.11 (verified against decompiled source):
 * <ul>
 * <li><b>Demote</b> stashes the current {@code (handler, world, player,
 * interactionManager)} tuple, then does a "soft disconnect" - vanilla's own
 * {@code disconnect(Screen, transferring=true, stopSounds)} overload, which
 * does all the local cleanup (renderer, HUD, screen transition) but never
 * itself calls {@code connection.disconnect(...)} the way the plain {@code
 * disconnect(Text)} convenience method does. The connection, and the
 * server's belief that this player is still connected (e.g. still holding a
 * queue slot), survives untouched.</li>
 * <li><b>Promote</b> first captures whatever's currently active as its own
 * new session (another server becomes a ghost the same way Demote makes one;
 * singleplayer becomes a "resume point" - see below), then leaves it (a
 * normal, full disconnect/save-and-quit) and reattaches the target session's
 * stashed tuple directly - no reconnect, no packet replay.</li>
 * <li>If the queue pops <em>while backgrounded</em> (a fresh join - common
 * for proxy-based queues swapping you to the real backend), {@link
 * net.veloclient.velo.client.mixin.QueueHandlerJoinGuardMixin} intercepts it
 * before it can hijack the foreground, and this class replays it for real
 * once the player actually promotes.</li>
 * </ul>
 *
 * <p><b>Singleplayer "resume points"</b>: a local integrated-server world
 * can't be held open in the background the way a remote connection can (it's
 * not a socket, it's a whole embedded server) - vanilla's own save-and-quit
 * is already the right way to leave it. So instead of a live ghost, leaving
 * SP just remembers the world's save name; "Switch" on that entry re-opens
 * it through the exact same path the Singleplayer world list itself uses
 * ({@code createIntegratedServerLoader().start(name, ...)}), verified
 * against 1.21.11's decompiled source. On 26.x, where that path isn't
 * verified, the entry is still tracked (so you don't lose track of which
 * world you were on) but "Switch" just tells you to reopen it from the
 * normal Singleplayer menu instead of guessing at an unverified API.
 *
 * <p><b>Cross-version note</b>: 26.1/26.2 ship only Mojang's official
 * mappings with no equivalent full decompiled source available in this
 * environment to verify the deep {@code disconnect(...)}/world-loader
 * overload shapes against, so on those versions this deliberately does
 * *not* attempt the live in-memory handoff - demote there does a real
 * disconnect (which loses the queue slot, same as manually reconnecting)
 * and promote is a normal reconnect via the cached server entry. Every
 * other part of this module (parsing, HUD, keybinds, settings, multi-
 * session tracking) works identically everywhere.
 *
 * <p><b>Auto Reconnect interaction</b>: both demote and promote trigger a
 * real vanilla disconnect/reconnect at some point (always on 26.x, only as
 * an error fallback on 1.21.11) - since {@code AutoReconnectModule} reacts
 * to any {@code DisconnectedScreen} it sees, a Velo-initiated disconnect
 * needs to not look like an unexpected kick to it. {@link
 * #consumeAutoReconnectSuppression()} is checked by that module and
 * suppresses exactly one reconnect attempt whenever this class was the one
 * that closed the connection.
 */
public final class BackgroundQueueManager {

	//? if <26.1 {
	private static final Identifier FALLBACK_ICON = Identifier.ofVanilla("textures/misc/unknown_server.png");
	//?} else {
	/*private static final Identifier FALLBACK_ICON = Identifier.withDefaultNamespace("textures/misc/unknown_server.png");
	*///?}

	public record SessionSummary(String key, String displayName, String address, boolean singleplayer,
			boolean poppedReady, QueueStatusParser.Status status, List<String> recentMessages) {
	}

	//? if <26.1 {
	private static final class Session {
		ClientPlayNetworkHandler handler;
		ClientWorld world;
		ClientPlayerEntity player;
		ClientPlayerInteractionManager interactionManager;
		GameJoinS2CPacket pendingJoinPacket;
		ServerInfo serverInfo;
		Identifier icon;
		String displayName = "";
		String address = "";
		String preset = "Generic";
		boolean singleplayer;
		String spLevelName;
		QueueStatusParser.Status status = QueueStatusParser.Status.EMPTY;
		final Deque<String> recentMessages = new ArrayDeque<>();
	}
	//?} else {
	/*private static final class Session {
		ClientPacketListener handler;
		ClientLevel world;
		LocalPlayer player;
		MultiPlayerGameMode interactionManager;
		ClientboundLoginPacket pendingJoinPacket;
		ServerData serverInfo;
		Identifier icon;
		String displayName = "";
		String address = "";
		String preset = "Generic";
		boolean singleplayer;
		String spLevelName;
		QueueStatusParser.Status status = QueueStatusParser.Status.EMPTY;
		final Deque<String> recentMessages = new ArrayDeque<>();
	}
	*///?}

	private static final Map<String, Session> SESSIONS = new LinkedHashMap<>();
	private static String peekedKey;
	private static Screen fallbackParentScreen;
	private static long suppressAutoReconnectUntilMs;
	private static String parsePreset = "Auto-detect";
	private static String parseCustomRegex = "";

	private static Consumer<String> onPoppedCallback = key -> {
	};
	private static BiConsumer<String, String> onTextCallback = (key, text) -> {
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

	public static void setOnText(BiConsumer<String, String> callback) {
		onTextCallback = callback == null ? (key, text) -> {
		} : callback;
	}

	public static List<SessionSummary> sessions() {
		List<SessionSummary> out = new ArrayList<>();
		for (var entry : SESSIONS.entrySet()) {
			out.add(toSummary(entry.getKey(), entry.getValue()));
		}
		return out;
	}

	public static boolean hasSessions() {
		return !SESSIONS.isEmpty();
	}

	public static SessionSummary summaryFor(String key) {
		Session s = SESSIONS.get(key);
		return s == null ? null : toSummary(key, s);
	}

	private static SessionSummary toSummary(String key, Session s) {
		return new SessionSummary(key, s.displayName, s.address, s.singleplayer, s.pendingJoinPacket != null,
				s.status, List.copyOf(s.recentMessages));
	}

	public static Identifier sessionIcon(String key) {
		Session s = SESSIONS.get(key);
		return s == null || s.icon == null ? FALLBACK_ICON : s.icon;
	}

	public static void setPeeked(String key) {
		Session s = SESSIONS.get(key);
		peekedKey = s != null && !s.singleplayer ? key : null;
	}

	public static void clearPeeked() {
		peekedKey = null;
	}

	public static String peekedKey() {
		return peekedKey;
	}

	public static SessionSummary peekedSummary() {
		if (peekedKey == null) {
			return null;
		}
		Session s = SESSIONS.get(peekedKey);
		return s == null ? null : toSummary(peekedKey, s);
	}

	/** Checked by {@code AutoReconnectModule} - true (and consumed) exactly once whenever this class just closed a connection on purpose. */
	public static boolean consumeAutoReconnectSuppression() {
		boolean suppressed = System.currentTimeMillis() < suppressAutoReconnectUntilMs;
		suppressAutoReconnectUntilMs = 0;
		return suppressed;
	}

	private static void markSuppressAutoReconnect() {
		// Generous window, not a precise handshake - the disconnect screen
		// this guards against always appears within a tick or two of the
		// connection actually closing.
		suppressAutoReconnectUntilMs = System.currentTimeMillis() + 5000;
	}

	//? if <26.1 {
	public static boolean isBackground(ClientPlayNetworkHandler handler) {
		return findKeyForHandler(handler) != null;
	}

	private static String findKeyForHandler(ClientPlayNetworkHandler handler) {
		for (var e : SESSIONS.entrySet()) {
			if (e.getValue().handler == handler) {
				return e.getKey();
			}
		}
		return null;
	}

	public static void onBackgroundText(ClientPlayNetworkHandler handler, String text) {
		String key = findKeyForHandler(handler);
		if (key != null) {
			recordText(key, text);
		}
	}

	public static void onBackgroundJoinIntercepted(ClientPlayNetworkHandler handler, GameJoinS2CPacket packet) {
		String key = findKeyForHandler(handler);
		if (key != null) {
			SESSIONS.get(key).pendingJoinPacket = packet;
			onPoppedCallback.accept(key);
		}
	}
	//?} else {
	/*public static boolean isBackground(ClientPacketListener handler) {
		return findKeyForHandler(handler) != null;
	}

	private static String findKeyForHandler(ClientPacketListener handler) {
		for (var e : SESSIONS.entrySet()) {
			if (e.getValue().handler == handler) {
				return e.getKey();
			}
		}
		return null;
	}

	public static void onBackgroundText(ClientPacketListener handler, String text) {
		String key = findKeyForHandler(handler);
		if (key != null) {
			recordText(key, text);
		}
	}

	public static void onBackgroundJoinIntercepted(ClientPacketListener handler, ClientboundLoginPacket packet) {
		String key = findKeyForHandler(handler);
		if (key != null) {
			SESSIONS.get(key).pendingJoinPacket = packet;
			onPoppedCallback.accept(key);
		}
	}
	*///?}

	private static void recordText(String key, String text) {
		Session s = SESSIONS.get(key);
		s.recentMessages.addLast(text);
		while (s.recentMessages.size() > 6) {
			s.recentMessages.removeFirst();
		}
		String effectivePreset = "Custom".equals(parsePreset) ? "Custom" : s.preset;
		String regex = "Custom".equals(parsePreset) ? parseCustomRegex : "";
		QueueStatusParser.Status parsed = QueueStatusParser.parse(effectivePreset, regex, text);
		if (parsed != null) {
			s.status = parsed;
		}
		onTextCallback.accept(key, text);
	}

	/** Whichever session's chat is currently being mirrored into the main chat, if any (the peeked one). */
	public static boolean isPeeked(String key) {
		return key != null && key.equals(peekedKey);
	}

	/** Sends the currently-active server (or singleplayer world) to the background/a resume point. Returns its key, or null if there's nothing to capture (title screen, nothing active). */
	public static String demote() {
		MinecraftClient client = MinecraftClient.getInstance();
		return captureCurrent(client);
	}

	//? if <26.1 {
	private static String captureCurrent(MinecraftClient client) {
		boolean inSingleplayer = client.isInSingleplayer();
	//?} else {
	/*private static String captureCurrent(MinecraftClient client) {
		boolean inSingleplayer = client.hasSingleplayerServer();
	*///?}
		var handler = client.getNetworkHandler();
		if (handler == null) {
			return null;
		}
		if (inSingleplayer) {
			return captureSingleplayer(client);
		}
		return captureServer(client, handler);
	}

	private static String captureSingleplayer(MinecraftClient client) {
		//? if <26.1 {
		String levelName;
		try {
			levelName = client.getServer().getSaveProperties().getLevelName();
		} catch (Exception e) {
			return null;
		}
		//?} else {
		/*return null;
		*///?}
		//? if <26.1 {
		Session session = new Session();
		session.singleplayer = true;
		session.spLevelName = levelName;
		session.displayName = levelName;
		session.address = "Singleplayer";
		String key = uniqueKey("Singleplayer: " + levelName);
		SESSIONS.put(key, session);

		markSuppressAutoReconnect();
		try {
			client.disconnect(new TitleScreen(), true, true);
		} catch (Exception e) {
			VeloClient.LOGGER.error("Velo background queue: leaving singleplayer failed", e);
			SESSIONS.remove(key);
			return null;
		}
		return key;
		//?}
	}

	private static String captureServer(MinecraftClient client, Object handlerObj) {
		//? if <26.1 {
		ClientPlayNetworkHandler handler = (ClientPlayNetworkHandler) handlerObj;
		//?} else {
		/*ClientPacketListener handler = (ClientPacketListener) handlerObj;
		*///?}
		Session session = new Session();
		session.handler = handler;
		session.world = client.world;
		session.player = client.player;
		//? if <26.1 {
		session.interactionManager = client.interactionManager;
		//?} else {
		/*session.interactionManager = client.gameMode;
		*///?}

		//? if <26.1 {
		ServerInfo serverInfo = client.getCurrentServerEntry();
		String address = serverInfo != null && serverInfo.address != null ? serverInfo.address : "unknown";
		String name = serverInfo != null && serverInfo.name != null ? serverInfo.name : address;
		session.serverInfo = serverInfo;
		if (serverInfo != null && serverInfo.getFavicon() != null) {
			try {
				WorldIcon icon = WorldIcon.forServer(client.getTextureManager(), serverInfo.address);
				icon.load(NativeImage.read(serverInfo.getFavicon()));
				session.icon = icon.getTextureId();
			} catch (Exception ignored) {
				// Bad/corrupt favicon data - keep the generic fallback icon rather than failing the whole demote.
			}
		}
		//?} else {
		/*ServerData serverInfo = client.getCurrentServer();
		String address = serverInfo != null && serverInfo.ip != null ? serverInfo.ip : "unknown";
		String name = serverInfo != null && serverInfo.name != null ? serverInfo.name : address;
		session.serverInfo = serverInfo;
		*///?}
		session.address = address;
		session.displayName = name;
		session.preset = "Auto-detect".equals(parsePreset) ? QueueStatusParser.presetForHost(address) : parsePreset;
		String key = uniqueKey(address);
		SESSIONS.put(key, session);
		markSuppressAutoReconnect();

		//? if <26.1 {
		try {
			client.disconnect(new TitleScreen(), true, true);
		} catch (Exception e) {
			VeloClient.LOGGER.error("Velo background queue: soft-disconnect failed, doing a real disconnect instead", e);
			SESSIONS.remove(key);
			client.disconnect(Text.literal("Velo: background queue error"));
			return null;
		}
		//?} else {
		/*try {
			handler.getConnection().disconnect(Component.literal("Velo: moved to background"));
		} catch (Exception e) {
			VeloClient.LOGGER.error("Velo background queue: disconnect failed", e);
			SESSIONS.remove(key);
			return null;
		}
		*///?}
		return key;
	}

	private static String uniqueKey(String address) {
		if (!SESSIONS.containsKey(address)) {
			return address;
		}
		int i = 2;
		while (SESSIONS.containsKey(address + " (" + i + ")")) {
			i++;
		}
		return address + " (" + i + ")";
	}

	/**
	 * Leaves whatever's currently active - capturing it as its own new
	 * session first, the same way {@link #demote()} would - and switches to
	 * actually playing the given session (or reconnects/re-opens it, where a
	 * live handoff isn't available).
	 */
	public static void promote(String key) {
		Session session = SESSIONS.get(key);
		if (session == null) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		try {
			if (client.getNetworkHandler() != null) {
				captureCurrent(client);
				// Re-fetch: capturing the outgoing session may have shifted
				// map iteration order or (in the singleplayer case) not
				// affected this entry at all, but the map itself is the
				// same instance either way - just guard against the target
				// having somehow been the one just captured (can't happen
				// today since capture always operates on the *foreground*
				// session and this one is, by definition, backgrounded).
				session = SESSIONS.get(key);
				if (session == null) {
					return;
				}
			}
			if (session.singleplayer) {
				resumeSingleplayer(client, key, session);
				return;
			}
			//? if <26.1 {
			if (session.pendingJoinPacket != null) {
				GameJoinS2CPacket packet = session.pendingJoinPacket;
				ClientPlayNetworkHandler handler = session.handler;
				removeSession(key);
				handler.onGameJoin(packet);
			} else {
				QueueHandlerAccessorMixin accessor = (QueueHandlerAccessorMixin) (Object) session.handler;
				accessor.velo$setWorld(session.world);
				accessor.velo$setWorldCleared(false);
				client.joinWorld(session.world);
				client.player = session.player;
				client.interactionManager = session.interactionManager;
				client.setCameraEntity(session.player);
				client.setScreen(null);
				removeSession(key);
			}
			//?} else {
			/*removeSession(key);
			reconnectSession(client, session);
			*///?}
		} catch (Exception e) {
			VeloClient.LOGGER.error("Velo background queue: promote failed, falling back to reconnect", e);
			removeSession(key);
			reconnectSession(client, session);
		}
	}

	private static void resumeSingleplayer(MinecraftClient client, String key, Session session) {
		removeSession(key);
		//? if <26.1 {
		try {
			client.createIntegratedServerLoader().start(session.spLevelName, () -> {
			});
		} catch (Exception e) {
			VeloClient.LOGGER.error("Velo background queue: resuming singleplayer world '{}' failed", session.spLevelName, e);
		}
		//?} else {
		/*// Not verified safe on this version (see class javadoc) - the
		// entry was still tracked so the world name isn't lost, but
		// re-opening it has to go through the normal Singleplayer menu.
		*///?}
	}

	/** Ends a background session for good (a real disconnect, not a switch) or forgets a singleplayer resume point (the save itself is never touched). */
	public static void terminate(String key) {
		Session session = removeSession(key);
		if (session == null || session.singleplayer || session.handler == null) {
			return;
		}
		markSuppressAutoReconnect();
		//? if <26.1 {
		try {
			session.handler.getConnection().disconnect(Text.literal("Velo: background session ended"));
		} catch (Exception ignored) {
			// Already gone - nothing left to clean up.
		}
		//?} else {
		/*try {
			session.handler.getConnection().disconnect(Component.literal("Velo: background session ended"));
		} catch (Exception ignored) {
		}
		*///?}
	}

	//? if <26.1 {
	private static Session removeSession(String key) {
	//?} else {
	/*private static Session removeSession(String key) {
	*///?}
		Session session = SESSIONS.remove(key);
		if (peekedKey != null && peekedKey.equals(key)) {
			peekedKey = null;
		}
		return session;
	}

	//? if <26.1 {
	private static void reconnectSession(MinecraftClient client, Session session) {
		if (session.serverInfo == null) {
			return;
		}
		ServerAddress address = ServerAddress.parse(session.address);
		ConnectScreen.connect(fallbackParentScreen != null ? fallbackParentScreen : new TitleScreen(),
				client, address, session.serverInfo, false, null);
	}
	//?} else {
	/*private static void reconnectSession(MinecraftClient client, Session session) {
		if (session.serverInfo == null) {
			return;
		}
		ServerAddress address = ServerAddress.parseString(session.address);
		ConnectScreen.startConnecting(fallbackParentScreen != null ? fallbackParentScreen : new TitleScreen(),
				client, address, session.serverInfo, false, null);
	}
	*///?}
}
