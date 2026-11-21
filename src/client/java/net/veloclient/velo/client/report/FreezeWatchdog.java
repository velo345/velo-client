package net.veloclient.velo.client.report;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.veloclient.velo.VeloClient;

import java.util.Map;

/**
 * Writes where every thread is stuck into the log when the game stops drawing and ticking for {@value #STALL_SECONDS}
 * seconds. A game that hangs leaves no error behind: Windows shows "not responding", the player closes it and the
 * report only says exit code -805306369 (0xCFFFFFFF) with a log that just stops (reports 2026-11-21, both while a
 * server resource pack loaded). With this, the report's log ends in the render thread's stack - whose code it was.
 * Once per freeze; off with -Dvelo.noFreezeWatchdog.
 */
public final class FreezeWatchdog {

	private static final int STALL_SECONDS = 10;
	/** Bumped every frame and tick - one increment, no clock read; the watchdog compares it once a second. */
	private static volatile int beats;
	private static Thread renderThread;

	private FreezeWatchdog() {
	}

	/** Every rendered frame. */
	public static void beat() {
		beats++;
	}

	public static void register() {
		if (Boolean.getBoolean("velo.noFreezeWatchdog")) {
			return;
		}
		renderThread = Thread.currentThread();
		ClientTickEvents.END_CLIENT_TICK.register(client -> beat());
		Thread t = new Thread(FreezeWatchdog::watch, "velo-freeze-watchdog");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	private static void watch() {
		int seen = 0;
		int stalled = 0;
		boolean dumped = false;
		while (true) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				return;
			}
			int now = beats;
			if (now == 0) {
				continue; // not started yet (startup has its own long frames)
			}
			if (now != seen) {
				seen = now;
				stalled = 0;
				dumped = false;
				continue;
			}
			stalled++;
			if (stalled >= STALL_SECONDS && !dumped) {
				dumped = true;
				try {
					dump(stalled);
				} catch (Throwable ignored) {
					// never take the game down from here
				}
			}
		}
	}

	private static void dump(int stalled) {
		StringBuilder b = new StringBuilder();
		b.append("Velo freeze watchdog: the game hasn't drawn a frame or ticked for ").append(stalled)
				.append(" s - where every thread is (render thread first):\n");
		Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
		if (renderThread != null && all.containsKey(renderThread)) {
			append(b, renderThread, all.get(renderThread), 80);
		}
		for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
			Thread t = e.getKey();
			if (t == renderThread || t == Thread.currentThread() || e.getValue().length == 0) {
				continue;
			}
			// idle pool workers say nothing - skip threads parked with nothing of interest on top
			StackTraceElement top = e.getValue()[0];
			boolean idle = t.getState() == Thread.State.WAITING || t.getState() == Thread.State.TIMED_WAITING;
			if (idle && (top.getClassName().startsWith("jdk.internal.misc.Unsafe") || top.getClassName().equals("java.lang.Object"))
					&& e.getValue().length > 2 && e.getValue()[2].getClassName().startsWith("java.util.concurrent")) {
				continue;
			}
			append(b, t, e.getValue(), 25);
		}
		VeloClient.LOGGER.warn(b.toString());
	}

	private static void append(StringBuilder b, Thread t, StackTraceElement[] stack, int max) {
		b.append("\"").append(t.getName()).append("\" ").append(t.getState()).append('\n');
		for (int i = 0; i < Math.min(max, stack.length); i++) {
			b.append("\tat ").append(stack[i]).append('\n');
		}
		if (stack.length > max) {
			b.append("\t... ").append(stack.length - max).append(" more\n");
		}
	}
}
