package net.veloclient.velo.devbuild.rewind;

/**
 * Velo Rewind playback (26.2+): the game joins the recording like a server. A {@code Connection} on a Netty
 * EmbeddedChannel (in-memory pipeline, like singleplayer) gets the login handshake's end, the recording's configuration
 * (registries, tags), the segment's snapshot and then the recorded packets tick by tick - handled on the game thread
 * right away. The viewer is a spectator camera; the recorded player keeps its own entity (its id and UUID are kept apart
 * from the camera's). Pause / speed go through the game's own tick freezing; jumping back restarts the segment from its
 * snapshot and fast-forwards.
 */
//? if <26.2 {
final class RewindPlayer {

	private RewindPlayer() {
	}

	static boolean active() {
		return false;
	}

	static void tick() {
	}

	static void flushEdits() {
	}

	static void testTick() {
	}
}
//?} else {
/*import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.network.Connection;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RewindPlayer {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Velo Rewind");
	// The camera's entity id - far from anything a server hands out.
	private static final int CAMERA_ID = 2_000_000_007;
	// What a replay must not do to the viewer: move / kill / inventory the camera, change its mode, open screens.
	private static final Set<String> DROP = Set.of("ClientboundPlayerPositionPacket", "ClientboundSetHealthPacket", "ClientboundSetExperiencePacket",
			"ClientboundContainerSetContentPacket", "ClientboundContainerSetSlotPacket", "ClientboundSetCursorItemPacket",
			"ClientboundSetPlayerInventoryPacket", "ClientboundSetHeldSlotPacket", "ClientboundPlayerAbilitiesPacket", "ClientboundPlayerCombatKillPacket",
			"ClientboundSetCameraPacket", "ClientboundOpenScreenPacket", "ClientboundOpenBookPacket", "ClientboundMountScreenOpenPacket",
			"ClientboundContainerClosePacket", "ClientboundResourcePackPushPacket", "ClientboundResourcePackPopPacket",
			"ClientboundShowDialogPacket", "ClientboundClearDialogPacket", "ClientboundCommandsPacket", "ClientboundRecipeBookAddPacket",
			"ClientboundUpdateAdvancementsPacket", "ClientboundCooldownPacket", "ClientboundPlayerLookAtPacket", "ClientboundStartConfigurationPacket");

	private static RewindPlayer current;
	private static final Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private final Path file;
	private final RewindReader reader;
	private final List<int[]> segments = new ArrayList<>(); // {config, ticks}
	private final int totalTicks;
	private final int selfId;
	private final byte[] selfUuid;
	private byte[] fakeUuid = uuidBytes(UUID.randomUUID());
	private EmbeddedChannel channel;
	private Connection connection;
	private RewindReader.Frames frames;
	private int segment;
	private int segmentStart;   // absolute tick at the segment's start
	private int tick;           // absolute tick played
	private boolean paused;
	private float speed = 1f;
	private boolean ended;
	private int loadWait;
	private int spectatorTries;
	private Vec3 cameraAt;
	// Chunks a packet changed since the segment's snapshot: jumping back re-sends only these (the rest is still right),
	// so the world doesn't reload. A dimension change in between means a real rejoin.
	private final java.util.Set<Long> dirty = new java.util.HashSet<>();
	private boolean inSnapshot;
	private boolean softSnapshot;
	private java.util.Set<Long> redoChunks;
	private boolean dimensionChanged;
	RewindEdits edits;

	private RewindPlayer(Path file) throws Exception {
		this.file = file;
		this.reader = new RewindReader(file);
		String meta = reader.meta();
		Matcher seg = Pattern.compile("\"config\":\\s*(\\d+),\\s*\"ticks\":\\s*(\\d+)").matcher(meta);
		int total = 0;
		while (seg.find()) {
			int t = Integer.parseInt(seg.group(2));
			segments.add(new int[] {Integer.parseInt(seg.group(1)), t});
			total += t;
		}
		totalTicks = total;
		Matcher id = Pattern.compile("\"selfId\":\\s*(-?\\d+)").matcher(meta);
		selfId = id.find() ? Integer.parseInt(id.group(1)) : -1;
		Matcher uuid = Pattern.compile("\"selfUuid\":\\s*\"([0-9a-f-]+)\"").matcher(meta);
		selfUuid = uuid.find() ? uuidBytes(UUID.fromString(uuid.group(1))) : null;
		if (selfUuid != null) {
			// stable per recording, so hidden entities / skin swaps saved for the recorder's own player still match
			fakeUuid = uuidBytes(UUID.nameUUIDFromBytes(("velo-rewind:" + uuid.group(1)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		int[] ticks = new int[segments.size()];
		for (int i = 0; i < ticks.length; i++) {
			ticks[i] = segments.get(i)[1];
		}
		edits = RewindEdits.load(file, ticks);
		autosave = new RewindAutosave(file, edits);
	}

	final RewindAutosave autosave;

	Path file() {
		return file;
	}

	// After every edit: saved once the changes settle (see RewindAutosave), and an undo step.
	void saveEdits() {
		autosave.changed();
	}

	boolean undo() {
		return restoreEdits(autosave.undo(edits));
	}

	boolean redo() {
		return restoreEdits(autosave.redo());
	}

	// Replaces the edits (the AI API's PUT /edits): one undo step.
	boolean replaceEdits(RewindEdits e) {
		if (!restoreEdits(e)) {
			return false;
		}
		saveEdits();
		return true;
	}

	private boolean restoreEdits(RewindEdits e) {
		if (e == null || e.clips == null || e.clips.isEmpty()) {
			return false;
		}
		int before = edits.selected;
		e.selected = Math.max(0, Math.min(e.clips.size() - 1, e.selected));
		edits = e;
		if (e.selected != before) {
			seek(clipStart(edits.clip()));
		}
		return true;
	}

	int segmentStart(int index) {
		int s = 0;
		for (int i = 0; i < index && i < segments.size(); i++) {
			s += segments.get(i)[1];
		}
		return s;
	}

	// Absolute tick where clip {@code c} starts / ends.
	int clipStart(RewindEdits.Clip c) {
		return segmentStart(c.segment) + c.start;
	}

	int clipEnd(RewindEdits.Clip c) {
		return clipStart(c) + c.length;
	}

	// The playhead inside the selected clip (ticks from its start).
	int clipPosition() {
		return Math.max(0, Math.min(edits.clip().length, tick - clipStart(edits.clip())));
	}

	void selectClip(int index) {
		edits.selected = Math.max(0, Math.min(edits.clips.size() - 1, index));
		seek(clipStart(edits.clip()));
		saveEdits();
	}

	// A new clip: a copy of the selected one's range with an empty timeline.
	void addClip() {
		RewindEdits.Clip from = edits.clip();
		RewindEdits.Clip c = new RewindEdits.Clip();
		c.segment = from.segment;
		c.start = from.start;
		c.length = from.length;
		c.name = "Clip " + (edits.clips.size() + 1);
		edits.clips.add(c);
		selectClip(edits.clips.size() - 1);
	}

	// Seeks inside the selected clip.
	void seekClip(int clipTick) {
		RewindEdits.Clip c = edits.clip();
		seek(clipStart(c) + Math.max(0, Math.min(c.length, clipTick)));
	}

	int segmentLength(int index) {
		return index >= 0 && index < segments.size() ? segments.get(index)[1] : 0;
	}

	int segmentCount() {
		return segments.size();
	}

	static boolean active() {
		return current != null;
	}

	static RewindPlayer current() {
		return current;
	}

	// ---- open / close ----

	static void open(Path file, Screen from) {
		Minecraft mc = Minecraft.getInstance();
		if (current != null || mc.level != null) {
			return;
		}
		try {
			RewindPlayer p = new RewindPlayer(file);
			if (p.segments.isEmpty()) {
				throw new IllegalStateException("empty recording");
			}
			current = p;
			p.connect(0);
			RewindSound.open(file);
		} catch (Exception e) {
			LOG.warn("Velo Rewind couldn't open {}", file, e);
			current = null;
			net.minecraft.client.gui.components.toasts.SystemToast.add(mc.gui.toastManager(),
					net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
					net.minecraft.network.chat.Component.literal("Velo Rewind"), net.minecraft.network.chat.Component.literal("Couldn't open: " + e.getMessage()));
		}
	}

	// Joins the recording at the start of segment {@code index}: handshake, its configuration, its snapshot.
	private void connect(int index) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		segment = index;
		segmentStart = 0;
		for (int i = 0; i < index; i++) {
			segmentStart += segments.get(i)[1];
		}
		tick = segmentStart;
		// Unregistered first: the Connection must be in the pipeline before the channel goes active (it takes its channel there).
		channel = new EmbeddedChannel(false, false);
		Connection.configureInMemoryPipeline(channel.pipeline(), PacketFlow.CLIENTBOUND);
		connection = new Connection(PacketFlow.CLIENTBOUND);
		channel.pipeline().addLast("packet_handler", connection);
		channel.register();
		ClientHandshakePacketListenerImpl login = new ClientHandshakePacketListenerImpl(connection, mc, null, new TitleScreen(), false, null,
				status -> { }, new LevelLoadTracker(0L) {
					// Vanilla waits for the player's chunk section to be compiled, a signal Sodium never sends (so every join
					// took the 30 s timeout): a replay is ready once its world and camera exist.
					@Override
					public boolean isLevelReady() {
						Minecraft m = Minecraft.getInstance();
						return m.level != null && m.player != null || super.isLevelReady();
					}
				}, new TransferState(java.util.Map.of(), java.util.Map.of(), false));
		connection.initiateServerboundPlayConnection("velo-rewind", 0, login);
		setPending(mc, connection);
		deliver(new ClientboundLoginFinishedPacket(mc.getGameProfile(), UUID.randomUUID()));
		try (RewindReader.Frames config = reader.open("config-" + segments.get(index)[0] + ".bin")) {
			for (RewindReader.Frame f; (f = config.next()) != null; ) {
				if (f.type() == RewindWriter.F_PACKET) {
					Packet<?> p = decode(ConfigurationProtocols.CLIENTBOUND, f.body());
					if (p != null) {
						deliver(p);
					}
				}
			}
		}
		frames = reader.open("seg-" + index + ".bin");
		dirty.clear();
		dimensionChanged = false;
		inSnapshot = true;
		// the snapshot, all at once
		for (RewindReader.Frame f; (f = frames.next()) != null; ) {
			if (f.type() == RewindWriter.F_SNAPSHOT_END) {
				break;
			}
			if (f.type() == RewindWriter.F_PACKET) {
				play(f.body());
			}
		}
		inSnapshot = false;
		deliver(new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0f));
		if (cameraAt != null) {
			deliver(new ClientboundPlayerPositionPacket(CAMERA_ID, new PositionMoveRotation(cameraAt.add(0, 1.5, 2.5), Vec3.ZERO, 180f, 20f), Set.of()));
		}
		applyRate();
		LOG.info("Velo Rewind: playing {} from tick {} of {}", file.getFileName(), tick, totalTicks);
	}

	private static java.lang.reflect.Field pendingField;

	// The game ticks its pending connection while it logs in (as for a server it connects to).
	private static void setPending(Minecraft mc, Connection c) {
		try {
			if (pendingField == null) {
				pendingField = Minecraft.class.getDeclaredField("pendingConnection");
				pendingField.setAccessible(true);
			}
			pendingField.set(mc, c);
		} catch (ReflectiveOperationException e) {
			LOG.warn("Velo Rewind: no pending connection", e);
		}
	}

	// The game is closing: the edits on disk now.
	// The viewer needs its own player-list entry in spectator mode: without one the game doesn't count the camera as a
	// spectator, and it bumps into blocks instead of flying through them (Phantom).
	void viewerSpectator(Minecraft mc) {
		try {
			var packet = new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(java.util.EnumSet.of(
					net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
					net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE), java.util.List.of());
			var profile = mc.getGameProfile();
			var f = net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.class.getDeclaredField("entries");
			f.setAccessible(true);
			f.set(packet, java.util.List.of(new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry(profile.id(), profile, false, 0,
					GameType.SPECTATOR, null, true, 0, null)));
			deliver(packet);
			if (mc.gameMode != null) {
				mc.gameMode.setLocalMode(GameType.SPECTATOR);
			}
		} catch (Exception e) {
			LOG.warn("Velo Rewind: couldn't make the camera a spectator", e);
		}
	}

	static void flushEdits() {
		RewindPlayer p = current;
		if (p != null) {
			p.autosave.flush(p.edits);
		}
	}

	static void close() {
		RewindPlayer p = current;
		current = null;
		if (p == null) {
			return;
		}
		p.autosave.flush(p.edits);
		RewindSound.close();
		try {
			if (p.frames != null) {
				p.frames.close();
			}
			p.reader.close();
		} catch (Exception ignored) {
		}
		if (p.channel != null) {
			p.channel.finishAndReleaseAll();
		}
	}

	// Leaves the replay (back to the library).
	static void exit() {
		Minecraft mc = Minecraft.getInstance();
		close();
		mc.disconnect(new RewindLibraryScreen(new TitleScreen()), false);
	}

	// ---- self test: -Dvelo.rewindPlayTest=<file>: open it from the title screen, watch, jump around, screenshot, quit ----

	private static final String PLAY_TEST = System.getProperty("velo.rewindPlayTest");
	private static int testTicks;
	private static RewindEditorScreen testEditor;

	static void testTick() {
		if (PLAY_TEST == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		testTicks++;
		if (testTicks == 60 && mc.level == null) {
			mc.options.pauseOnLostFocus = false; // the hidden test window never has focus
			open(Path.of(PLAY_TEST), mc.gui.screen());
		}
		RewindPlayer p = current;
		if (p == null || mc.player == null) {
			return;
		}
		if (testTicks == 150) {
			// a camera move, a whole-clip time of day and some rain
			var cam = mc.gameRenderer.mainCamera();
			RewindEdits.Clip c = p.edits.clip();
			c.tracks.clear();
			put(c, "cam_x", 0, cam.position().x);
			put(c, "cam_x", 80, cam.position().x + 6);
			put(c, "cam_y", 0, cam.position().y + 2);
			put(c, "cam_z", 0, cam.position().z);
			put(c, "cam_yaw", 0, cam.yRot());
			put(c, "cam_yaw", 80, cam.yRot() + 40);
			put(c, "cam_pitch", 0, 20);
			put(c, "cam_roll", 40, 8);
			c.track("time").global = 13000.0;
			put(c, "rain", 0, 0);
			put(c, "rain", 60, 1);
			c.track("cam_x").keys.get(80).label = "pan";
		}
		if (testTicks == 200) {
			shot(mc, "rewind-play-1.png");
			LOG.info("Velo Rewind test: tick {} / {}, entities {}", p.position(), p.totalTicks(), mc.level.getEntityCount());
		}
		if (testTicks == 205) {
			shot(mc, "rewind-play-viewport.png"); // opens in the viewport: bar + keys
			mc.setScreen(new RewindEditorScreen());
		}
		if (testTicks == 210) {
			p.seekClip(20);
		}
		if (testTicks == 240) {
			shot(mc, "rewind-play-seek.png");
			LOG.info("Velo Rewind test after soft seek: clip tick {}, level still {}", p.clipPosition(), mc.level != null);
		}
		if (testTicks == 250) {
			mc.setScreen(null);
		}
		if (testTicks == 290) {
			shot(mc, "rewind-play-bar.png");
		}
		if (testTicks == 300 && !Boolean.getBoolean("velo.rewindBarDebug")) {
			// the test never records the speakers
			RewindExport.audioMode = RewindExport.AUDIO_MODES.get("voice".equals(System.getProperty("velo.rewindTestAudio")) ? 1 : 2);
			LOG.info("Velo Rewind test: viewer spectator {}, audio {}", mc.player.isSpectator(), RewindSound.status());
			// like a player: open the editor, its export window, press Start
			testEditor = new RewindEditorScreen();
			mc.setScreen(testEditor);
			testEditor.testOpenExport();
		}
		if (testTicks == 304 && testEditor != null) {
			LOG.info("Velo Rewind test: Start pressed {}", testEditor.testPress("Start"));
		}
		if (testTicks == 320) {
			shot(mc, "rewind-export-progress.png");
		}
		if (testTicks > 300 && (RewindExport.state == RewindExport.State.DONE || RewindExport.state == RewindExport.State.FAILED) && exportShot == 0) {
			exportShot = testTicks;
			LOG.info("Velo Rewind test export: {} {} -> {}", RewindExport.state, RewindExport.message, RewindExport.output);
		}
		if (exportShot > 0 && testTicks == exportShot + 20) {
			shot(mc, "rewind-play-export.png");
		}
		if (exportShot > 0 && testTicks == exportShot + 30 || testTicks > 2400 || testTicks == 320 && Boolean.getBoolean("velo.rewindBarDebug")) {
			mc.stop();
		}
	}

	private static int exportShot;

	private static void put(RewindEdits.Clip c, String id, int tick, double value) {
		RewindEdits.Key k = new RewindEdits.Key();
		k.value = value;
		c.track(id).keys.put(tick, k);
	}

	private static void shot(Minecraft mc, String name) {
		net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, msg -> LOG.info("Velo Rewind test shot {}", name));
	}

	// ---- per game tick ----

	static void tick() {
		RewindPlayer p = current;
		if (p == null) {
			return;
		}
		p.autosave.tick(p.edits);
		Minecraft mc = Minecraft.getInstance();
		if (p.connection != null && !p.connection.isConnected()) {
			close(); // left the replay
			return;
		}
		if (mc.level == null && p.loadWait++ > 1200) {
			// never got into its world: back to the library with a message instead of a black screen
			LOG.warn("Velo Rewind: {} didn't load in 60 s", p.file.getFileName());
			exit();
			net.minecraft.client.gui.components.toasts.SystemToast.add(mc.gui.toastManager(),
					net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
					net.minecraft.network.chat.Component.literal("Velo Rewind"), net.minecraft.network.chat.Component.literal("The replay didn't load"));
			return;
		}
		if (mc.player != null && mc.level != null) {
			RewindSound.tick(p);
		}
		if (mc.player != null && !mc.player.isSpectator() && p.spectatorTries++ < 40 && mc.level.getGameTime() % 10 == 0) {
			p.viewerSpectator(mc);
		}
		p.channel.releaseOutbound(); // what the game "sends to the server" goes nowhere
		if (mc.level == null || mc.player == null || p.paused || p.ended) {
			return;
		}
		p.loadWait = 0;
		if (p.tick >= p.clipEnd(p.edits.clip())) {
			p.paused = true; // the selected clip ends here
			p.applyRate();
			return;
		}
		p.advance(1);
	}

	// Plays {@code n} recorded ticks (stops at a segment end; the next segment needs a fresh join).
	private void advance(int n) {
		try {
			while (n > 0) {
				RewindReader.Frame f = frames.next();
				if (f == null) {
					if (segment + 1 < segments.size()) {
						int next = segment + 1;
						frames.close();
						if (canSoft(next)) {
							softReset(next);
						} else {
							reconnect(next, -1);
						}
					} else {
						ended = true;
						paused = true;
						applyRate();
					}
					return;
				}
				if (f.type() == RewindWriter.F_TICK) {
					tick++;
					n--;
				} else if (f.type() == RewindWriter.F_PACKET) {
					play(f.body());
				}
			}
		} catch (Exception e) {
			LOG.warn("Velo Rewind playback failed", e);
			ended = true;
		}
	}

	// ---- controls (the viewer's) ----

	// Each clip's length in ticks (a clip = start / resume to pause / stop of the recording).
	int[] clipTicks() {
		int[] out = new int[segments.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = segments.get(i)[1];
		}
		return out;
	}

	// Keyframes (ticks) per timeline track - camera paths, speed and FOV build on them.
	final java.util.Map<String, java.util.TreeSet<Integer>> tracks = new java.util.LinkedHashMap<>(java.util.Map.of());
	{
		for (String t : new String[] {"Camera", "Speed", "FOV"}) {
			tracks.put(t, new java.util.TreeSet<>());
		}
	}
	final java.util.TreeSet<Integer> keyframes = tracks.get("Camera");
	boolean editorOpened;

	int position() {
		return tick;
	}

	int totalTicks() {
		return totalTicks;
	}

	boolean paused() {
		return paused;
	}

	float speed() {
		return speed;
	}

	String name() {
		return file.getFileName().toString().replaceFirst("\\.vrewind$", "");
	}

	void togglePause() {
		if (paused && (ended || tick >= clipEnd(edits.clip()))) {
			ended = false;
			seek(clipStart(edits.clip())); // from the clip's start again
		}
		paused = !paused;
		applyRate();
	}

	void setSpeed(float s) {
		speed = Math.max(0.1f, Math.min(8f, s));
		applyRate();
	}

	// Jumps to {@code target}: forward = fast-forward from here, back = rejoin the segment and fast-forward from its start.
	void seek(int target) {
		target = Math.max(0, Math.min(totalTicks, target));
		int segIndex = 0;
		int start = 0;
		while (segIndex + 1 < segments.size() && target >= start + segments.get(segIndex)[1]) {
			start += segments.get(segIndex)[1];
			segIndex++;
		}
		ended = false;
		if (segIndex == segment && target >= tick) {
			advance(target - tick);
			return;
		}
		try {
			frames.close();
			if (canSoft(segIndex)) {
				softReset(segIndex);
				if (target > tick) {
					advance(target - tick);
				}
			} else {
				reconnect(segIndex, target);
			}
		} catch (Exception e) {
			LOG.warn("Velo Rewind seek failed", e);
		}
	}

	private void reconnect(int index, int target) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		Vec3 eye = mc.player != null ? mc.player.position() : null;
		float yRot = mc.player != null ? mc.player.getYRot() : 0;
		float xRot = mc.player != null ? mc.player.getXRot() : 0;
		Screen screen = mc.gui.screen();
		// leave the old world properly (a short "Velo Rewind" screen in between), then join the segment again
		EmbeddedChannel oldChannel = channel;
		connection = null;
		mc.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(net.minecraft.network.chat.Component.literal("Velo Rewind")), false);
		oldChannel.finishAndReleaseAll();
		connect(index);
		if (target > tick) {
			advance(target - tick);
		}
		if (eye != null) {
			deliver(new ClientboundPlayerPositionPacket(CAMERA_ID, new PositionMoveRotation(eye, Vec3.ZERO, yRot, xRot), Set.of()));
		}
		if (screen != null && mc.level != null) {
			mc.setScreen(screen);
		}
	}

	private boolean canSoft(int index) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.player != null && !dimensionChanged && segments.get(index)[0] == segments.get(segment)[0];
	}

	// Back to the start of segment {@code index} without leaving the world: every recorded entity goes, the snapshot is
	// played again - its chunks only where something changed since (same segment) - and playback goes on from there.
	private void softReset(int index) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		it.unimi.dsi.fastutil.ints.IntArrayList ids = new it.unimi.dsi.fastutil.ints.IntArrayList();
		for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
			if (e != mc.player) {
				ids.add(e.getId());
			}
		}
		if (!ids.isEmpty()) {
			deliver(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(ids));
		}
		redoChunks = index == segment ? new java.util.HashSet<>(dirty) : null;
		segment = index;
		segmentStart = segmentStart(index);
		tick = segmentStart;
		frames = reader.open("seg-" + index + ".bin");
		softSnapshot = true;
		inSnapshot = true;
		try {
			for (RewindReader.Frame f; (f = frames.next()) != null; ) {
				if (f.type() == RewindWriter.F_SNAPSHOT_END) {
					break;
				}
				if (f.type() == RewindWriter.F_PACKET) {
					play(f.body());
				}
			}
		} finally {
			softSnapshot = false;
			inSnapshot = false;
			redoChunks = null;
		}
		dirty.clear();
		applyRate();
	}

	private static java.lang.reflect.Field sectionPosField;

	private static long chunkKey(int x, int z) {
		return (long) x << 32 | (z & 0xFFFFFFFFL);
	}

	private void markDirty(Packet<?> p) {
		if (p instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket b) {
			dirty.add(chunkKey(b.getPos().getX() >> 4, b.getPos().getZ() >> 4));
		} else if (p instanceof ClientboundLevelChunkWithLightPacket c) {
			dirty.add(chunkKey(RewindCompat.chunkX(c), RewindCompat.chunkZ(c)));
		} else if (p instanceof net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket f) {
			dirty.add(chunkKey(f.pos().x(), f.pos().z()));
		} else if (p instanceof net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket u) {
			try {
				if (sectionPosField == null) {
					sectionPosField = u.getClass().getDeclaredField("sectionPos");
					sectionPosField.setAccessible(true);
				}
				var sp = (net.minecraft.core.SectionPos) sectionPosField.get(u);
				dirty.add(chunkKey(sp.x(), sp.z()));
			} catch (ReflectiveOperationException e) {
				dimensionChanged = true; // can't tell: rejoin to be safe
			}
		} else if (p instanceof ClientboundLoginPacket || p instanceof ClientboundRespawnPacket) {
			dimensionChanged = true;
		}
	}

	private void applyRate() {
		deliver(new ClientboundTickingStatePacket(20f * speed, paused));
	}

	// ---- packets ----

	// One recorded play packet: self UUID kept apart from the camera's, the login / respawns made the camera's, the rest
	// dropped or handed over.
	private void play(byte[] body) {
		if (selfUuid != null) {
			replace(body, selfUuid, fakeUuid);
		}
		ProtocolInfo<?> info = RewindRecorder.playProtocol();
		Packet<?> p = decode(info, body);
		if (p == null) {
			return;
		}
		String type = p.getClass().getSimpleName();
		if (DROP.contains(type)) {
			return;
		}
		if (softSnapshot) {
			if (p instanceof ClientboundLoginPacket || p instanceof ClientboundRespawnPacket) {
				return;
			}
			if (p instanceof ClientboundLevelChunkWithLightPacket c && redoChunks != null && !redoChunks.contains(chunkKey(RewindCompat.chunkX(c), RewindCompat.chunkZ(c)))
					&& Minecraft.getInstance().level.getChunkSource().getChunk(RewindCompat.chunkX(c), RewindCompat.chunkZ(c), net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null) {
				return; // unchanged since the snapshot: keep it, no rebuild
			}
		} else if (!inSnapshot) {
			markDirty(p);
		}
		if (p instanceof ClientboundLoginPacket login) {
			p = new ClientboundLoginPacket(CAMERA_ID, login.hardcore(), login.levels(), login.maxPlayers(), login.chunkRadius(), login.simulationDistance(),
					login.reducedDebugInfo(), false, login.doLimitedCrafting(), spectator(login.commonPlayerSpawnInfo()), login.onlineMode(), false);
		} else if (p instanceof ClientboundRespawnPacket respawn) {
			p = new ClientboundRespawnPacket(spectator(respawn.commonPlayerSpawnInfo()), respawn.dataToKeep());
		} else if (p instanceof ClientboundAddEntityPacket add && add.getId() == selfId && cameraAt == null) {
			cameraAt = new Vec3(add.getX(), add.getY(), add.getZ());
		} else if (p instanceof ClientboundGameEventPacket event && event.getEvent() == ClientboundGameEventPacket.CHANGE_GAME_MODE) {
			return;
		}
		deliver(p);
	}

	private static CommonPlayerSpawnInfo spectator(CommonPlayerSpawnInfo i) {
		return RewindCompat.spectator(i);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void deliver(Packet<?> packet) {
		try {
			// In the world: handled right here, one by one - a packet about something the recording didn't capture (an
			// older boss bar, a team) used to throw inside the connection and end the whole replay ("Network Exception").
			var listener = Minecraft.getInstance().getConnection();
			if (listener != null && connection != null && listener.getConnection() == connection
					&& packet.type().flow() == PacketFlow.CLIENTBOUND && !(packet instanceof ClientboundLoginPacket)) {
				((Packet) packet).handle(listener);
				return;
			}
			channel.writeInbound(packet);
			channel.runPendingTasks();
		} catch (Throwable t) {
			if (FAILED.add(packet.getClass().getSimpleName())) {
				LOG.warn("Velo Rewind: {} failed", packet.getClass().getSimpleName(), t);
			}
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Packet<?> decode(ProtocolInfo<?> info, byte[] body) {
		if (info == null) {
			return null;
		}
		ByteBuf buf = Unpooled.wrappedBuffer(body);
		try {
			return (Packet<?>) ((StreamCodec<ByteBuf, Packet>) (StreamCodec) info.codec()).decode(buf);
		} catch (Throwable t) {
			return null;
		}
	}

	private static byte[] uuidBytes(UUID u) {
		return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
	}

	private static void replace(byte[] data, byte[] from, byte[] to) {
		outer:
		for (int i = 0; i + 16 <= data.length; i++) {
			for (int k = 0; k < 16; k++) {
				if (data[i + k] != from[k]) {
					continue outer;
				}
			}
			System.arraycopy(to, 0, data, i, 16);
			i += 15;
		}
	}
}
*///?}
