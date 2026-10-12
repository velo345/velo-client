package net.veloclient.velo.client.clips;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Clips are Motion-JPEG in an AVI container: every frame is a plain JPEG, so recording needs only
 * the JDK's JPEG encoder (no native video codec), VLC / Windows Media Player / browsers' download
 * previews play them, and the in-game and launcher viewers can decode any single frame instantly.
 */
public final class MjpegAvi {

	private MjpegAvi() {
	}

	/** Writes {@code jpegs} as an AVI at {@code fps} frames per second. */
	public static void write(Path file, List<byte[]> jpegs, double fps, int width, int height) throws IOException {
		Out movi = new Out();
		movi.fourcc("movi");
		List<int[]> index = new ArrayList<>();
		int maxFrame = 0;
		for (byte[] jpeg : jpegs) {
			index.add(new int[] {movi.size(), jpeg.length});
			movi.fourcc("00dc").int32(jpeg.length).bytes(jpeg);
			if ((jpeg.length & 1) == 1) {
				movi.bytes(new byte[1]);
			}
			maxFrame = Math.max(maxFrame, jpeg.length);
		}

		int frames = jpegs.size();
		int microsPerFrame = (int) Math.round(1_000_000 / fps);
		Out avih = new Out();
		avih.int32(microsPerFrame).int32((int) (maxFrame * fps)).int32(0).int32(0x10).int32(frames).int32(0).int32(1)
				.int32(maxFrame + 8).int32(width).int32(height).int32(0).int32(0).int32(0).int32(0);
		Out strh = new Out();
		strh.fourcc("vids").fourcc("MJPG").int32(0).int16(0).int16(0).int32(0)
				.int32(1000).int32((int) Math.round(fps * 1000)).int32(0).int32(frames).int32(maxFrame + 8).int32(-1).int32(0)
				.int16(0).int16(0).int16(width).int16(height);
		Out strf = new Out();
		strf.int32(40).int32(width).int32(height).int16(1).int16(24).fourcc("MJPG").int32(width * height * 3)
				.int32(0).int32(0).int32(0).int32(0);
		Out strl = new Out();
		strl.fourcc("strl").chunk("strh", strh).chunk("strf", strf);
		Out hdrl = new Out();
		hdrl.fourcc("hdrl").chunk("avih", avih).list(strl);

		Out idx1 = new Out();
		for (int[] entry : index) {
			idx1.fourcc("00dc").int32(0x10).int32(entry[0]).int32(entry[1]);
		}
		Out riff = new Out();
		riff.fourcc("AVI ").list(hdrl).list(movi).chunk("idx1", idx1);
		Out file0 = new Out();
		file0.fourcc("RIFF").int32(riff.size()).bytes(riff.toByteArray());
		Path tmp = file.resolveSibling(file.getFileName() + ".part");
		Files.write(tmp, file0.toByteArray());
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	/** A clip opened for playback: every frame's JPEG bytes, plus its frame rate. */
	public record Clip(List<byte[]> frames, double fps, int width, int height) {
		public double seconds() {
			return frames.size() / Math.max(1, fps);
		}
	}

	/** Reads an MJPEG AVI (ours, or any simple one with '##dc' JPEG chunks). */
	public static Clip read(Path file) throws IOException {
		ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		List<byte[]> frames = new ArrayList<>();
		int[] header = {66666, 0, 0};
		scan(data, 12, data.capacity(), frames, header);
		double fps = header[0] > 0 ? 1_000_000.0 / header[0] : 15;
		return new Clip(frames, fps, header[1], header[2]);
	}

	private static void scan(ByteBuffer data, int from, int to, List<byte[]> frames, int[] header) {
		int pos = from;
		while (pos + 8 <= to) {
			String id = new String(new byte[] {data.get(pos), data.get(pos + 1), data.get(pos + 2), data.get(pos + 3)}, StandardCharsets.US_ASCII);
			int size = data.getInt(pos + 4);
			if (size < 0 || pos + 8 + size > to) {
				break;
			}
			if (id.equals("LIST")) {
				scan(data, pos + 12, pos + 8 + size, frames, header);
			} else if (id.equals("avih") && size >= 40) {
				header[0] = data.getInt(pos + 8);
				header[1] = data.getInt(pos + 8 + 32);
				header[2] = data.getInt(pos + 8 + 36);
			} else if (id.length() == 4 && id.endsWith("dc")) {
				byte[] frame = new byte[size];
				data.get(pos + 8, frame);
				frames.add(frame);
			}
			pos += 8 + size + (size & 1);
		}
	}

	/** Little-endian RIFF builder. */
	private static final class Out extends ByteArrayOutputStream {
		Out fourcc(String id) {
			write(id.getBytes(StandardCharsets.US_ASCII), 0, 4);
			return this;
		}

		Out int32(int v) {
			write(v);
			write(v >>> 8);
			write(v >>> 16);
			write(v >>> 24);
			return this;
		}

		Out int16(int v) {
			write(v);
			write(v >>> 8);
			return this;
		}

		Out bytes(byte[] b) {
			write(b, 0, b.length);
			return this;
		}

		Out chunk(String id, Out body) {
			fourcc(id).int32(body.size()).bytes(body.toByteArray());
			if ((body.size() & 1) == 1) {
				write(0);
			}
			return this;
		}

		Out list(Out body) {
			return chunk("LIST", body);
		}
	}
}
