package net.veloclient.velo.client.emotes;

import net.minecraft.client.MinecraftClient;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Plays the emotes' tracks every client tick, for your emotes and other Velo players' alike: sounds and music at the
 * player ({@link EmoteSounds}), effects and props into that player's {@link EmoteFx.Emitter}, which
 * {@code EmoteFxRenderer} draws around them. A preview (Store, Locker, title screen) gets its own emitter and flat
 * sounds. All on the client thread, like the rendering.
 */
public final class EmoteEffects {

	/** Effects of players further away than this aren't fired (the sounds wouldn't carry anyway). */
	private static final double MAX_DISTANCE_SQ = 64 * 64;

	private static volatile boolean particles = true;
	private static volatile boolean sounds = true;
	private static volatile boolean music = true;
	private static volatile boolean othersSounds = true;
	private static volatile float volume = 1f;
	private static volatile float musicVolume = 0.8f;
	private static volatile float othersVolume = 0.8f;

	private static final Map<UUID, EmoteFx.Emitter> EMITTERS = new HashMap<>();
	private static final Map<UUID, Emotes.Playing> SEEN = new HashMap<>();
	private static final Map<UUID, EmoteSounds.Handle> MUSIC = new HashMap<>();
	private static final EmoteFx.Emitter PREVIEW = new EmoteFx.Emitter();
	private static Emotes.Playing previewSeen;
	private static EmoteSounds.Handle previewMusic;

	private static final float[] POSE = new float[EmoteLibrary.FLOATS_PER_FRAME];
	private static final float[] POINT = new float[3];

	private EmoteEffects() {
	}

	public static void configure(boolean particlesOn, boolean soundsOn, boolean musicOn, boolean othersOn, float soundVolume, float music,
			float others) {
		particles = particlesOn;
		sounds = soundsOn;
		EmoteEffects.music = musicOn;
		othersSounds = othersOn;
		volume = soundVolume;
		musicVolume = music;
		othersVolume = others;
	}

	// ---------------------------------------------------------------- for the renderer

	/** This player's effects, or null when they have none. */
	public static EmoteFx.Emitter emitter(UUID uuid) {
		if (uuid != null && uuid.equals(Emotes.localUuid()) && previewSeen != null) {
			return PREVIEW;
		}
		return uuid == null ? null : EMITTERS.get(uuid);
	}

	/** The preview's effects (the title screen's avatar, Store and Locker previews). */
	public static EmoteFx.Emitter previewEmitter() {
		return previewSeen == null && PREVIEW.idle() ? null : PREVIEW;
	}

	public static boolean particlesOn() {
		return particles;
	}

	/** The anchors of this player's pose right now (body space). */
	public static EmoteFx.Anchors anchors(UUID uuid, boolean preview) {
		return (at, out) -> anchor(uuid, preview, at, out);
	}

	private static boolean anchor(UUID uuid, boolean preview, String at, float[] out) {
		if (at != null && at.startsWith("@")) {
			// a fixed spot in body space: "@x,y,z" (where a prop stands, say)
			String[] xyz = at.substring(1).split(",");
			try {
				for (int i = 0; i < 3; i++) {
					out[i] = Float.parseFloat(xyz[i].trim());
				}
				return true;
			} catch (RuntimeException e) {
				return false;
			}
		}
		switch (at == null ? "chest" : at) {
			case "ground" -> {
				out[0] = 0;
				out[1] = 0.02f;
				out[2] = 0;
				return true;
			}
			case "front" -> {
				out[0] = 0;
				out[1] = 0.02f;
				out[2] = 0.9f;
				return true;
			}
			case "above" -> {
				out[0] = 0;
				out[1] = 2.45f;
				out[2] = 0;
				return true;
			}
			default -> {
			}
		}
		float w = preview || uuid == null ? Emotes.posePreview(POSE) : Emotes.pose(uuid, POSE);
		if (w <= 0f) {
			System.arraycopy(EmoteGeometry.restPose(), 0, POSE, 0, POSE.length);
		}
		EmoteGeometry.anchor(POSE, at, POINT);
		// model pixels (y down, facing -Z, right = -X; feet at y = 24) -> body space blocks
		out[0] = -POINT[0] / 16f;
		out[1] = (24f - POINT[1]) / 16f;
		out[2] = -POINT[2] / 16f;
		return true;
	}

	// ---------------------------------------------------------------- every tick

	public static void tick() {
		MinecraftClient client = MinecraftClient.getInstance();
		Emotes.forEachPlaying((uuid, playing) -> fire(client, uuid, playing));
		// players whose emote is over: effects wind down, music fades
		for (Iterator<Map.Entry<UUID, Emotes.Playing>> it = SEEN.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Emotes.Playing> e = it.next();
			if (!Emotes.isPlaying(e.getKey(), e.getValue())) {
				end(e.getKey());
				it.remove();
			}
		}
		EMITTERS.values().removeIf(em -> em.idle() && System.nanoTime() - em.lastNanos() > 2_000_000_000L);
		previewTick();
	}

	private static void fire(MinecraftClient client, UUID uuid, Emotes.Playing playing) {
		if (SEEN.get(uuid) != playing) {
			// a new emote: the last one's effects go
			end(uuid);
			EmoteFx.Emitter old = EMITTERS.get(uuid);
			if (old != null) {
				old.clear();
			}
			SEEN.put(uuid, playing);
		}
		if (playing.stopping()) {
			end(uuid);
			return;
		}
		EmoteLibrary.Emote emote = EmoteLibrary.get(playing.id);
		float t = playing.seconds();
		if (emote == null || t < 0f) {
			return;
		}
		Object player = player(client, uuid);
		if (player == null || distanceSq(player) > MAX_DISTANCE_SQ) {
			playing.firedUntil = t;
			return;
		}
		boolean self = uuid.equals(Emotes.localUuid());
		boolean hear = sounds && (self || othersSounds);
		float gain = volume * (self ? 1f : othersVolume);
		EmoteFx.Emitter emitter = EMITTERS.computeIfAbsent(uuid, k -> new EmoteFx.Emitter());
		EmoteFx.Anchors anchors = anchors(uuid, false);
		forEvents(emote, emote.loop(), playing, t, e -> {
			if (e.sound() != null && hear) {
				EmoteSounds.play(player, e.sound(), e.volume() * gain, e.pitch());
			}
			if (e.music() != null && hear && music && !MUSIC.containsKey(uuid)) {
				EmoteSounds.Handle h = EmoteSounds.music(player, e.music(), e.volume() * gain * musicVolume, e.every() > 0);
				if (h != null) {
					MUSIC.put(uuid, h);
				}
			}
			if (e.fx() != null && particles) {
				emitter.fire(e.fx(), e.at(), anchors);
			}
			if (e.prop() != null) {
				emitter.prop(e.prop());
			}
		});
		EmoteSounds.Handle h = MUSIC.get(uuid);
		if (h != null) {
			if (h.done()) {
				MUSIC.remove(uuid);
			} else if (!music || !hear) {
				h.fadeOut();
				MUSIC.remove(uuid);
			} else {
				h.volume(gain * musicVolume);
			}
		}
	}

	private static void end(UUID uuid) {
		EmoteSounds.Handle h = MUSIC.remove(uuid);
		if (h != null) {
			h.fadeOut();
		}
		EmoteFx.Emitter em = EMITTERS.get(uuid);
		if (em != null) {
			em.release();
		}
	}

	private static void previewTick() {
		Emotes.Playing preview = Emotes.preview();
		if (preview != previewSeen) {
			stopPreviewMusic();
			PREVIEW.clear();
			previewSeen = preview;
		}
		if (preview == null) {
			return;
		}
		if (preview.stopping()) {
			stopPreviewMusic();
			PREVIEW.release();
			return;
		}
		EmoteLibrary.Emote emote = EmoteLibrary.get(preview.id);
		float t = preview.seconds();
		if (emote == null || t < 0f) {
			return;
		}
		EmoteFx.Anchors anchors = anchors(null, true);
		float cycle = emote.durationSeconds();
		// a one-shot preview replays: its music and props start over each time round
		if (!emote.loop() && preview.firedUntil >= 0 && (int) (t / cycle) != (int) (preview.firedUntil / cycle)) {
			stopPreviewMusic();
			PREVIEW.release();
		}
		forEvents(emote, true, preview, t, e -> {
			if (e.sound() != null && sounds) {
				EmoteSounds.play(null, e.sound(), e.volume() * volume * 0.6f, e.pitch());
			}
			if (e.music() != null && sounds && music && previewMusic == null) {
				previewMusic = EmoteSounds.music(null, e.music(), e.volume() * volume * musicVolume * 0.6f, e.every() > 0);
			}
			if (e.fx() != null && particles) {
				PREVIEW.fire(e.fx(), e.at(), anchors);
			}
			if (e.prop() != null) {
				PREVIEW.prop(e.prop());
			}
		});
	}

	private static void stopPreviewMusic() {
		if (previewMusic != null) {
			previewMusic.fadeOut();
			previewMusic = null;
		}
	}

	/** Calls {@code action} for each event between where this emote's effects stopped and {@code t} (loops repeat them). */
	private static void forEvents(EmoteLibrary.Emote emote, boolean loops, Emotes.Playing playing, float t, Consumer<EmoteLibrary.Event> action) {
		float from = playing.firedUntil;
		playing.firedUntil = t;
		if (emote.events().isEmpty() || t <= from) {
			return;
		}
		float cycle = emote.durationSeconds();
		int firstLoop = loops ? (int) Math.floor(Math.max(0f, from) / cycle) : 0;
		int lastLoop = loops ? (int) Math.floor(t / cycle) : 0;
		for (int k = firstLoop; k <= lastLoop; k++) {
			for (EmoteLibrary.Event e : emote.events()) {
				// music plays once and loops by itself
				if (e.music() != null && k > 0 && e.every() > 0) {
					continue;
				}
				float at = k * cycle + e.t();
				if (at > from && at <= t) {
					action.accept(e);
				}
			}
		}
	}

	// ---- version facade ----

	private static double distanceSq(Object player) {
		double[] cam = net.veloclient.velo.client.pvp.PvpCompat.cameraPos();
		double[] pos = position(player);
		if (cam == null || pos == null) {
			return 0;
		}
		double dx = cam[0] - pos[0];
		double dy = cam[1] - pos[1];
		double dz = cam[2] - pos[2];
		return dx * dx + dy * dy + dz * dz;
	}

	//? if <26.1 {
	private static Object player(MinecraftClient client, UUID uuid) {
		return client.world == null ? null : client.world.getPlayerByUuid(uuid);
	}

	private static double[] position(Object o) {
		return o instanceof net.minecraft.entity.player.PlayerEntity p ? new double[] {p.getX(), p.getY(), p.getZ()} : null;
	}
	//?} else {
	/*private static Object player(MinecraftClient client, UUID uuid) {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		return level == null ? null : level.getPlayerByUUID(uuid);
	}

	private static double[] position(Object o) {
		return o instanceof net.minecraft.world.entity.player.Player p ? new double[] {p.getX(), p.getY(), p.getZ()} : null;
	}
	*///?}
}
