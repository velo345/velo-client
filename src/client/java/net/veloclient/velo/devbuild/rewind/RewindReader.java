package net.veloclient.velo.devbuild.rewind;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads a {@code .vrewind} (plain Java): meta.json and the frame streams written by {@link RewindWriter}. */
public final class RewindReader implements AutoCloseable {

	/** One frame: its type ({@code RewindWriter.F_*}) and body (null for markers). */
	public record Frame(int type, byte[] body) {
	}

	private final ZipFile zip;

	public RewindReader(Path file) throws IOException {
		this.zip = new ZipFile(file.toFile());
	}

	public String meta() throws IOException {
		ZipEntry e = zip.getEntry("meta.json");
		if (e == null) {
			return "{}";
		}
		try (InputStream in = zip.getInputStream(e)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** Every entry's name. */
	public java.util.List<String> names() {
		java.util.List<String> out = new java.util.ArrayList<>();
		zip.stream().forEach(e -> out.add(e.getName()));
		return out;
	}

	/** Number of {@code seg-N.bin} entries. */
	public int segments() {
		int n = 0;
		while (zip.getEntry("seg-" + n + ".bin") != null) {
			n++;
		}
		return n;
	}

	/** A frame stream: "config.bin", "seg-N.bin", "audio-....bin". Null if there's no such entry. */
	public Frames open(String name) throws IOException {
		ZipEntry e = zip.getEntry(name);
		return e == null ? null : new Frames(zip.getInputStream(e));
	}

	@Override
	public void close() throws IOException {
		zip.close();
	}

	/** Sequential frames of one stream. */
	public static final class Frames implements AutoCloseable {
		private final DataInputStream in;

		public Frames(InputStream raw) {
			in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(raw, new Inflater(), 1 << 16), 1 << 16));
		}

		/** The next frame, or null at the end. */
		public Frame next() throws IOException {
			int type;
			try {
				type = in.readUnsignedByte();
			} catch (EOFException | java.util.zip.ZipException e) {
				return null;
			}
			if (type == RewindWriter.F_TICK || type == RewindWriter.F_SNAPSHOT_END) {
				return new Frame(type, null);
			}
			try {
				int len = 0;
				int shift = 0;
				int b;
				do {
					b = in.readUnsignedByte();
					len |= (b & 0x7F) << shift;
					shift += 7;
				} while ((b & 0x80) != 0 && shift < 35);
				if (len < 0 || len > (64 << 20)) {
					return null; // garbage after a crash
				}
				byte[] body = new byte[len];
				in.readFully(body);
				return new Frame(type, body);
			} catch (EOFException e) {
				return null; // a recovered recording ends mid-frame: stop at the last whole one
			}
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}
}
