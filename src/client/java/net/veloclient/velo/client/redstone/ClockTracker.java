package net.veloclient.velo.client.redstone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds redstone clocks by watching blocks switch on and off: every repeater, comparator,
 * observer, torch, dust, lamp or piston that keeps toggling with a steady period is a clock part.
 * Nearby parts with the same period are grouped and labelled in Redstone Mode ("Clock 4 gt -
 * 5 Hz"). Purely client-side: it just watches the block updates the game receives anyway.
 */
public final class ClockTracker {

	/** A detected clock: where to put the label and its period in game ticks. */
	public record Clock(double x, double y, double z, int periodTicks, int parts) {
	}

	private static final class Track {
		boolean on;
		final long[] rises = new long[6];
		int count;
		long lastChange;
	}

	public static volatile boolean enabled;
	private static final Map<Long, Track> TRACKS = new HashMap<>();
	private static volatile List<Clock> clocks = List.of();
	private static int ticks;

	private ClockTracker() {
	}

	public static List<Clock> clocks() {
		return clocks;
	}

	private static boolean isComponent(String id) {
		return id.endsWith("repeater") || id.endsWith("comparator") || id.endsWith("observer") || id.contains("redstone_torch")
				|| id.contains("redstone_wall_torch") || id.endsWith("redstone_wire") || id.endsWith("redstone_lamp") || id.endsWith("piston");
	}

	private static boolean isOn(Object state) {
		String powered = RmWorld.prop(state, "powered");
		if (powered != null) {
			return powered.equals("true");
		}
		String lit = RmWorld.prop(state, "lit");
		if (lit != null) {
			return lit.equals("true");
		}
		String extended = RmWorld.prop(state, "extended");
		if (extended != null) {
			return extended.equals("true");
		}
		String power = RmWorld.prop(state, "power");
		return power != null && !power.equals("0");
	}

	/** A client block update (from the mixin). */
	public static void observe(Object level, int x, int y, int z, Object state) {
		String id = RmWorld.id(state);
		if (!isComponent(id)) {
			return;
		}
		boolean on = isOn(state);
		long time = RmWorld.gameTime(level);
		synchronized (TRACKS) {
			Track track = TRACKS.computeIfAbsent(Wiring.key(x, y, z), k -> new Track());
			if (track.on == on) {
				return;
			}
			track.on = on;
			track.lastChange = time;
			if (on) {
				System.arraycopy(track.rises, 1, track.rises, 0, track.rises.length - 1);
				track.rises[track.rises.length - 1] = time;
				track.count = Math.min(track.rises.length, track.count + 1);
			}
			if (TRACKS.size() > 4000) {
				TRACKS.clear();
			}
		}
	}

	/** Client tick: refresh the clock list a few times a second. */
	public static void tick() {
		enabled = RedstoneModeModule.active() && RedstoneModeModule.clockTracking();
		if (!enabled) {
			if (!clocks.isEmpty()) {
				clocks = List.of();
			}
			return;
		}
		if (++ticks % 5 != 0) {
			return;
		}
		Object level = RmWorld.clientLevel();
		if (level == null) {
			return;
		}
		long now = RmWorld.gameTime(level);
		Map<Long, Integer> periodic = new HashMap<>();
		synchronized (TRACKS) {
			TRACKS.entrySet().removeIf(e -> now - e.getValue().lastChange > 400);
			for (var e : TRACKS.entrySet()) {
				Track t = e.getValue();
				if (t.count < 3) {
					continue;
				}
				long[] r = t.rises;
				int n = r.length;
				long p1 = r[n - 1] - r[n - 2];
				long p2 = r[n - 2] - r[n - 3];
				if (p1 <= 0 || Math.abs(p1 - p2) > 1 || now - r[n - 1] > p1 * 3 + 20) {
					continue;
				}
				periodic.put(e.getKey(), (int) Math.max(p1, p2));
			}
		}
		// Group parts within 4 blocks that share a period.
		List<Clock> found = new ArrayList<>();
		List<Long> keys = new ArrayList<>(periodic.keySet());
		boolean[] used = new boolean[keys.size()];
		for (int i = 0; i < keys.size() && found.size() < 40; i++) {
			if (used[i]) {
				continue;
			}
			int period = periodic.get(keys.get(i));
			int[] a = decode(keys.get(i));
			double sx = a[0];
			double sy = a[1];
			double sz = a[2];
			int parts = 1;
			used[i] = true;
			for (int j = i + 1; j < keys.size(); j++) {
				if (used[j] || Math.abs(periodic.get(keys.get(j)) - period) > 1) {
					continue;
				}
				int[] b = decode(keys.get(j));
				if (Math.abs(a[0] - b[0]) <= 4 && Math.abs(a[1] - b[1]) <= 4 && Math.abs(a[2] - b[2]) <= 4) {
					used[j] = true;
					sx += b[0];
					sy += b[1];
					sz += b[2];
					parts++;
				}
			}
			found.add(new Clock(sx / parts + 0.5, sy / parts + 1.3, sz / parts + 0.5, period, parts));
		}
		clocks = found;
	}

	static int[] decode(long k) {
		return new int[] {(int) ((k >>> 38) - 30_000_000), (int) (((k >>> 26) & 0xFFF) - 2048), (int) ((k & 0x3FFFFFF) - 30_000_000)};
	}
}
