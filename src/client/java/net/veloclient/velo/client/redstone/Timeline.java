package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Timeline: a frame-by-frame recording of the selected area, played back right in the world.
 * Press K with an area selected to start recording (the world runs as usual - or step it with
 * Tick Mode), K again to stop: the world freezes and the area becomes a 3D replay you can scrub
 * (arrow keys / scroll), play at the configured speed (up arrow) or jump to the start (down
 * arrow). K once more leaves the replay and puts the area back exactly as it was at the end of the
 * recording. Shift+K re-opens the last recording.
 *
 * <p>Only block states are recorded (that's what redstone shows), as per-tick differences. It's
 * kept for this session only - leaving the world drops it - and it stops by itself at the
 * configured length or when it would take more than {@link #MAX_MEMORY_INTS} values, and areas
 * above {@link #MAX_VOLUME} blocks can't be recorded, so the PC can't run out of memory.
 */
public final class Timeline {

	public enum Phase { IDLE, RECORDING, PLAYBACK }

	public static final int MAX_VOLUME = 32_768;
	private static final long MAX_MEMORY_INTS = 24_000_000;

	private static volatile Phase phase = Phase.IDLE;

	// Recording data. Written on the server thread while recording, then read-only.
	private static int[] box;
	private static int sx;
	private static int sy;
	private static int sz;
	private static int[] firstFrame;
	private static int[] serverCurrent;
	/** Per recorded tick: {index, oldId, newId, index, oldId, newId, ...}. */
	private static final List<int[]> DIFFS = new ArrayList<>();
	private static long storedInts;
	private static Object recordingLevel;
	private static int maxFrames;

	// Playback (client thread).
	private static volatile int frameCount;
	private static int shownFrame;
	private static int targetFrame;
	private static boolean playing;
	private static double playAccumulator;
	private static int heldTicks;
	private static boolean hasRecording;

	private Timeline() {
	}

	public static Phase phase() {
		return phase;
	}

	public static boolean playbackActive() {
		return phase == Phase.PLAYBACK;
	}

	public static int frameCount() {
		return frameCount;
	}

	public static int shownFrame() {
		return shownFrame;
	}

	public static boolean playing() {
		return playing;
	}

	public static int[] box() {
		return box;
	}

	/** Change counts per frame, for the timeline bar's activity marks. */
	public static synchronized int changesAt(int frame) {
		return frame >= 1 && frame <= DIFFS.size() ? DIFFS.get(frame - 1).length / 3 : 0;
	}

	public static void toggle() {
		switch (phase) {
			case IDLE -> {
				if (RmKeys.shift() && hasRecording) {
					enterPlayback();
				} else {
					startRecording();
				}
			}
			case RECORDING -> stopRecording();
			case PLAYBACK -> exitPlayback();
		}
	}

	private static void startRecording() {
		int[] b = RmState.box();
		if (b == null) {
			RmState.error("Select the area to record first (left + right click), then press K");
			return;
		}
		if (!RmWorld.singleplayer()) {
			RmState.error("Timelines need singleplayer");
			return;
		}
		long volume = RmState.volume();
		if (volume > MAX_VOLUME) {
			RmState.error("Area too big to record (" + volume + " blocks, max " + MAX_VOLUME + ")");
			return;
		}
		int[] area = b.clone();
		maxFrames = (int) Math.round(RedstoneModeModule.timelineFrames);
		RmWorld.onServer(level -> {
			synchronized (Timeline.class) {
				box = area;
				sx = area[3] - area[0] + 1;
				sy = area[4] - area[1] + 1;
				sz = area[5] - area[2] + 1;
				firstFrame = new int[sx * sy * sz];
				for (int x = 0; x < sx; x++) {
					for (int y = 0; y < sy; y++) {
						for (int z = 0; z < sz; z++) {
							firstFrame[(x * sy + y) * sz + z] = RmWorld.rawId(RmWorld.state(level, area[0] + x, area[1] + y, area[2] + z));
						}
					}
				}
				serverCurrent = firstFrame.clone();
				DIFFS.clear();
				storedInts = firstFrame.length;
				recordingLevel = level;
				phase = Phase.RECORDING;
				frameCount = 0;
			}
			MinecraftClient.getInstance().execute(() -> RmState.info("Recording " + sx + "x" + sy + "x" + sz
					+ " - run or step your contraption, K to stop"));
		});
	}

	/** Server thread, at the start of every level tick that actually runs. */
	public static void onServerTick(Object level) {
		if (phase != Phase.RECORDING || level != recordingLevel) {
			return;
		}
		synchronized (Timeline.class) {
			int[] scratch = new int[96];
			int used = 0;
			for (int x = 0; x < sx; x++) {
				for (int y = 0; y < sy; y++) {
					for (int z = 0; z < sz; z++) {
						int i = (x * sy + y) * sz + z;
						int id = RmWorld.rawId(RmWorld.state(level, box[0] + x, box[1] + y, box[2] + z));
						if (id != serverCurrent[i]) {
							if (used + 3 > scratch.length) {
								scratch = java.util.Arrays.copyOf(scratch, scratch.length * 2);
							}
							scratch[used++] = i;
							scratch[used++] = serverCurrent[i];
							scratch[used++] = id;
							serverCurrent[i] = id;
						}
					}
				}
			}
			DIFFS.add(java.util.Arrays.copyOf(scratch, used));
			storedInts += used + 4;
			frameCount = DIFFS.size();
			if (DIFFS.size() >= maxFrames || storedInts > MAX_MEMORY_INTS) {
				MinecraftClient.getInstance().execute(Timeline::stopRecording);
			}
		}
	}

	private static void stopRecording() {
		if (phase != Phase.RECORDING) {
			return;
		}
		synchronized (Timeline.class) {
			phase = Phase.IDLE;
			hasRecording = frameCount > 0;
		}
		if (!hasRecording) {
			RmState.error("Nothing recorded - the world didn't run (unfreeze with the up arrow or step with right arrow)");
			return;
		}
		enterPlayback();
	}

	private static void enterPlayback() {
		TickControls.freeze(true);
		phase = Phase.PLAYBACK;
		shownFrame = frameCount;
		targetFrame = frameCount;
		playing = false;
		RmState.info("Replay: ←/→ frame, scroll to scrub, ↑ play/pause, ↓ to start, K to leave (" + frameCount + " ticks)");
	}

	private static void exitPlayback() {
		seek(frameCount);
		phase = Phase.IDLE;
		playing = false;
		RmState.info("Left the replay - area restored. Shift+K reopens it.");
	}

	/** Moves the shown frame by {@code delta}. */
	public static void scrub(int delta) {
		playing = false;
		seek(targetFrame + delta);
	}

	private static void seek(int frame) {
		int to = Math.max(0, Math.min(frameCount, frame));
		if (to == targetFrame && to == shownFrame) {
			return;
		}
		targetFrame = to;
		RmWorld.onServer(level -> applyFrame(level, to));
	}

	/** Server thread: walks the area from the frame it shows to {@code to}, setting changed blocks silently. */
	private static void applyFrame(Object level, int to) {
		synchronized (Timeline.class) {
			int from = shownFrame;
			if (box == null || from == to) {
				return;
			}
			java.util.Map<Integer, Integer> changed = new java.util.HashMap<>();
			if (to > from) {
				for (int f = from; f < to; f++) {
					int[] d = DIFFS.get(f);
					for (int i = 0; i < d.length; i += 3) {
						changed.put(d[i], d[i + 2]);
					}
				}
			} else {
				for (int f = from - 1; f >= to; f--) {
					int[] d = DIFFS.get(f);
					for (int i = d.length - 3; i >= 0; i -= 3) {
						changed.put(d[i], d[i + 1]);
					}
				}
			}
			for (var e : changed.entrySet()) {
				int i = e.getKey();
				int x = i / (sy * sz);
				int y = (i / sz) % sy;
				int z = i % sz;
				RmWorld.set(level, box[0] + x, box[1] + y, box[2] + z, RmWorld.byRawId(e.getValue()), RmWorld.SILENT);
			}
			shownFrame = to;
		}
	}

	/** Client tick: playback controls and auto-play. */
	public static void tick() {
		if (phase != Phase.PLAYBACK || !RedstoneModeModule.active()) {
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
			playing = !playing;
			if (playing && targetFrame >= frameCount) {
				seek(0);
			}
			playAccumulator = 0;
		}
		while (RmKeys.REWIND.wasPressed()) {
			playing = false;
			seek(0);
		}
		if (playing) {
			playAccumulator += RedstoneModeModule.playbackSpeed;
			int steps = (int) playAccumulator;
			if (steps > 0) {
				playAccumulator -= steps;
				int next = targetFrame + steps;
				if (next >= frameCount) {
					next = frameCount;
					playing = false;
				}
				seek(next);
			}
		}
	}

	/** Leaving Redstone Mode / the world: restore the area and forget the recording. */
	public static void stopAll() {
		if (phase == Phase.PLAYBACK) {
			seek(frameCount);
		}
		phase = Phase.IDLE;
		playing = false;
	}

	public static synchronized void discard() {
		phase = Phase.IDLE;
		playing = false;
		hasRecording = false;
		DIFFS.clear();
		firstFrame = null;
		serverCurrent = null;
		box = null;
		frameCount = 0;
		storedInts = 0;
		recordingLevel = null;
	}
}
