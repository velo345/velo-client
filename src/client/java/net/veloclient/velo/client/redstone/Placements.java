package net.veloclient.velo.client.redstone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Schematic placements shown in the world, Litematica-style. Loading a schematic creates a placement
 * that follows the crosshair; a left click locks it in place, and it stays there - next to any
 * number of others - until removed from the Schematics panel. Each placement can be hidden, rotated
 * and mirrored, limited to one layer or a range of layers along Y, X or Z, pasted (singleplayer),
 * or used as a build guide in survival: the preview only shows blocks the world doesn't have yet,
 * wrong blocks in red, and middle click picks the block you're looking at from your inventory.
 */
public final class Placements {

	public enum LayerMode { ALL, SINGLE, RANGE }

	public static final class Placement {
		public final String name;
		public final Clip clip;
		public int rotation;
		public boolean mirror;
		public int x;
		public int y;
		public int z;
		public boolean visible = true;
		public boolean locked;
		/** Only the bounding box lines, no blocks - for digging out the area on a server. */
		public boolean outlineOnly;
		public LayerMode layerMode = LayerMode.ALL;
		public char axis = 'y';
		public int layer;
		public int layerTo;
		private Clip view;
		private int viewRotation = -1;
		private boolean viewMirror;

		Placement(String name, Clip clip) {
			this.name = name;
			this.clip = clip;
		}

		/** The clip as currently rotated / mirrored. */
		public synchronized Clip view() {
			if (view == null || viewRotation != rotation || viewMirror != mirror) {
				view = clip.transformed(rotation, mirror);
				viewRotation = rotation;
				viewMirror = mirror;
			}
			return view;
		}

		public int layerCount() {
			Clip v = view();
			return switch (axis) {
				case 'x' -> v.sx;
				case 'z' -> v.sz;
				default -> v.sy;
			};
		}

		/** Whether a block at local x/y/z is in the shown layers. */
		public boolean shows(int lx, int ly, int lz) {
			if (layerMode == LayerMode.ALL) {
				return true;
			}
			int value = switch (axis) {
				case 'x' -> lx;
				case 'z' -> lz;
				default -> ly;
			};
			// A range works whichever way round From and To were set.
			int from = layerMode == LayerMode.SINGLE ? layer : Math.min(layer, layerTo);
			int to = layerMode == LayerMode.SINGLE ? layer : Math.max(layer, layerTo);
			return value >= from && value <= to;
		}

		/** Moves the placement so its lower corner (min x/y/z) is the block you're standing in. */
		public void moveToFeet() {
			int[] feet = RmWorld.playerBlock();
			if (feet != null) {
				x = feet[0];
				y = feet[1];
				z = feet[2];
				locked = true;
			}
		}

		/** The layer (along the current axis) of the block you're standing in, clamped to this placement. */
		public int layerAtFeet() {
			int[] feet = RmWorld.playerBlock();
			if (feet == null) {
				return layer;
			}
			int value = switch (axis) {
				case 'x' -> feet[0] - x;
				case 'z' -> feet[2] - z;
				default -> feet[1] - y;
			};
			return Math.max(0, Math.min(layerCount() - 1, value));
		}
	}

	private static final List<Placement> PLACEMENTS = new ArrayList<>();
	private static Placement selected;

	private Placements() {
	}

	public static synchronized List<Placement> all() {
		return List.copyOf(PLACEMENTS);
	}

	public static Placement selected() {
		return selected;
	}

	public static void select(Placement p) {
		selected = p;
	}

	/** The placement still following the crosshair (not locked yet), or null. */
	public static synchronized Placement following() {
		for (Placement p : PLACEMENTS) {
			if (!p.locked) {
				return p;
			}
		}
		return null;
	}

	public static synchronized Placement add(String name, Clip clip) {
		PLACEMENTS.removeIf(p -> !p.locked);
		Placement p = new Placement(name, clip);
		PLACEMENTS.add(p);
		selected = p;
		return p;
	}

	public static synchronized void remove(Placement p) {
		PLACEMENTS.remove(p);
		if (selected == p) {
			selected = PLACEMENTS.isEmpty() ? null : PLACEMENTS.getLast();
		}
	}

	public static synchronized void clear() {
		PLACEMENTS.clear();
		selected = null;
	}

	/** Keeps an unlocked placement under the crosshair. Client tick. */
	public static void tick() {
		Placement p = following();
		if (p == null) {
			return;
		}
		int[] look = RmWorld.lookedBlock(128);
		if (look == null) {
			return;
		}
		Clip v = p.view();
		int[] d = RmWorld.faceOffset(look[3]);
		p.x = look[0] + d[0] - v.sx / 2;
		p.y = look[3] == 0 ? look[1] - v.sy : look[1] + d[1];
		p.z = look[2] + d[2] - v.sz / 2;
	}

	/** The schematic block meant to be at world x/y/z (visible placements, shown layers), or null. */
	public static synchronized Object stateAt(int x, int y, int z) {
		for (int i = PLACEMENTS.size() - 1; i >= 0; i--) {
			Placement p = PLACEMENTS.get(i);
			if (!p.visible || p.outlineOnly) {
				continue;
			}
			Clip v = p.view();
			int lx = x - p.x;
			int ly = y - p.y;
			int lz = z - p.z;
			if (lx < 0 || ly < 0 || lz < 0 || lx >= v.sx || ly >= v.sy || lz >= v.sz || !p.shows(lx, ly, lz)) {
				continue;
			}
			Object state = v.states[v.index(lx, ly, lz)];
			if (!RmWorld.isAir(state)) {
				return state;
			}
		}
		return null;
	}

	/**
	 * Middle click: the first schematic block along your view that the world is still missing,
	 * picked from your inventory like vanilla pick block. True when it handled the click.
	 */
	public static boolean pickLooked() {
		var player = net.veloclient.velo.client.pvp.PvpCompat.localPlayer();
		Object level = RmWorld.clientLevel();
		if (player == null || level == null || PLACEMENTS.isEmpty()) {
			return false;
		}
		double[] eye = RmWorld.eyeAndLook();
		if (eye == null) {
			return false;
		}
		for (double t = 0; t < 8; t += 0.05) {
			int x = (int) Math.floor(eye[0] + eye[3] * t);
			int y = (int) Math.floor(eye[1] + eye[4] * t);
			int z = (int) Math.floor(eye[2] + eye[5] * t);
			Object state = stateAt(x, y, z);
			Object world = RmWorld.state(level, x, y, z);
			if (state != null && world != state) {
				if (RmWorld.pickItem(state)) {
					return true;
				}
				RmState.error("You don't have " + RmWorld.id(state).replace("minecraft:", "").replace('_', ' '));
				return true;
			}
			if (!RmWorld.isAir(world)) {
				return false;
			}
		}
		return false;
	}

	/** {block id -> {needed, already placed}} for a placement's shown layers, sorted by most missing. */
	public static List<Object[]> materials(Placement p) {
		Object level = RmWorld.clientLevel();
		Clip v = p.view();
		Map<String, int[]> counts = new HashMap<>();
		Map<String, Object> sample = new HashMap<>();
		for (int x = 0; x < v.sx; x++) {
			for (int y = 0; y < v.sy; y++) {
				for (int z = 0; z < v.sz; z++) {
					Object state = v.states[v.index(x, y, z)];
					if (RmWorld.isAir(state) || !p.shows(x, y, z)) {
						continue;
					}
					String id = RmWorld.id(state);
					int[] c = counts.computeIfAbsent(id, k -> new int[2]);
					sample.putIfAbsent(id, state);
					c[0]++;
					if (level != null && RmWorld.state(level, p.x + x, p.y + y, p.z + z) == state) {
						c[1]++;
					}
				}
			}
		}
		List<Object[]> rows = new ArrayList<>();
		for (var e : counts.entrySet()) {
			rows.add(new Object[] {e.getKey(), e.getValue()[0], e.getValue()[1], sample.get(e.getKey())});
		}
		rows.sort((a, b) -> Integer.compare(((int) b[1] - (int) b[2]), ((int) a[1] - (int) a[2])));
		return rows;
	}
}
