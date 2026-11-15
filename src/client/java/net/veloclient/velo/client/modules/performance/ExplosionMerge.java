package net.veloclient.velo.client.modules.performance;

/**
 * TNT Optimizer explosion merge (TntOptimizerModule, default off): every explosion packet spawns a full smoke cloud of its own (an emitter that keeps adding
 * particles for 8 ticks plus 26.x's explosion block effects) and plays its sound. In TNT dupers, cannons and farms dozens
 * of TNT go off in the same few blocks every second; their clouds overlap into one, but each still costs its own
 * particles - profiled 2026-11-11 with ~80 TNT a second: 96% of the render thread was explosion particles (654 FPS).
 *
 * <p>Merging only starts once a spot is clearly a farm or cannon: at least {@link #MIN_RATE} explosions within
 * {@link #RADIUS} blocks during the last {@link #RATE_TICKS} ticks. Below that every explosion keeps its full effect -
 * two end crystals popping together in a PvP fight, a lone TNT or a creeper never lose a particle or a sound. Past the
 * threshold an explosion joins the cloud of one that got its full effect less than {@link #WINDOW_TICKS} ticks ago (no
 * second emitter, effect set or sound), and the spot is refreshed every {@link #WINDOW_TICKS} ticks so it never fades.
 * Knockback and every block change are untouched - only purely visual duplicates go. Off with -Dvelo.explosionMerge=false.
 */
public final class ExplosionMerge {

	public static final boolean ON = !"false".equals(System.getProperty("velo.explosionMerge"));
	private static final double RADIUS = 4.0;
	private static final long RATE_TICKS = 20;
	private static final int MIN_RATE = 6;
	private static final long WINDOW_TICKS = 8;
	private static final int MAX = 128;
	private static final double[] X = new double[MAX], Y = new double[MAX], Z = new double[MAX];
	private static final long[] AT = new long[MAX];
	/** Whether that explosion got its full effect (it's the cloud later ones join). */
	private static final boolean[] FULL = new boolean[MAX];
	private static int next;
	/** Explosions merged / kept since start (dev stats). */
	public static long merged, kept;

	private ExplosionMerge() {
	}

	/** True = this explosion's visuals join a recent cloud; false = it gets its full effect. */
	public static boolean merge(double x, double y, double z, long gameTime) {
		if (!ON || !TntOptimizerModule.mergeExplosions()) {
			return false;
		}
		double r2 = RADIUS * RADIUS;
		int recent = 0;
		boolean cloudNearby = false;
		for (int i = 0; i < MAX; i++) {
			long age = gameTime - AT[i];
			if (AT[i] == 0 || age < 0 || age >= RATE_TICKS) {
				continue;
			}
			double dx = X[i] - x, dy = Y[i] - y, dz = Z[i] - z;
			if (dx * dx + dy * dy + dz * dz > r2) {
				continue;
			}
			recent++;
			if (FULL[i] && age < WINDOW_TICKS) {
				cloudNearby = true;
			}
		}
		boolean merge = recent >= MIN_RATE - 1 && cloudNearby;
		X[next] = x;
		Y[next] = y;
		Z[next] = z;
		AT[next] = gameTime == 0 ? 1 : gameTime;
		FULL[next] = !merge;
		next = (next + 1) % MAX;
		if (merge) {
			merged++;
		} else {
			kept++;
		}
		return merge;
	}
}
