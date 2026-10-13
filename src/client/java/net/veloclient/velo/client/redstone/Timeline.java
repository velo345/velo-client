package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recordings of a selected area, replayed in the world. K starts recording (the world keeps running
 * normally), K stops it and opens the replay: the world freezes and the area shows the recorded
 * ticks - walk around it, scrub with the slider / arrow keys / scroll, play it at any speed. K once
 * more leaves the replay and puts the area back exactly as it was. The last few recordings stay
 * available from the Recordings menu until you leave the world.
 *
 * <p>Only block states are recorded (that's what redstone shows), as per-tick changes. Areas up to
 * {@link #MAX_VOLUME} blocks; a recording stops by itself at the configured length or memory limit.
 */
public final class Timeline {

	public enum Phase { IDLE, RECORDING }

	public static final int MAX_VOLUME = 1_000_000;
	private static final long MAX_MEMORY_INTS = 40_000_000;

	/** One recording (server-side data, read-only once finished). */
	public static final class Recording {
		final int number;
		final int[] box;
		final int sx;
		final int sy;
		final int sz;
		final Object level;
		final int[] first;
		int[] last;
		final List<int[]> diffs = new ArrayList<>();
		long stored;

		Recording(int number, int[] box, Object level) {
			this.number = number;
			this.box = box;
			this.sx = box[3] - box[0] + 1;
			this.sy = box[4] - box[1] + 1;
			this.sz = box[5] - box[2] + 1;
			this.level = level;
			this.first = new int[sx * sy * sz];
		}

		public int number() {
			return number;
		}

		public synchronized int frames() {
			return diffs.size();
		}

		public String size() {
			return sx + "x" + sy + "x" + sz;
		}
	}

	private static volatile Phase phase = Phase.IDLE;
	private static final List<Recording> RECORDINGS = new ArrayList<>();
	private static Recording recording;
	private static int counter;

	// Replay (server-side arrays guarded by Timeline.class)
	private static volatile Recording replay;
	private static int[] saved;
	private static int[] showing;
	private static volatile int shownFrame;
	private static int targetFrame;
	private static boolean playing;
	private static double accumulator;
	private static int heldTicks;

	private Timeline() {
	}

	public static Phase phase() {
		return phase;
	}

	public static boolean replayOpen() {
		return replay != null;
	}

	public static int recordingFrames() {
		Recording r = recording;
		return r == null ? 0 : r.frames();
	}

	public static int frameCount() {
		Recording r = replay;
		return r == null ? 0 : r.frames();
	}

	public static int shownFrame() {
		return shownFrame;
	}

	public static boolean playing() {
		return playing;
	}

	/** The box being recorded or replayed (for the outline), or null. */
	public static int[] box() {
		Recording r = replay != null ? replay : recording;
		return r == null ? null : r.box;
	}

	public static synchronized List<Recording> recordings() {
		return List.copyOf(RECORDINGS);
	}

	public static synchronized int changesAt(int frame) {
		Recording r = replay;
		if (r == null || frame < 1 || frame > r.diffs.size()) {
			return 0;
		}
		return r.diffs.get(frame - 1).length / 3;
	}

	/** K: start recording, stop and replay, or leave the replay. */
	public static void toggle() {
		if (replay != null) {
			closeReplay();
		} else if (phase == Phase.RECORDING) {
			stopRecording();
		} else {
			startRecording();
		}
	}

	private static void startRecording() {
		int[] b = RmState.box();
		if (b == null) {
			RmState.error("Select the area to record first, then press K");
			return;
		}
		if (!RmWorld.singleplayer()) {
			RmState.error("Recording needs singleplayer");
			return;
		}
		if (RmState.volume() > MAX_VOLUME) {
			RmState.error("Area too big to record (max " + MAX_VOLUME + " blocks)");
			return;
		}
		int[] area = b.clone();
		RmWorld.onServer(level -> {
			synchronized (Timeline.class) {
				Recording r = new Recording(++counter, area, level);
				for (int x = 0; x < r.sx; x++) {
					for (int y = 0; y < r.sy; y++) {
						for (int z = 0; z < r.sz; z++) {
							r.first[(x * r.sy + y) * r.sz + z] = RmWorld.rawId(RmWorld.state(level, area[0] + x, area[1] + y, area[2] + z));
						}
					}
				}
				r.last = r.first.clone();
				r.stored = r.first.length;
				recording = r;
				phase = Phase.RECORDING;
			}
			MinecraftClient.getInstance().execute(() -> RmState.info("Recording - run your contraption, K stops"));
		});
	}

	/** Server thread, every level tick that actually runs. */
	public static void onServerTick(Object level) {
		Recording r = recording;
		if (phase != Phase.RECORDING || r == null || level != r.level) {
			return;
		}
		synchronized (Timeline.class) {
			int[] scratch = new int[96];
			int used = 0;
			for (int x = 0; x < r.sx; x++) {
				for (int y = 0; y < r.sy; y++) {
					for (int z = 0; z < r.sz; z++) {
						int i = (x * r.sy + y) * r.sz + z;
						int id = RmWorld.rawId(RmWorld.state(level, r.box[0] + x, r.box[1] + y, r.box[2] + z));
						if (id != r.last[i]) {
							if (used + 3 > scratch.length) {
								scratch = java.util.Arrays.copyOf(scratch, scratch.length * 2);
							}
							scratch[used++] = i;
							scratch[used++] = r.last[i];
							scratch[used++] = id;
							r.last[i] = id;
						}
					}
				}
			}
			synchronized (r) {
				r.diffs.add(java.util.Arrays.copyOf(scratch, used));
			}
			r.stored += used + 4;
			if (r.diffs.size() >= RmSettings.get().timelineFrames || r.stored > MAX_MEMORY_INTS) {
				MinecraftClient.getInstance().execute(Timeline::stopRecording);
			}
		}
	}

	private static void stopRecording() {
		Recording r = recording;
		if (phase != Phase.RECORDING || r == null) {
			return;
		}
		synchronized (Timeline.class) {
			phase = Phase.IDLE;
			recording = null;
			if (r.frames() == 0) {
				MinecraftClient.getInstance().execute(() -> RmState.error("Nothing recorded - the world didn't run"));
				return;
			}
			RECORDINGS.add(r);
			while (RECORDINGS.size() > Math.max(1, RmSettings.get().keepRecordings)) {
				RECORDINGS.removeFirst();
			}
		}
		openReplay(r);
	}

	/** Opens a recording: freezes the world and shows its last tick, keeping the area's current blocks to restore later. */
	public static void openReplay(Recording r) {
		if (replay != null) {
			closeReplay();
		}
		if (phase == Phase.RECORDING) {
			RmState.error("Stop the recording first (K)");
			return;
		}
		TickControls.setReplay(true);
		replay = r;
		playing = false;
		int last = r.frames();
		targetFrame = last;
		shownFrame = last;
		RmWorld.onServer(level -> {
			synchronized (Timeline.class) {
				int[] current = new int[r.first.length];
				for (int x = 0; x < r.sx; x++) {
					for (int y = 0; y < r.sy; y++) {
						for (int z = 0; z < r.sz; z++) {
							current[(x * r.sy + y) * r.sz + z] = RmWorld.rawId(RmWorld.state(level, r.box[0] + x, r.box[1] + y, r.box[2] + z));
						}
					}
				}
				saved = current;
				showing = current.clone();
				applyAbsolute(level, r, r.last);
				shownFrame = last;
			}
		});
		RmState.info("Replay #" + r.number + " - scrub with the slider or ← →, ↑ plays, K leaves");
	}

	/** Leaves the replay: the area goes back to how it was when the replay was opened. */
	public static void closeReplay() {
		Recording r = replay;
		if (r == null) {
			return;
		}
		replay = null;
		playing = false;
		RmWorld.onServer(level -> {
			synchronized (Timeline.class) {
				if (saved != null) {
					applyAbsolute(level, r, saved);
				}
				saved = null;
				showing = null;
			}
			MinecraftClient.getInstance().execute(() -> TickControls.setReplay(false));
		});
		RmState.info("Left the replay - area restored");
	}

	/** Server thread: sets every block of the area that differs from {@code target}. */
	private static void applyAbsolute(Object level, Recording r, int[] target) {
		for (int i = 0; i < target.length; i++) {
			if (showing[i] != target[i]) {
				setIndex(level, r, i, target[i]);
				showing[i] = target[i];
			}
		}
	}

	private static void setIndex(Object level, Recording r, int i, int id) {
		int x = i / (r.sy * r.sz);
		int y = (i / r.sz) % r.sy;
		int z = i % r.sz;
		RmWorld.set(level, r.box[0] + x, r.box[1] + y, r.box[2] + z, RmWorld.byRawId(id), RmWorld.SILENT);
	}

	public static void scrub(int delta) {
		playing = false;
		seek(targetFrame + delta);
	}

	public static void seekFraction(double fraction) {
		playing = false;
		seek((int) Math.round(Math.max(0, Math.min(1, fraction)) * frameCount()));
	}

	public static void togglePlay() {
		playing = !playing;
		if (playing && targetFrame >= frameCount()) {
			seek(0);
		}
		accumulator = 0;
	}

	private static final double[] SPEEDS = {0.05, 0.1, 0.25, 0.5, 1, 2, 4};

	public static void changeSpeed(int direction) {
		RmSettings.Data s = RmSettings.get();
		int i = 0;
		while (i < SPEEDS.length - 1 && SPEEDS[i] < s.playbackSpeed - 1e-6) {
			i++;
		}
		s.playbackSpeed = SPEEDS[Math.max(0, Math.min(SPEEDS.length - 1, i + direction))];
		RmSettings.save();
	}

	private static void seek(int frame) {
		Recording r = replay;
		if (r == null) {
			return;
		}
		int to = Math.max(0, Math.min(r.frames(), frame));
		targetFrame = to;
		RmWorld.onServer(level -> {
			synchronized (Timeline.class) {
				if (replay != r || showing == null) {
					return;
				}
				int from = shownFrame;
				java.util.Map<Integer, Integer> changed = new java.util.HashMap<>();
				if (to > from) {
					for (int f = from; f < to; f++) {
						int[] d = r.diffs.get(f);
						for (int i = 0; i < d.length; i += 3) {
							changed.put(d[i], d[i + 2]);
						}
					}
				} else {
					for (int f = from - 1; f >= to; f--) {
						int[] d = r.diffs.get(f);
						for (int i = d.length - 3; i >= 0; i -= 3) {
							changed.put(d[i], d[i + 1]);
						}
					}
				}
				for (var e : changed.entrySet()) {
					if (showing[e.getKey()] != e.getValue()) {
						setIndex(level, r, e.getKey(), e.getValue());
						showing[e.getKey()] = e.getValue();
					}
				}
				shownFrame = to;
			}
		});
	}

	/** Client tick: replay keys and auto-play. */
	public static void tick() {
		if (replay == null || !RedstoneMode.active()) {
			return;
		}
		while (RmKeys.STEP.wasPressed()) {
			scrub(RmKeys.shift() ? 10 : 1);
			heldTicks = 0;
		}
		while (RmKeys.BACK.wasPressed()) {
			scrub(RmKeys.shift() ? -10 : -1);
			heldTicks = 0;
		}
		if (RmKeys.STEP.isPressed() || RmKeys.BACK.isPressed()) {
			if (++heldTicks > 6) {
				scrub(RmKeys.STEP.isPressed() ? 1 : -1);
			}
		} else {
			heldTicks = 0;
		}
		while (RmKeys.PLAY.wasPressed()) {
			togglePlay();
		}
		while (RmKeys.REWIND.wasPressed()) {
			playing = false;
			seek(0);
		}
		if (playing) {
			accumulator += RmSettings.get().playbackSpeed;
			int steps = (int) accumulator;
			if (steps > 0) {
				accumulator -= steps;
				int next = targetFrame + steps;
				if (next >= frameCount()) {
					next = frameCount();
					playing = false;
				}
				seek(next);
			}
		}
	}

	public static String speedText() {
		return String.format(Locale.ROOT, "%.2fx", RmSettings.get().playbackSpeed);
	}

	/** Leaving the world: drop everything. */
	public static synchronized void discardAll() {
		if (replay != null) {
			closeReplay();
		}
		RECORDINGS.clear();
		recording = null;
		phase = Phase.IDLE;
	}
}
