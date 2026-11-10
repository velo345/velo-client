package net.veloclient.launcher.sandbox;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Retro sound for the Home mini-games: every effect and the music are synthesized here (square, triangle and noise
 * voices like an old console) - nothing is bundled. One mixer thread feeds one audio line while a game is open; the
 * line is closed again the moment the games stop, so the launcher is silent and idle the rest of the time.
 */
final class ChipAudio {

	static final int RATE = 22050;

	private final Map<String, float[]> sounds = new HashMap<>();
	private final List<Voice> voices = new ArrayList<>();
	private volatile Thread thread;
	private volatile boolean running;
	private volatile float[] music;
	private volatile String track = "";
	private int musicPos;
	private float musicGain;
	private volatile float[] pendingMusic;
	volatile boolean sfxOn = true;
	volatile boolean musicOn = true;
	/** Separate levels (0..1) for the music and the sounds - quieter by default, set in the pause menu. */
	volatile float musicVolume = 0.35f;
	volatile float sfxVolume = 0.5f;

	private record Voice(float[] data, double step, float gain, double[] pos) {
	}

	synchronized void start() {
		if (running || Arcade.TOUR) {
			return;
		}
		running = true;
		Thread t = new Thread(this::mixLoop, "velo-arcade-audio");
		t.setDaemon(true);
		thread = t;
		t.start();
	}

	/** Stops the line (after a short fade) and frees the music. */
	synchronized void stop() {
		running = false;
		thread = null;
		music = null;
		pendingMusic = null;
		track = "";
		synchronized (voices) {
			voices.clear();
		}
	}

	boolean running() {
		return running;
	}

	/** Music for one of the games: "world", "snake", "blocks"; fades across when it changes. */
	void music(String name) {
		if (name.equals(track)) {
			return;
		}
		track = name;
		Thread t = new Thread(() -> {
			float[] made = compose(name);
			if (name.equals(track)) {
				pendingMusic = made;
			}
		}, "velo-arcade-music");
		t.setDaemon(true);
		t.start();
	}

	void play(String name) {
		play(name, 1, 1);
	}

	void play(String name, float volume, double pitch) {
		if (!sfxOn || !running) {
			return;
		}
		float[] data = sounds.computeIfAbsent(name, ChipAudio::effect);
		if (data == null) {
			return;
		}
		synchronized (voices) {
			if (voices.size() < 12) {
				voices.add(new Voice(data, pitch, volume, new double[1]));
			}
		}
	}

	private void mixLoop() {
		SourceDataLine line = null;
		try {
			AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
			line = AudioSystem.getSourceDataLine(format);
			line.open(format, 4096);
			line.start();
			int n = 512;
			byte[] bytes = new byte[n * 2];
			float fadeOut = 1;
			while (running || fadeOut > 0) {
				if (!running) {
					fadeOut -= n / (RATE * 0.25f);
				}
				if (pendingMusic != null && (music == null || musicGain < 0.01f)) {
					music = pendingMusic;
					pendingMusic = null;
					musicPos = 0;
				}
				float[] m = music;
				float target = musicOn && m != null && running && pendingMusic == null ? 1 : 0;
				for (int i = 0; i < n; i++) {
					float s = 0;
					musicGain += (target - musicGain) * 0.00012f;
					if (m != null) {
						s += m[musicPos] * musicGain * 0.55f * musicVolume;
						musicPos = (musicPos + 1) % m.length;
					}
					synchronized (voices) {
						for (int v = voices.size() - 1; v >= 0; v--) {
							Voice voice = voices.get(v);
							int p = (int) voice.pos[0];
							if (p >= voice.data.length) {
								voices.remove(v);
								continue;
							}
							s += voice.data[p] * voice.gain * sfxVolume;
							voice.pos[0] += voice.step;
						}
					}
					s *= Math.max(0, Math.min(1, fadeOut));
					s = (float) Math.tanh(s * 1.1);
					int v = (int) (s * 26000);
					bytes[i * 2] = (byte) v;
					bytes[i * 2 + 1] = (byte) (v >> 8);
				}
				line.write(bytes, 0, bytes.length);
			}
			line.drain();
		} catch (Exception | LinkageError ignored) {
			// no audio device: the games just stay silent
		} finally {
			if (line != null) {
				line.close();
			}
		}
	}

	// ------------------------------------------------------------------ effects

	private static float[] effect(String name) {
		return switch (name) {
			case "jump" -> sweep(0.11, 260, 600, 0.5, 0.32, false);
			case "land" -> noise(0.06, 0.08, 0.22);
			case "step" -> noise(0.035, 0.05, 0.10);
			case "dig" -> mix(noise(0.05, 0.18, 0.22), sweep(0.05, 150, 110, 0.5, 0.12, false));
			case "break" -> mix(noise(0.16, 0.25, 0.32), sweep(0.14, 220, 80, 0.5, 0.16, false));
			case "place" -> mix(sweep(0.07, 190, 120, 0.5, 0.24, true), noise(0.04, 0.1, 0.12));
			case "pickup" -> seq(new double[] {880, 1320}, 0.045, 0.25, 0.22);
			case "swing" -> noise(0.09, 0.5, 0.14);
			case "hit" -> mix(sweep(0.09, 320, 140, 0.5, 0.3, false), noise(0.06, 0.3, 0.2));
			case "hurt" -> sweep(0.22, 520, 170, 0.5, 0.32, false);
			case "mobdie" -> seq(new double[] {660, 440, 330, 220}, 0.045, 0.5, 0.26);
			case "craft" -> seq(new double[] {523, 659, 784, 1046}, 0.05, 0.25, 0.22);
			case "click" -> sweep(0.03, 1000, 900, 0.25, 0.14, false);
			case "open" -> sweep(0.08, 380, 620, 0.5, 0.18, true);
			case "close" -> sweep(0.08, 620, 380, 0.5, 0.18, true);
			case "death" -> sweep(0.7, 440, 70, 0.5, 0.32, false);
			case "hop" -> sweep(0.08, 150, 270, 0.5, 0.16, true);
			case "bat" -> sweep(0.03, 1800, 2500, 0.25, 0.06, false);
			case "eat" -> seq(new double[] {660, 990}, 0.04, 0.5, 0.24);
			case "turn" -> sweep(0.02, 700, 700, 0.25, 0.06, false);
			case "move" -> sweep(0.02, 520, 520, 0.25, 0.08, false);
			case "rotate" -> sweep(0.035, 700, 980, 0.25, 0.12, false);
			case "drop" -> mix(sweep(0.08, 240, 90, 0.5, 0.24, true), noise(0.05, 0.12, 0.16));
			case "clear" -> seq(new double[] {523, 659, 784, 1046, 1318}, 0.05, 0.25, 0.24);
			case "gameover" -> seq(new double[] {523, 466, 415, 349, 262}, 0.12, 0.5, 0.26);
			case "whoosh" -> noise(0.35, 0.6, 0.18);
			case "level" -> seq(new double[] {784, 988, 1175, 1568}, 0.07, 0.25, 0.22);
			case "bow" -> mix(sweep(0.06, 900, 300, 0.25, 0.14, false), noise(0.08, 0.6, 0.12));
			case "fire" -> mix(noise(0.22, 0.25, 0.26), sweep(0.18, 180, 90, 0.5, 0.1, true));
			case "orb" -> sweep(0.2, 420, 900, 0.5, 0.14, true);
			case "teleport" -> mix(sweep(0.25, 300, 1600, 0.25, 0.14, false), sweep(0.25, 1600, 300, 0.5, 0.08, true));
			case "crumble" -> noise(0.3, 0.15, 0.24);
			case "unseal" -> mix(seq(new double[] {196, 247, 294, 392}, 0.12, 0.5, 0.22), noise(0.5, 0.1, 0.2));
			case "roar" -> mix(sweep(0.9, 140, 60, 0.5, 0.34, false), noise(0.9, 0.12, 0.3));
			case "telegraph" -> sweep(0.25, 600, 1200, 0.125, 0.12, false);
			case "slam" -> mix(sweep(0.35, 110, 40, 0.5, 0.4, true), noise(0.3, 0.08, 0.38));
			case "volley" -> mix(noise(0.12, 0.7, 0.18), sweep(0.1, 700, 400, 0.25, 0.1, false));
			case "erupt" -> mix(noise(0.6, 0.3, 0.34), sweep(0.5, 90, 200, 0.5, 0.2, true));
			case "summon" -> seq(new double[] {330, 311, 294, 277}, 0.09, 0.5, 0.2);
			case "dive" -> sweep(0.3, 1200, 200, 0.5, 0.2, false);
			case "chest" -> seq(new double[] {523, 784, 1046, 1568}, 0.06, 0.25, 0.22);
			case "lever" -> mix(sweep(0.06, 300, 200, 0.5, 0.2, false), noise(0.04, 0.3, 0.14));
			case "rune" -> sweep(0.25, 660, 990, 0.5, 0.2, true);
			case "rune_wrong" -> seq(new double[] {300, 200}, 0.12, 0.5, 0.24);
			case "potion" -> mix(sweep(0.25, 300, 700, 0.5, 0.14, true), noise(0.2, 0.05, 0.08));
			case "equip" -> mix(noise(0.08, 0.5, 0.16), sweep(0.08, 500, 800, 0.25, 0.12, false));
			case "clank" -> mix(sweep(0.06, 1800, 1400, 0.25, 0.16, false), noise(0.05, 0.9, 0.1));
			case "shatter" -> mix(noise(0.3, 0.8, 0.26), seq(new double[] {1568, 1318, 1046}, 0.05, 0.25, 0.14));
			case "vent" -> noise(0.7, 0.35, 0.22);
			case "talk" -> seq(new double[] {392, 523, 440, 494, 392}, 0.035, 0.5, 0.12);
			case "trade" -> seq(new double[] {988, 1318}, 0.05, 0.25, 0.2);
			case "gate" -> mix(sweep(0.6, 200, 900, 0.5, 0.16, true), noise(0.6, 0.06, 0.12));
			case "victory" -> seq(new double[] {523, 659, 784, 1046, 784, 1046, 1318}, 0.11, 0.25, 0.24);
			default -> null;
		};
	}

	private static float[] sweep(double seconds, double from, double to, double duty, double volume, boolean triangle) {
		int n = (int) (seconds * RATE);
		float[] out = new float[n];
		double phase = 0;
		for (int i = 0; i < n; i++) {
			double t = i / (double) n;
			double f = from + (to - from) * t;
			phase += f / RATE;
			double p = phase % 1;
			double v = triangle ? 4 * Math.abs(p - 0.5) - 1 : (p < duty ? 1 : -1);
			out[i] = (float) (v * volume * env(t));
		}
		return out;
	}

	private static float[] noise(double seconds, double brightness, double volume) {
		int n = (int) (seconds * RATE);
		float[] out = new float[n];
		Random random = new Random(n);
		double y = 0;
		for (int i = 0; i < n; i++) {
			double t = i / (double) n;
			y += brightness * ((random.nextDouble() * 2 - 1) - y);
			out[i] = (float) (y * volume * 2.2 * env(t));
		}
		return out;
	}

	private static float[] seq(double[] notes, double each, double duty, double volume) {
		int per = (int) (each * RATE);
		float[] out = new float[per * notes.length];
		for (int k = 0; k < notes.length; k++) {
			double phase = 0;
			for (int i = 0; i < per; i++) {
				phase += notes[k] / RATE;
				double t = i / (double) per;
				out[k * per + i] = (float) (((phase % 1) < duty ? 1 : -1) * volume * (1 - t * 0.7));
			}
		}
		return out;
	}

	private static float[] mix(float[] a, float[] b) {
		float[] out = new float[Math.max(a.length, b.length)];
		for (int i = 0; i < out.length; i++) {
			out[i] = (i < a.length ? a[i] : 0) + (i < b.length ? b[i] : 0);
		}
		return out;
	}

	private static double env(double t) {
		return t < 0.04 ? t / 0.04 : Math.pow(1 - t, 1.6);
	}

	// ------------------------------------------------------------------ music

	private static double freq(int midi) {
		return 440 * Math.pow(2, (midi - 69) / 12.0);
	}

	/** How a track is played: tempo, chords, lead voice, and which parts join in. */
	/**
	 * chords: {root midi, quality} - 0 major, 1 minor, 2 maj7, 3 min7, 4 dom7. {@code tune}: a written melody, per bar
	 * {slot (eighth), midi, length in eighths, ...} - else one is made up from the chord's own pentatonic scale.
	 * {@code lowpass}: 0..1, lower = softer and darker.
	 */
	private record Track(int bpm, int[][] chords, long seed, String lead, boolean pad, int drums, boolean arp, double echo, int bassStyle,
			int[][] tune, double lowpass) {
		Track(int bpm, int[][] chords, long seed, String lead, boolean pad, int drums, boolean arp, double echo, int bassStyle) {
			this(bpm, chords, seed, lead, pad, drums, arp, echo, bassStyle, null, 0.5);
		}
	}

	/** Chord tones (semitones over the root) for a chord quality. */
	private static int[] tones(int quality) {
		return switch (quality) {
			case 1 -> new int[] {0, 3, 7};
			case 2 -> new int[] {0, 4, 7, 11};
			case 3 -> new int[] {0, 3, 7, 10};
			case 4 -> new int[] {0, 4, 7, 10};
			default -> new int[] {0, 4, 7};
		};
	}

	// Written tunes (2026-11 rework: calmer, no clashing notes). Overworld: F major, Fmaj7 - Am7 - Bbmaj7 - C ...
	private static final int[][] TUNE_WORLD = {
			{0, 69, 2, 2, 72, 2, 4, 76, 4},
			{0, 72, 3, 3, 69, 1, 4, 67, 4},
			{0, 65, 2, 2, 69, 2, 4, 74, 2, 6, 72, 2},
			{0, 74, 2, 2, 72, 2, 4, 67, 4},
			{0, 69, 2, 2, 72, 2, 4, 77, 2, 6, 76, 2},
			{0, 74, 4, 4, 72, 2, 6, 69, 2},
			{0, 65, 2, 2, 67, 2, 4, 69, 2, 6, 74, 2},
			{0, 67, 2, 2, 69, 2, 4, 72, 4}};
	// Desert (Ashlands): D harmonic minor, slow and warm - Dm - C - Bbmaj7 - A (the C# only over the A chords).
	private static final int[][] TUNE_DESERT = {
			{0, 69, 2, 2, 74, 2, 4, 77, 3, 7, 76, 1},
			{0, 74, 4, 4, 72, 2, 6, 69, 2},
			{0, 67, 2, 2, 72, 2, 4, 76, 2, 6, 74, 2},
			{0, 70, 2, 2, 74, 2, 4, 77, 2, 6, 81, 2},
			{0, 73, 3, 3, 76, 1, 4, 81, 4},
			{0, 77, 2, 2, 76, 1, 3, 74, 1, 4, 72, 2, 6, 69, 2},
			{0, 70, 2, 2, 74, 2, 4, 79, 2, 6, 77, 2},
			{0, 76, 2, 2, 73, 2, 4, 69, 4}};
	// Snake: C major, bright but easy - C - G - Am7 - Fmaj7.
	private static final int[][] TUNE_SNAKE = {
			{0, 76, 1, 1, 79, 1, 2, 76, 1, 3, 74, 1, 4, 72, 2, 6, 67, 2},
			{0, 74, 2, 2, 71, 1, 3, 74, 1, 4, 79, 2, 6, 74, 2},
			{0, 72, 1, 1, 76, 1, 2, 81, 2, 4, 79, 1, 5, 76, 1, 6, 72, 2},
			{0, 69, 2, 2, 72, 2, 4, 77, 1, 5, 76, 1, 6, 74, 2},
			{0, 76, 1, 1, 79, 1, 2, 76, 1, 3, 74, 1, 4, 72, 2, 6, 67, 2},
			{0, 74, 2, 2, 71, 1, 3, 74, 1, 4, 79, 2, 6, 74, 2},
			{0, 69, 2, 2, 72, 2, 4, 77, 2, 6, 81, 2},
			{0, 79, 2, 2, 77, 1, 3, 76, 1, 4, 74, 4}};
	// Blockfall: A minor, steady - Am7 - Fmaj7 - C - G.
	private static final int[][] TUNE_BLOCKS = {
			{0, 69, 2, 2, 72, 1, 3, 76, 1, 4, 81, 2, 6, 79, 1, 7, 76, 1},
			{0, 77, 2, 2, 76, 1, 3, 72, 1, 4, 69, 4},
			{0, 67, 1, 1, 72, 1, 2, 76, 2, 4, 79, 2, 6, 76, 2},
			{0, 74, 3, 3, 71, 1, 4, 67, 4},
			{0, 69, 2, 2, 72, 1, 3, 76, 1, 4, 81, 2, 6, 79, 1, 7, 76, 1},
			{0, 81, 2, 2, 79, 1, 3, 77, 1, 4, 76, 2, 6, 72, 2},
			{0, 76, 2, 2, 79, 2, 4, 83, 2, 6, 79, 2},
			{0, 71, 2, 2, 74, 2, 4, 79, 4}};

	private static Track track(String name) {
		return switch (name) {
			case "forest", "world" -> new Track(76, new int[][] {{41, 2}, {45, 3}, {46, 2}, {48, 0}, {41, 2}, {38, 3}, {46, 2}, {48, 4}}, 3,
					"flute", true, 1, false, 0.4, 2, TUNE_WORLD, 0.35);
			case "ash" -> new Track(76, new int[][] {{38, 1}, {38, 1}, {36, 0}, {46, 2}, {45, 0}, {38, 3}, {43, 3}, {45, 4}}, 7,
					"pluck", true, 3, false, 0.4, 2, TUNE_DESERT, 0.38);
			case "frost" -> new Track(70, new int[][] {{40, 1}, {48, 0}, {43, 0}, {50, 0}, {40, 1}, {48, 0}, {45, 1}, {47, 0}}, 11,
					"bell", true, 0, false, 0.45, 2);
			case "cave" -> new Track(62, new int[][] {{45, 1}, {50, 1}, {45, 1}, {40, 0}, {45, 1}, {41, 0}, {38, 1}, {40, 0}}, 17,
					"bell", true, 0, false, 0.5, 2);
			case "crypt", "dungeon" -> new Track(78, new int[][] {{45, 1}, {50, 1}, {40, 0}, {45, 1}, {41, 0}, {50, 1}, {40, 0}, {40, 0}}, 13,
					"organ", true, 2, false, 0.42, 2);
			case "foundry" -> new Track(104, new int[][] {{40, 1}, {36, 0}, {38, 0}, {35, 0}, {40, 1}, {36, 0}, {43, 0}, {35, 0}}, 19,
					"pulse", false, 4, true, 0.25, 1);
			case "spire" -> new Track(84, new int[][] {{45, 1}, {51, 0}, {50, 1}, {44, 0}, {45, 1}, {51, 0}, {41, 0}, {44, 0}}, 23,
					"bell", true, 2, true, 0.55, 2);
			case "boss0" -> new Track(152, new int[][] {{45, 1}, {45, 1}, {41, 0}, {43, 0}, {45, 1}, {45, 1}, {46, 0}, {44, 0}}, 21,
					"pulse", true, 5, true, 0.2, 3);
			case "boss1" -> new Track(138, new int[][] {{40, 1}, {40, 1}, {36, 0}, {38, 0}, {40, 1}, {43, 0}, {41, 0}, {39, 0}}, 27,
					"organ", true, 5, true, 0.22, 3);
			case "boss2" -> new Track(160, new int[][] {{45, 1}, {51, 0}, {45, 1}, {51, 0}, {41, 0}, {47, 0}, {44, 0}, {44, 0}}, 33,
					"bell", true, 5, true, 0.3, 3);
			case "snake" -> new Track(100, new int[][] {{48, 0}, {43, 0}, {45, 3}, {41, 2}, {48, 0}, {43, 0}, {41, 2}, {43, 4}}, 5,
					"pluck", true, 3, true, 0.25, 0, TUNE_SNAKE, 0.45);
			default -> new Track(108, new int[][] {{45, 3}, {41, 2}, {48, 0}, {43, 0}, {45, 3}, {41, 2}, {48, 2}, {43, 0}}, 9,
					"bell", true, 3, true, 0.3, 0, TUNE_BLOCKS, 0.45);
		};
	}

	/**
	 * A looping tune in the retro style, but warmer than raw chiptune: a soft pad, a bass, an arpeggio, a lead voice
	 * (flute, bell, pluck, organ or pulse), drums in one of a few styles, and a circular echo for space. Every place in
	 * Velo World gets its own (forest, Ashlands, Frostpeaks, caves, each dungeon, each boss).
	 */
	private static float[] compose(String name) {
		Track tr = track(name);
		double beat = 60.0 / tr.bpm();
		double eighth = beat / 2;
		int barSamples = (int) (beat * 4 * RATE);
		int bars = tr.chords().length * 2;
		int total = barSamples * bars;
		float[] out = new float[total];
		Random random = new Random(tr.seed());
		int[] scale = {0, 2, 3, 5, 7, 8, 10};
		int lastNote = 72;
		boolean boss = name.startsWith("boss");
		int[][] melody = new int[tr.chords().length][];
		for (int bar = 0; bar < bars; bar++) {
			int[] chord = tr.chords()[bar % tr.chords().length];
			int root = chord[0];
			boolean minor = chord[1] == 1 || chord[1] == 3;
			int[] tones = tones(chord[1]);
			int start = bar * barSamples;
			if (tr.pad()) {
				for (int t : tones) {
					pad(out, start, barSamples, freq(root + 12 + t), boss ? 0.035 : 0.045);
				}
			}
			int[] bassPattern = switch (tr.bassStyle()) {
				case 1 -> new int[] {0, -1, 0, 0, -1, 0, 12, -1};
				case 2 -> new int[] {0, -1, -1, -1, 7, -1, -1, -1};
				case 3 -> new int[] {0, 0, 12, 0, 0, 12, 7, 0};
				default -> new int[] {0, -1, 0, 12, -1, 0, 7, -1};
			};
			for (int e = 0; e < 8; e++) {
				if (bassPattern[e] >= 0) {
					int len = (int) (eighth * (tr.bassStyle() == 2 ? 3.6 : 0.85) * RATE);
					note(out, start + (int) (e * eighth * RATE), len, freq(root + bassPattern[e]), 0, tr.bassStyle() == 2 ? 0.16 : 0.2);
				}
			}
			if (tr.arp()) {
				for (int st = 0; st < 16; st++) {
					int tone = tones[st % tones.length] + (st / tones.length % 2 == 0 ? 24 : 36);
					note(out, start + (int) (st * eighth / 2 * RATE), (int) (eighth / 2 * 0.6 * RATE), freq(root + tone), tr.tune() != null ? 0 : 0.125,
							boss ? 0.04 : tr.tune() != null ? 0.018 : 0.024);
				}
			}
			// the melody: a written tune, or made up once per chord (from that chord's own pentatonic scale, so it never
			// rubs against the harmony) and played again, varied, in the second half
			boolean repeat = bar >= tr.chords().length;
			int index = bar % tr.chords().length;
			if (tr.tune() != null) {
				int[] line = tr.tune()[index % tr.tune().length];
				int[] notes = new int[8];
				java.util.Arrays.fill(notes, -1);
				for (int q = 0; q + 2 < line.length; q += 3) {
					notes[line[q]] = line[q + 1] * 10 + line[q + 2];
				}
				melody[index] = notes;
			} else if (!repeat || melody[index] == null) {
				int[] penta = minor ? new int[] {0, 3, 5, 7, 10} : new int[] {0, 2, 4, 7, 9};
				java.util.List<Integer> pool = new java.util.ArrayList<>();
				for (int o = root - 36; o <= 96; o += 12) {
					for (int pc : penta) {
						int n = o + pc;
						if (n >= 64 && n <= 84) {
							pool.add(n);
						}
					}
				}
				java.util.Collections.sort(pool);
				int[] rhythm = RHYTHMS[random.nextInt(RHYTHMS.length)];
				int[] notes = new int[8];
				for (int e = 0; e < 8; e++) {
					if (rhythm[e] == 0) {
						notes[e] = -1;
						continue;
					}
					int candidate;
					if (e % 4 == 0) {
						candidate = root + tones[random.nextInt(tones.length)];
						while (candidate < lastNote - 6) {
							candidate += 12;
						}
						while (candidate > lastNote + 6) {
							candidate -= 12;
						}
					} else {
						int near = 0;
						for (int q = 0; q < pool.size(); q++) {
							if (Math.abs(pool.get(q) - lastNote) < Math.abs(pool.get(near) - lastNote)) {
								near = q;
							}
						}
						int k = Math.max(0, Math.min(pool.size() - 1, near + random.nextInt(3) - 1));
						candidate = pool.get(k);
					}
					candidate = Math.max(64, Math.min(84, candidate));
					lastNote = candidate;
					notes[e] = candidate * 10 + rhythm[e];
				}
				melody[index] = notes;
			}
			boolean quietStart = !boss && bar < 2 && tr.tune() == null;
			if (!quietStart) {
				int[] notes = melody[index];
				for (int e = 0; e < 8; e++) {
					if (notes[e] < 0) {
						continue;
					}
					int n = notes[e] / 10;
					int length = notes[e] % 10;
					lead(out, tr.lead(), start + (int) (e * eighth * RATE), (int) (eighth * length * 0.92 * RATE), freq(n + (boss ? -12 : 0)),
							boss ? 0.07 : tr.tune() != null ? 0.05 : 0.055);
				}
			}
			for (int b = 0; b < 4; b++) {
				int at = start + (int) (b * beat * RATE);
				switch (tr.drums()) {
					case 1 -> { // soft: brushed
						if (b == 0) {
							kick(out, at);
						}
						hat(out, at + (int) (eighth * RATE), 0.03, 0.025, 0.85);
					}
					case 2 -> { // slow, heavy
						if (b % 2 == 0) {
							kick(out, at);
						}
						if (b == 3) {
							hat(out, at, 0.18, 0.05, 0.2);
						}
					}
					case 3 -> { // hand drums
						if (b % 2 == 0) {
							kick(out, at);
						}
						hat(out, at + (int) (eighth * RATE), 0.05, 0.05, 0.4);
						hat(out, at + (int) (eighth * 1.5 * RATE), 0.03, 0.03, 0.6);
					}
					case 4 -> { // industrial
						kick(out, at);
						hat(out, at + (int) (eighth * RATE), 0.04, 0.06, 0.95);
						if (b % 2 == 1) {
							hat(out, at, 0.12, 0.09, 0.3);
						}
					}
					case 5 -> { // driving (bosses)
						kick(out, at);
						if (b % 2 == 1) {
							hat(out, at, 0.1, 0.11, 0.35);
						}
						hat(out, at + (int) (eighth * RATE), 0.025, 0.04, 0.9);
						hat(out, at, 0.02, 0.03, 0.9);
					}
					default -> {
					}
				}
			}
		}
		// circular echo (wraps around the loop so the seam stays seamless), then a gentle low-pass and normalise
		int delay = (int) (eighth * 1.5 * RATE);
		float[] wet = out.clone();
		double feedback = 0.38;
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < total; i++) {
				wet[i] = (float) (out[i] + feedback * wet[(i - delay + total) % total]);
			}
		}
		float y = 0;
		float peak = 0.001f;
		for (int i = 0; i < total; i++) {
			float mixed = (float) (out[i] * (1 - tr.echo() * 0.3) + (wet[i] - out[i]) * tr.echo());
			y += (float) tr.lowpass() * (mixed - y);
			out[i] = y;
			peak = Math.max(peak, Math.abs(y));
		}
		for (int i = 0; i < total; i++) {
			out[i] = out[i] / peak * 0.72f;
		}
		return out;
	}

	/** Lead voices. */
	private static void lead(float[] out, String voice, int at, int length, double f, double volume) {
		switch (voice) {
			case "flute" -> {
				double phase = 0;
				for (int i = 0; i < length && at + i < out.length; i++) {
					double t = i / (double) RATE;
					double vib = t > 0.12 ? Math.sin(t * 2 * Math.PI * 5) * 0.004 : 0;
					phase += f * (1 + vib) / RATE;
					double p = phase % 1;
					double tri = 4 * Math.abs(p - 0.5) - 1;
					double env = Math.min(1, t / 0.05) * (1 - 0.35 * i / (double) length);
					out[at + i] += (float) ((tri * 0.85 + Math.sin(phase * 2 * Math.PI) * 0.3) * volume * env);
				}
			}
			case "bell" -> {
				for (int i = 0; i < (int) (length * 1.6) && at + i < out.length; i++) {
					double t = i / (double) RATE;
					double env = Math.exp(-t * 3.2);
					double v = Math.sin(2 * Math.PI * f * t) + 0.45 * Math.sin(2 * Math.PI * f * 2.0 * t) * Math.exp(-t * 6)
							+ 0.25 * Math.sin(2 * Math.PI * f * 3.01 * t) * Math.exp(-t * 9);
					out[at + i] += (float) (v * volume * 0.9 * env);
				}
			}
			case "pluck" -> {
				double phase = 0;
				for (int i = 0; i < length && at + i < out.length; i++) {
					double t = i / (double) RATE;
					phase += f / RATE;
					double p = phase % 1;
					double duty = 0.25 + 0.2 * Math.exp(-t * 12);
					double env = Math.exp(-t * 7);
					out[at + i] += (float) ((p < duty ? 1 : -1) * volume * 0.8 * env);
				}
			}
			case "organ" -> {
				for (int i = 0; i < length && at + i < out.length; i++) {
					double t = i / (double) RATE;
					double env = Math.min(1, t / 0.03) * (1 - 0.2 * i / (double) length);
					double v = Math.sin(2 * Math.PI * f * t) + 0.5 * Math.sin(2 * Math.PI * f * 2 * t) + 0.3 * Math.sin(2 * Math.PI * f * 4 * t)
							+ 0.15 * Math.signum(Math.sin(2 * Math.PI * f * 0.5 * t));
					out[at + i] += (float) (v * volume * 0.55 * env);
				}
			}
			default -> note(out, at, length, f, 0.25, volume);
		}
	}

	/** A soft, slow pad: two detuned soft-saws per note, swelling in and out over the bar. */
	private static void pad(float[] out, int at, int length, double f, double volume) {
		double p1 = 0;
		double p2 = 0;
		for (int i = 0; i < length && at + i < out.length; i++) {
			double t = i / (double) length;
			p1 += f * 1.003 / RATE;
			p2 += f * 0.997 / RATE;
			double s1 = Math.sin(p1 * 2 * Math.PI) + 0.33 * Math.sin(p1 * 4 * Math.PI) + 0.15 * Math.sin(p1 * 6 * Math.PI);
			double s2 = Math.sin(p2 * 2 * Math.PI) + 0.33 * Math.sin(p2 * 4 * Math.PI);
			double env = Math.min(1, t / 0.3) * Math.min(1, (1 - t) / 0.25);
			out[at + i] += (float) ((s1 + s2) * 0.5 * volume * env);
		}
	}

	private static final int[][] RHYTHMS = {
			{2, 0, 1, 1, 2, 0, 2, 0}, {1, 1, 2, 0, 1, 1, 2, 0}, {3, 0, 0, 1, 2, 0, 1, 1}, {2, 0, 2, 0, 1, 1, 1, 1},
			{4, 0, 0, 0, 2, 0, 1, 1}, {1, 0, 1, 1, 0, 1, 2, 0}};

	/** duty 0 = triangle. */
	private static void note(float[] out, int at, int length, double f, double duty, double volume) {
		double phase = 0;
		for (int i = 0; i < length && at + i < out.length; i++) {
			phase += f / RATE;
			double p = phase % 1;
			double t = i / (double) length;
			double vib = i > RATE * 0.15 ? Math.sin(i / (double) RATE * 2 * Math.PI * 5.5) * 0.003 : 0;
			p = (p + vib + 1) % 1;
			double v = duty == 0 ? 4 * Math.abs(p - 0.5) - 1 : (p < duty ? 1 : -1);
			double env = Math.min(1, i / (RATE * 0.004)) * (duty == 0 ? 1 - t * 0.4 : Math.pow(1 - t, 0.8));
			out[at + i] += (float) (v * volume * env);
		}
	}

	private static void kick(float[] out, int at) {
		int n = (int) (0.13 * RATE);
		double phase = 0;
		for (int i = 0; i < n && at + i < out.length; i++) {
			double t = i / (double) n;
			phase += (125 - 80 * t) / RATE;
			out[at + i] += (float) (Math.sin(phase * 2 * Math.PI) * 0.32 * (1 - t));
		}
	}

	private static void hat(float[] out, int at, double seconds, double volume, double brightness) {
		int n = (int) (seconds * RATE);
		Random random = new Random(at);
		double y = 0;
		for (int i = 0; i < n && at + i < out.length; i++) {
			double t = i / (double) n;
			double x = random.nextDouble() * 2 - 1;
			y += brightness * (x - y);
			out[at + i] += (float) ((x - y * (1 - brightness)) * volume * (1 - t));
		}
	}
}
