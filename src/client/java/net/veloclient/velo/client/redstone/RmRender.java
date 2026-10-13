package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.util.math.Box;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.modules.redstone.TickJournal;
import net.veloclient.velo.client.util.ClientCompat;

import java.util.List;
import java.util.Locale;

/**
 * Everything Redstone Mode draws: in the world the selection box and corners, paste / move /
 * stack previews (with ghost blocks), the planned wiring, dust-range warnings and the recorded
 * timeline area; on screen the tool panel with the keys that apply right now, the Tick Mode
 * state, the timeline bar and labels for clocks and dust warnings.
 */
public final class RmRender {

	private static final int MAX_GHOSTS = 2500;
	private static Clip ghostSource;
	private static int ghostRotation;
	private static boolean ghostMirror;
	private static Clip ghostClip;

	private RmRender() {
	}

	private static void outline(double x0, double y0, double z0, double x1, double y1, double z1, int color, float width) {
		GizmoDrawing.box(new Box(x0, y0, z0, x1, y1, z1), DrawStyle.stroked(color, width)).ignoreOcclusion();
	}

	private static void fill(double x0, double y0, double z0, double x1, double y1, double z1, int color) {
		GizmoDrawing.box(new Box(x0, y0, z0, x1, y1, z1), DrawStyle.filled(color)).ignoreOcclusion();
	}

	/** World pass (gizmos). */
	public static void world() {
		if (DustAnalyzer.visible()) {
			for (int[] p : DustAnalyzer.flagged()) {
				fill(p[0] + 0.05, p[1], p[2] + 0.05, p[0] + 0.95, p[1] + 0.08, p[2] + 0.95, 0xA0FF3030);
			}
		}
		if (!RedstoneModeModule.active()) {
			return;
		}
		int[] b = RmState.box();
		if (b != null) {
			outline(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1, 0xFF4FD8FF, 2.5f);
			fill(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1, 0x184FD8FF);
		}
		if (RmState.cornerA != null) {
			int[] a = RmState.cornerA;
			outline(a[0] - 0.02, a[1] - 0.02, a[2] - 0.02, a[0] + 1.02, a[1] + 1.02, a[2] + 1.02, 0xFF6FE39A, 3f);
		}
		if (RmState.cornerB != null) {
			int[] c = RmState.cornerB;
			outline(c[0] - 0.02, c[1] - 0.02, c[2] - 0.02, c[0] + 1.02, c[1] + 1.02, c[2] + 1.02, 0xFFFFA040, 3f);
		}
		int[] tl = Timeline.box();
		if (tl != null && Timeline.phase() != Timeline.Phase.IDLE) {
			int color = Timeline.phase() == Timeline.Phase.RECORDING ? 0xFFFF4060 : 0xFFC080FF;
			outline(tl[0] - 0.05, tl[1] - 0.05, tl[2] - 0.05, tl[3] + 1.05, tl[4] + 1.05, tl[5] + 1.05, color, 3f);
		}
		switch (RmState.pending) {
			case PASTE, MOVE -> previewPlacement();
			case STACK -> previewStack(b);
			case WIRE, WATER -> previewWiring();
			default -> {
			}
		}
		if (RmState.wireStart != null && RmState.pending != RmState.Pending.WIRE && RmState.pending != RmState.Pending.WATER) {
			int[] s = RmState.wireStart;
			outline(s[0], s[1], s[2], s[0] + 1, s[1] + 1, s[2] + 1, 0xFF6FE39A, 3f);
		}
	}

	private static void previewPlacement() {
		int[] origin = RmInput.previewOrigin();
		int[] size = RmInput.previewSize();
		if (origin == null || size == null) {
			return;
		}
		outline(origin[0], origin[1], origin[2], origin[0] + size[0], origin[1] + size[1], origin[2] + size[2], 0xFFFFD84A, 2.5f);
		if (RmState.pending != RmState.Pending.PASTE) {
			return;
		}
		Clip source = RmOps.clipboard();
		if (source == null) {
			return;
		}
		if (source != ghostSource || ghostRotation != RmState.rotation || ghostMirror != RmState.mirrored) {
			ghostSource = source;
			ghostRotation = RmState.rotation;
			ghostMirror = RmState.mirrored;
			ghostClip = source.volume() <= 200_000 ? source.transformed(RmState.rotation, RmState.mirrored) : null;
		}
		Clip clip = ghostClip;
		if (clip == null) {
			return;
		}
		int drawn = 0;
		for (int x = 0; x < clip.sx && drawn < MAX_GHOSTS; x++) {
			for (int y = 0; y < clip.sy && drawn < MAX_GHOSTS; y++) {
				for (int z = 0; z < clip.sz && drawn < MAX_GHOSTS; z++) {
					Object state = clip.states[clip.index(x, y, z)];
					if (RmWorld.isAir(state)) {
						continue;
					}
					int color = colorFor(RmWorld.id(state), 0x50);
					double px = origin[0] + x;
					double py = origin[1] + y;
					double pz = origin[2] + z;
					fill(px + 0.1, py + 0.1, pz + 0.1, px + 0.9, py + 0.9, pz + 0.9, color);
					drawn++;
				}
			}
		}
	}

	private static void previewStack(int[] b) {
		if (b == null) {
			return;
		}
		int sx = b[3] - b[0] + 1;
		int sy = b[4] - b[1] + 1;
		int sz = b[5] - b[2] + 1;
		int[] d = RmWorld.faceOffset(RmState.stackFace);
		for (int i = 1; i <= Math.min(RmState.stackCount, 64); i++) {
			double x = b[0] + d[0] * sx * i;
			double y = b[1] + d[1] * sy * i;
			double z = b[2] + d[2] * sz * i;
			outline(x, y, z, x + sx, y + sy, z + sz, 0xFFFFD84A, 2f);
		}
	}

	private static void previewWiring() {
		List<Wiring.Placement> placements = Wiring.preview();
		int drawn = 0;
		for (Wiring.Placement p : placements) {
			if (drawn++ > MAX_GHOSTS) {
				break;
			}
			String id = RmWorld.id(p.state());
			int color = colorFor(id, 0x90);
			double inset = id.contains("redstone_wire") ? 0.3 : 0.08;
			double top = id.contains("redstone_wire") ? p.y() + 0.15 : p.y() + 1 - inset;
			fill(p.x() + inset, p.y() + (id.contains("redstone_wire") ? 0 : inset), p.z() + inset, p.x() + 1 - inset, top, p.z() + 1 - inset, color);
		}
		int[] s = RmState.wireStart;
		int[] e = RmState.wireEnd;
		if (s != null) {
			outline(s[0], s[1], s[2], s[0] + 1, s[1] + 1, s[2] + 1, 0xFF6FE39A, 3f);
		}
		if (e != null) {
			outline(e[0], e[1], e[2], e[0] + 1, e[1] + 1, e[2] + 1, 0xFFFF6060, 3f);
		}
	}

	/** Preview colour per block kind. */
	static int colorFor(String id, int alpha) {
		int rgb;
		if (id.contains("redstone_wire")) {
			rgb = 0xFF2020;
		} else if (id.contains("repeater") || id.contains("comparator")) {
			rgb = 0xFF9030;
		} else if (id.contains("torch")) {
			rgb = 0xFFE040;
		} else if (id.contains("observer")) {
			rgb = 0xB070FF;
		} else if (id.contains("water")) {
			rgb = 0x3070FF;
		} else if (id.contains("ice")) {
			rgb = 0x90D0FF;
		} else if (id.contains("sign")) {
			rgb = 0xB08050;
		} else if (id.contains("soul_sand")) {
			rgb = 0x6A4A30;
		} else if (id.contains("glass")) {
			rgb = 0xE0F0FF;
		} else if (id.contains("air")) {
			rgb = 0xFFFFFF;
			alpha = Math.min(alpha, 0x20);
		} else {
			rgb = 0xA0A0A8;
		}
		return (alpha << 24) | rgb;
	}

	// ---- HUD ----

	/** HUD pass (from HudManager). */
	public static void hud(DrawContext context, int screenWidth, int screenHeight) {
		if (DustAnalyzer.visible()) {
			for (DustAnalyzer.Label label : DustAnalyzer.labels()) {
				worldLabel(context, label.x(), label.y(), label.z(), label.text(), 0xFFFF6060, screenWidth, screenHeight);
			}
		}
		if (!RedstoneModeModule.active()) {
			return;
		}
		for (ClockTracker.Clock clock : ClockTracker.clocks()) {
			double hz = 20.0 / Math.max(1, clock.periodTicks());
			worldLabel(context, clock.x(), clock.y(), clock.z(),
					String.format(Locale.ROOT, "Clock %d gt  ·  %.2f Hz", clock.periodTicks(), hz), 0xFF7FD8FF, screenWidth, screenHeight);
		}
		panel(context);
		if (Timeline.playbackActive()) {
			timelineBar(context, screenWidth, screenHeight);
		}
	}

	private static void worldLabel(DrawContext context, double x, double y, double z, String text, int color, int w, int h) {
		float[] view = ClientCompat.cameraView(x, y, z);
		float[] screen = ClientCompat.project(view, w, h);
		if (screen == null) {
			return;
		}
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int tw = textRenderer.getWidth(text);
		int sx = Math.round(screen[0]) - tw / 2;
		int sy = Math.round(screen[1]);
		VeloDraw.fillRounded(context, sx - 4, sy - 2, tw + 8, 12, 3, 0xB0101016);
		context.drawTextWithShadow(textRenderer, text, sx, sy, color);
	}

	private static void panel(DrawContext context) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int x = 6;
		int y = 6;
		int w = 210;
		java.util.List<String[]> lines = new java.util.ArrayList<>();
		// Tools
		StringBuilder tools = new StringBuilder();
		for (RmState.Tool tool : RmState.Tool.values()) {
			tools.append(tool == RmState.tool ? "[" + tool.label + "]" : tool.label).append("  ");
		}
		String hint = switch (RmState.pending) {
			case PASTE -> "Paste: scroll rotate · H flip · click / Enter place";
			case MOVE -> "Move: scroll rotate · H flip · click / Enter drop";
			case STACK -> "Stack x" + RmState.stackCount + " " + RmWorld.faceName(RmState.stackFace) + ": scroll count · click / Enter";
			case WIRE, WATER -> Wiring.summary() == null ? "" : "Enter / click build · Backspace cancel";
			default -> RmState.tool.hint;
		};
		int[] b = RmState.box();
		String selection = b == null ? "No selection" : (b[3] - b[0] + 1) + " x " + (b[4] - b[1] + 1) + " x " + (b[5] - b[2] + 1)
				+ "  (" + RmState.volume() + " blocks)";
		Clip clip = RmOps.clipboard();
		String clipLine = clip == null ? "Clipboard empty" : "Clipboard " + clip.sx + "x" + clip.sy + "x" + clip.sz;
		String ticks;
		if (!TickControls.on()) {
			ticks = RmWorld.singleplayer() ? "Ticks: running" : "Ticks: server (read-only)";
		} else {
			ticks = (TickControls.playing() ? "▶ playing" : "❚❚ frozen") + "  +" + TickControls.stepped() + " ticks  ·  "
					+ TickJournal.available() + " to rewind";
		}
		String timeline = switch (Timeline.phase()) {
			case RECORDING -> "● REC  " + Timeline.frameCount() + " ticks  (K to stop)";
			case PLAYBACK -> "Replay " + Timeline.shownFrame() + "/" + Timeline.frameCount() + (Timeline.playing() ? " ▶" : "");
			default -> "K: record selection timeline";
		};
		int height = 12 + 11 * 8;
		String message = RmState.message();
		if (message != null) {
			height += 11 * Math.min(3, VeloUi.wrap(message, w - 12).size());
		}
		VeloDraw.fillRounded(context, x, y, w, height, 6, 0xC0101016);
		int ty = y + 5;
		context.drawTextWithShadow(textRenderer, "REDSTONE MODE", x + 6, ty, 0xFFFF5050);
		String key = "R to leave";
		context.drawTextWithShadow(textRenderer, key, x + w - 6 - textRenderer.getWidth(key), ty, 0xFF8888A0);
		ty += 12;
		context.drawTextWithShadow(textRenderer, VeloUi.trim(tools.toString(), w - 12), x + 6, ty, 0xFFFFFFFF);
		ty += 11;
		context.drawTextWithShadow(textRenderer, VeloUi.trim(hint, w - 12), x + 6, ty, 0xFFFFD84A);
		ty += 11;
		context.drawTextWithShadow(textRenderer, VeloUi.trim(selection, w - 12), x + 6, ty, 0xFF4FD8FF);
		ty += 11;
		context.drawTextWithShadow(textRenderer, VeloUi.trim(clipLine, w - 12), x + 6, ty, 0xFFB8B8C8);
		ty += 11;
		context.drawTextWithShadow(textRenderer, VeloUi.trim(ticks, w - 12), x + 6, ty, TickControls.playing() ? 0xFF6FE39A : 0xFFFFC53D);
		ty += 11;
		context.drawTextWithShadow(textRenderer, VeloUi.trim(timeline, w - 12), x + 6, ty,
				Timeline.phase() == Timeline.Phase.RECORDING ? 0xFFFF4060 : 0xFFC080FF);
		ty += 11;
		context.drawTextWithShadow(textRenderer, VeloUi.trim("Z tool · ^C/^X/^V · G move · U stack · J replace · Del · ^Z/^Y · O", w - 12),
				x + 6, ty, 0xFF8888A0);
		ty += 11;
		if (message != null) {
			for (String line : VeloUi.wrap(message, w - 12).stream().limit(3).toList()) {
				context.drawTextWithShadow(textRenderer, line, x + 6, ty, RmState.messageIsError() ? 0xFFFF7070 : 0xFF6FE39A);
				ty += 11;
			}
		}
	}

	private static void timelineBar(DrawContext context, int screenWidth, int screenHeight) {
		int count = Math.max(1, Timeline.frameCount());
		int w = Math.min(420, screenWidth - 40);
		int x = (screenWidth - w) / 2;
		int y = screenHeight - 70;
		VeloDraw.fillRounded(context, x - 6, y - 14, w + 12, 30, 6, 0xC0101016);
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		String title = "Tick " + Timeline.shownFrame() + " / " + count + "   ← → step  ·  ↑ " + (Timeline.playing() ? "pause" : "play")
				+ "  ·  ↓ start  ·  " + String.format(Locale.ROOT, "%.2fx", RedstoneModeModule.playbackSpeed);
		context.drawTextWithShadow(textRenderer, VeloUi.trim(title, w), x, y - 11, 0xFFFFFFFF);
		context.fill(x, y + 4, x + w, y + 8, 0xFF2A2A33);
		// Activity marks: taller where more blocks changed.
		int step = Math.max(1, count / w);
		for (int f = 1; f <= count; f += step) {
			int changes = Timeline.changesAt(f);
			if (changes > 0) {
				int px = x + (int) ((long) f * w / count);
				int hgt = Math.min(6, 1 + changes / 2);
				context.fill(px, y + 4 - hgt, px + 1, y + 4, 0xFFC080FF);
			}
		}
		int cursor = x + (int) ((long) Timeline.shownFrame() * w / count);
		context.fill(cursor - 1, y + 1, cursor + 2, y + 11, 0xFFFFD84A);
	}
}
