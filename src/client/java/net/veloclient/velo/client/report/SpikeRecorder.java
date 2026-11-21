package net.veloclient.velo.client.report;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.config.VeloPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Record lag spikes" in the bug report: 60 seconds of Java Flight Recorder plus the time of every frame, then an
 * analysis on this PC - which frames were slow and what the render thread was doing in exactly those frames (its
 * stacks, garbage collection, waiting on a lock, reading files). Only that short text goes into the report; the raw
 * recording stays in .velo-client/spike-reports for when the team asks for it.
 */
public final class SpikeRecorder {

	/** A finished recording, attached to the next bug report. */
	public record Result(String summary, int spikes, Path file) {
	}

	private static final int MAX_FRAMES = 400_000;
	private static volatile boolean recording;
	private static volatile boolean analysing;
	private static long endsAtMillis;
	private static long baseNano;
	private static long baseEpochNanos;
	private static long lastFrameNano;
	private static float[] frameMs = new float[0];
	private static long[] frameEnd = new long[0];
	private static int frames;
	private static Recording jfr;
	private static volatile Result last;

	private SpikeRecorder() {
	}

	public static boolean busy() {
		return recording || analysing;
	}

	public static Result last() {
		return last;
	}

	public static void clear() {
		last = null;
	}

	/** Seconds left while recording, -1 when not recording. */
	public static int secondsLeft() {
		return recording ? (int) Math.max(0, (endsAtMillis - System.currentTimeMillis() + 999) / 1000) : -1;
	}

	/** Starts a recording of {@code seconds} (render thread). */
	public static void start(int seconds) {
		if (busy()) {
			return;
		}
		frames = 0;
		frameMs = new float[60_000];
		frameEnd = new long[60_000];
		baseNano = System.nanoTime();
		Instant now = Instant.now();
		baseEpochNanos = now.getEpochSecond() * 1_000_000_000L + now.getNano();
		lastFrameNano = 0;
		try {
			Recording r = new Recording(Configuration.getConfiguration("profile"));
			r.setName("Velo lag spikes");
			r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(5));
			r.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10));
			r.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(4)).withStackTrace();
			r.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(4)).withStackTrace();
			r.enable("jdk.FileRead").withThreshold(Duration.ofMillis(4)).withStackTrace();
			r.enable("jdk.FileWrite").withThreshold(Duration.ofMillis(4)).withStackTrace();
			r.enable("jdk.SocketRead").withThreshold(Duration.ofMillis(4)).withStackTrace();
			r.enable("jdk.ThreadSleep").withThreshold(Duration.ofMillis(4)).withStackTrace();
			r.setToDisk(true);
			r.start();
			jfr = r;
		} catch (Throwable t) {
			// a Java without Flight Recorder: frame times only
			VeloClient.LOGGER.warn("Velo: Flight Recorder isn't available - recording frame times only", t);
			jfr = null;
		}
		endsAtMillis = System.currentTimeMillis() + seconds * 1000L;
		recording = true;
		toast("Recording lag spikes", "Keep playing like when it lags - " + seconds + " seconds. Velo tells you when it's done.");
	}

	/** Every rendered frame (end of GameRenderer#render). */
	/** Dev: -Dvelo.frameLog=true logs every frame over 30 ms while recording (with the open screen). */
	private static final boolean FRAME_LOG = Boolean.getBoolean("velo.frameLog");
	private static final float FRAME_LOG_MS = Float.parseFloat(System.getProperty("velo.frameLogMs", "30"));

	public static void frame() {
		FreezeWatchdog.beat();
		if (!recording) {
			return;
		}
		long now = System.nanoTime();
		if (lastFrameNano != 0 && frames < MAX_FRAMES) {
			if (frames == frameMs.length) {
				frameMs = Arrays.copyOf(frameMs, Math.min(MAX_FRAMES, frames * 2));
				frameEnd = Arrays.copyOf(frameEnd, frameMs.length);
			}
			frameMs[frames] = (now - lastFrameNano) / 1_000_000f;
			if (FRAME_LOG && frameMs[frames] > FRAME_LOG_MS) {
				VeloClient.LOGGER.info("spike-probe frame {} ms at {} s, screen {}", frameMs[frames], (now - baseNano) / 1e9,
						net.veloclient.velo.client.util.ClientCompat.currentScreen() == null ? "none"
								: net.veloclient.velo.client.util.ClientCompat.currentScreen().getClass().getSimpleName());
			}
			frameEnd[frames] = baseEpochNanos + (now - baseNano);
			frames++;
		}
		lastFrameNano = now;
		if (System.currentTimeMillis() >= endsAtMillis) {
			finish();
		}
	}

	private static void finish() {
		recording = false;
		analysing = true;
		Recording r = jfr;
		jfr = null;
		float[] ms = Arrays.copyOf(frameMs, frames);
		long[] ends = Arrays.copyOf(frameEnd, frames);
		frameMs = new float[0];
		frameEnd = new long[0];
		toast("Lag spikes recorded", "Looking through the recording...");
		Thread.ofPlatform().daemon().name("velo-spike-analysis").start(() -> {
			Path file = null;
			String summary;
			int spikes = 0;
			try {
				Path dir = VeloPaths.root().resolve("spike-reports");
				Files.createDirectories(dir);
				cleanup(dir);
				if (r != null) {
					file = dir.resolve("spikes-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".jfr");
					r.stop();
					r.dump(file);
				}
				Analysis a = analyse(ms, ends, file);
				summary = a.text;
				spikes = a.spikes;
			} catch (Throwable t) {
				VeloClient.LOGGER.warn("Velo: lag spike analysis failed", t);
				summary = "Lag spike analysis failed: " + t;
			} finally {
				if (r != null) {
					try {
						r.close();
					} catch (Throwable ignored) {
						// already closed
					}
				}
			}
			Result result = new Result(summary, spikes, file);
			MinecraftClient.getInstance().execute(() -> {
				last = result;
				analysing = false;
				toast("Lag spike report ready", result.spikes() == 0 ? "No lag spikes in these 60 seconds - open Report a bug to send it anyway."
						: result.spikes() + " lag spikes found - open Report a bug (pause menu) and send it.");
			});
		});
	}

	/** Keeps the newest 5 recordings. */
	private static void cleanup(Path dir) {
		try (var files = Files.list(dir)) {
			List<Path> all = files.filter(p -> p.getFileName().toString().endsWith(".jfr")).sorted(Comparator.reverseOrder()).toList();
			for (int i = 4; i < all.size(); i++) {
				Files.deleteIfExists(all.get(i));
			}
		} catch (Exception ignored) {
			// not important
		}
	}

	// ---------------------------------------------------------------- analysis

	private record Analysis(String text, int spikes) {
	}

	private record Spike(long fromNanos, long toNanos, float ms, Map<String, Integer> stacks, List<String> causes) {
	}

	static Analysis analyse(float[] ms, long[] ends, Path file) throws Exception {
		StringBuilder out = new StringBuilder(32_000);
		out.append("==== Velo lag spike report (").append(Math.round(sum(ms) / 1000)).append(" s, ").append(ms.length).append(" frames) ====\n");
		if (ms.length < 10) {
			out.append("Too few frames were drawn (game minimised or in a menu without rendering).\n");
			return new Analysis(out.toString(), 0);
		}
		float[] sorted = ms.clone();
		Arrays.sort(sorted);
		float median = sorted[sorted.length / 2];
		float p99 = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.99))];
		float p999 = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.999))];
		float max = sorted[sorted.length - 1];
		float threshold = Math.max(33f, median * 3f);
		out.append(String.format(Locale.ROOT, "Average %.0f FPS, median frame %.2f ms, 1%% low %.0f FPS (%.1f ms), 0.1%% %.1f ms, worst %.1f ms%n",
				ms.length / (sum(ms) / 1000.0), median, 1000 / p99, p99, p999, max));
		out.append(String.format(Locale.ROOT, "A spike = a frame over %.0f ms (3x the median, at least 33 ms)%n", threshold));
		List<Spike> spikes = new ArrayList<>();
		for (int i = 0; i < ms.length; i++) {
			if (ms[i] >= threshold) {
				spikes.add(new Spike(ends[i] - (long) (ms[i] * 1_000_000L), ends[i], ms[i], new HashMap<>(), new ArrayList<>()));
			}
		}
		out.append("Spikes: ").append(spikes.size()).append('\n');
		// when in the recording they happened (seconds from the start), to see a rhythm (e.g. every 10 s)
		if (!spikes.isEmpty()) {
			long t0 = ends[0] - (long) (ms[0] * 1_000_000L);
			StringBuilder when = new StringBuilder();
			for (Spike s : spikes.subList(0, Math.min(60, spikes.size()))) {
				when.append(String.format(Locale.ROOT, "%.1fs(%.0fms) ", (s.fromNanos - t0) / 1e9, s.ms));
			}
			out.append("When: ").append(when.toString().strip()).append('\n');
		}
		if (file == null) {
			out.append("(No Flight Recorder on this Java - frame times only.)\n");
			return new Analysis(out.toString(), spikes.size());
		}
		Map<String, Integer> allStacks = new HashMap<>();
		Map<String, Long> allocations = new HashMap<>();
		long gcCount = 0;
		double gcTotal = 0;
		double gcMax = 0;
		String renderThread = null;
		List<RecordedEvent> events = new ArrayList<>();
		try (RecordingFile rf = new RecordingFile(file)) {
			while (rf.hasMoreEvents()) {
				RecordedEvent e = rf.readEvent();
				String type = e.getEventType().getName();
				switch (type) {
					case "jdk.GarbageCollection" -> {
						double pause = e.getDuration("longestPause").toNanos() / 1e6;
						gcCount++;
						gcTotal += e.getDuration("sumOfPauses").toNanos() / 1e6;
						gcMax = Math.max(gcMax, pause);
						events.add(e);
					}
					case "jdk.ObjectAllocationSample" -> {
						String frame = topFrame(e.getStackTrace(), true);
						allocations.merge(frame, e.getLong("weight"), Long::sum);
					}
					case "jdk.ExecutionSample", "jdk.NativeMethodSample", "jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.FileRead", "jdk.FileWrite",
							"jdk.SocketRead", "jdk.ThreadSleep", "jdk.SafepointBegin" -> {
						RecordedThread t = thread(e);
						if (t != null && renderThread == null && "Render thread".equals(t.getJavaName())) {
							renderThread = t.getJavaName();
						}
						if (t == null || !"Render thread".equals(t.getJavaName())) {
							if (!type.equals("jdk.SafepointBegin")) {
								continue;
							}
						}
						events.add(e);
					}
					default -> {
						// not used
					}
				}
			}
		}
		for (RecordedEvent e : events) {
			long from = nanos(e.getStartTime());
			long to = nanos(e.getEndTime());
			String type = e.getEventType().getName();
			for (Spike s : spikes) {
				if (to < s.fromNanos - 2_000_000L || from > s.toNanos + 2_000_000L) {
					continue;
				}
				switch (type) {
					case "jdk.ExecutionSample", "jdk.NativeMethodSample" -> {
						String stack = stack(e.getStackTrace(), 10);
						s.stacks.merge(stack, 1, Integer::sum);
						allStacks.merge(stack, 1, Integer::sum);
					}
					case "jdk.GarbageCollection" -> s.causes.add(String.format(Locale.ROOT, "GC %s, pause %.1f ms", e.getString("name"),
							e.getDuration("longestPause").toNanos() / 1e6));
					case "jdk.SafepointBegin" -> {
						double d = (to - from) / 1e6;
						if (d >= 4) {
							s.causes.add(String.format(Locale.ROOT, "JVM safepoint %.1f ms", d));
						}
					}
					default -> s.causes.add(String.format(Locale.ROOT, "%s %.1f ms at %s", type.substring(4), (to - from) / 1e6,
							topFrame(e.getStackTrace(), false)));
				}
			}
		}
		out.append(String.format(Locale.ROOT, "Garbage collection: %d runs, %.0f ms paused in total, longest pause %.1f ms%n", gcCount, gcTotal, gcMax));
		out.append("Java: ").append(System.getProperty("java.vm.name")).append(' ').append(System.getProperty("java.version"))
				.append(", heap max ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MB\n");
		if (renderThread == null) {
			out.append("(The render thread wasn't seen in the samples.)\n");
		}
		out.append("\n---- What the render thread did during the spikes (all spikes together) ----\n");
		appendTop(out, allStacks, 12);
		out.append("\n---- The worst spikes ----\n");
		List<Spike> worst = new ArrayList<>(spikes);
		worst.sort((a, b) -> Float.compare(b.ms, a.ms));
		long t0 = ends[0] - (long) (ms[0] * 1_000_000L);
		for (Spike s : worst.subList(0, Math.min(12, worst.size()))) {
			out.append(String.format(Locale.ROOT, "%n# %.1f ms at %.1f s%n", s.ms, (s.fromNanos - t0) / 1e9));
			s.causes.stream().distinct().limit(6).forEach(c -> out.append("  ! ").append(c).append('\n'));
			appendTop(out, s.stacks, 3);
		}
		out.append("\n---- Biggest allocators (whole recording, causes GC) ----\n");
		allocations.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10)
				.forEach(en -> out.append(String.format(Locale.ROOT, "%8.1f MB  %s%n", en.getValue() / 1048576.0, en.getKey())));
		String text = out.length() > 120_000 ? out.substring(0, 120_000) + "\n[... cut ...]\n" : out.toString();
		return new Analysis(text, spikes.size());
	}

	private static void appendTop(StringBuilder out, Map<String, Integer> stacks, int count) {
		if (stacks.isEmpty()) {
			out.append("  (no samples - the time went to waiting for the GPU/driver or outside Java)\n");
			return;
		}
		int total = stacks.values().stream().mapToInt(Integer::intValue).sum();
		stacks.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(count).forEach(en -> {
			out.append(String.format(Locale.ROOT, "  %d samples (%d%%):%n", en.getValue(), Math.round(100.0 * en.getValue() / total)));
			for (String line : en.getKey().split("\n")) {
				out.append("      ").append(line).append('\n');
			}
		});
	}

	private static RecordedThread thread(RecordedEvent e) {
		if (e.hasField("sampledThread")) {
			return e.getThread("sampledThread");
		}
		return e.getThread();
	}

	private static long nanos(Instant t) {
		return t.getEpochSecond() * 1_000_000_000L + t.getNano();
	}

	private static String stack(RecordedStackTrace trace, int depth) {
		if (trace == null) {
			return "(no stack)";
		}
		StringBuilder b = new StringBuilder();
		int n = 0;
		for (RecordedFrame f : trace.getFrames()) {
			if (n++ >= depth) {
				break;
			}
			b.append(f.getMethod().getType().getName()).append('.').append(f.getMethod().getName());
			if (f.getLineNumber() > 0) {
				b.append(':').append(f.getLineNumber());
			}
			b.append('\n');
		}
		return b.toString().strip();
	}

	/** The first frame that isn't the JDK itself (where the game/mod code asked for it). */
	private static String topFrame(RecordedStackTrace trace, boolean skipJdk) {
		if (trace == null || trace.getFrames().isEmpty()) {
			return "(no stack)";
		}
		for (RecordedFrame f : trace.getFrames()) {
			String type = f.getMethod().getType().getName();
			if (!type.startsWith("java.") && !type.startsWith("jdk.") && !type.startsWith("sun.")) {
				return type + "." + f.getMethod().getName() + (f.getLineNumber() > 0 ? ":" + f.getLineNumber() : "");
			}
		}
		RecordedFrame f = trace.getFrames().get(0);
		return f.getMethod().getType().getName() + "." + f.getMethod().getName();
	}

	private static double sum(float[] values) {
		double s = 0;
		for (float v : values) {
			s += v;
		}
		return s;
	}

	private static void toast(String title, String body) {
		NotificationOverlay.show(new NotificationOverlay.Toast(null, null, title, body, 7000, null, null, null));
	}

	/** A small "recording" label at the top while it runs. */
	public static void renderHud(DrawContext context, int screenWidth) {
		int left = secondsLeft();
		if (left < 0 && !analysing) {
			return;
		}
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		String label = left >= 0 ? "Recording lag spikes  " + left + " s" : "Analysing lag spikes...";
		int w = textRenderer.getWidth(label) + 18;
		int x = (screenWidth - w) / 2;
		net.veloclient.velo.client.gui.widget.VeloDraw.fillRounded(context, x, 4, w, 14, 7, 0xC0101014);
		boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
		net.veloclient.velo.client.gui.widget.VeloDraw.fillCircle(context, x + 8f, 11f, 2.5f, blink || left < 0 ? 0xFFFF3B47 : 0x60FF3B47);
		context.drawTextWithShadow(textRenderer, label, x + 14, 7, 0xFFFFFFFF);
	}
}
