package net.veloclient.launcher.sandbox;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Water and lava that flow (2026-11, "like Terraria"): every liquid cell holds an amount (0..1, a missing entry = a
 * full block). Liquid falls into the cell below first, then evens out with its left and right neighbours, so a pocket
 * pours down as a stream, spreads over the floor and settles flat. The amount is finite - dig under a pool and it
 * drains. Lava is thick and slow, water quick. Where they meet, stone forms. Only cells that can move are simulated:
 * a block change next to liquid wakes it, and a cheap scan of the screen picks up pockets hanging over open caves.
 */
final class Liquids {

	/** Changes a block through the game (so the party and the save see it). */
	interface Setter {
		void set(Level level, int x, int y, int id);
	}

	private static final float EMPTY = 0.02f;
	private final Map<Long, Float> amounts = new HashMap<>();
	private final ArrayDeque<Long> water = new ArrayDeque<>();
	private final ArrayDeque<Long> lava = new ArrayDeque<>();
	private final Set<Long> queued = new HashSet<>();
	private final Random random = new Random();
	private double waterClock;
	private double lavaClock;
	private double scanClock;

	static boolean liquid(int id) {
		return id == Items.WATER || id == Items.LAVA;
	}

	private static long key(int x, int y) {
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	private static int kx(long k) {
		return (int) (k >> 32);
	}

	private static int ky(long k) {
		return (int) k;
	}

	/** How full a liquid cell is (1 = a whole block). */
	float amount(Level level, int x, int y) {
		if (!liquid(level.get(x, y))) {
			return 0;
		}
		Float a = amounts.get(key(x, y));
		return a == null ? 1f : a;
	}

	/** Something changed at (x, y): its liquid neighbours may move now. */
	void touch(Level level, int x, int y) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				wake(level, x + dx, y + dy);
			}
		}
	}

	private void wake(Level level, int x, int y) {
		if (y < 0 || y >= level.height()) {
			return;
		}
		int id = level.get(x, y);
		if (!liquid(id)) {
			return;
		}
		long k = key(x, y);
		if (queued.add(k)) {
			(id == Items.WATER ? water : lava).add(k);
		}
	}

	/** Runs the flow (host / single player only). {@code x0..x1, y0..y1}: what's on screen, for the scan. */
	void update(Level level, double dt, int x0, int x1, int y0, int y1, Setter setter) {
		scanClock -= dt;
		if (scanClock <= 0) {
			scanClock = 0.5;
			for (int x = x0; x <= x1; x++) {
				for (int y = Math.max(0, y0); y <= Math.min(level.height() - 2, y1); y++) {
					int id = level.get(x, y);
					if (liquid(id) && (open(level, x, y + 1, id) || open(level, x - 1, y, id) || open(level, x + 1, y, id))) {
						wake(level, x, y);
					}
				}
			}
		}
		waterClock += dt;
		while (waterClock >= 1 / 30.0) {
			waterClock -= 1 / 30.0;
			step(level, water, Items.WATER, setter);
		}
		lavaClock += dt;
		while (lavaClock >= 1 / 7.0) {
			lavaClock -= 1 / 7.0;
			step(level, lava, Items.LAVA, setter);
		}
	}

	/** Room for liquid of type {@code id} at (x, y): air, or the same liquid not yet full. */
	private boolean open(Level level, int x, int y, int id) {
		if (y < 0 || y >= level.height()) {
			return false;
		}
		int at = level.get(x, y);
		return at == Items.AIR || at == id && amount(level, x, y) < 0.99f;
	}

	private void step(Level level, ArrayDeque<Long> queue, int id, Setter setter) {
		int n = Math.min(queue.size(), 600);
		for (int i = 0; i < n; i++) {
			long k = queue.poll();
			queued.remove(k);
			flow(level, kx(k), ky(k), id, setter);
		}
	}

	private void flow(Level level, int x, int y, int id, Setter setter) {
		if (level.get(x, y) != id) {
			amounts.remove(key(x, y));
			return;
		}
		int other = id == Items.WATER ? Items.LAVA : Items.WATER;
		float a = amount(level, x, y);
		float start = a;
		boolean moved = false;
		// down first
		if (y + 1 < level.height()) {
			int below = level.get(x, y + 1);
			if (below == Items.AIR || below == id) {
				float ba = below == Items.AIR ? 0 : amount(level, x, y + 1);
				float move = Math.min(a, 1f - ba);
				if (move > 0.001f) {
					a -= move;
					put(level, x, y + 1, id, ba + move, setter);
					moved = true;
				}
			} else if (below == other) {
				// water meets lava: the lava cools to stone
				put(level, x, y + (id == Items.WATER ? 1 : 0), Items.STONE, 0, setter);
				if (id == Items.LAVA) {
					return;
				}
				a = Math.max(0, a - 0.3f);
				moved = true;
			}
		}
		// then even out sideways (only standing liquid spreads, and never thinner than a film)
		if (a > EMPTY * 2 && !(y + 1 < level.height() && open(level, x, y + 1, id) && level.get(x, y + 1) == Items.AIR)) {
			int first = random.nextBoolean() ? 1 : -1;
			for (int s = 0; s < 2; s++) {
				int dx = s == 0 ? first : -first;
				int side = level.get(x + dx, y);
				if (side == other) {
					put(level, id == Items.WATER ? x + dx : x, y, Items.STONE, 0, setter);
					if (id == Items.LAVA) {
						return;
					}
					a = Math.max(0, a - 0.3f);
					moved = true;
					continue;
				}
				if (side != Items.AIR && side != id) {
					continue;
				}
				float na = side == Items.AIR ? 0 : amount(level, x + dx, y);
				if (a > na + 0.02f) {
					float give = (a - na) / 3f;
					if (side == Items.AIR && give < EMPTY) {
						continue;
					}
					a -= give;
					put(level, x + dx, y, id, na + give, setter);
					moved = true;
				}
			}
		}
		if (a != start || moved) {
			put(level, x, y, a < EMPTY ? Items.AIR : id, a, setter);
			touch(level, x, y);
		}
	}

	/** Writes a cell: the block through the game when it changes, the amount here. */
	private void put(Level level, int x, int y, int id, float amount, Setter setter) {
		long k = key(x, y);
		if (!liquid(id)) {
			amounts.remove(k);
			if (level.get(x, y) != id) {
				setter.set(level, x, y, id);
			}
			return;
		}
		float clamped = Math.min(1f, Math.max(0f, amount));
		if (clamped >= 0.995f) {
			amounts.remove(k);
		} else {
			amounts.put(k, clamped);
		}
		if (level.get(x, y) != id) {
			setter.set(level, x, y, id);
		}
		wake(level, x, y);
	}

	/** Forgets the amounts (a new world). */
	void clear() {
		amounts.clear();
		water.clear();
		lava.clear();
		queued.clear();
	}
}
