package net.veloclient.velo.client.redstone;

import net.minecraft.client.render.DrawStyle;
import net.minecraft.util.math.Box;
import net.minecraft.world.debug.gizmo.GizmoDrawing;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redstone Mode in the world: the selection box, paste / move / stack previews (blocks in their own
 * colours, only those with an open side so even big builds stay cheap), locked schematic placements
 * as hologram blocks (see {@link RmBlockRender}) with wrong blocks red, the right block in the wrong
 * state yellow and blocks that shouldn't be there pink (like Litematica), wiring routes, dust range
 * warnings and the recorded area.
 */
public final class RmRender {

	private static final int MAX_VOXELS = 12_000;
	/** Per clip: its visible blocks as {x, y, z, rgb} (surface only). */
	private static final Map<Clip, int[]> SURFACES = new IdentityHashMap<>();
	private static final int MAX_GHOST_BLOCKS = 8_000;
	/** Litematica's colours: a different block, the right block in the wrong state, a block that shouldn't be there. */
	private static final int WRONG_BLOCK = 0x66FF2A2A;
	private static final int WRONG_STATE = 0x66FFD21E;
	private static final int EXTRA = 0x66FF5AD2;
	private static List<float[]> placementVoxels = List.of();
	/** Schematic water / lava still to place: {x, y, z, argb, height} (only with Show schematic water / lava on). */
	private static List<float[]> fluidCells = List.of();
	/** Missing schematic blocks to draw as real blocks: {x, y, z, state}. */
	private static volatile List<Object[]> ghostBlocks = List.of();
	private static int placementTicks;

	private RmRender() {
	}

	private static void outline(double x0, double y0, double z0, double x1, double y1, double z1, int color, float width) {
		GizmoDrawing.box(new Box(x0, y0, z0, x1, y1, z1), DrawStyle.stroked(color, width)).ignoreOcclusion();
	}

	private static void fill(double x0, double y0, double z0, double x1, double y1, double z1, int color) {
		GizmoDrawing.box(new Box(x0, y0, z0, x1, y1, z1), DrawStyle.filled(color)).ignoreOcclusion();
	}

	/** Depth-tested: hidden behind blocks in front of it (for the wrong-block overlays, which got overwhelming through walls). */
	private static void outlineOccluded(double x0, double y0, double z0, double x1, double y1, double z1, int color, float width) {
		GizmoDrawing.box(new Box(x0, y0, z0, x1, y1, z1), DrawStyle.stroked(color, width));
	}

	private static void fillOccluded(double x0, double y0, double z0, double x1, double y1, double z1, int color) {
		GizmoDrawing.box(new Box(x0, y0, z0, x1, y1, z1), DrawStyle.filled(color));
	}

	/** Locked placements' missing blocks, drawn as real blocks by {@link RmBlockRender}. */
	static List<Object[]> ghostBlocks() {
		return ghostBlocks;
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
		if (RmState.stackCopies() == 0) {
			return;
		}
		if (moveSource == null || moveSourceBox == null || !java.util.Arrays.equals(moveSourceBox, b)) {
			moveSourceBox = b.clone();
			moveSource = captureClient(b);
		}
		int[] s = RmState.size();
		int[] c = RmState.stack;
		int ghosts = 0;
		int outlines = 0;
		for (int i = 0; i <= Math.abs(c[0]); i++) {
			for (int j = 0; j <= Math.abs(c[1]); j++) {
				for (int k = 0; k <= Math.abs(c[2]); k++) {
					if (i == 0 && j == 0 && k == 0 || outlines++ > 512) {
						continue;
					}
					int x = b[0] + Integer.signum(c[0]) * s[0] * i;
					int y = b[1] + Integer.signum(c[1]) * s[1] * j;
					int z = b[2] + Integer.signum(c[2]) * s[2] * k;
					if (moveSource != null && ghosts++ < 8) {
						ghost(moveSource, x, y, z, 0x70);
					}
					outline(x, y, z, x + s[0], y + s[1], z + s[2], 0xFFFFD84A, 2f);
				}
			}
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
			fluidCells = List.of();
			ghostBlocks = List.of();
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
		// Wrong (red), wrong state (yellow) and extra blocks where the schematic is empty (pink): a
		// tinted box plus a solid outline, so they're easy to spot even in a busy build. Depth-tested: only the ones you
		// can actually see, not every mistake in the build through the walls.
		int outlined = 0;
		for (float[] v : placementVoxels) {
			int color = (int) v[3];
			fillOccluded(v[0] - 0.01, v[1] - 0.01, v[2] - 0.01, v[0] + 1.01, v[1] + 1.01, v[2] + 1.01, color);
			if (outlined++ < 3000) {
				outlineOccluded(v[0] - 0.01, v[1] - 0.01, v[2] - 0.01, v[0] + 1.01, v[1] + 1.01, v[2] + 1.01, color | 0xFF000000, 2f);
			}
		}
		// Fluids have no block model, so the hologram pass can't show them: tinted boxes instead, as high as the water is.
		for (float[] f : fluidCells) {
			fillOccluded(f[0] + 0.02, f[1], f[2] + 0.02, f[0] + 0.98, f[1] + f[4], f[2] + 0.98, (int) f[3]);
		}
		for (Placements.Placement p : list) {
			if (p.visible && p.locked) {
				Clip v = p.view();
				int color = p.outlineOnly ? 0xFF4FD8FF : p == Placements.selected() ? 0xFFFF8040 : 0x80FF8040;
				outline(p.x, p.y, p.z, p.x + v.sx, p.y + v.sy, p.z + v.sz, color, p.outlineOnly ? 3f : 2f);
			}
		}
	}

	/**
	 * Refreshes {@link #ghostBlocks} (missing blocks, nearest first, hidden ones skipped) and returns
	 * the red overlay boxes for blocks that are wrong or in the way.
	 */
	private static List<float[]> collectPlacements(List<Placements.Placement> list) {
		Object level = RmWorld.clientLevel();
		int[] player = RmWorld.playerBlock();
		if (level == null || player == null) {
			ghostBlocks = List.of();
			return List.of();
		}
		List<float[]> out = new ArrayList<>();
		List<Object[]> ghosts = new ArrayList<>();
		List<float[]> fluids = new ArrayList<>();
		boolean showFluids = RmSettings.get().showFluids;
		int radius = 40;
		for (Placements.Placement p : list) {
			if (!p.visible || !p.locked || p.outlineOnly) {
				continue; // outline-only placements show just their box
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
						if (want == have || sameFunction(want, have)) {
							continue;
						}
						boolean wantAir = RmWorld.isAir(want);
						if (wantAir && RmWorld.isAir(have)) {
							continue;
						}
						String fluid = fluid(want);
						if (fluid != null) {
							// Schematic water / lava: hidden unless switched on; flowing parts count once any of that fluid is there.
							if (showFluids && fluids.size() < MAX_GHOST_BLOCKS && !(fluid.equals(fluid(have)) && (!source(want) || source(have)))) {
								if (!RmWorld.isAir(have) && !RmWorld.replaceable(have)) {
									out.add(new float[] {p.x + x, p.y + y, p.z + z, WRONG_BLOCK});
								} else {
									fluids.add(fluidCell(p.x + x, p.y + y, p.z + z, fluid, want));
								}
							}
							continue;
						}
						if (wantAir) {
							// Something built where the schematic has nothing: pink.
							out.add(new float[] {p.x + x, p.y + y, p.z + z, EXTRA});
						} else if (!RmWorld.isAir(have) && !RmWorld.replaceable(have)) {
							// Right block in the wrong state (facing, waterlogged...) yellow, a different block red.
							out.add(new float[] {p.x + x, p.y + y, p.z + z, RmWorld.id(have).equals(RmWorld.id(want)) ? WRONG_STATE : WRONG_BLOCK});
						} else if (!hidden(p, v, level, x, y, z)) {
							ghosts.add(new Object[] {p.x + x, p.y + y, p.z + z, want});
						}
					}
				}
			}
		}
		if (ghosts.size() > MAX_GHOST_BLOCKS) {
			ghosts.sort(java.util.Comparator.comparingLong(g -> distance(g, player)));
			ghosts = new ArrayList<>(ghosts.subList(0, MAX_GHOST_BLOCKS));
		}
		ghostBlocks = ghosts;
		fluidCells = fluids;
		return out;
	}

	/** "water" / "lava" for those blocks (a bubble column is water too), else null. */
	static String fluid(Object state) {
		if (state == null) {
			return null;
		}
		return switch (RmWorld.id(state)) {
			case "minecraft:water", "minecraft:bubble_column" -> "water";
			case "minecraft:lava" -> "lava";
			default -> null;
		};
	}

	/** A source block (level 0), not flowing water / lava. */
	static boolean source(Object state) {
		String level = RmWorld.prop(state, "level");
		return level == null || level.equals("0");
	}

	private static float[] fluidCell(int x, int y, int z, String fluid, Object state) {
		int rgb = fluid.equals("lava") ? 0xFF7A1E : 0x3A7BFF;
		if (source(state)) {
			return new float[] {x, y, z, 0x70000000 | rgb, 0.9f};
		}
		// Flowing: fainter and lower, the way the water stands there.
		int level = 0;
		try {
			level = Integer.parseInt(RmWorld.prop(state, "level"));
		} catch (NumberFormatException ignored) {
			// Unknown level: full height.
		}
		float height = level >= 8 ? 0.9f : Math.max(0.12f, (8 - level) / 9f);
		return new float[] {x, y, z, 0x38000000 | rgb, height};
	}

	/**
	 * Two different states that work the same, so a placement counts them as right. A crafter facing up or down works
	 * the same whichever way its top side points ({@code up_north} = {@code up_east}...), and {@code triggered} /
	 * {@code crafting} only show what it's doing at the moment.
	 */
	static boolean sameFunction(Object want, Object have) {
		if (want == null || have == null || !RmWorld.id(want).equals("minecraft:crafter") || !RmWorld.id(have).equals("minecraft:crafter")) {
			return false;
		}
		String w = RmWorld.prop(want, "orientation");
		String h = RmWorld.prop(have, "orientation");
		if (w == null || h == null) {
			return false;
		}
		boolean vertical = w.startsWith("up_") || w.startsWith("down_");
		if (vertical ? !h.startsWith(w.substring(0, w.indexOf('_') + 1)) : !w.equals(h)) {
			return false;
		}
		Object normalized = RmWorld.with(have, "orientation", w);
		for (String transientProp : new String[] {"triggered", "crafting"}) {
			String value = RmWorld.prop(want, transientProp);
			if (value != null) {
				normalized = RmWorld.with(normalized, transientProp, value);
			}
		}
		return normalized == want;
	}

	private static long distance(Object[] g, int[] player) {
		long dx = (int) g[0] - player[0];
		long dy = (int) g[1] - player[1];
		long dz = (int) g[2] - player[2];
		return dx * dx + dy * dy + dz * dz;
	}

	/** A missing block with something solid (built or still to build) on all six sides can't be seen. */
	private static boolean hidden(Placements.Placement p, Clip v, Object level, int x, int y, int z) {
		int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (int[] d : sides) {
			int nx = x + d[0];
			int ny = y + d[1];
			int nz = z + d[2];
			boolean inside = nx >= 0 && ny >= 0 && nz >= 0 && nx < v.sx && ny < v.sy && nz < v.sz && p.shows(nx, ny, nz);
			Object schematic = inside ? v.states[v.index(nx, ny, nz)] : null;
			boolean covered = !RmWorld.isAir(RmWorld.state(level, p.x + nx, p.y + ny, p.z + nz))
					|| schematic != null && !RmWorld.isAir(schematic) && RmWorld.opaque(schematic);
			if (!covered) {
				return false;
			}
		}
		return true;
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
			connectionCell(s, 0xFF6FE39A);
		}
		if (e != null) {
			outline(e[0], e[1], e[2], e[0] + 1, e[1] + 1, e[2] + 1, 0xFFFF6060, 3f);
			connectionCell(e, 0xFFFF6060);
		}
		// Before building: show exactly which cell the next click would use - the one in front of
		// the face you aim at (green = start, red = end).
		if (RedstoneMode.toolHeld() && Wiring.preview().isEmpty()) {
			int[] look = RmWorld.lookedBlock(256);
			if (look != null) {
				connectionCell(look, s == null ? 0xFF6FE39A : 0xFFFF6060);
			}
		}
	}

	/** The cell in front of a clicked face: filled lightly, outlined, with the clicked face marked. */
	private static void connectionCell(int[] clicked, int color) {
		int[] o = RmWorld.faceOffset(clicked[3]);
		int x = clicked[0] + o[0];
		int y = clicked[1] + o[1];
		int z = clicked[2] + o[2];
		boolean water = RmState.tool == RmState.Tool.WATER;
		fill(x + 0.02, y + 0.02, z + 0.02, x + 0.98, y + 0.98, z + 0.98, water ? 0x403070FF : 0x30FF3030);
		outline(x, y, z, x + 1, y + 1, z + 1, color, 2f);
		// A thin plate on the clicked face, so it's clear which side counts.
		double t = 0.04;
		double x0 = clicked[0] + (o[0] > 0 ? 1 - t : 0);
		double x1 = clicked[0] + (o[0] < 0 ? t : 1);
		double y0 = clicked[1] + (o[1] > 0 ? 1 - t : 0);
		double y1 = clicked[1] + (o[1] < 0 ? t : 1);
		double z0 = clicked[2] + (o[2] > 0 ? 1 - t : 0);
		double z1 = clicked[2] + (o[2] < 0 ? t : 1);
		fill(x0, y0, z0, x1, y1, z1, (color & 0x00FFFFFF) | 0x90000000);
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
