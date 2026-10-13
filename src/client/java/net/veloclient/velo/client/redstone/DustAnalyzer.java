package net.veloclient.velo.client.redstone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flags redstone dust that's out of signal range: a dust line can carry a signal 15 blocks, the
 * 16th dust after a power source gets nothing. Scans the dust around you (in small slices, so
 * it's cheap), follows each line from the power sources feeding it, and marks every dust 16+
 * blocks away in red with a label - in Redstone Mode, or always (setting "Dust Length Warnings").
 */
public final class DustAnalyzer {

	/** A warning label: position and text. */
	public record Label(double x, double y, double z, String text) {
	}

	private static final int RADIUS = 20;
	private static final int HEIGHT = 8;
	private static final int SLICES = 4;
	private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};

	private static volatile List<int[]> flagged = List.of();
	private static volatile List<Label> labels = List.of();
	private static final Set<Long> SCAN = new HashSet<>();
	private static int slice;
	private static int[] center;
	private static int ticks;

	private DustAnalyzer() {
	}

	public static List<int[]> flagged() {
		return flagged;
	}

	public static List<Label> labels() {
		return labels;
	}

	public static boolean visible() {
		String mode = RedstoneModeModule.dustWarnings();
		return "Always".equals(mode) || "Redstone Mode".equals(mode) && RedstoneModeModule.active();
	}

	public static void tick() {
		if (!visible()) {
			if (!flagged.isEmpty()) {
				flagged = List.of();
				labels = List.of();
			}
			return;
		}
		if (++ticks % 3 != 0) {
			return;
		}
		Object level = RmWorld.clientLevel();
		int[] player = RmWorld.playerBlock();
		if (level == null || player == null) {
			return;
		}
		if (slice == 0) {
			center = player;
			SCAN.clear();
		}
		// One slice of x columns per step.
		int width = (RADIUS * 2 + 1 + SLICES - 1) / SLICES;
		int x0 = center[0] - RADIUS + slice * width;
		int x1 = Math.min(center[0] + RADIUS, x0 + width - 1);
		for (int x = x0; x <= x1; x++) {
			for (int z = center[2] - RADIUS; z <= center[2] + RADIUS; z++) {
				for (int y = center[1] - HEIGHT; y <= center[1] + HEIGHT; y++) {
					if (RmWorld.id(RmWorld.state(level, x, y, z)).equals("minecraft:redstone_wire")) {
						SCAN.add(Wiring.key(x, y, z));
					}
				}
			}
		}
		if (++slice < SLICES) {
			return;
		}
		slice = 0;
		analyze(level, new HashSet<>(SCAN));
	}

	private static boolean isSource(String id) {
		return id.contains("redstone_torch") || id.contains("redstone_wall_torch") || id.endsWith("repeater") || id.endsWith("comparator")
				|| id.endsWith("observer") || id.endsWith("redstone_block") || id.endsWith("lever") || id.endsWith("_button")
				|| id.endsWith("daylight_detector") || id.endsWith("target") || id.contains("pressure_plate") || id.endsWith("tripwire_hook")
				|| id.endsWith("sculk_sensor") || id.endsWith("lectern") || id.endsWith("trapped_chest") || id.endsWith("detector_rail");
	}

	/** Dust neighbours: flat on four sides, and one up / down the way dust climbs stairs. */
	private static List<Long> neighbours(Set<Long> dust, int[] p) {
		List<Long> out = new ArrayList<>(4);
		for (int[] s : SIDES) {
			for (int dy = -1; dy <= 1; dy++) {
				long k = Wiring.key(p[0] + s[0], p[1] + dy, p[2] + s[2]);
				if (dust.contains(k)) {
					out.add(k);
				}
			}
		}
		return out;
	}

	private static void analyze(Object level, Set<Long> dust) {
		Map<Long, Integer> distance = new HashMap<>();
		ArrayDeque<Long> queue = new ArrayDeque<>();
		// Every dust touching a power source (or a block a source powers - one block in) starts at distance 0.
		for (long k : dust) {
			int[] p = ClockTracker.decode(k);
			boolean fed = false;
			for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}}) {
				if (isSource(RmWorld.id(RmWorld.state(level, p[0] + d[0], p[1] + d[1], p[2] + d[2])))) {
					fed = true;
					break;
				}
			}
			if (fed) {
				distance.put(k, 0);
				queue.add(k);
			}
		}
		while (!queue.isEmpty()) {
			long k = queue.poll();
			int dist = distance.get(k);
			for (long n : neighbours(dust, ClockTracker.decode(k))) {
				if (!distance.containsKey(n)) {
					distance.put(n, dist + 1);
					queue.add(n);
				}
			}
		}
		List<int[]> marks = new ArrayList<>();
		List<Label> found = new ArrayList<>();
		Set<Long> labelled = new HashSet<>();
		for (var e : distance.entrySet()) {
			if (e.getValue() < 15) {
				continue;
			}
			int[] p = ClockTracker.decode(e.getKey());
			marks.add(p);
			if (e.getValue() == 15 && labelled.add(e.getKey()) && found.size() < 20) {
				found.add(new Label(p[0] + 0.5, p[1] + 0.6, p[2] + 0.5, "16th dust - no signal past here, add a repeater"));
			}
		}
		flagged = marks.size() > 600 ? marks.subList(0, 600) : marks;
		labels = found;
	}
}
