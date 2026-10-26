package net.veloclient.velo.client.gui.packs;

import net.veloclient.velo.client.clips.FfmpegTool;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A decoded sound: interleaved 16-bit PCM. Ogg Vorbis (what the game uses) decodes through LWJGL's stb_vorbis,
 * which every supported version ships; other formats and every save go through FFmpeg (Velo's own download, see
 * {@link FfmpegTool}). Edits (trim, gain, fades, speed, reverse, normalize) work on the samples.
 */
public final class AudioData {

	public final short[] pcm;
	public final int channels;
	public final int rate;

	public AudioData(short[] pcm, int channels, int rate) {
		this.pcm = pcm;
		this.channels = Math.max(1, channels);
		this.rate = rate;
	}

	public int frames() {
		return pcm.length / channels;
	}

	public double seconds() {
		return frames() / (double) rate;
	}

	// ---- Decoding ----

	public static AudioData decodeOgg(byte[] ogg) throws IOException {
		// The pull API with buffers we own: stb's one-shot decoder returns memory from LWJGL's allocator, and freeing
		// that with the wrong free() aborts the JVM.
		ByteBuffer data = MemoryUtil.memAlloc(ogg.length);
		ShortBuffer chunk = null;
		long decoder = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			data.put(ogg).flip();
			IntBuffer error = stack.mallocInt(1);
			decoder = STBVorbis.stb_vorbis_open_memory(data, error, null);
			if (decoder == 0) {
				throw new IOException("Not an Ogg Vorbis file (" + error.get(0) + ")");
			}
			STBVorbisInfo info = STBVorbisInfo.malloc(stack);
			STBVorbis.stb_vorbis_get_info(decoder, info);
			int channels = info.channels();
			int rate = info.sample_rate();
			int total = STBVorbis.stb_vorbis_stream_length_in_samples(decoder);
			short[] pcm = new short[Math.max(0, total) * channels];
			int written = 0;
			chunk = MemoryUtil.memAllocShort(4096 * channels);
			while (true) {
				chunk.clear();
				int frames = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, channels, chunk);
				if (frames <= 0) {
					break;
				}
				int n = frames * channels;
				if (written + n > pcm.length) {
					pcm = java.util.Arrays.copyOf(pcm, Math.max(pcm.length * 2, written + n));
				}
				chunk.get(pcm, written, n);
				written += n;
			}
			return new AudioData(written == pcm.length ? pcm : java.util.Arrays.copyOf(pcm, written), channels, rate);
		} finally {
			if (decoder != 0) {
				STBVorbis.stb_vorbis_close(decoder);
			}
			if (chunk != null) {
				MemoryUtil.memFree(chunk);
			}
			MemoryUtil.memFree(data);
		}
	}

	/** Any audio file: .ogg directly, everything else (mp3, wav, flac, m4a...) through FFmpeg. */
	public static AudioData decodeFile(Path file) throws Exception {
		String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		if (name.endsWith(".ogg")) {
			try {
				return decodeOgg(Files.readAllBytes(file));
			} catch (IOException notVorbis) {
				// An .ogg with Opus inside: FFmpeg below.
			}
		}
		Path ffmpeg = FfmpegTool.binary();
		if (ffmpeg == null) {
			throw new IOException("FFMPEG");
		}
		Process process = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-i", file.toString(), "-vn", "-f", "s16le",
				"-acodec", "pcm_s16le", "-ac", "2", "-ar", "44100", "-").redirectError(ProcessBuilder.Redirect.DISCARD).start();
		byte[] raw;
		try (InputStream in = process.getInputStream()) {
			raw = in.readAllBytes();
		}
		process.waitFor(60, TimeUnit.SECONDS);
		if (raw.length < 4) {
			throw new IOException("FFmpeg couldn't read " + file.getFileName());
		}
		short[] pcm = new short[raw.length / 2];
		for (int i = 0; i < pcm.length; i++) {
			pcm[i] = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
		}
		return new AudioData(pcm, 2, 44100);
	}

	/** Encodes to Ogg Vorbis (needs FFmpeg). */
	public void writeOgg(Path out) throws Exception {
		Path ffmpeg = FfmpegTool.binary();
		if (ffmpeg == null) {
			throw new IOException("FFMPEG");
		}
		Files.createDirectories(out.getParent());
		Path tmp = out.resolveSibling(out.getFileName() + ".tmp.ogg");
		boolean lib = FfmpegTool.hasEncoder("libvorbis");
		ProcessBuilder builder = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-y", "-f", "s16le", "-ar",
				String.valueOf(rate), "-ac", String.valueOf(channels), "-i", "-", "-c:a", lib ? "libvorbis" : "vorbis", "-q:a", "5");
		if (!lib) {
			builder.command().add("-strict");
			builder.command().add("-2");
			if (channels > 2) {
				builder.command().add("-ac");
				builder.command().add("2");
			}
		}
		builder.command().add(tmp.toString());
		Process process = builder.redirectErrorStream(true).start();
		Thread drain = Thread.ofVirtual().start(() -> {
			try (InputStream in = process.getInputStream()) {
				in.readAllBytes();
			} catch (IOException ignored) {
				// Done.
			}
		});
		try (OutputStream o = process.getOutputStream()) {
			byte[] bytes = new byte[pcm.length * 2];
			for (int i = 0; i < pcm.length; i++) {
				bytes[i * 2] = (byte) pcm[i];
				bytes[i * 2 + 1] = (byte) (pcm[i] >> 8);
			}
			o.write(bytes);
		}
		if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0 || !Files.exists(tmp)) {
			process.destroyForcibly();
			Files.deleteIfExists(tmp);
			throw new IOException("FFmpeg couldn't encode the sound");
		}
		drain.join(1000);
		Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	/** A plain 16-bit WAV (no FFmpeg needed) - for exporting. */
	public void writeWav(Path out) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(44 + pcm.length * 2);
		int dataLen = pcm.length * 2;
		writeAscii(bytes, "RIFF");
		writeInt(bytes, 36 + dataLen);
		writeAscii(bytes, "WAVEfmt ");
		writeInt(bytes, 16);
		writeShort(bytes, 1);
		writeShort(bytes, channels);
		writeInt(bytes, rate);
		writeInt(bytes, rate * channels * 2);
		writeShort(bytes, channels * 2);
		writeShort(bytes, 16);
		writeAscii(bytes, "data");
		writeInt(bytes, dataLen);
		for (short s : pcm) {
			writeShort(bytes, s);
		}
		Files.write(out, bytes.toByteArray());
	}

	private static void writeAscii(ByteArrayOutputStream out, String s) {
		out.writeBytes(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
	}

	private static void writeInt(ByteArrayOutputStream out, int v) {
		out.write(v);
		out.write(v >> 8);
		out.write(v >> 16);
		out.write(v >> 24);
	}

	private static void writeShort(ByteArrayOutputStream out, int v) {
		out.write(v);
		out.write(v >> 8);
	}

	// ---- Edits ----

	/** The edits of the sound editor, applied in this order: trim, speed, reverse, gain / normalize, fades. */
	public record Edits(double start, double end, double gain, double speed, double fadeIn, double fadeOut, boolean reverse, boolean normalize,
			boolean mono) {
		public static Edits none(double seconds) {
			return new Edits(0, seconds, 1, 1, 0, 0, false, false, false);
		}

		public boolean isNone(double seconds) {
			return start <= 0.0005 && end >= seconds - 0.0005 && Math.abs(gain - 1) < 0.005 && Math.abs(speed - 1) < 0.005 && fadeIn <= 0
					&& fadeOut <= 0 && !reverse && !normalize && !mono;
		}
	}

	public AudioData apply(Edits e) {
		int from = (int) Math.max(0, Math.min(frames(), Math.round(e.start() * rate)));
		int to = (int) Math.max(from + 1, Math.min(frames(), Math.round(e.end() * rate)));
		int outChannels = e.mono() ? 1 : channels;
		int length = to - from;
		int outFrames = Math.max(1, (int) Math.round(length / Math.max(0.1, e.speed())));
		float[] out = new float[outFrames * outChannels];
		for (int f = 0; f < outFrames; f++) {
			double src = from + f * e.speed();
			int i0 = Math.min(to - 1, (int) Math.floor(src));
			int i1 = Math.min(to - 1, i0 + 1);
			double t = src - Math.floor(src);
			int target = e.reverse() ? outFrames - 1 - f : f;
			if (outChannels == 1 && channels > 1) {
				double sum = 0;
				for (int c = 0; c < channels; c++) {
					sum += pcm[i0 * channels + c] * (1 - t) + pcm[i1 * channels + c] * t;
				}
				out[target] = (float) (sum / channels);
			} else {
				for (int c = 0; c < outChannels; c++) {
					out[target * outChannels + c] = (float) (pcm[i0 * channels + c] * (1 - t) + pcm[i1 * channels + c] * t);
				}
			}
		}
		double gain = e.gain();
		if (e.normalize()) {
			float peak = 1;
			for (float v : out) {
				peak = Math.max(peak, Math.abs(v));
			}
			gain *= 32000.0 / peak;
		}
		int fadeIn = (int) Math.round(e.fadeIn() * rate);
		int fadeOut = (int) Math.round(e.fadeOut() * rate);
		short[] result = new short[out.length];
		for (int f = 0; f < outFrames; f++) {
			double g = gain;
			if (fadeIn > 0 && f < fadeIn) {
				g *= f / (double) fadeIn;
			}
			if (fadeOut > 0 && f > outFrames - fadeOut) {
				g *= (outFrames - f) / (double) fadeOut;
			}
			for (int c = 0; c < outChannels; c++) {
				int i = f * outChannels + c;
				result[i] = (short) Math.max(-32768, Math.min(32767, Math.round(out[i] * g)));
			}
		}
		return new AudioData(result, outChannels, rate);
	}

	/** Peak level (0..1) per column for a waveform {@code columns} wide. */
	public float[] peaks(int columns) {
		float[] out = new float[Math.max(1, columns)];
		int frames = frames();
		if (frames == 0) {
			return out;
		}
		for (int col = 0; col < out.length; col++) {
			int a = (int) ((long) col * frames / out.length);
			int b = (int) Math.max(a + 1, (long) (col + 1) * frames / out.length);
			int step = Math.max(1, (b - a) / 64);
			int peak = 0;
			for (int f = a; f < b; f += step) {
				for (int c = 0; c < channels; c++) {
					peak = Math.max(peak, Math.abs(pcm[f * channels + c]));
				}
			}
			out[col] = peak / 32768f;
		}
		return out;
	}
}
