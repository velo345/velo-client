package net.veloclient.velo.client.devtools;

/**
 * Dev probe ({@code -Dvelo.quadProbe=true}, see TurboEntityQuadProbeMixin): per real frame, item submits and quads by
 * display context, cutout / translucent split, render-type switches inside a submit (each one is a new draw in
 * vanilla's feature renderer), and whether a submit's first quad was also drawn last frame (cache-key stability).
 */
public final class QuadProbe {

	public static final boolean ON = Boolean.getBoolean("velo.quadProbe");
	private static final java.util.Map<String, long[]> STATS = new java.util.TreeMap<>();
	private static java.util.Set<Object> last = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private static java.util.Set<Object> now = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private static long frames;
	private static long lastLog;

	private QuadProbe() {
	}

	public static void frame() {
		if (!ON) {
			return;
		}
		frames++;
		var swap = last;
		last = now;
		now = swap;
		now.clear();
		long t = System.nanoTime();
		if (t - lastLog > 5_000_000_000L) {
			lastLog = t;
			StringBuilder sb = new StringBuilder("Velo quad probe per frame:");
			for (var e : STATS.entrySet()) {
				long[] v = e.getValue();
				double f = Math.max(1, frames);
				sb.append(String.format(java.util.Locale.ROOT, " | %s %.1f submits %.0f quads (cutout %.0f transl %.0f) switches %.1f glint %.1f, first quad seen last frame %.0f%%",
						e.getKey(), v[0] / f, v[1] / f, v[2] / f, v[3] / f, v[4] / f, v[5] / f, 100.0 * v[6] / Math.max(1, v[0])));
			}
			net.veloclient.velo.VeloClient.LOGGER.info(sb.toString());
			STATS.clear();
			frames = 0;
		}
	}

	public static void submit(String context, java.util.List<?> quads, boolean[] blendedPerQuad, boolean glint) {
		long[] v = STATS.computeIfAbsent(context, k -> new long[7]);
		v[0]++;
		v[1] += quads.size();
		int switches = 0;
		for (int i = 0; i < blendedPerQuad.length; i++) {
			if (blendedPerQuad[i]) {
				v[3]++;
			} else {
				v[2]++;
			}
			if (i > 0 && blendedPerQuad[i] != blendedPerQuad[i - 1]) {
				switches++;
			}
		}
		v[4] += switches;
		if (glint) {
			v[5]++;
		}
		if (!quads.isEmpty()) {
			Object first = quads.get(0);
			if (last.contains(first)) {
				v[6]++;
			}
			now.add(first);
		}
	}
}
