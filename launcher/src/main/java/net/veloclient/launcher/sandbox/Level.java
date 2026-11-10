package net.veloclient.launcher.sandbox;

import java.util.HashMap;
import java.util.Map;

/** A place you can be in Velo World: the endless overworld or one of the dungeons. Rows grow downwards. */
abstract class Level {

	/** Every block changed since generation, by {@link #key} (what gets saved and sent to party members). */
	final Map<Long, Integer> edits = new HashMap<>();

	abstract String id();

	abstract int get(int x, int y);

	/** Sets a block and remembers it as an edit. */
	abstract void set(int x, int y, int id);

	abstract int height();

	/** False for dungeons: no sky light, no daylight. */
	abstract boolean outdoors();

	/** The back wall behind a non-solid tile at (x, y), or 0 for open sky. */
	abstract int wallAt(int x, int y);

	/** A decoration drawn at an empty tile (see {@link Items#decor}), or -1. */
	int decorAt(int x, int y) {
		return -1;
	}

	/** Light that is always there (0..1): caves are dark, dungeons are dim. */
	double ambient() {
		return 0.07;
	}

	static long key(int x, int y) {
		return ((long) x << 32) | (y & 0xFFFFFFFFL);
	}

	static int keyX(long key) {
		return (int) (key >> 32);
	}

	static int keyY(long key) {
		return (int) key;
	}
}
