package net.veloclient.velo.client.devtools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Dev-only ({@code -Dvelo.benchMove=<blocks per second>}, with {@code -Dvelo.renderProfile=true}): FPS while moving,
 * not just standing. Three seconds after joining it flies the player on a circle (radius 150 blocks) at that speed, so
 * chunks keep leaving and entering view (meshing, uploads, culling changes), and logs average FPS plus 1% / 0.1% lows
 * and the worst frame every 10 seconds, and a total at the end. The same path every run - comparable between builds.
 */
public final class MoveBench {

	private static final double RADIUS = 150;
	private static final double SPEED = parseSpeed();
	private static int ticks;
	private static double startX;
	private static double startY;
	private static double startZ;
	private static double angle;
	private static volatile boolean moving;
	/** Frame stats without the flight (FarmBench). */
	private static volatile boolean recording;

	private static long lastFrame;
	private static long[] window = new long[200_000];
	private static int windowCount;
	private static long windowStart;
	private static long[] total = new long[2_000_000];
	private static int totalCount;

	private MoveBench() {
	}

	private static double parseSpeed() {
		String s = System.getProperty("velo.benchMove");
		if (s == null) {
			return 0;
		}
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return 20;
		}
	}

	/** Starts the frame statistics without moving the player. */
	public static void recordOnly() {
		windowStart = System.nanoTime();
		recording = true;
	}

	public static boolean active() {
		return SPEED > 0;
	}

	/** {@code -Dvelo.benchRecord=true}: frame stats + SlowFrame JFR events while standing (profiles of hitches). */
	private static final boolean RECORD_STATIC = Boolean.getBoolean("velo.benchRecord");

	public static void register() {
		if (RECORD_STATIC && !active()) {
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (client.player != null && ++ticks == 60) {
					recordOnly();
				}
			});
			return;
		}
		if (!active()) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			//? if >=26.2 {
			/*var player = client.player;
			if (player == null) {
				return;
			}
			ticks++;
			if (ticks == 60) {
				startX = player.getX();
				startY = player.getY() + 6;
				startZ = player.getZ();
				angle = 0;
				moving = true;
				windowStart = System.nanoTime();
				net.veloclient.velo.VeloClient.LOGGER.info("Velo move bench: flying a {}-block circle at {} blocks/s", (int) RADIUS, SPEED);
			}
			if (!moving) {
				return;
			}
			angle += SPEED / 20.0 / RADIUS;
			double cx = startX - RADIUS;
			double x = cx + Math.cos(angle) * RADIUS;
			double z = startZ + Math.sin(angle) * RADIUS;
			player.getAbilities().flying = true;
			player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
			player.setPos(x, startY, z);
			// face along the circle (tangent), slightly down so terrain fills the view
			float yaw = (float) Math.toDegrees(angle);
			player.setYRot(yaw);
			player.setYHeadRot(yaw);
			player.setXRot(15f);
			*///?}
		});
	}

	/** JFR marker for frames over 2 ms while moving: profiles can then look at just the slow frames. */
	@jdk.jfr.Name("velo.SlowFrame")
	@jdk.jfr.Label("Velo slow frame")
	static final class SlowFrame extends jdk.jfr.Event {
		@jdk.jfr.Label("Frame ms")
		float ms;
	}

	private static SlowFrame pending;

	/** Called at the start of every frame by RenderProfiler. */
	public static void frameStart() {
		if (moving || recording) {
			pending = new SlowFrame();
			pending.begin();
		}
	}

	/** Called at the end of every frame by RenderProfiler. */
	public static void frame(long now) {
		SlowFrame event = pending;
		pending = null;
		if (event != null) {
			event.end();
			if (event.shouldCommit() && lastFrame != 0 && now - lastFrame > 2_000_000L) {
				event.ms = (now - lastFrame) / 1e6f;
				event.commit();
			}
		}
		if (!moving && !recording) {
			lastFrame = now;
			return;
		}
		if (lastFrame != 0) {
			long dt = now - lastFrame;
			if (windowCount < window.length) {
				window[windowCount++] = dt;
			}
			if (totalCount < total.length) {
				total[totalCount++] = dt;
			}
		}
		lastFrame = now;
		if (now - windowStart >= 10_000_000_000L) {
			net.veloclient.velo.VeloClient.LOGGER.info("Velo move bench window: " + stats(window, windowCount));
			windowCount = 0;
			windowStart = now;
		}
	}

	/** Logs the run's total; called when the profile run exits. */
	public static void finish() {
		if ((moving || recording) && totalCount > 0) {
			net.veloclient.velo.VeloClient.LOGGER.info("Velo move bench TOTAL: " + stats(total, totalCount));
		}
	}

	private static String stats(long[] values, int count) {
		if (count == 0) {
			return "no frames";
		}
		long[] sorted = java.util.Arrays.copyOf(values, count);
		java.util.Arrays.sort(sorted);
		long sum = 0;
		for (long v : sorted) {
			sum += v;
		}
		double avgFps = count * 1e9 / sum;
		// 1% low = average FPS over the slowest 1% of frames (the common definition)
		return String.format(java.util.Locale.ROOT, "avg %.0f FPS | 1%% low %.0f | 0.1%% low %.0f | worst frame %.1f ms | %d frames",
				avgFps, lowFps(sorted, 0.01), lowFps(sorted, 0.001), sorted[count - 1] / 1e6, count);
	}

	private static double lowFps(long[] sorted, double fraction) {
		int n = Math.max(1, (int) Math.round(sorted.length * fraction));
		long sum = 0;
		for (int i = sorted.length - n; i < sorted.length; i++) {
			sum += sorted[i];
		}
		return n * 1e9 / sum;
	}
}
