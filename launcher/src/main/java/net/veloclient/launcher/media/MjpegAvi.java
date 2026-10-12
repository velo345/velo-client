package net.veloclient.launcher.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the game's clips (Motion-JPEG in AVI, written by the mod's {@code ClipRecorder}): every
 * frame is a plain JPEG, so JavaFX can show any frame directly. Same parser as the mod's.
 */
public final class MjpegAvi {

	public record Clip(List<byte[]> frames, double fps, int width, int height) {
		public double seconds() {
			return frames.size() / Math.max(1, fps);
		}
	}

	private MjpegAvi() {
	}

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
			} else if (id.endsWith("dc")) {
				byte[] frame = new byte[size];
				data.get(pos + 8, frame);
				frames.add(frame);
			}
			pos += 8 + size + (size & 1);
		}
	}
}
