package net.veloclient.velo.devbuild.rewind;

/**
 * Velo Rewind - recording (26.2+): every packet the game handles while recording is queued for {@link RewindWriter} (the
 * object only - encoding happens on the writer's thread), in the exact order the game applies them. The start of each
 * segment (start / resume) gets a snapshot of the world as the client knows it, so a recording can begin mid-game; the
 * recorder's own player - which the server never echoes back - is written as packets every tick (position, head, entity
 * data, equipment, swings). Recording is client-only: it works on every server and in singleplayer.
 */
//? if <26.2 {
public final class RewindRecorder {

	private RewindRecorder() {
	}

	public static boolean active() {
		return false;
	}

	public static boolean paused() {
		return false;
	}

	public static void toggle() {
	}

	public static void togglePause() {
	}

	public static void tick() {
	}

	public static String status() {
		return "";
	}

	public static void shutdown() {
	}

	public static void testTick() {
	}
}
//?} else {
/*import com.mojang.datafixers.util.Pair;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RewindRecorder {

	private enum State { IDLE, RECORDING, PAUSED }

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Velo Rewind");

	private static volatile State state = State.IDLE;
	// The clientbound protocols the connection uses right now (the play one is bound to the server's registries).
	private static volatile ProtocolInfo<?> configProtocol;
	private static volatile ProtocolInfo<?> playProtocol;
	private static volatile boolean newConfigPhase;

	// Kept from joining on (main thread): what a fresh client needs before the snapshot.
	private static final List<Object[]> CONFIG = new ArrayList<>();
	private static Packet<?> login;
	private static Packet<?> respawn;
	private static final Map<Class<?>, Packet<?>> STICKY = new LinkedHashMap<>();
	private static final Set<String> STICKY_TYPES = Set.of("ClientboundUpdateTagsPacket", "ClientboundServerDataPacket",
			"ClientboundChangeDifficultyPacket", "ClientboundSetDefaultSpawnPositionPacket", "ClientboundTabListPacket",
			"ClientboundSetSimulationDistancePacket", "ClientboundSetChunkCacheRadiusPacket", "ClientboundSetTimePacket",
			"ClientboundPlayerAbilitiesPacket", "ClientboundUpdateMobEffectPacket");
	// Network housekeeping that means nothing in a replay.
	private static final Set<String> SKIP = Set.of("ClientboundKeepAlivePacket", "ClientboundPingPacket", "ClientboundDisconnectPacket",
			"ClientboundTransferPacket", "ClientboundCookieRequestPacket", "ClientboundStoreCookiePacket", "ClientboundPongResponsePacket",
			"ClientboundCustomQueryPacket");

	private static RewindWriter writer;
	private static String fileName;
	private static long startedAt;
	private static int selfId = -1;
	// Who and where, remembered while in the world: a recording stopped by leaving the server is finished when there's
	// no player any more - its meta came out with an empty selfUuid, and the replay couldn't find you (2026-11-10).
	private static String knownSelfName = "";
	private static String knownSelfUuid = "";
	private static String knownServer;
	private static boolean selfSpawn;
	private static final Map<Integer, Object> lastData = new java.util.HashMap<>();
	private static final ItemStack[] lastEquip = new ItemStack[EquipmentSlot.values().length];
	private static boolean lastSwinging;
	// Following the player to another server: config packets go into a new configuration, then a fresh snapshot.
	private static boolean switching;
	private static long switchingSince;
	private static boolean resnapshot;
	private static int resnapshotWait;
	// The AI API's stop: save under this name without asking.
	static volatile String apiName;

	static Path lastFile() {
		return lastFile;
	}
	private static int thumbIn = -1;
	private static boolean thumbHudWas;

	private RewindRecorder() {
	}

	public static boolean active() {
		return state != State.IDLE;
	}

	public static boolean paused() {
		return state == State.PAUSED;
	}

	// The play protocol the connection uses now (Velo Rewind's playback decodes with it).
	public static ProtocolInfo<?> playProtocol() {
		return playProtocol;
	}

	// ---- hooks (mixins) ----

	// Connection.setupInboundProtocol: which protocol the following packets use.
	public static void onInbound(ProtocolInfo<?> info) {
		if (info.flow() != PacketFlow.CLIENTBOUND) {
			return;
		}
		if (info.id() == ConnectionProtocol.CONFIGURATION) {
			configProtocol = info;
			newConfigPhase = true;
			if (state != State.IDLE) {
				// A proxy moving you to another server (lobby -> arena): the world is cleared right now, but the first
				// config packet can come ticks later - tick() saw no player and no switch yet and stopped the recording.
				switching = true;
				switchingSince = System.currentTimeMillis();
			}
		} else if (info.id() == ConnectionProtocol.PLAY) {
			playProtocol = info;
		}
	}

	// A packet about to be handled on the game thread.
	public static void onHandle(PacketListener listener, Packet<?> packet) {
		String type = packet.getClass().getSimpleName();
		if (state != State.IDLE && ("ClientboundTransferPacket".equals(type) || "ClientboundStartConfigurationPacket".equals(type))) {
			// moving to another server: no world for a moment, keep recording
			switching = true;
			switchingSince = System.currentTimeMillis();
		}
		if (SKIP.contains(type)) {
			return;
		}
		if (listener instanceof ClientConfigurationPacketListenerImpl) {
			if (newConfigPhase) {
				newConfigPhase = false;
				CONFIG.clear();
				if (state != State.IDLE) {
					switching = true;
					switchingSince = System.currentTimeMillis();
					writer.newConfig();
				}
			}
			CONFIG.add(new Object[] {packet, configProtocol});
			if (state != State.IDLE) {
				writer.config(packet, configProtocol);
			}
			return;
		}
		if (!(listener instanceof ClientPacketListener)) {
			return;
		}
		if (packet instanceof ClientboundLoginPacket) {
			login = packet;
			respawn = null;
			STICKY.clear();
			selfSpawn = true;
			if (switching) {
				switching = false;
				resnapshot = true; // once the new world is set up (next tick)
			}
		} else if (packet instanceof ClientboundRespawnPacket) {
			respawn = packet;
			selfSpawn = true;
		} else if (STICKY_TYPES.contains(type)) {
			STICKY.put(packet.getClass(), packet);
		}
		if (state == State.RECORDING && !"ClientboundPlayerPositionPacket".equals(type)) {
			writer.packet(packet, playProtocol);
		}
	}

	// ---- controls ----

	public static void toggle() {
		if (state == State.IDLE) {
			start();
		} else {
			stop();
		}
	}

	public static void togglePause() {
		if (state == State.RECORDING) {
			writer.endSegment();
			state = State.PAUSED;
			say("Velo Rewind paused");
		} else if (state == State.PAUSED && !switching) {
			resnapshot = false;
			writer.beginSegment();
			snapshot();
			state = State.RECORDING;
			say("Velo Rewind recording");
		}
	}

	private static void start() {
		Minecraft mc = Minecraft.getInstance();
		if (RewindPlayer.active()) {
			return; // watching a recording
		}
		if (mc.player == null || mc.level == null || mc.getConnection() == null) {
			return;
		}
		if (login == null || playProtocol == null) {
			say("Velo Rewind: rejoin the world once to record it");
			return;
		}
		try {
			Path dir = mc.gameDirectory.toPath().resolve("velo-rewind");
			Files.createDirectories(dir);
			fileName = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
			writer = new RewindWriter(dir.resolve(fileName + ".vrewind"), RewindRecorder::encode);
		} catch (Exception e) {
			LOG.warn("Couldn't start a Velo Rewind recording", e);
			say("Velo Rewind couldn't start: " + e.getMessage());
			return;
		}
		writer.start();
		for (Object[] c : CONFIG) {
			writer.config(c[0], c[1]);
		}
		startedAt = System.currentTimeMillis();
		knownServer = null;
		remember(mc);
		writer.beginSegment();
		snapshot();
		writer.head(metaFields(mc)); // after the snapshot: it knows the recorder's own entity id
		state = State.RECORDING;
		switching = false;
		resnapshot = false;
		thumbIn = 30;
		RewindWriter audioTo = writer;
		RewindAudio.sink = (track, pcm, channels, rate, x, y, z) -> {
			if (writer == audioTo && state == State.RECORDING) {
				audioTo.audio(track, pcm, channels, rate, x, y, z);
			}
		};
		RewindAudio.started();
		say("Velo Rewind recording");
	}

	// The meta fields about where / who recorded (also written when recording starts, for crash recovery).
	private static String metaFields(Minecraft mc) {
		remember(mc);
		String server = knownServer != null ? knownServer : "singleplayer";
		String selfName = knownSelfName;
		String selfUuid = knownSelfUuid;
		return "\"minecraft\": \"" + net.minecraft.SharedConstants.getCurrentVersion().name() + "\", \"protocol\": "
				+ net.minecraft.SharedConstants.getProtocolVersion() + ", \"server\": " + json(server) + ", \"startedAt\": " + startedAt
				+ ", \"selfId\": " + selfId + ", \"selfUuid\": " + json(selfUuid) + ", \"selfName\": " + json(selfName);
	}

	private static void remember(Minecraft mc) {
		if (mc.player != null) {
			knownSelfName = mc.player.getGameProfile().name();
			knownSelfUuid = mc.player.getUUID().toString();
			knownServer = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : mc.hasSingleplayerServer() ? "singleplayer" : knownServer;
		}
	}

	// The game is closing: finish the recording right now (it keeps its date name; no name prompt).
	public static void shutdown() {
		RewindWriter w = writer;
		if (w == null) {
			return;
		}
		RewindAudio.sink = null;
		RewindAudio.stopped();
		writer = null;
		state = State.IDLE;
		w.endSegment();
		Path file = w.finishBlocking(metaFields(Minecraft.getInstance()), 15_000);
		LOG.info("Velo Rewind: saved {} as the game closed", file);
	}

	private static void stop() {
		Minecraft mc = Minecraft.getInstance();
		RewindAudio.sink = null;
		RewindAudio.stopped();
		RewindWriter w = writer;
		writer = null;
		state = State.IDLE;
		if (w == null) {
			return;
		}
		w.endSegment();
		String meta = metaFields(mc);
		switching = false;
		resnapshot = false;
		say("Velo Rewind saving...");
		w.finish(meta, file -> mc.execute(() -> {
			if (file == null) {
				say("Velo Rewind couldn't save: " + w.error());
				return;
			}
			// Saved already under its date; now ask for a name (in game, or on the title / server list after leaving).
			lastFile = file;
			String named = apiName;
			apiName = null;
			if (named != null) {
				// stopped through the AI API: saved under that name right away, no name prompt
				try {
					Path to = file.resolveSibling(named.replaceAll("[\\\\/:*?\"<>|]", "_") + ".vrewind");
					if (!java.nio.file.Files.exists(to)) {
						java.nio.file.Files.move(file, to);
						lastFile = to;
					}
				} catch (Exception e) {
					LOG.warn("Velo Rewind: couldn't name the recording {}", named, e);
				}
				say("Velo Rewind saved");
				return;
			}
			RewindSaveScreen.open(file);
		}));
	}

	// ---- per tick: the recorder's own player ----

	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (state == State.IDLE) {
			return;
		}
		if (mc.player == null || mc.level == null) {
			// Between two servers the client has no world for a moment - keep going; anything else is leaving.
			if (!switching || System.currentTimeMillis() - switchingSince > 60_000) {
				stop();
			}
			return;
		}
		if (mc.level.getGameTime() % 20 == 0) {
			remember(mc);
		}
		if (state != State.RECORDING) {
			return;
		}
		if (resnapshot) {
			// wait until the new server has sent the chunks around the player (up to 5 s), so the new segment's snapshot
			// has a world to show instead of starting empty
			boolean chunkHere = mc.level.hasChunk(mc.player.blockPosition().getX() >> 4, mc.player.blockPosition().getZ() >> 4);
			if (resnapshotWait++ < 100 && (!chunkHere || resnapshotWait < 20)) {
				return;
			}
			resnapshotWait = 0;
			resnapshot = false;
			writer.beginSegment();
			snapshot();
			say("Velo Rewind: following you to this server");
		}
		if (thumbIn >= 0) {
			// The library's picture: the world without the HUD - hidden for the frame the screenshot reads.
			if (thumbIn == 1) {
				thumbHudWas = mc.gui.hud.isHidden();
				hideHud(mc, true);
			} else if (thumbIn == 0) {
				thumbnail(mc);
				hideHud(mc, thumbHudWas);
			}
			thumbIn--;
		}
		LocalPlayer p = mc.player;
		if (selfSpawn || p.getId() != selfId) {
			spawnSelf(p);
		}
		writer.packet(ClientboundEntityPositionSyncPacket.of(p), playProtocol);
		writer.packet(new ClientboundRotateHeadPacket(p, (byte) Math.floor(p.getYHeadRot() * 256f / 360f)), playProtocol);
		List<SynchedEntityData.DataValue<?>> changed = dataChanges(p, false);
		if (!changed.isEmpty()) {
			writer.packet(new ClientboundSetEntityDataPacket(p.getId(), changed), playProtocol);
		}
		List<Pair<EquipmentSlot, ItemStack>> equip = new ArrayList<>();
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack now = p.getItemBySlot(slot);
			ItemStack was = lastEquip[slot.ordinal()];
			if (was == null || !ItemStack.matches(was, now)) {
				lastEquip[slot.ordinal()] = now.copy();
				if (was != null || !now.isEmpty()) {
					equip.add(Pair.of(slot, now.copy()));
				}
			}
		}
		if (!equip.isEmpty()) {
			writer.packet(new ClientboundSetEquipmentPacket(p.getId(), equip), playProtocol);
		}
		int swing = RewindCompat.swing(p);
		if (swing >= 0 && !lastSwinging) {
			writer.packet(new ClientboundAnimatePacket(p, swing), playProtocol);
		}
		lastSwinging = swing >= 0;
		writer.tick();
	}

	// F1's state (26.2 keeps it private in Hud).
	private static void hideHud(Minecraft mc, boolean hidden) {
		try {
			java.lang.reflect.Field f = mc.gui.hud.getClass().getDeclaredField("isHidden");
			f.setAccessible(true);
			f.setBoolean(mc.gui.hud, hidden);
		} catch (ReflectiveOperationException ignored) {
		}
	}

	// The library's picture: the screen a moment after the recording started, 480 wide.
	private static void thumbnail(Minecraft mc) {
		RewindWriter w = writer;
		net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
			try (image) {
				int width = 480;
				int height = Math.max(1, image.getHeight() * width / Math.max(1, image.getWidth()));
				try (com.mojang.blaze3d.platform.NativeImage small = new com.mojang.blaze3d.platform.NativeImage(width, height, false)) {
					image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), small);
					Path tmp = Files.createTempFile("velo-rewind", ".png");
					small.writeToFile(tmp);
					w.thumbnail(Files.readAllBytes(tmp), "thumb.png");
					Files.deleteIfExists(tmp);
				}
			} catch (Exception e) {
				LOG.warn("Velo Rewind: no thumbnail", e);
			}
		});
	}

	// ---- self test (-Dvelo.rewindTest): record with a pause, read it back, decode every packet, quit ----

	private static final boolean TEST = Boolean.getBoolean("velo.rewindTest");
	private static int testTicks;
	private static int saveShown;
	private static Path lastFile;

	public static void testTick() {
		if (!TEST) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		testTicks++;
		if (mc.gui.screen() instanceof RewindSaveScreen) {
			if (++saveShown == 25) {
				shot(mc, "rewind-save.png");
			} else if (saveShown > 30) {
				mc.setScreen(null);
			}
		}
		switch (testTicks) {
			case 100 -> toggle();
			case 150 -> mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
			case 170 -> shot(mc, "rewind-pause-recording.png");
			case 180 -> mc.setScreen(null);
			case 200 -> togglePause();
			case 240 -> togglePause();
			case 400 -> toggle();
			default -> {
			}
		}
		if (testTicks > 400 && lastFile != null) {
			Path f = lastFile;
			lastFile = null;
			verify(f);
			testTicks = 1000;
		}
		if (testTicks == 1050) {
			mc.setScreen(new RewindLibraryScreen(null));
		}
		if (testTicks == 1100) {
			shot(mc, "rewind-library.png");
		}
		if (testTicks == 1110) {
			mc.stop();
		}
	}

	private static void shot(Minecraft mc, String name) {
		net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, msg -> LOG.info("Velo Rewind test shot {}", name));
	}

	public static void verify(Path file) {
		Map<String, Integer> counts = new java.util.TreeMap<>();
		int frames = 0;
		int ticks = 0;
		int bad = 0;
		try (RewindReader reader = new RewindReader(file)) {
			LOG.info("Velo Rewind verify {}: meta {}", file.getFileName(), reader.meta());
			for (int k = 0; ; k++) {
				try (RewindReader.Frames f = reader.open("config-" + k + ".bin")) {
					if (f == null) {
						break;
					}
					for (RewindReader.Frame fr; (fr = f.next()) != null; ) {
						frames++;
						bad += decode(configProtocol, fr.body(), counts, "config ") ? 0 : 1;
					}
				}
			}
			for (int s = 0; s < reader.segments(); s++) {
				try (RewindReader.Frames f = reader.open("seg-" + s + ".bin")) {
					for (RewindReader.Frame fr; (fr = f.next()) != null; ) {
						frames++;
						if (fr.type() == RewindWriter.F_TICK) {
							ticks++;
						} else if (fr.type() == RewindWriter.F_PACKET) {
							bad += decode(playProtocol, fr.body(), counts, "") ? 0 : 1;
						}
					}
				}
			}
		} catch (Exception e) {
			LOG.warn("Velo Rewind verify failed", e);
		}
		LOG.info("Velo Rewind verify: {} frames, {} ticks, {} undecodable, packets {}", frames, ticks, bad, counts);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static boolean decode(ProtocolInfo<?> info, byte[] body, Map<String, Integer> counts, String prefix) {
		ByteBuf buf = Unpooled.wrappedBuffer(body);
		try {
			Object packet = ((StreamCodec<ByteBuf, Packet>) (StreamCodec) info.codec()).decode(buf);
			counts.merge(prefix + packet.getClass().getSimpleName(), 1, Integer::sum);
			return buf.readableBytes() == 0;
		} catch (Throwable t) {
			counts.merge(prefix + "ERROR " + t.getClass().getSimpleName(), 1, Integer::sum);
			return false;
		}
	}

	public static String status() {
		RewindWriter w = writer;
		if (w == null) {
			return "";
		}
		int s = w.ticks() / 20;
		return (state == State.PAUSED ? "Paused " : "REC ") + String.format("%d:%02d", s / 60, s % 60);
	}

	private static void spawnSelf(LocalPlayer p) {
		selfId = p.getId();
		selfSpawn = false;
		lastData.clear();
		java.util.Arrays.fill(lastEquip, null);
		writer.packet(addEntity(p), playProtocol);
		List<SynchedEntityData.DataValue<?>> all = dataChanges(p, true);
		if (!all.isEmpty()) {
			writer.packet(new ClientboundSetEntityDataPacket(p.getId(), all), playProtocol);
		}
	}

	// The player's entity data that changed since last tick (all of it when {@code all}); read straight from its items,
	// since the client never marks its own changes dirty.
	private static List<SynchedEntityData.DataValue<?>> dataChanges(LocalPlayer p, boolean all) {
		List<SynchedEntityData.DataValue<?>> out = new ArrayList<>();
		for (SynchedEntityData.DataItem<?> item : items(p.getEntityData())) {
			if (item == null) {
				continue;
			}
			SynchedEntityData.DataValue<?> v = item.value();
			Object now = v.value();
			Object was = lastData.get(v.id());
			if (all || !lastData.containsKey(v.id()) || !same(was, now)) {
				lastData.put(v.id(), now instanceof ItemStack s ? s.copy() : now);
				out.add(v);
			}
		}
		return out;
	}

	private static boolean same(Object a, Object b) {
		if (a instanceof ItemStack x && b instanceof ItemStack y) {
			return ItemStack.matches(x, y);
		}
		return java.util.Objects.equals(a, b);
	}

	private static java.lang.reflect.Field itemsField;

	private static SynchedEntityData.DataItem<?>[] items(SynchedEntityData data) {
		try {
			if (itemsField == null) {
				itemsField = SynchedEntityData.class.getDeclaredField("itemsById");
				itemsField.setAccessible(true);
			}
			return (SynchedEntityData.DataItem<?>[]) itemsField.get(data);
		} catch (ReflectiveOperationException e) {
			return new SynchedEntityData.DataItem<?>[0];
		}
	}

	// ---- the snapshot: the world as the client has it, as packets ----

	private static void snapshot() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		ClientPacketListener conn = mc.getConnection();
		LocalPlayer self = mc.player;
		if (level == null || conn == null || self == null) {
			writer.snapshotEnd();
			return;
		}
		long t0 = System.nanoTime();
		writer.packet(login, playProtocol);
		if (respawn != null) {
			writer.packet(respawn, playProtocol);
		}
		for (Packet<?> p : STICKY.values()) {
			writer.packet(p, playProtocol);
		}
		writer.packet(playerInfo(conn), playProtocol);
		writer.packet(new ClientboundInitializeBorderPacket(level.getWorldBorder()), playProtocol);
		ChunkPos center = self.chunkPosition();
		writer.packet(new ClientboundSetChunkCacheCenterPacket(center.x(), center.z()), playProtocol);
		int radius = Math.min(34, mc.options.getEffectiveRenderDistance() + 2);
		int chunks = 0;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(center.x() + dx, center.z() + dz, ChunkStatus.FULL, false);
				if (chunk != null && !chunk.isEmpty()) {
					writer.packet(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null), playProtocol);
					chunks++;
				}
			}
		}
		int entities = 0;
		List<Entity> riders = new ArrayList<>();
		for (Entity e : level.entitiesForRendering()) {
			if (e == self) {
				continue;
			}
			writer.packet(addEntity(e), playProtocol);
			List<SynchedEntityData.DataValue<?>> data = e.getEntityData().getNonDefaultValues();
			if (data != null && !data.isEmpty()) {
				writer.packet(new ClientboundSetEntityDataPacket(e.getId(), data), playProtocol);
			}
			if (e instanceof LivingEntity living) {
				List<Pair<EquipmentSlot, ItemStack>> equip = new ArrayList<>();
				for (EquipmentSlot slot : EquipmentSlot.values()) {
					ItemStack stack = living.getItemBySlot(slot);
					if (!stack.isEmpty()) {
						equip.add(Pair.of(slot, stack.copy()));
					}
				}
				if (!equip.isEmpty()) {
					writer.packet(new ClientboundSetEquipmentPacket(e.getId(), equip), playProtocol);
				}
			}
			if (!e.getPassengers().isEmpty()) {
				riders.add(e);
			}
			entities++;
		}
		for (Entity vehicle : riders) {
			writer.packet(new ClientboundSetPassengersPacket(vehicle), playProtocol);
		}
		spawnSelf(self);
		// boss bars and the scoreboard (sidebar, tab list, teams) as they are now - later updates refer to them
		try {
			for (Object event : bossEvents(mc)) {
				writer.packet(net.minecraft.network.protocol.game.ClientboundBossEventPacket.createAddPacket((net.minecraft.world.BossEvent) event), playProtocol);
			}
			var board = level.getScoreboard();
			for (var objective : board.getObjectives()) {
				writer.packet(new net.minecraft.network.protocol.game.ClientboundSetObjectivePacket(objective, 0), playProtocol);
				for (var entry : board.listPlayerScores(objective)) {
					writer.packet(new net.minecraft.network.protocol.game.ClientboundSetScorePacket(entry.owner(), objective.getName(), entry.value(),
							java.util.Optional.ofNullable(entry.display()), java.util.Optional.ofNullable(entry.numberFormatOverride())), playProtocol);
				}
			}
			for (var slot : net.minecraft.world.scores.DisplaySlot.values()) {
				var shown = board.getDisplayObjective(slot);
				if (shown != null) {
					writer.packet(new net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket(slot, shown), playProtocol);
				}
			}
			for (var team : board.getPlayerTeams()) {
				writer.packet(net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true), playProtocol);
			}
		} catch (RuntimeException e) {
			LOG.warn("Velo Rewind: no boss bars / scoreboard in the snapshot", e);
		}
		if (level.isRaining()) {
			writer.packet(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0f), playProtocol);
			writer.packet(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1f)), playProtocol);
			writer.packet(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1f)), playProtocol);
		}
		writer.snapshotEnd();
		LOG.info("Velo Rewind snapshot: {} chunks, {} entities in {} ms", chunks, entities, (System.nanoTime() - t0) / 1_000_000);
	}

	private static java.lang.reflect.Field bossEventsField;

	private static java.util.Collection<?> bossEvents(Minecraft mc) {
		try {
			Object overlay = mc.gui.hud.getBossOverlay();
			if (bossEventsField == null) {
				bossEventsField = overlay.getClass().getDeclaredField("events");
				bossEventsField.setAccessible(true);
			}
			return ((java.util.Map<?, ?>) bossEventsField.get(overlay)).values();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return java.util.List.of();
		}
	}

	private static ClientboundAddEntityPacket addEntity(Entity e) {
		int data = 0;
		if (e instanceof FallingBlockEntity falling) {
			data = Block.getId(falling.getBlockState());
		} else if (e instanceof HangingEntity hanging) {
			data = hanging.getDirection().get3DDataValue();
		} else if (e instanceof Projectile projectile && projectile.getOwner() != null) {
			data = projectile.getOwner().getId();
		}
		EntityType<?> type = e.getType();
		return new ClientboundAddEntityPacket(e.getId(), e.getUUID(), e.getX(), e.getY(), e.getZ(), e.getXRot(), e.getYRot(), type, data,
				e.getDeltaMovement(), e.getYHeadRot());
	}

	private static java.lang.reflect.Field entriesField;

	// Everyone in the tab list (and the hidden ones the server told us about) - the packet has no public way to build it
	// from client-side entries, so they go in after construction.
	private static ClientboundPlayerInfoUpdatePacket playerInfo(ClientPacketListener conn) {
		EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
				ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
				ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
				ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER);
		ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(actions, List.of());
		List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>();
		java.util.Collection<PlayerInfo> listed = conn.getListedOnlinePlayers();
		for (PlayerInfo info : conn.getOnlinePlayers()) {
			entries.add(new ClientboundPlayerInfoUpdatePacket.Entry(info.getProfile().id(), info.getProfile(), listed.contains(info),
					info.getLatency(), info.getGameMode(), info.getTabListDisplayName(), info.showHat(), info.getTabListOrder(), null));
		}
		// NPCs (FancyNPCs, Citizens, ...): fake players whose list entry the server removed right after spawning them - the
		// client keeps drawing them, but a replay can't spawn a player without an entry, so they get an unlisted one
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			for (var pl : mc.level.players()) {
				// also yourself: servers that hide you from your own tab list left the replay without an entry for you
				if (conn.getPlayerInfo(pl.getUUID()) == null) {
					entries.add(new ClientboundPlayerInfoUpdatePacket.Entry(pl.getUUID(), pl.getGameProfile(), false, 0,
							net.minecraft.world.level.GameType.SURVIVAL, null, true, 0, null));
				}
			}
		}
		try {
			if (entriesField == null) {
				entriesField = ClientboundPlayerInfoUpdatePacket.class.getDeclaredField("entries");
				entriesField.setAccessible(true);
			}
			entriesField.set(packet, entries);
		} catch (ReflectiveOperationException e) {
			LOG.warn("Velo Rewind: couldn't write the player list", e);
		}
		return packet;
	}

	// ---- encoding (writer thread) ----

	private static final Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void encode(Object packet, Object protocol, java.util.function.Consumer<byte[]> out) {
		if (packet instanceof BundlePacket<?> bundle) {
			for (Packet<?> sub : bundle.subPackets()) {
				encode(sub, protocol, out);
			}
			return;
		}
		ProtocolInfo<?> info = (ProtocolInfo<?>) protocol;
		if (info == null) {
			return;
		}
		ByteBuf buf = Unpooled.buffer(256);
		try {
			((StreamCodec<ByteBuf, Packet>) (StreamCodec) info.codec()).encode(buf, (Packet) packet);
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			out.accept(bytes);
		} catch (Throwable t) {
			if (FAILED.add(packet.getClass().getName())) {
				LOG.warn("Velo Rewind can't record {}: {}", packet.getClass().getSimpleName(), t.toString());
			}
		} finally {
			buf.release();
		}
	}

	private static void say(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal(text));
		}
	}

	private static String json(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
}
*///?}
