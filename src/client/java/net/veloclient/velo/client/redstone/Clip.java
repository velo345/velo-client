package net.veloclient.velo.client.redstone;

/**
 * A captured box of blocks: states (air included), block-entity data, and every block / fluid tick
 * that was scheduled when it was copied - with its remaining delay and priority, in run order - so a
 * paste continues running clocks, observer chains and flowing water exactly where they were.
 * Index order is x, then y, then z.
 */
public final class Clip {

	public final int sx;
	public final int sy;
	public final int sz;
	public final Object[] states;
	public final Object[] blockEntities;
	/** Scheduled ticks in run order: {index, delay, priority ordinal, fluid ? 1 : 0}. */
	public final java.util.List<int[]> ticks = new java.util.ArrayList<>();
	/** Entities (minecarts, mobs, item frames, armor stands, items...): {dx, dy, dz, yaw, tag}, relative to the clip's corner. */
	public final java.util.List<Object[]> entities = new java.util.ArrayList<>();

	public Clip(int sx, int sy, int sz) {
		this.sx = sx;
		this.sy = sy;
		this.sz = sz;
		int n = sx * sy * sz;
		states = new Object[n];
		blockEntities = new Object[n];
	}

	public int index(int x, int y, int z) {
		return (x * sy + y) * sz + z;
	}

	public long volume() {
		return (long) sx * sy * sz;
	}

	/** This clip mirrored east-west (if asked) and then turned {@code quarterTurns} times clockwise (seen from above). */
	public Clip transformed(int quarterTurns, boolean mirror) {
		int turns = ((quarterTurns % 4) + 4) % 4;
		if (turns == 0 && !mirror) {
			return this;
		}
		boolean swap = turns % 2 == 1;
		Clip out = new Clip(swap ? sz : sx, sy, swap ? sx : sz);
		int[] moved = ticks.isEmpty() ? null : new int[states.length];
		for (int x = 0; x < sx; x++) {
			for (int y = 0; y < sy; y++) {
				for (int z = 0; z < sz; z++) {
					int mx = mirror ? sx - 1 - x : x;
					int nx;
					int nz;
					switch (turns) {
						case 1 -> {
							nx = sz - 1 - z;
							nz = mx;
						}
						case 2 -> {
							nx = sx - 1 - mx;
							nz = sz - 1 - z;
						}
						case 3 -> {
							nx = z;
							nz = sx - 1 - mx;
						}
						default -> {
							nx = mx;
							nz = z;
						}
					}
					int from = index(x, y, z);
					int to = out.index(nx, y, nz);
					Object state = states[from];
					if (state != null) {
						if (mirror) {
							state = RmWorld.mirrorX(state);
						}
						state = RmWorld.rotate(state, turns);
					}
					out.states[to] = state;
					out.blockEntities[to] = blockEntities[from];
					if (moved != null) {
						moved[from] = to;
					}
				}
			}
		}
		if (moved != null) {
			for (int[] t : ticks) {
				out.ticks.add(new int[] {moved[t[0]], t[1], t[2], t[3]});
			}
		}
		for (Object[] e : entities) {
			double x = (double) e[0];
			double z = (double) e[2];
			float yaw = (float) e[3];
			if (mirror) {
				x = sx - x;
				yaw = -yaw;
			}
			double nx;
			double nz;
			switch (turns) {
				case 1 -> {
					nx = sz - z;
					nz = x;
				}
				case 2 -> {
					nx = sx - x;
					nz = sz - z;
				}
				case 3 -> {
					nx = z;
					nz = sx - x;
				}
				default -> {
					nx = x;
					nz = z;
				}
			}
			out.entities.add(new Object[] {nx, e[1], nz, yaw + 90f * turns, e[4]});
		}
		return out;
	}

	/** Non-air block count (for the overlay). */
	public int solidCount() {
		int count = 0;
		for (Object state : states) {
			if (!RmWorld.isAir(state)) {
				count++;
			}
		}
		return count;
	}
}
