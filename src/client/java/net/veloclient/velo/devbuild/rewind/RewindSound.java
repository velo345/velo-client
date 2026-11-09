package net.veloclient.velo.devbuild.rewind;

/**
 * Velo Rewind playback audio: the recording's voice / microphone / system audio tracks, mixed and played in step with the
 * replay (voices come from where their player is, quieter with distance, panned left / right), and the volume tracks
 * applied to the game's own sounds (RewindSoundMixin asks {@link #categoryGain}). During an export in "voice tracks" mode
 * the mix is also written to a file for the video.
 */
//? if <26.2 {
public final class RewindSound {

	private RewindSound() {
	}

	public static float categoryGain(int ordinal) {
		return 1f;
	}

	static java.util.List<String> voiceTracks() {
		return java.util.List.of();
	}

	static String status() {
		return null;
	}
}
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RewindSound {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Velo Rewind");
	private static final int RATE = RewindAudio.RATE;
	private static final int PER_TICK = RATE / 20;
	private static final int CHUNK = 960; // 20 ms
	private static final double VOICE_RANGE = 48;

	private record Block(long start, int frames, short[] pcm, int channels, float x, float y, float z) {
	}

	private static final class Track {
		final String id;
		final List<Block> blocks = new ArrayList<>();
		int cursor;
		volatile float gain = 1f;
		volatile double ex = Double.NaN;
		volatile double ey;
		volatile double ez;

		Track(String id) {
			this.id = id;
		}
	}

	private static volatile Map<String, Track> tracks = Map.of();
	private static volatile String status;
	private static volatile Object loadedFor;
	private static Thread mixer;
	private static volatile boolean running;

	// the playhead, from the game thread
	private static volatile long tickAt;
	private static volatile long tickNanos;
	private static volatile boolean playing;
	private static volatile double camX;
	private static volatile double camY;
	private static volatile double camZ;
	private static volatile double camYaw;
	private static volatile float master = 1f;
	private static volatile float voiceVolume = 1f;

	// Export ("voice tracks" mode): the mix is copied here as raw 48 kHz stereo s16le while set.
	static volatile OutputStream capture;

	// game sound categories: the clip's multiplier per SoundSource ordinal
	private static final float[] CATEGORY = new float[SoundSource.values().length];
	private static final float[] APPLIED = new float[SoundSource.values().length];

	static {
		java.util.Arrays.fill(CATEGORY, 1f);
		java.util.Arrays.fill(APPLIED, 1f);
	}

	private RewindSound() {
	}

	public static float categoryGain(int ordinal) {
		return ordinal >= 0 && ordinal < CATEGORY.length ? CATEGORY[ordinal] : 1f;
	}

	static List<String> voiceTracks() {
		List<String> out = new ArrayList<>();
		for (String id : tracks.keySet()) {
			if (id.startsWith("voice-") && id.length() == 6 + 36) {
				out.add(id.substring(6));
			}
		}
		return out;
	}

	static String status() {
		return status;
	}

	// ---- loading (a replay opened) ----

	static void open(java.nio.file.Path file) {
		close();
		Object token = new Object();
		loadedFor = token;
		status = "Loading audio...";
		Thread.ofPlatform().daemon().name("velo-rewind-audio-load").start(() -> {
			Map<String, Track> loaded = new LinkedHashMap<>();
			try (RewindReader r = new RewindReader(file)) {
				for (String name : r.names()) {
					if (!name.startsWith("audio-") || !name.endsWith(".bin")) {
						continue;
					}
					Track t = new Track(name.substring(6, name.length() - 4));
					long next = 0;
					try (RewindReader.Frames f = r.open(name)) {
						for (RewindReader.Frame fr; (fr = f.next()) != null; ) {
							if (fr.type() != RewindWriter.F_AUDIO) {
								continue;
							}
							ByteBuffer b = ByteBuffer.wrap(fr.body());
							int tick = b.getInt();
							int ch = Math.max(1, b.get());
							b.getInt(); // rate (always 48 kHz)
							float x = b.getFloat();
							float y = b.getFloat();
							float z = b.getFloat();
							short[] pcm = new short[b.remaining() / 2];
							b.asShortBuffer().get(pcm);
							long at = (long) tick * PER_TICK;
							// blocks of one tick follow each other; a gap (silence, a pause) starts again at its tick
							long start = next > at && next - at < PER_TICK * 2L ? next : at;
							int frames = pcm.length / ch;
							t.blocks.add(new Block(start, frames, pcm, ch, x, y, z));
							next = start + frames;
						}
					}
					if (!t.blocks.isEmpty()) {
						loaded.put(t.id, t);
					}
				}
			} catch (Exception e) {
				LOG.warn("Velo Rewind: couldn't read the audio of {}", file.getFileName(), e);
			}
			if (loadedFor != token) {
				return;
			}
			tracks = loaded;
			status = loaded.isEmpty() ? null : loaded.size() + (loaded.size() == 1 ? " audio track" : " audio tracks");
			if (!loaded.isEmpty()) {
				startMixer();
			}
		});
	}

	static void close() {
		loadedFor = null;
		running = false;
		mixer = null;
		tracks = Map.of();
		status = null;
		java.util.Arrays.fill(CATEGORY, 1f);
		Minecraft.getInstance().execute(RewindSound::refreshCategories);
	}

	// ---- every game tick ----

	static void tick(RewindPlayer p) {
		Minecraft mc = Minecraft.getInstance();
		RewindEdits.Clip c = p.edits.clip();
		double t = RewindApply.previewTick >= 0 ? RewindApply.previewTick : p.clipPosition();
		float all = (float) or(c.value("volume", t), 1);
		// game sounds
		for (SoundSource s : SoundSource.values()) {
			String id = switch (s) {
				case PLAYERS -> "vol_players";
				case HOSTILE -> "vol_hostile";
				case NEUTRAL -> "vol_neutral";
				case BLOCKS -> "vol_blocks";
				case WEATHER -> "vol_weather";
				case AMBIENT -> "vol_ambient";
				case RECORDS -> "vol_records";
				case MUSIC -> "vol_music";
				case VOICE -> "vol_voice";
				default -> null;
			};
			CATEGORY[s.ordinal()] = s == SoundSource.MASTER || s == SoundSource.UI ? 1f : all * (float) or(id == null ? null : c.value(id, t), 1);
		}
		refreshCategories();
		// the recorded audio
		master = all * mc.options.getFinalSoundSourceVolume(SoundSource.MASTER);
		voiceVolume = mc.options.getSoundSourceVolume(SoundSource.VOICE);
		for (Track tr : tracks.values()) {
			float g;
			if (tr.id.startsWith("voice-")) {
				g = (float) (or(c.value("vol_voice", t), 1) * voiceVolume);
				if (tr.id.length() == 6 + 36) {
					g *= (float) or(c.value("voice:" + tr.id.substring(6), t), 1);
					Entity e = entity(mc, tr.id.substring(6));
					if (e != null) {
						tr.ex = e.getX();
						tr.ey = e.getEyeY();
						tr.ez = e.getZ();
					} else {
						tr.ex = Double.NaN;
					}
				}
			} else if (tr.id.equals("mic")) {
				g = (float) (or(c.value("vol_mic", t), 1) * voiceVolume);
			} else {
				g = (float) or(c.value("vol_system", t), 1);
			}
			tr.gain = g;
		}
		var cam = mc.gameRenderer.mainCamera();
		camX = cam.position().x;
		camY = cam.position().y;
		camZ = cam.position().z;
		camYaw = Math.toRadians(cam.yRot());
		tickAt = p.position();
		tickNanos = System.nanoTime();
		playing = !p.paused() && Math.abs(p.speed() - 1f) < 0.01f && RewindApply.previewTick < 0;
		// player names for the voice tracks
		if (mc.getConnection() != null && mc.level != null && mc.level.getGameTime() % 20 == 0) {
			for (String uuid : voiceTracks()) {
				var info = mc.getConnection().getPlayerInfo(UUID.fromString(uuid));
				if (info != null) {
					RewindEdits.VOICE_NAMES.put(uuid, info.getProfile().name());
				}
			}
		}
	}

	private static Entity entity(Minecraft mc, String uuid) {
		try {
			UUID id = UUID.fromString(uuid);
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e.getUUID().equals(id)) {
					return e;
				}
			}
		} catch (IllegalArgumentException ignored) {
		}
		return null;
	}

	private static void refreshCategories() {
		Minecraft mc = Minecraft.getInstance();
		for (SoundSource s : SoundSource.values()) {
			if (Math.abs(APPLIED[s.ordinal()] - CATEGORY[s.ordinal()]) > 0.001f) {
				APPLIED[s.ordinal()] = CATEGORY[s.ordinal()];
				mc.getSoundManager().refreshCategoryVolume(s);
			}
		}
	}

	private static double or(Double v, double fallback) {
		return v == null ? fallback : v;
	}

	// ---- the mixer ----

	private static synchronized void startMixer() {
		if (running) {
			return;
		}
		running = true;
		mixer = Thread.ofPlatform().daemon().name("velo-rewind-audio").start(RewindSound::mixLoop);
	}

	private static void mixLoop() {
		javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(RATE, 16, 2, true, false);
		try (javax.sound.sampled.SourceDataLine line = javax.sound.sampled.AudioSystem.getSourceDataLine(format)) {
			line.open(format, CHUNK * 4 * 6);
			line.start();
			long clock = 0;
			float[] mix = new float[CHUNK * 2];
			byte[] out = new byte[CHUNK * 4];
			while (running) {
				long target = target();
				boolean on = playing;
				if (!on || Math.abs(clock - target) > PER_TICK * 2L) {
					clock = target; // a jump, a pause, a different speed: follow the replay
					for (Track t : tracks.values()) {
						t.cursor = 0;
					}
				}
				java.util.Arrays.fill(mix, 0f);
				if (on) {
					for (Track t : tracks.values()) {
						mixTrack(t, clock, mix);
					}
				}
				ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
				for (float v : mix) {
					bb.putShort((short) Math.max(-32768, Math.min(32767, Math.round(v))));
				}
				line.write(out, 0, out.length); // blocks while the line is full: keeps us in real time
				OutputStream cap = capture;
				if (cap != null) {
					try {
						cap.write(out);
					} catch (java.io.IOException e) {
						capture = null;
					}
				}
				if (on) {
					clock += CHUNK;
				}
			}
			line.drain();
		} catch (Exception e) {
			LOG.warn("Velo Rewind: no audio output", e);
			status = "No audio output: " + e.getMessage();
		}
	}

	// Where the replay is now, in samples (between ticks too).
	private static long target() {
		long since = System.nanoTime() - tickNanos;
		long into = playing ? Math.min(PER_TICK, since * RATE / 1_000_000_000L) : 0;
		return tickAt * PER_TICK + into;
	}

	private static void mixTrack(Track t, long clock, float[] mix) {
		List<Block> blocks = t.blocks;
		// first block that may still sound at clock (blocks are in time order)
		int i = t.cursor;
		if (i >= blocks.size() || blocks.get(i).start() > clock) {
			int lo = 0;
			int hi = blocks.size() - 1;
			i = blocks.size();
			while (lo <= hi) {
				int mid = (lo + hi) >>> 1;
				Block b = blocks.get(mid);
				if (b.start() + b.frames() > clock) {
					i = mid;
					hi = mid - 1;
				} else {
					lo = mid + 1;
				}
			}
		}
		while (i < blocks.size() && blocks.get(i).start() + blocks.get(i).frames() <= clock) {
			i++;
		}
		t.cursor = i;
		float gain = t.gain * master;
		if (gain <= 0.0001f) {
			return;
		}
		for (int k = i; k < blocks.size(); k++) {
			Block b = blocks.get(k);
			if (b.start() >= clock + CHUNK) {
				break;
			}
			float[] lr = pan(t, b);
			float gl = gain * lr[0];
			float gr = gain * lr[1];
			if (gl <= 0.0001f && gr <= 0.0001f) {
				continue;
			}
			int from = (int) Math.max(0, clock - b.start());
			int to = (int) Math.min(b.frames(), clock + CHUNK - b.start());
			for (int f = from; f < to; f++) {
				int o = (int) (b.start() + f - clock) * 2;
				if (b.channels() == 2) {
					mix[o] += b.pcm()[f * 2] * gl;
					mix[o + 1] += b.pcm()[f * 2 + 1] * gr;
				} else {
					float s = b.pcm()[f];
					mix[o] += s * gl;
					mix[o + 1] += s * gr;
				}
			}
		}
	}

	// Distance fade and left / right from where the voice is, relative to the camera (non-positional: both sides).
	private static float[] pan(Track t, Block b) {
		double x;
		double y;
		double z;
		if (!Float.isNaN(b.x())) {
			x = b.x();
			y = b.y();
			z = b.z();
		} else if (!Double.isNaN(t.ex)) {
			x = t.ex;
			y = t.ey;
			z = t.ez;
		} else {
			return t.id.startsWith("voice-") && t.id.length() == 6 + 36 ? new float[] {0f, 0f} : new float[] {1f, 1f};
		}
		double dx = x - camX;
		double dy = y - camY;
		double dz = z - camZ;
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		float fade = (float) Math.max(0, 1 - d / VOICE_RANGE);
		if (d < 0.5) {
			return new float[] {fade, fade};
		}
		// the camera's right: (-cos yaw, -sin yaw) in Minecraft's x / z
		double side = (dx * -Math.cos(camYaw) + dz * -Math.sin(camYaw)) / Math.max(0.001, Math.hypot(dx, dz));
		double angle = (side + 1) * Math.PI / 4; // 0 = left, pi/2 = right
		return new float[] {(float) (Math.cos(angle) * Math.sqrt(2)) * fade * 0.85f, (float) (Math.sin(angle) * Math.sqrt(2)) * fade * 0.85f};
	}
}
*///?}
