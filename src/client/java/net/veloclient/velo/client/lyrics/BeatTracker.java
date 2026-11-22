package net.veloclient.velo.client.lyrics;

import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.clips.DesktopAudio;

/**
 * Listens to what the computer plays (Spotify...) and finds the beat, for the Lyrics beat effects. Everything stays on
 * this PC - the audio is only analysed, never stored or sent.
 *
 * <p>How: the sound is low-passed (~150 Hz: kick drum and bass) and cut into 10.7 ms hops. A hop whose bass energy jumps
 * clearly above the last second's level (an adaptive threshold, so quiet and loud songs both work) is a beat candidate.
 * The tempo comes from the autocorrelation of that onset curve over the last 6 s (70-180 BPM); with a confident tempo,
 * candidates off the beat grid are ignored, so hi-hats and vocals don't fire. "Big moments" are beats much stronger than
 * the last 8 s, and drops: the level jumping to over twice what the seconds before had.
 *
 * <p>Runs only while someone asks for it ({@link #request()} each frame); closes the audio after 3 s without a request.
 */
public final class BeatTracker {

	private static final int HOP = 512;
	private static final float HOP_SEC = HOP / (float) DesktopAudio.RATE;
	private static final int HISTORY = 8 * DesktopAudio.RATE / HOP;   // ~8 s of hops

	private static volatile long requestedAt;
	private static Thread thread;

	// results (read from the render thread)
	private static volatile int beats;
	private static volatile float lastStrength;
	private static volatile boolean lastBig;
	private static volatile long lastBeatNanos;
	private static volatile float bpm;
	private static volatile float level;
	private static volatile boolean listening;
	private static volatile boolean unavailable;
	private static volatile boolean quiet = true;

	private BeatTracker() {
	}

	/** Where the samples come from (the desktop loopback, or a file in a test). */
	interface Pcm {
		void read(float[] out, int frames);

		boolean dead();
	}

	/** One beat, for tests: time in seconds, strength, big. */
	interface Listener {
		void beat(double seconds, float strength, boolean big, float bpm);
	}

	static Listener testListener;

	/** Keep listening (call every frame while beat effects are on). */
	public static synchronized void request() {
		requestedAt = System.nanoTime();
		if (thread == null && !unavailable) {
			thread = Thread.ofPlatform().daemon().priority(Thread.NORM_PRIORITY + 1).name("velo-beat-tracker").start(BeatTracker::run);
		}
	}

	/** Beats so far (a new value = a new beat). */
	public static int beats() {
		return beats;
	}

	/** The last beat's strength (1 = an ordinary beat of this song, 2+ = a big one). */
	public static float strength() {
		return lastStrength;
	}

	/** Whether the last beat was a big moment (a hard hit or a drop). */
	public static boolean big() {
		return lastBig;
	}

	public static long lastBeatNanos() {
		return lastBeatNanos;
	}

	/** The tempo it locked on (0 = not sure yet). */
	public static float bpm() {
		return bpm;
	}

	/** The bass level right now, 0..1 (for the Studio's meter). */
	public static float level() {
		return level;
	}

	public static boolean listening() {
		return listening;
	}

	/** Nothing audible is playing. */
	public static boolean quiet() {
		return quiet;
	}

	/** No loopback on this system (macOS) or it couldn't be opened. */
	public static boolean unavailable() {
		return unavailable;
	}

	private static void run() {
		DesktopAudio audio = null;
		try {
			audio = DesktopAudio.open();
			if (audio == null) {
				unavailable = true;
				return;
			}
			listening = true;
			DesktopAudio tap = audio;
			analyse(new Pcm() {
				@Override
				public void read(float[] out, int frames) {
					tap.read(out, frames);
				}

				@Override
				public boolean dead() {
					return tap.dead();
				}
			}, true);
		} catch (Throwable t) {
			VeloClient.LOGGER.warn("Beat tracker stopped: {}", t.toString());
			unavailable = true;
		} finally {
			listening = false;
			if (audio != null) {
				audio.shutdown();
			}
			synchronized (BeatTracker.class) {
				thread = null;
			}
		}
	}

	static void analyse(Pcm audio, boolean realtime) throws InterruptedException {
		// 2nd-order low-pass at 150 Hz (biquad, RBJ cookbook)
		double w0 = 2 * Math.PI * 150 / DesktopAudio.RATE;
		double alpha = Math.sin(w0) / (2 * 0.707);
		double cos = Math.cos(w0);
		double a0 = 1 + alpha;
		double b0 = (1 - cos) / 2 / a0, b1 = (1 - cos) / a0, b2 = b0, a1 = -2 * cos / a0, a2 = (1 - alpha) / a0;
		double x1 = 0, x2 = 0, y1 = 0, y2 = 0;

		float[] chunk = new float[480];
		float[] onset = new float[HISTORY];
		float[] energyHist = new float[HISTORY];
		int hopFill = 0;
		double hopBass = 0;
		double hopAll = 0;
		int hops = 0;
		double prevBass = 0;
		double prevAll = 0;
		double longBass = 1e-6;     // ~8 s average of the bass energy
		double shortAll = 1e-6;     // ~0.4 s average of the whole level
		double slowAll = 1e-6;      // ~4 s average
		resetClock();
		double peakBass = 1e-6;
		long next = System.nanoTime();
		while ((!realtime || System.nanoTime() - requestedAt < 3_000_000_000L) && !audio.dead()) {
			if (realtime) {
				next += 10_000_000L;
				long sleep = next - System.nanoTime();
				if (sleep > 0) {
					Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
				} else if (sleep < -200_000_000L) {
					next = System.nanoTime(); // fell far behind (system hiccup): don't catch up in a burst
				}
			}
			audio.read(chunk, chunk.length);
			for (float s : chunk) {
				double y = b0 * s + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
				x2 = x1;
				x1 = s;
				y2 = y1;
				y1 = y;
				hopBass += y * y;
				hopAll += s * s;
				if (++hopFill < HOP) {
					continue;
				}
				hopFill = 0;
				double bass = hopBass / HOP;
				double all = hopAll / HOP;
				hopBass = 0;
				hopAll = 0;
				int at = hops % HISTORY;
				hops++;

				// onset strength: positive change of the (log-compressed) bass energy
				double logB = Math.log1p(bass * 1e4);
				double logA = Math.log1p(all * 1e4);
				double flux = Math.max(0, logB - prevBass);
				double fluxAll = Math.max(0, logA - prevAll);
				prevBass = logB;
				prevAll = logA;
				onset[at] = (float) (flux + 0.5 * fluxAll);
				energyHist[at] = (float) bass;
				longBass += (bass - longBass) * (HOP_SEC / 8.0);
				shortAll += (all - shortAll) * (HOP_SEC / 0.4);
				slowAll += (all - slowAll) * (HOP_SEC / 4.0);
				peakBass = Math.max(bass, peakBass * Math.exp(-HOP_SEC / 3.0));
				quiet = slowAll < 2e-7;
				level = (float) Math.min(1, bass / Math.max(1e-9, peakBass));

				// tempo, once a second
				if (hops % 94 == 0 && hops > HISTORY / 2) {
					float raw = tempo(onset, hops);
					lock(raw);
				}

				// adaptive threshold over the last ~1 s of onsets
				int win = Math.min(hops, 94);
				if (hops <= 1) {
					resetClock();
				}
				double mean = 0;
				double sq = 0;
				for (int k = 1; k <= win; k++) {
					float o = onset[Math.floorMod(at - k, HISTORY)];
					mean += o;
					sq += o * o;
				}
				mean /= Math.max(1, win);
				double sd = Math.sqrt(Math.max(0, sq / Math.max(1, win) - mean * mean));
				boolean loudEnough = slowAll > 1e-7 && all > 1e-7;
				boolean onsetHere = loudEnough && flux > mean + 1.5 * sd && flux > 0.08;
				double t = hops * HOP_SEC;
				windowPeak = Math.max(windowPeak, bass);
				if (lockedBpm > 0) {
					// Phase-locked beat clock: beats come on the tempo grid; real kicks near a grid point pull the phase.
					double period = 60.0 / lockedBpm;
					if (Double.isNaN(nextBeat)) {
						nextBeat = onsetHere ? t : t + period;
					}
					if (onsetHere && Math.abs(t - nextBeat) < period * 0.18) {
						nextBeat += (t - nextBeat) * 0.5;
						lastOnset = t;
					} else if (onsetHere && t - lastOnset > period * 3) {
						nextBeat = t; // lost the phase for a while: restart from this kick
						lastOnset = t;
					}
					if (t >= nextBeat) {
						if (loudEnough && t - lastOnset < period * 8) {
							emit(windowPeak, shortAll, slowAll, t);
						}
						windowPeak = 0;
						nextBeat += period;
						if (nextBeat < t) {
							nextBeat = t + period;
						}
					}
				} else if (onsetHere && t - lastEmit > 0.24) {
					lastOnset = t;
					emit(bass, shortAll, slowAll, t);
					windowPeak = 0;
				}
			}
		}
	}

	// beat clock state (tracker thread only)
	private static double nextBeat = Double.NaN;
	private static double lastOnset = -999;
	private static double lastEmit = -999;
	private static double lastDrop = -999;
	private static double windowPeak;
	private static float lockedBpm;
	private static float candidateBpm;
	private static int candidateVotes;

	private static void resetClock() {
		nextBeat = Double.NaN;
		lastOnset = -999;
		lastEmit = -999;
		lastDrop = -999;
		windowPeak = 0;
		lockedBpm = 0;
		candidateBpm = 0;
		candidateVotes = 0;
		bpm = 0;
		beatAvg = 0;
		lastBigAt = -999;
	}

	/** A tempo estimate in: kept while estimates agree (within 4%, or a half / double of it), switched after 3 votes. */
	private static void lock(float raw) {
		if (raw <= 0) {
			return;
		}
		if (lockedBpm > 0) {
			for (float m : new float[] {1f, 2f, 0.5f}) {
				if (Math.abs(raw * m - lockedBpm) / lockedBpm < 0.04f) {
					lockedBpm += (raw * m - lockedBpm) * 0.2f;
					candidateVotes = 0;
					bpm = lockedBpm;
					return;
				}
			}
		}
		if (candidateBpm > 0 && Math.abs(raw - candidateBpm) / candidateBpm < 0.04f) {
			candidateVotes++;
		} else {
			candidateBpm = raw;
			candidateVotes = 1;
		}
		if (lockedBpm <= 0 && candidateVotes >= 2 || candidateVotes >= 5) {
			lockedBpm = candidateBpm;
			candidateVotes = 0;
			nextBeat = Double.NaN;
		}
		bpm = lockedBpm;
	}

	private static double beatAvg;
	private static double lastBigAt = -999;

	private static void emit(double peak, double shortAll, double slowAll, double t) {
		double minGap = lockedBpm > 0 ? 60.0 / lockedBpm * 0.6 : 0.24;
		if (t - lastEmit < minGap) {
			return;
		}
		// 1 = a typical beat of this song; the average follows the last ~12 beats
		double strength = beatAvg <= 0 ? 1 : peak / beatAvg;
		beatAvg = beatAvg <= 0 ? peak : beatAvg + (peak - beatAvg) / 12.0;
		boolean drop = t - lastDrop > 4 && shortAll > slowAll * 2.2;
		if (drop) {
			lastDrop = t;
		}
		lastEmit = t;
		lastStrength = (float) Math.min(4, strength);
		lastBig = drop || strength > 1.75 && t - lastBigAt > 1.5;
		if (lastBig) {
			lastBigAt = t;
		}
		lastBeatNanos = System.nanoTime();
		beats++;
		if (testListener != null) {
			testListener.beat(t, lastStrength, lastBig, bpm);
		}
	}

	/** BPM from a harmonic comb over the onset curve's autocorrelation (0 when it isn't clear). */
	private static float tempo(float[] onset, int hops) {
		int n = Math.min(hops, HISTORY);
		int newest = hops - 1;
		double[] x = new double[n];
		double mean = 0;
		for (int i = 0; i < n; i++) {
			x[i] = onset[Math.floorMod(newest - i, HISTORY)];
			mean += x[i];
		}
		mean /= n;
		double zero = 0;
		for (int i = 0; i < n; i++) {
			x[i] -= mean;
			zero += x[i] * x[i];
		}
		if (zero <= 1e-9) {
			return 0f;
		}
		int minLag = Math.round(60f / 180f / HOP_SEC);
		int maxLag = Math.round(60f / 70f / HOP_SEC);
		double[] ac = new double[maxLag * 3 + 2];
		for (int lag = minLag; lag < ac.length && lag < n; lag++) {
			double sum = 0;
			for (int i = 0; i + lag < n; i++) {
				sum += x[i] * x[i + lag];
			}
			ac[lag] = sum / (n - lag);
		}
		double best = 0;
		int bestLag = 0;
		for (int lag = minLag; lag <= maxLag; lag++) {
			// the true beat period also lines up at 2x and 3x; a stray peak usually doesn't
			double score = ac[lag] + 0.5 * at(ac, lag * 2) + 0.33 * at(ac, lag * 3);
			double bpmHere = 60.0 / (lag * HOP_SEC);
			score *= 0.5 + 0.5 * Math.exp(-Math.pow(Math.log(bpmHere / 110.0) / 0.45, 2) / 2);
			if (score > best) {
				best = score;
				bestLag = lag;
			}
		}
		if (bestLag == 0 || best / (zero / n) < 0.12) {
			return 0f;
		}
		// sub-hop precision: parabola through the neighbours
		double l = at(ac, bestLag - 1), c = ac[bestLag], r = at(ac, bestLag + 1);
		double shift = (l - 2 * c + r) != 0 ? 0.5 * (l - r) / (l - 2 * c + r) : 0;
		return (float) (60.0 / ((bestLag + Math.max(-0.5, Math.min(0.5, shift))) * HOP_SEC));
	}

	private static double at(double[] a, int i) {
		return i >= 0 && i < a.length ? a[i] : 0;
	}
}
