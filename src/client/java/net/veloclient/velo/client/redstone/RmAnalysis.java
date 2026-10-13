package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.util.ClientCompat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What Redstone Mode labels in the world: signal strength on dust and comparator outputs, repeater
 * delays, quasi-connectivity ("QC") and budded ("BUD") pistons / droppers / dispensers, item rate
 * meters (items per second through a block), clock speeds and dust range warnings. Labels are drawn
 * on screen at the block, nearest first, capped so a big build stays readable and cheap.
 */
public final class RmAnalysis {

	private record Label(double x, double y, double z, String text, int color, double distance) {
	}

	private static final int RADIUS = 12;
	private static final int MAX_LABELS = 140;
	private static volatile List<Label> labels = List.of();
	private static volatile Map<Long, Integer> comparators = Map.of();
	private static int ticks;

	/** Rate meters: block key -> recent (time, entity id, count) entries. */
	private static final Map<Long, ArrayDeque<long[]>> METERS = new HashMap<>();
	private static final Map<Long, Set<Integer>> METER_SEEN = new HashMap<>();

	private RmAnalysis() {
	}

	public static void addMeterAtLook() {
		int[] look = RmWorld.lookedBlock(64);
		if (look == null) {
			RmState.error("Look at the stream block to measure");
			return;
		}
		int[] d = RmWorld.faceOffset(look[3]);
		long k = Wiring.key(look[0] + d[0], look[1] + d[1], look[2] + d[2]);
		synchronized (METERS) {
			METERS.put(k, new ArrayDeque<>());
			METER_SEEN.put(k, new HashSet<>());
		}
		RmState.info("Rate meter added - counts items passing through that block");
	}

	public static void clearMeters() {
		synchronized (METERS) {
			METERS.clear();
			METER_SEEN.clear();
		}
	}

	/** Client tick. */
	public static void tick() {
		if (!RedstoneMode.active()) {
			return;
		}
		ticks++;
		Object level = RmWorld.clientLevel();
		if (level == null) {
			return;
		}
		RmSettings.Data s = RmSettings.get();
		if (s.rateMeters) {
			tickMeters(level);
		}
		if (ticks % 4 != 0) {
			return;
		}
		if (s.signalNumbers && ticks % 10 == 0 && RmWorld.singleplayer()) {
			queryComparators();
		}
		int[] p = RmWorld.playerBlock();
		if (p == null) {
			return;
		}
		List<Label> found = new ArrayList<>();
		for (int x = p[0] - RADIUS; x <= p[0] + RADIUS; x++) {
			for (int y = p[1] - 6; y <= p[1] + 6; y++) {
				for (int z = p[2] - RADIUS; z <= p[2] + RADIUS; z++) {
					Object state = RmWorld.state(level, x, y, z);
					if (RmWorld.isAir(state)) {
						continue;
					}
					String id = RmWorld.id(state);
					double dist = Math.sqrt((x - p[0]) * (x - p[0]) + (y - p[1]) * (y - p[1]) + (z - p[2]) * (z - p[2]));
					if (s.signalNumbers) {
						if (id.equals("minecraft:redstone_wire")) {
							String power = RmWorld.prop(state, "power");
							if (power != null) {
								int v = Integer.parseInt(power);
								found.add(new Label(x + 0.5, y + 0.15, z + 0.5, power, v == 0 ? 0xFF7A7A80 : signalColor(v), dist));
							}
						} else if (id.equals("minecraft:comparator")) {
							Integer out = comparators.get(Wiring.key(x, y, z));
							String mode = "subtract".equals(RmWorld.prop(state, "mode")) ? "-" : "";
							found.add(new Label(x + 0.5, y + 0.45, z + 0.5, mode + (out != null ? out : "?"), out != null && out > 0 ? signalColor(out)
									: 0xFF9A9AAE, dist));
						} else if (id.equals("minecraft:repeater")) {
							found.add(new Label(x + 0.5, y + 0.45, z + 0.5, RmWorld.prop(state, "delay") + "t", 0xFFFFB060, dist));
						}
					}
					if (s.qcMarkers && (id.equals("minecraft:piston") || id.equals("minecraft:sticky_piston") || id.equals("minecraft:dropper")
							|| id.equals("minecraft:dispenser"))) {
						boolean direct = RmWorld.hasNeighborSignal(level, x, y, z);
						boolean qc = RmWorld.hasNeighborSignal(level, x, y + 1, z);
						boolean active = id.contains("piston") ? "true".equals(RmWorld.prop(state, "extended"))
								: "true".equals(RmWorld.prop(state, "triggered"));
						if (!direct && qc) {
							found.add(new Label(x + 0.5, y + 1.05, z + 0.5, active || !id.contains("piston") ? "QC" : "QC (BUD)", 0xFFFFA040, dist));
						} else if (id.contains("piston") && active != (direct || qc)) {
							found.add(new Label(x + 0.5, y + 1.05, z + 0.5, "BUD", 0xFFFF6060, dist));
						}
					}
				}
			}
		}
		if (s.clockLabels) {
			for (ClockTracker.Clock clock : ClockTracker.clocks()) {
				double hz = 20.0 / Math.max(1, clock.periodTicks());
				found.add(new Label(clock.x(), clock.y(), clock.z(), String.format(Locale.ROOT, "clock %d gt · %.2f Hz", clock.periodTicks(), hz),
						0xFF7FD8FF, 0));
			}
		}
		if (s.rateMeters) {
			synchronized (METERS) {
				for (var e : METERS.entrySet()) {
					int[] b = ClockTracker.decode(e.getKey());
					long now = System.currentTimeMillis();
					long items = 0;
					for (long[] entry : e.getValue()) {
						if (now - entry[0] <= 5000) {
							items += entry[2];
						}
					}
					found.add(new Label(b[0] + 0.5, b[1] + 1.2, b[2] + 0.5, String.format(Locale.ROOT, "%.1f items/s", items / 5.0), 0xFF8FE3A8, 0));
				}
			}
		}
		for (DustAnalyzer.Label warning : DustAnalyzer.labels()) {
			found.add(new Label(warning.x(), warning.y() + 0.4, warning.z(), "no signal past here", 0xFFFF6060, 0));
		}
		found.sort((a, b) -> Double.compare(a.distance(), b.distance()));
		labels = found.size() > MAX_LABELS ? found.subList(0, MAX_LABELS) : found;
	}

	private static int signalColor(int power) {
		int red = 120 + power * 9;
		return 0xFF000000 | Math.min(255, red) << 16 | (40 + power * 4) << 8 | 40;
	}

	private static void queryComparators() {
		int[] p = RmWorld.playerBlock();
		if (p == null) {
			return;
		}
		RmWorld.onServer(level -> {
			Map<Long, Integer> out = new HashMap<>();
			for (int x = p[0] - RADIUS; x <= p[0] + RADIUS; x++) {
				for (int y = p[1] - 6; y <= p[1] + 6; y++) {
					for (int z = p[2] - RADIUS; z <= p[2] + RADIUS; z++) {
						int value = RmWorld.comparatorOutput(level, x, y, z);
						if (value >= 0) {
							out.put(Wiring.key(x, y, z), value);
						}
					}
				}
			}
			comparators = out;
		});
	}

	private static void tickMeters(Object level) {
		long now = System.currentTimeMillis();
		synchronized (METERS) {
			for (var e : METERS.entrySet()) {
				int[] b = ClockTracker.decode(e.getKey());
				Set<Integer> seen = METER_SEEN.get(e.getKey());
				Set<Integer> inside = new HashSet<>();
				for (int[] item : RmWorld.itemEntities(level, b[0], b[1], b[2], b[0] + 1, b[1] + 1, b[2] + 1)) {
					inside.add(item[0]);
					if (!seen.contains(item[0])) {
						e.getValue().addLast(new long[] {now, item[0], item[1]});
					}
				}
				seen.clear();
				seen.addAll(inside);
				while (!e.getValue().isEmpty() && now - e.getValue().peekFirst()[0] > 5000) {
					e.getValue().pollFirst();
				}
			}
		}
	}

	/** Outside Redstone Mode: only the dust range warnings (when set to "Always"). */
	public static void renderOutside(DrawContext context, int w, int h) {
		if (!DustAnalyzer.visible() || DustAnalyzer.labels().isEmpty()) {
			return;
		}
		List<Label> warnings = new ArrayList<>();
		for (DustAnalyzer.Label warning : DustAnalyzer.labels()) {
			warnings.add(new Label(warning.x(), warning.y() + 0.4, warning.z(), "no signal past here", 0xFFFF6060, 0));
		}
		draw(context, w, h, warnings);
	}

	/** HUD: all labels, projected onto the screen. */
	static void renderLabels(DrawContext context, int w, int h) {
		if (RedstoneMode.overlayHidden()) {
			return;
		}
		draw(context, w, h, labels);
	}

	private static void draw(DrawContext context, int w, int h, List<Label> list) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		for (Label label : list) {
			float[] view = ClientCompat.cameraView(label.x(), label.y(), label.z());
			float[] screen = ClientCompat.project(view, w, h);
			if (screen == null || screen[0] < -40 || screen[0] > w + 40 || screen[1] < -20 || screen[1] > h + 20) {
				continue;
			}
			int tw = textRenderer.getWidth(label.text());
			int sx = Math.round(screen[0]) - tw / 2;
			int sy = Math.round(screen[1]) - 4;
			VeloDraw.fillRounded(context, sx - 3, sy - 2, tw + 6, 11, 3, 0xA0101016);
			context.drawTextWithShadow(textRenderer, label.text(), sx, sy, label.color());
		}
	}
}
