package net.veloclient.velo.client.redstone;

import net.minecraft.client.render.DrawStyle;
import net.minecraft.util.math.Box;
import net.minecraft.world.debug.gizmo.GizmoDrawing;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redstone Mode in the world: the selection box, paste / move / stack previews and schematic
 * placements drawn as blocks in their own colours (only blocks with an open side, so even big
 * builds stay cheap), wiring routes, dust range warnings and the recorded area.
 */
public final class RmRender {

	private static final int MAX_VOXELS = 12_000;
	/** Per clip: its visible blocks as {x, y, z, rgb} (surface only). */
	private static final Map<Clip, int[]> SURFACES = new IdentityHashMap<>();
	private static List<float[]> placementVoxels = List.of();
	private static int placementTicks;

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
		if (!RedstoneMode.active()) {
			return;
		}
		placements();
		int[] b = RmState.box();
		if (b != null) {
			outline(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1, 0xFF4FD8FF, 2.5f);
			fill(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1, 0x124FD8FF);
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
		if (tl != null) {
			int color = Timeline.phase() == Timeline.Phase.RECORDING ? 0xFFFF4060 : 0xFFC080FF;
			outline(tl[0] - 0.05, tl[1] - 0.05, tl[2] - 0.05, tl[3] + 1.05, tl[4] + 1.05, tl[5] + 1.05, color, 3f);
		}
		if (RmState.pasting) {
			Clip clip = RmOps.clipboard();
			if (clip != null) {
				Clip turned = turned(clip, RmState.pasteRotation, RmState.pasteMirror);
				int[] origin = RmInput.pasteOrigin(turned);
				if (origin != null) {
					ghost(turned, origin[0], origin[1], origin[2], 0x90);
					outline(origin[0], origin[1], origin[2], origin[0] + turned.sx, origin[1] + turned.sy, origin[2] + turned.sz, 0xFFFFD84A, 2f);
				}
			}
		} else if (RedstoneMode.toolHeld() && b != null) {
			switch (RmState.tool) {
				case MOVE -> previewMove(b);
				case STACK -> previewStack(b);
				default -> {
				}
			}
		}
		if (RmState.tool == RmState.Tool.WIRE || RmState.tool == RmState.Tool.WATER) {
			previewWiring();
		}
	}

	// ---- block previews ----

	private static Clip lastSource;
	private static Clip lastTurned;
	private static int lastRotation;
	private static boolean lastMirror;

	private static Clip turned(Clip clip, int rotation, boolean mirror) {
		if (clip != lastSource || rotation != lastRotation || mirror != lastMirror) {
			lastSource = clip;
			lastRotation = rotation;
			lastMirror = mirror;
			lastTurned = clip.transformed(rotation, mirror);
		}
		return lastTurned;
	}

	/** Visible blocks of a clip: those with at least one air neighbour (or on its edge). */
	private static int[] surface(Clip clip) {
		int[] cached = SURFACES.get(clip);
		if (cached != null) {
			return cached;
		}
		if (SURFACES.size() > 16) {
			SURFACES.clear();
		}
		Object level = RmWorld.clientLevel();
		List<int[]> cells = new ArrayList<>();
		Map<Object, Integer> colors = new IdentityHashMap<>();
		for (int x = 0; x < clip.sx && cells.size() < MAX_VOXELS * 4; x++) {
			for (int y = 0; y < clip.sy; y++) {
				for (int z = 0; z < clip.sz; z++) {
					Object state = clip.states[clip.index(x, y, z)];
					if (RmWorld.isAir(state) || !exposed(clip, x, y, z)) {
						continue;
					}
					int rgb = colors.computeIfAbsent(state, s -> level == null ? 0x808080 : RmWorld.mapColor(level, s, 0, 64, 0));
					cells.add(new int[] {x, y, z, rgb == 0 ? 0x808080 : rgb});
				}
			}
		}
		int[] flat = new int[cells.size() * 4];
		for (int i = 0; i < cells.size(); i++) {
			System.arraycopy(cells.get(i), 0, flat, i * 4, 4);
		}
		SURFACES.put(clip, flat);
		return flat;
	}

	private static boolean exposed(Clip clip, int x, int y, int z) {
		if (x == 0 || y == 0 || z == 0 || x == clip.sx - 1 || y == clip.sy - 1 || z == clip.sz - 1) {
			return true;
		}
		return RmWorld.isAir(clip.states[clip.index(x + 1, y, z)]) || RmWorld.isAir(clip.states[clip.index(x - 1, y, z)])
				|| RmWorld.isAir(clip.states[clip.index(x, y + 1, z)]) || RmWorld.isAir(clip.states[clip.index(x, y - 1, z)])
				|| RmWorld.isAir(clip.states[clip.index(x, y, z + 1)]) || RmWorld.isAir(clip.states[clip.index(x, y, z - 1)]);
	}

	private static void ghost(Clip clip, int ox, int oy, int oz, int alpha) {
		int[] cells = surface(clip);
		int count = Math.min(cells.length / 4, MAX_VOXELS);
		for (int i = 0; i < count; i++) {
			int x = ox + cells[i * 4];
			int y = oy + cells[i * 4 + 1];
			int z = oz + cells[i * 4 + 2];
			int color = (alpha << 24) | cells[i * 4 + 3];
			fill(x + 0.04, y + 0.04, z + 0.04, x + 0.96, y + 0.96, z + 0.96, color);
		}
	}

	/** Move preview: the selection's blocks where they'd go (captured from the world once per move). */
	private static Clip moveSource;
	private static int[] moveSourceBox;

	private static void previewMove(int[] b) {
		if (!RmState.moveActive()) {
			return;
		}
		if (moveSource == null || moveSourceBox == null || !java.util.Arrays.equals(moveSourceBox, b)) {
			moveSourceBox = b.clone();
			moveSource = captureClient(b);
		}
		if (moveSource == null) {
			return;
		}
		Clip turned = turned(moveSource, RmState.moveRotation, RmState.moveMirror);
		int[] s = RmState.size();
		int nx = b[0] + RmState.moveOffset[0] + (s[0] - turned.sx) / 2;
		int ny = b[1] + RmState.moveOffset[1];
		int nz = b[2] + RmState.moveOffset[2] + (s[2] - turned.sz) / 2;
		ghost(turned, nx, ny, nz, 0x90);
		outline(nx, ny, nz, nx + turned.sx, ny + turned.sy, nz + turned.sz, 0xFFFFD84A, 2.5f);
	}

	private static void previewStack(int[] b) {
		if (RmState.stackCount == 0) {
			return;
		}
		if (moveSource == null || moveSourceBox == null || !java.util.Arrays.equals(moveSourceBox, b)) {
			moveSourceBox = b.clone();
			moveSource = captureClient(b);
		}
		int[] s = RmState.size();
		int[] d = RmWorld.faceOffset(RmState.stackFace);
		for (int i = 1; i <= RmState.stackCount; i++) {
			int x = b[0] + d[0] * s[0] * i;
			int y = b[1] + d[1] * s[1] * i;
			int z = b[2] + d[2] * s[2] * i;
			if (moveSource != null && i <= 8) {
				ghost(moveSource, x, y, z, 0x70);
			}
			outline(x, y, z, x + s[0], y + s[1], z + s[2], 0xFFFFD84A, 2f);
		}
	}

	/** A read-only copy of the selection from the client world, just for previews (skipped for huge selections). */
	private static Clip captureClient(int[] b) {
		Object level = RmWorld.clientLevel();
		long volume = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
		if (level == null || volume > 400_000) {
			return null;
		}
		Clip clip = new Clip(b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1);
		for (int x = 0; x < clip.sx; x++) {
			for (int y = 0; y < clip.sy; y++) {
				for (int z = 0; z < clip.sz; z++) {
					clip.states[clip.index(x, y, z)] = RmWorld.state(level, b[0] + x, b[1] + y, b[2] + z);
				}
			}
		}
		return clip;
	}

	// ---- schematic placements ----

	/** Placements: only blocks the world still lacks (wrong blocks red), refreshed a few times a second. */
	private static void placements() {
		List<Placements.Placement> list = Placements.all();
		if (list.isEmpty()) {
			placementVoxels = List.of();
			return;
		}
		Placements.Placement following = Placements.following();
		if (following != null) {
			Clip v = following.view();
			ghost(v, following.x, following.y, following.z, 0x80);
			outline(following.x, following.y, following.z, following.x + v.sx, following.y + v.sy, following.z + v.sz, 0xFFFFD84A, 2f);
		}
		if (++placementTicks % 8 == 0) {
			placementVoxels = collectPlacements(list);
		}
		for (float[] v : placementVoxels) {
			fill(v[0] + 0.05, v[1] + 0.05, v[2] + 0.05, v[0] + 0.95, v[1] + 0.95, v[2] + 0.95, (int) v[3]);
		}
		for (Placements.Placement p : list) {
			if (p.visible && p.locked) {
				Clip v = p.view();
				outline(p.x, p.y, p.z, p.x + v.sx, p.y + v.sy, p.z + v.sz, p == Placements.selected() ? 0xFFFF8040 : 0x80FF8040, 2f);
			}
		}
	}

	private static List<float[]> collectPlacements(List<Placements.Placement> list) {
		Object level = RmWorld.clientLevel();
		int[] player = RmWorld.playerBlock();
		if (level == null || player == null) {
			return List.of();
		}
		List<float[]> out = new ArrayList<>();
		int radius = 40;
		for (Placements.Placement p : list) {
			if (!p.visible || !p.locked) {
				continue;
			}
			Clip v = p.view();
			int x0 = Math.max(0, player[0] - radius - p.x);
			int x1 = Math.min(v.sx - 1, player[0] + radius - p.x);
			int y0 = Math.max(0, player[1] - radius - p.y);
			int y1 = Math.min(v.sy - 1, player[1] + radius - p.y);
			int z0 = Math.max(0, player[2] - radius - p.z);
			int z1 = Math.min(v.sz - 1, player[2] + radius - p.z);
			for (int x = x0; x <= x1 && out.size() < MAX_VOXELS; x++) {
				for (int y = y0; y <= y1 && out.size() < MAX_VOXELS; y++) {
					for (int z = z0; z <= z1 && out.size() < MAX_VOXELS; z++) {
						if (!p.shows(x, y, z)) {
							continue;
						}
						Object want = v.states[v.index(x, y, z)];
						Object have = RmWorld.state(level, p.x + x, p.y + y, p.z + z);
						if (want == have) {
							continue;
						}
						boolean wantAir = RmWorld.isAir(want);
						if (wantAir && RmWorld.isAir(have)) {
							continue;
						}
						int color;
						if (wantAir || !RmWorld.isAir(have)) {
							color = 0x70FF3030;
						} else {
							int rgb = RmWorld.mapColor(level, want, p.x + x, p.y + y, p.z + z);
							color = 0x80000000 | (rgb == 0 ? 0x808080 : rgb);
						}
						out.add(new float[] {p.x + x, p.y + y, p.z + z, color});
					}
				}
			}
		}
		return out;
	}

	// ---- wiring ----

	private static void previewWiring() {
		int drawn = 0;
		for (Wiring.Placement p : Wiring.preview()) {
			if (drawn++ > MAX_VOXELS) {
				break;
			}
			String id = RmWorld.id(p.state());
			int color = colorFor(id, 0x90);
			boolean flat = id.contains("redstone_wire");
			double inset = flat ? 0.3 : 0.06;
			double top = flat ? p.y() + 0.12 : p.y() + 1 - inset;
			fill(p.x() + inset, p.y() + (flat ? 0 : inset), p.z() + inset, p.x() + 1 - inset, top, p.z() + 1 - inset, color);
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
		} else if (id.contains("sign") || id.contains("rail")) {
			rgb = 0xB08050;
		} else if (id.contains("soul_sand")) {
			rgb = 0x6A4A30;
		} else if (id.contains("glass")) {
			rgb = 0xE0F0FF;
			alpha = Math.min(alpha, 0x50);
		} else if (id.contains("air")) {
			rgb = 0xFFFFFF;
			alpha = Math.min(alpha, 0x18);
		} else {
			rgb = 0xA0A0A8;
		}
		return (alpha << 24) | rgb;
	}
}
