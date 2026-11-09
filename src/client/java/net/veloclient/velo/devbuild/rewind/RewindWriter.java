package net.veloclient.velo.devbuild.rewind;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Velo Rewind's disk side (plain Java): the game thread only queues packet objects and markers; this thread encodes them
 * (through the {@link Encoder} the game supplies), compresses and writes them, so recording costs the game next to
 * nothing.
 *
 * <p>A recording is built in a folder next to the final file ({@code .<name>.rewind-tmp/}) and zipped into
 * {@code <name>.vrewind} when it stops:
 * <ul>
 *   <li>{@code meta.json} - version, Minecraft version / protocol, server, start time, ticks, segments, the recorder's own
 *       entity id / uuid / name</li>
 *   <li>{@code config-K.bin} - the configuration-phase packets (registries, tags, features) a player needs to join; a new one
 *       each time the recording follows the player to another server</li>
 *   <li>{@code seg-N.bin} - one per stretch between start / resume and pause / stop: a snapshot of the world (so every
 *       segment can be played on its own), then the packets tick by tick</li>
 *   <li>{@code audio-*.bin} - optional audio tracks (see {@link #audio})</li>
 * </ul>
 * Every {@code .bin} is a deflate stream of frames: {@code [type u8][varint length][bytes]} - see the {@code F_} constants.
 */
public final class RewindWriter extends Thread {

	public static final int FORMAT = 1;

	/** A packet: the protocol's own encoding (id + body). */
	public static final int F_PACKET = 1;
	/** End of a client tick (no body). */
	public static final int F_TICK = 2;
	/** The snapshot at the start of a segment ends here (no body); everything after is live. */
	public static final int F_SNAPSHOT_END = 3;
	/** Audio: [i32 tick][u8 channels][i32 sample rate][f32 x][f32 y][f32 z][i16 pcm...] (track files only). */
	public static final int F_AUDIO = 4;

	/** Turns one packet object into its bytes (more than one for a bundle); the encoding runs on this thread. */
	public interface Encoder {
		void encode(Object packet, Object protocol, Consumer<byte[]> out);
	}

	private record Entry(int kind, Object packet, Object protocol, Object extra) {
	}

	private static final int K_CONFIG = 1;
	private static final int K_PACKET = 2;
	private static final int K_TICK = 3;
	private static final int K_SNAPSHOT_END = 4;
	private static final int K_SEGMENT = 5;
	private static final int K_END_SEGMENT = 6;
	private static final int K_AUDIO = 7;
	private static final int K_FINISH = 8;
	private static final int K_NEW_CONFIG = 9;
	private static final int K_THUMB = 10;
	private static final int K_HEAD = 11;

	/** Recording data reaches the disk at least this often (ticks), so a crash loses seconds, not the recording. */
	private static final int CHECKPOINT_TICKS = 100;

	private final LinkedBlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
	private final Encoder encoder;
	private final Path dir;
	private final Path target;
	private volatile boolean failed;
	private volatile String error;

	private DataOutputStream config;
	private DataOutputStream segment;
	private final java.util.Map<String, DataOutputStream> tracks = new java.util.HashMap<>();
	private int segmentIndex = -1;
	private int configIndex;
	private final List<String> segmentInfo = new ArrayList<>();
	private int segmentTicks;
	private final List<Integer> segmentLengths = new ArrayList<>();
	private long packets;
	private long bytes;
	private int ticks;
	private int sinceCheckpoint;
	private volatile String head = "";
	private volatile boolean finishing;
	private final java.nio.channels.FileChannel lockChannel;
	private final java.nio.channels.FileLock lock;
	private final Thread shutdownHook;

	/** {@code target}: the final {@code .vrewind} file. */
	public RewindWriter(Path target, Encoder encoder) throws IOException {
		super("Velo Rewind writer");
		setDaemon(true);
		setPriority(Thread.NORM_PRIORITY - 1);
		this.target = target;
		this.encoder = encoder;
		String name = target.getFileName().toString().replaceFirst("\\.vrewind$", "");
		this.dir = target.resolveSibling("." + name + ".rewind-tmp");
		Files.createDirectories(dir);
		// held while recording, so crash recovery (maybe from another game instance) leaves this folder alone
		lockChannel = java.nio.channels.FileChannel.open(dir.resolve(".lock"), java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.WRITE);
		lock = lockChannel.tryLock();
		config = open(dir.resolve("config-0.bin"));
		// the game closing (window closed, crash report, System.exit) without stopping first: finish the file anyway
		shutdownHook = new Thread(() -> {
			if (!finishing && isAlive()) {
				endSegment();
				finishBlocking(null, 10_000);
			}
		}, "Velo Rewind shutdown save");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
	}

	/** The meta fields known when recording starts (server, versions, the recorder's own player) - used if it ends abruptly. */
	public void head(String metaFields) {
		head = metaFields;
		queue.add(new Entry(K_HEAD, metaFields, null, null));
	}

	/** {@link #finish} and wait for it (the game is closing); {@code meta} null = the fields from {@link #head}. */
	public Path finishBlocking(String meta, long timeoutMs) {
		java.util.concurrent.CompletableFuture<Path> done = new java.util.concurrent.CompletableFuture<>();
		finish(meta, done::complete);
		try {
			return done.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			return null;
		}
	}

	// ---- game thread ----

	public void config(Object packet, Object protocol) {
		queue.add(new Entry(K_CONFIG, packet, protocol, null));
	}

	public void packet(Object packet, Object protocol) {
		queue.add(new Entry(K_PACKET, packet, protocol, null));
	}

	public void tick() {
		queue.add(new Entry(K_TICK, null, null, null));
	}

	public void snapshotEnd() {
		queue.add(new Entry(K_SNAPSHOT_END, null, null, null));
	}

	/** The player moved to another server: the following config packets start a new configuration (config-K.bin). */
	public void newConfig() {
		queue.add(new Entry(K_NEW_CONFIG, null, null, null));
	}

	/** The library's picture (JPEG/PNG bytes). */
	public void thumbnail(byte[] image, String name) {
		queue.add(new Entry(K_THUMB, image, name, null));
	}

	/** A new segment starts (recording started or resumed); its snapshot follows. */
	public void beginSegment() {
		queue.add(new Entry(K_SEGMENT, null, null, null));
	}

	/** The segment ends (paused / stopping). */
	public void endSegment() {
		queue.add(new Entry(K_END_SEGMENT, null, null, null));
	}

	/**
	 * One block of audio for track {@code track} ("voice-&lt;uuid&gt;", "mic", "system"): 16-bit PCM, positioned at x/y/z
	 * (NaN = not positioned), at the current tick.
	 */
	public void audio(String track, short[] pcm, int channels, int sampleRate, float x, float y, float z) {
		queue.add(new Entry(K_AUDIO, pcm, track, new float[] {channels, sampleRate, x, y, z}));
	}

	/** Writes meta.json ({@code meta} = its other fields, already JSON) and zips the recording; {@code done} gets the file or null. */
	public void finish(String meta, Consumer<Path> done) {
		if (finishing) {
			return;
		}
		finishing = true;
		queue.add(new Entry(K_FINISH, meta == null ? head : meta, done, null));
	}

	public int ticks() {
		return ticks;
	}

	public long packets() {
		return packets;
	}

	/** Compressed bytes written so far. */
	public long bytes() {
		return bytes;
	}

	public String error() {
		return error;
	}

	// ---- writer thread ----

	@Override
	public void run() {
		while (true) {
			Entry e;
			try {
				e = queue.take();
			} catch (InterruptedException ex) {
				return;
			}
			try {
				if (handle(e)) {
					return;
				}
			} catch (Throwable t) {
				if (!failed) {
					failed = true;
					error = t.toString();
					org.slf4j.LoggerFactory.getLogger("Velo Rewind").warn("Rewind writer failed", t);
				}
				if (e.kind == K_FINISH) {
					@SuppressWarnings("unchecked")
					Consumer<Path> done = (Consumer<Path>) e.protocol;
					done.accept(null);
					return;
				}
			}
		}
	}

	private boolean handle(Entry e) throws IOException {
		switch (e.kind) {
			case K_CONFIG -> {
				if (config != null) {
					encoder.encode(e.packet, e.protocol, b -> frame(config, F_PACKET, b));
				}
			}
			case K_NEW_CONFIG -> {
				closeSegment();
				if (config != null) {
					config.close();
				}
				configIndex++;
				config = open(dir.resolve("config-" + configIndex + ".bin"));
			}
			case K_THUMB -> Files.write(dir.resolve((String) e.protocol), (byte[]) e.packet);
			case K_HEAD -> Files.writeString(dir.resolve("head.json"), (String) e.packet, StandardCharsets.UTF_8);
			case K_PACKET -> {
				if (segment != null) {
					encoder.encode(e.packet, e.protocol, b -> {
						frame(segment, F_PACKET, b);
						packets++;
					});
				}
			}
			case K_TICK -> {
				if (segment != null) {
					frame(segment, F_TICK, null);
					segmentTicks++;
					ticks++;
					if (++sinceCheckpoint >= CHECKPOINT_TICKS) {
						sinceCheckpoint = 0;
						segment.flush(); // a sync flush: everything so far is readable from the file
					}
				}
			}
			case K_SNAPSHOT_END -> {
				if (segment != null) {
					frame(segment, F_SNAPSHOT_END, null);
				}
			}
			case K_SEGMENT -> {
				closeSegment();
				if (config != null) {
					config.close();
					config = null;
				}
				segmentIndex++;
				segmentTicks = 0;
				segment = open(dir.resolve("seg-" + segmentIndex + ".bin"));
				Files.writeString(dir.resolve("segments.log"), "seg-" + segmentIndex + " " + configIndex + "\n", StandardCharsets.UTF_8,
						java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			}
			case K_END_SEGMENT -> closeSegment();
			case K_AUDIO -> {
				String track = (String) e.protocol;
				DataOutputStream out = tracks.get(track);
				if (out == null) {
					out = open(dir.resolve("audio-" + track.replaceAll("[^a-zA-Z0-9-]", "_") + ".bin"));
					tracks.put(track, out);
				}
				short[] pcm = (short[]) e.packet;
				float[] f = (float[]) e.extra;
				java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(4 + 1 + 4 + 12 + pcm.length * 2);
				b.putInt(ticks).put((byte) f[0]).putInt((int) f[1]).putFloat(f[2]).putFloat(f[3]).putFloat(f[4]);
				for (short s : pcm) {
					b.putShort(s);
				}
				frame(out, F_AUDIO, b.array());
			}
			case K_FINISH -> {
				closeSegment();
				if (config != null) {
					config.close();
					config = null;
				}
				for (DataOutputStream out : tracks.values()) {
					out.close();
				}
				tracks.clear();
				String meta = "{\"format\": " + FORMAT + ", \"ticks\": " + ticks + ", \"packets\": " + packets + ", \"segments\": " + segmentInfo
						+ ", " + e.packet + "}";
				Files.writeString(dir.resolve("meta.json"), meta, StandardCharsets.UTF_8);
				releaseLock();
				zip(dir, target);
				try {
					Runtime.getRuntime().removeShutdownHook(shutdownHook);
				} catch (IllegalStateException ignored) {
					// already shutting down
				}
				@SuppressWarnings("unchecked")
				Consumer<Path> done = (Consumer<Path>) e.protocol;
				done.accept(target);
				return true;
			}
			default -> {
			}
		}
		return false;
	}

	private void closeSegment() throws IOException {
		if (segment != null) {
			segment.close();
			segment = null;
			segmentLengths.add(segmentTicks);
			segmentInfo.add("{\"file\": \"seg-" + segmentIndex + ".bin\", \"config\": " + configIndex + ", \"ticks\": " + segmentTicks + "}");
		}
	}

	private DataOutputStream open(Path file) throws IOException {
		OutputStream raw = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16) {
			@Override
			public synchronized void write(byte[] b, int off, int len) throws IOException {
				super.write(b, off, len);
				bytes += len;
			}

			@Override
			public synchronized void write(int b) throws IOException {
				super.write(b);
				bytes++;
			}
		};
		// syncFlush: flush() pushes everything written so far out as complete deflate blocks (the checkpoints)
		return new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(raw, new Deflater(Deflater.BEST_SPEED), 1 << 16, true), 1 << 16));
	}

	private static void frame(DataOutputStream out, int type, byte[] body) {
		try {
			out.writeByte(type);
			if (type == F_TICK || type == F_SNAPSHOT_END) {
				return;
			}
			int len = body.length;
			while ((len & ~0x7F) != 0) {
				out.writeByte((len & 0x7F) | 0x80);
				len >>>= 7;
			}
			out.writeByte(len);
			out.write(body);
		} catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

	private void releaseLock() {
		try {
			if (lock != null) {
				lock.release();
			}
			lockChannel.close();
		} catch (IOException ignored) {
		}
	}

	// The temp folder -> target (entries stored: the streams are compressed already), then the folder goes.
	private static void zip(Path dir, Path target) throws IOException {
		Path part = target.resolveSibling(target.getFileName() + ".part");
		try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(part), 1 << 16));
				Stream<Path> files = Files.list(dir)) {
			zip.setLevel(Deflater.NO_COMPRESSION);
			for (Path f : files.sorted().toList()) {
				String n = f.getFileName().toString();
				if (n.equals(".lock") || n.equals("head.json") || n.equals("segments.log")) {
					continue;
				}
				zip.putNextEntry(new ZipEntry(n));
				Files.copy(f, zip);
				zip.closeEntry();
			}
		}
		Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	// ---- crash recovery ----

	/**
	 * Recordings a crash (or a killed game) left unfinished in {@code folder}: each {@code .<name>.rewind-tmp/} that no
	 * running recorder holds becomes {@code <name> (recovered).vrewind} - everything up to its last checkpoint (5 s at most
	 * lost). Returns the recovered files.
	 */
	public static List<Path> recover(Path folder) {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(folder)) {
			return out;
		}
		List<Path> dirs;
		try (Stream<Path> s = Files.list(folder)) {
			dirs = s.filter(p -> Files.isDirectory(p) && p.getFileName().toString().endsWith(".rewind-tmp")).toList();
		} catch (IOException e) {
			return out;
		}
		for (Path dir : dirs) {
			try {
				Path recovered = recoverOne(folder, dir);
				if (recovered != null) {
					out.add(recovered);
				}
			} catch (Exception e) {
				org.slf4j.LoggerFactory.getLogger("Velo Rewind").warn("Velo Rewind couldn't recover {}", dir.getFileName(), e);
			}
		}
		return out;
	}

	private static Path recoverOne(Path folder, Path dir) throws IOException {
		try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dir.resolve(".lock"), java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.WRITE)) {
			java.nio.channels.FileLock l = ch.tryLock();
			if (l == null) {
				return null; // still recording
			}
			l.release();
		} catch (java.nio.channels.OverlappingFileLockException e) {
			return null; // this game is recording into it
		}
		// which config each segment used (older recordings without the log: in order, config 0)
		java.util.Map<Integer, Integer> configOf = new java.util.HashMap<>();
		Path log = dir.resolve("segments.log");
		if (Files.exists(log)) {
			for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
				String[] parts = line.trim().split(" ");
				if (parts.length == 2 && parts[0].startsWith("seg-")) {
					configOf.put(Integer.parseInt(parts[0].substring(4)), Integer.parseInt(parts[1]));
				}
			}
		}
		List<String> segs = new ArrayList<>();
		int total = 0;
		for (int i = 0; Files.exists(dir.resolve("seg-" + i + ".bin")); i++) {
			int segTicks = 0;
			boolean live = false;
			try (RewindReader.Frames f = new RewindReader.Frames(Files.newInputStream(dir.resolve("seg-" + i + ".bin")))) {
				for (RewindReader.Frame fr; (fr = f.next()) != null; ) {
					if (fr.type() == F_SNAPSHOT_END) {
						live = true;
					} else if (fr.type() == F_TICK) {
						segTicks++;
					}
				}
			} catch (IOException ignored) {
				// cut off mid-stream: everything before counts
			}
			if (!live || segTicks == 0) {
				break; // no usable snapshot: this and later segments can't be played
			}
			segs.add("{\"file\": \"seg-" + i + ".bin\", \"config\": " + configOf.getOrDefault(i, 0) + ", \"ticks\": " + segTicks + "}");
			total += segTicks;
		}
		String name = dir.getFileName().toString().replaceFirst("^\\.", "").replaceFirst("\\.rewind-tmp$", "");
		if (segs.isEmpty()) {
			// nothing playable (crashed in the first seconds): just clean up
			try (Stream<Path> walk = Files.walk(dir)) {
				for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(p);
				}
			}
			return null;
		}
		// drop the segment files that couldn't be used, so the reader doesn't count them
		for (int i = segs.size(); Files.exists(dir.resolve("seg-" + i + ".bin")); i++) {
			Files.delete(dir.resolve("seg-" + i + ".bin"));
		}
		String head = Files.exists(dir.resolve("head.json")) ? Files.readString(dir.resolve("head.json"), StandardCharsets.UTF_8) : "";
		String meta = "{\"format\": " + FORMAT + ", \"ticks\": " + total + ", \"recovered\": true, \"segments\": " + segs
				+ (head.isBlank() ? "" : ", " + head) + "}";
		Files.writeString(dir.resolve("meta.json"), meta, StandardCharsets.UTF_8);
		Path target = folder.resolve(name + " (recovered).vrewind");
		for (int n = 2; Files.exists(target); n++) {
			target = folder.resolve(name + " (recovered " + n + ").vrewind");
		}
		zip(dir, target);
		org.slf4j.LoggerFactory.getLogger("Velo Rewind").info("Velo Rewind recovered an unfinished recording: {} ({} ticks)", target.getFileName(), total);
		return target;
	}
}
