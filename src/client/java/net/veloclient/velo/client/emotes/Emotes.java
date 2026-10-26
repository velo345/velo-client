package net.veloclient.velo.client.emotes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Who is playing which emote, and the blended pose for a player right now (read by EmotePoseMixin while the player
 * model is set up). Your own emotes start here and go out with the Velo heartbeat as {@code "id;seq"}; other Velo
 * players' emotes come back with the online list and start when they arrive (a few seconds late, but complete).
 *
 * <p>An emote whose animation is still downloading waits and starts the moment it's there. A preview (Store, Locker,
 * the title screen's player) loops one emote on you - and the menu avatar - without telling anyone.
 */
public final class Emotes {

	/** Blend from the normal pose into the emote and back. */
	private static final float BLEND_SECONDS = 0.25f;
	/** Your emote stays in the heartbeat at least this long, so the 5 s online poll of others can't miss a short one. */
	private static final long STICKY_NANOS = 7_000_000_000L;
	/** An emote that still isn't loaded after this is dropped. */
	private static final long LOAD_TIMEOUT_NANOS = 10_000_000_000L;

	/** One emote being played by someone. {@code startNanos} is 0 while its animation loads. */
	public static final class Playing {
		public final String id;
		final long requestedNanos;
		volatile long startNanos;
		volatile long stopNanos;
		/** For the effects: how far into the emote (seconds, counting every loop) its events have fired. */
		public float firedUntil = -1f;

		Playing(String id, long now) {
			this.id = id;
			this.requestedNanos = now;
		}

		/** Seconds since it started (counting loops), or -1 while loading. */
		public float seconds() {
			long start = startNanos;
			return start == 0 ? -1f : (System.nanoTime() - start) / 1e9f;
		}

		public boolean stopping() {
			return stopNanos != 0;
		}
	}

	private static final Map<UUID, Playing> PLAYING = new ConcurrentHashMap<>();
	private static final Map<String, String> LAST_REMOTE = new HashMap<>();

	private static volatile UUID localUuid;
	private static int seq;
	private static volatile String networkValue;
	private static volatile long networkSince;
	private static volatile boolean showOthers = true;
	private static volatile Playing preview;

	private Emotes() {
	}

	// ---------------------------------------------------------------- your own

	/** Starts one of your emotes (restarts it if it's already playing). Only emotes you own. */
	public static void playLocal(UUID self, String id) {
		if (self == null || id == null || !EmoteCatalog.owns(id)) {
			return;
		}
		localUuid = self;
		PLAYING.put(self, new Playing(id, System.nanoTime()));
		EmoteLibrary.get(id);
		seq = (seq + 1) % 1_000_000;
		networkValue = id + ";" + seq;
		networkSince = System.nanoTime();
		net.veloclient.velo.client.network.VeloServerClient.syncSoon();
	}

	/** Ends your emote (blends back to the normal pose). */
	public static void stopLocal() {
		UUID self = localUuid;
		if (self != null) {
			stop(self);
		}
	}

	public static void setLocalUuid(UUID self) {
		localUuid = self;
	}

	/** The emote you're playing, or null. */
	public static String localEmote() {
		UUID self = localUuid;
		Playing p = self == null ? null : PLAYING.get(self);
		return p == null || p.stopNanos != 0 ? null : p.id;
	}

	/** What the heartbeat sends: your emote while it plays (and a few seconds after it started), else null. */
	public static String networkValue() {
		String value = networkValue;
		if (value == null) {
			return null;
		}
		if (localEmote() == null && System.nanoTime() - networkSince > STICKY_NANOS) {
			networkValue = null;
			return null;
		}
		return value;
	}

	// ---------------------------------------------------------------- preview

	/** Loops {@code id} on you and the menu avatar (Store / Locker / title screen); null ends the preview. */
	public static void setPreview(String id) {
		Playing current = preview;
		if (id == null) {
			if (current != null && current.stopNanos == 0) {
				current.stopNanos = System.nanoTime();
			}
			return;
		}
		if (current == null || !current.id.equals(id) || current.stopNanos != 0) {
			preview = new Playing(id, System.nanoTime());
			EmoteLibrary.get(id);
		}
	}

	public static String previewId() {
		Playing p = preview;
		return p == null || p.stopNanos != 0 ? null : p.id;
	}

	/** The preview being played (for its 2D effects), or null. */
	public static Playing preview() {
		return preview;
	}

	// ---------------------------------------------------------------- others

	public static void setShowOthers(boolean show) {
		showOthers = show;
		if (!show) {
			PLAYING.keySet().removeIf(uuid -> !uuid.equals(localUuid));
		}
	}

	/** From the online list: uuid (32 hex chars) -> "id;seq" for everyone playing an emote. */
	public static synchronized void onOnline(Map<String, String> playing) {
		long now = System.nanoTime();
		for (Map.Entry<String, String> e : playing.entrySet()) {
			UUID uuid = parseUuid(e.getKey());
			if (uuid == null || uuid.equals(localUuid) || e.getValue().equals(LAST_REMOTE.get(e.getKey()))) {
				continue;
			}
			LAST_REMOTE.put(e.getKey(), e.getValue());
			String id = e.getValue().split(";", 2)[0];
			if (showOthers) {
				PLAYING.put(uuid, new Playing(id, now));
				EmoteLibrary.get(id);
			}
		}
		// someone stopped: a looping emote ends (one-shots end by themselves)
		LAST_REMOTE.keySet().removeIf(key -> {
			if (playing.containsKey(key)) {
				return false;
			}
			UUID uuid = parseUuid(key);
			if (uuid != null) {
				Playing p = PLAYING.get(uuid);
				EmoteLibrary.Emote emote = p == null ? null : EmoteLibrary.get(p.id);
				if (p != null && (emote == null || emote.loop())) {
					stop(uuid);
				}
			}
			return true;
		});
	}

	private static void stop(UUID uuid) {
		Playing p = PLAYING.get(uuid);
		if (p != null && p.stopNanos == 0) {
			p.stopNanos = System.nanoTime();
		}
	}

	private static UUID parseUuid(String hex) {
		if (hex == null || hex.length() != 32) {
			return null;
		}
		try {
			return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- pose

	public static boolean any() {
		return !PLAYING.isEmpty() || preview != null;
	}

	/** Every emote playing in the world right now (for the effects). */
	public static void forEachPlaying(BiConsumer<UUID, Playing> action) {
		PLAYING.forEach(action);
	}

	/**
	 * This player's emote pose right now into {@code out} (36 floats, see {@link EmoteLibrary}); returns how much of
	 * it to use (0 = none: leave the model alone). While a preview runs, it's what you (and the menu avatar) show.
	 */
	public static float sample(UUID uuid, float[] out) {
		if (uuid == null) {
			return 0f;
		}
		if (uuid.equals(localUuid)) {
			float w = samplePreview(out);
			if (w > 0f) {
				return w;
			}
		}
		Playing p = PLAYING.get(uuid);
		if (p == null) {
			return 0f;
		}
		float w = sample(p, out);
		if (w < 0f) {
			PLAYING.remove(uuid, p);
			return 0f;
		}
		return w;
	}

	/** The menu avatar (title screen, previews without a world) and previews on you. */
	public static float samplePreview(float[] out) {
		Playing p = preview;
		if (p == null) {
			return 0f;
		}
		float w = sample(p, out);
		if (w < 0f) {
			preview = null;
			return 0f;
		}
		return w;
	}

	/** Weight of {@code p} now (pose into {@code out}); -1 when it's over and can be forgotten. */
	private static float sample(Playing p, float[] out) {
		long now = System.nanoTime();
		EmoteLibrary.Emote emote = EmoteLibrary.get(p.id);
		if (emote == null) {
			return now - p.requestedNanos > LOAD_TIMEOUT_NANOS || p.stopNanos != 0 ? -1f : 0f;
		}
		if (p.startNanos == 0) {
			p.startNanos = now;
		}
		float t = (now - p.startNanos) / 1e9f;
		float duration = emote.durationSeconds();
		boolean loops = emote.loop() || p == preview;
		float weight = smooth(t / BLEND_SECONDS);
		if (p.stopNanos != 0) {
			weight = Math.min(weight, 1f - smooth((now - p.stopNanos) / 1e9f / BLEND_SECONDS));
		} else if (!loops) {
			weight = Math.min(weight, smooth((duration - t) / BLEND_SECONDS));
		}
		if (weight <= 0f && (p.stopNanos != 0 || !loops && t > duration)) {
			return -1f;
		}
		float frame = loops ? (t * emote.fps()) % Math.max(1, emote.frameCount() - 1) : Math.min(t * emote.fps(), emote.frameCount() - 1);
		poseAt(emote, frame, out);
		return Math.max(0f, Math.min(1f, weight));
	}

	/** The pose at a (fractional) frame of an emote. */
	public static void poseAt(EmoteLibrary.Emote emote, float frame, float[] out) {
		int a = Math.max(0, Math.min(emote.frameCount() - 1, (int) frame));
		int b = Math.min(emote.frameCount() - 1, a + 1);
		float f = frame - a;
		float[] data = emote.frames();
		int fa = a * EmoteLibrary.FLOATS_PER_FRAME;
		int fb = b * EmoteLibrary.FLOATS_PER_FRAME;
		for (int i = 0; i < EmoteLibrary.FLOATS_PER_FRAME; i++) {
			float va = data[fa + i];
			float vb = data[fb + i];
			out[i] = i % 6 < 3 ? va + (vb - va) * f : va + wrap(vb - va) * f;
		}
	}

	/** This player's emote pose now (for effect anchors) without ending anything; returns its weight (0 = none). */
	public static float pose(UUID uuid, float[] out) {
		Playing p = uuid == null ? null : PLAYING.get(uuid);
		return p == null ? 0f : Math.max(0f, sample(p, out));
	}

	/** The preview's pose now (for effect anchors). */
	public static float posePreview(float[] out) {
		Playing p = preview;
		return p == null ? 0f : Math.max(0f, sample(p, out));
	}

	/** Whether {@code playing} is still this player's current emote. */
	public static boolean isPlaying(UUID uuid, Playing playing) {
		return PLAYING.get(uuid) == playing;
	}

	public static UUID localUuid() {
		return localUuid;
	}

	/** Whether a one-shot emote of yours just finished (the module then gives the camera back). */
	public static boolean localDone() {
		UUID self = localUuid;
		return self == null || !PLAYING.containsKey(self);
	}

	/** Moves {@code current} towards {@code target} by {@code weight}, taking the short way round for angles. */
	public static float blendAngle(float current, float target, float weight) {
		return current + wrap(target - current) * weight;
	}

	private static float wrap(float angle) {
		float a = angle % (float) (Math.PI * 2);
		if (a > Math.PI) {
			a -= (float) (Math.PI * 2);
		} else if (a < -Math.PI) {
			a += (float) (Math.PI * 2);
		}
		return a;
	}

	private static float smooth(float x) {
		float t = Math.max(0f, Math.min(1f, x));
		return t * t * (3f - 2f * t);
	}
}
