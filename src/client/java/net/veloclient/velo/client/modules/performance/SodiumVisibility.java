package net.veloclient.velo.client.modules.performance;

/**
 * Sodium's per-frame section visibility (frustum + occlusion), for Velo Turbo culling. Only touched from mixins that
 * VeloMixinPlugin applies when Sodium is installed, so the Sodium classes are always there when this loads.
 */
public final class SodiumVisibility {

	private SodiumVisibility() {
	}

	// Sodium's tree lookup costs ~1 us (it cost 8% of the frame when asked per particle). Particles cluster in a few
	// sections, so answers are kept per section in a small direct-mapped cache that every new pass invalidates.
	private static final int SLOTS = 512;
	private static final long[] KEYS = new long[SLOTS];
	private static final int[] STAMPS = new int[SLOTS];
	private static final boolean[] VISIBLE = new boolean[SLOTS];
	private static int stamp = 1;

	/** Forget cached answers (call once per extraction pass - visibility changes every frame). */
	public static void newPass() {
		stamp++;
	}

	/** False only when Sodium culled the chunk section this point lies in this frame. Render thread only. */
	public static boolean pointVisible(double x, double y, double z) {
		int sx = (int) Math.floor(x) >> 4;
		int sy = (int) Math.floor(y) >> 4;
		int sz = (int) Math.floor(z) >> 4;
		long key = ((long) sx & 0x3FFFFF) << 42 | ((long) sy & 0xFFFFF) << 22 | ((long) sz & 0x3FFFFF);
		int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 55) & (SLOTS - 1);
		if (STAMPS[slot] == stamp && KEYS[slot] == key) {
			return VISIBLE[slot];
		}
		boolean visible = sectionVisible(sx, sy, sz);
		KEYS[slot] = key;
		STAMPS[slot] = stamp;
		VISIBLE[slot] = visible;
		return visible;
	}

	private static boolean sectionVisible(int sx, int sy, int sz) {
		//? if >=26.2 {
		/*var renderer = net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable();
		if (renderer == null) {
			return true;
		}
		try {
			// a hair inside the section, so neighbours sharing the face don't count
			double x0 = sx * 16.0;
			double y0 = sy * 16.0;
			double z0 = sz * 16.0;
			return renderer.isBoxVisible(x0 + 0.01, y0 + 0.01, z0 + 0.01, x0 + 15.99, y0 + 15.99, z0 + 15.99);
		} catch (RuntimeException e) {
			return true;
		}
		*///?} else {
		return true;
		//?}
	}
}
