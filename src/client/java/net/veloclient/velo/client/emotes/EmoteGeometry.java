package net.veloclient.velo.client.emotes;

/**
 * The player model's six boxes posed by an emote frame, in model pixels (y down, facing -Z, the player's right is
 * -X; a part's rotation applies Z * Y * X around its pivot - like ModelPart). Gives the anchor points for an emote's
 * particles (a hand, the head...) and the corners for the small 2D emote figures on Store and Locker cards.
 */
public final class EmoteGeometry {

	/** Box offset from its pivot and size, per part (head, body, right arm, left arm, right leg, left leg). */
	private static final float[][] BOXES = {
			{-4, -8, -4, 8, 8, 8}, {-4, 0, -2, 8, 12, 4}, {-3, -2, -2, 4, 12, 4}, {-1, -2, -2, 4, 12, 4}, {-2, 0, -2, 4, 12, 4},
			{-2, 0, -2, 4, 12, 4}};

	private EmoteGeometry() {
	}

	/** Point {@code (lx, ly, lz)} of part {@code part} (in its own frame) into model space; {@code out} gets x, y, z. */
	public static void transform(float[] pose, int part, float lx, float ly, float lz, float[] out) {
		int o = part * 6;
		float xr = pose[o + 3];
		float yr = pose[o + 4];
		float zr = pose[o + 5];
		// Rx
		float cy = (float) Math.cos(xr);
		float sy = (float) Math.sin(xr);
		float y1 = ly * cy - lz * sy;
		float z1 = ly * sy + lz * cy;
		float x1 = lx;
		// Ry
		float c = (float) Math.cos(yr);
		float s = (float) Math.sin(yr);
		float x2 = x1 * c + z1 * s;
		float z2 = -x1 * s + z1 * c;
		float y2 = y1;
		// Rz
		c = (float) Math.cos(zr);
		s = (float) Math.sin(zr);
		out[0] = x2 * c - y2 * s + pose[o];
		out[1] = x2 * s + y2 * c + pose[o + 1];
		out[2] = z2 + pose[o + 2];
	}

	/**
	 * Where an event's particles come from, in model pixels: "head" (just above it), "chest", "feet", "rhand" /
	 * "lhand" (the end of that arm) or "ring" (around the waist - the caller spreads them in a circle).
	 */
	public static void anchor(float[] pose, String at, float[] out) {
		switch (at == null ? "chest" : at) {
			case "head" -> transform(pose, 0, 0, -10, 0, out);
			case "rhand" -> transform(pose, 2, -1, 10, 0, out);
			case "lhand" -> transform(pose, 3, 1, 10, 0, out);
			case "feet" -> {
				out[0] = (pose[4 * 6] + pose[5 * 6]) / 2f;
				out[1] = 23f;
				out[2] = (pose[4 * 6 + 2] + pose[5 * 6 + 2]) / 2f;
			}
			case "ring" -> transform(pose, 1, 0, 10, 0, out);
			default -> transform(pose, 1, 0, 4, 0, out);
		}
	}

	/** The eight corners of a part's box (model space), as 24 floats. */
	public static float[] corners(float[] pose, int part) {
		float[] b = BOXES[part];
		float[] out = new float[24];
		float[] p = new float[3];
		int k = 0;
		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 2; j++) {
				for (int l = 0; l < 2; l++) {
					transform(pose, part, b[0] + b[3] * i, b[1] + b[4] * j, b[2] + b[5] * l, p);
					out[k++] = p[0];
					out[k++] = p[1];
					out[k++] = p[2];
				}
			}
		}
		return out;
	}

	/** Corner indices of the six faces of a box from {@link #corners}. */
	public static final int[][] FACES = {{0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};

	/** The standing pose (an emote's blend target outside any emote). */
	public static float[] restPose() {
		return new float[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -5, 2, 0, 0, 0, 0, 5, 2, 0, 0, 0, 0, -1.9f, 12, 0, 0, 0, 0, 1.9f, 12, 0, 0, 0, 0};
	}
}
