package net.veloclient.velo.client.cosmetics.physics;

import net.veloclient.velo.client.cosmetics.CapePhysicsPreset;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * A cloak as real cloth: a 2D grid of particles (Verlet, position-based constraints) hanging from the wearer's
 * shoulders, simulated as if in the world - so it keeps its momentum when the wearer runs, jumps, turns or stops -
 * but stored in the wearer's body space, where it's drawn.
 *
 * <p>What moves it, beyond gravity: air. Every particle feels drag against its surface normal from its velocity
 * relative to the air (that's what lifts the cloak behind a sprinting player and makes it billow sideways when
 * strafing), a slowly turning breeze with gusts, and a flutter that grows with airspeed and toward the hem. It
 * collides with the back and with the legs as they swing, and bends (not just hinges) along both axes.
 *
 * <p>Body space is the player model's body part: blocks, x across the back, y down, z out of the back. Each frame
 * the caller passes where that space is in the world ({@link #frame}); the particles are re-expressed in the new
 * space so that, in the world, they stayed put - that is all inertia needs.
 */
public final class ClothCape {

	/** Particles across and down (8 x 14 cells over the 10 x 16 pixel cloak). */
	public static final int COLS = 9;
	public static final int ROWS = 15;
	public static final float WIDTH = 10 / 16f;
	public static final float LENGTH = 16 / 16f;
	/** How far off the back the cloak hangs (vanilla: 2 px body half-depth). */
	public static final float BACK = 2.15f / 16f;

	private static final float STEP = 1 / 120f;
	private static final int ITERATIONS = 4;
	/**
	 * How much of the wearer's turning the cloth resists (the rest it simply turns with). The cause of the wild
	 * shaking: with full inertia every mouse flick left the cloth standing in the world and the body spun under it -
	 * in PvP that's many flicks a second, whipping the cloak around the head. Real cloaks are tied at the neck and
	 * shoulders and turn with you; a little lag keeps the swing.
	 */
	private static final float TURN_INERTIA = 0.25f;
	/**
	 * Damping (per second) of the cloth's motion relative to the wearer - fabric tied to a body, and the air around
	 * the body moving along with it. Swinging back and forth dies out quickly; being lifted by running or falling
	 * (motion through the world) isn't damped by this at all.
	 */
	private static final float REL_DAMP = 8f;
	/** How much of the wearer's vertical motion the cloth simply follows instead of lagging behind (0 = all inertia). */
	private static final float VERTICAL_FOLLOW = 0.65f;

	private final float[] px = new float[COLS * ROWS], py = new float[COLS * ROWS], pz = new float[COLS * ROWS];
	private final float[] ox = new float[COLS * ROWS], oy = new float[COLS * ROWS], oz = new float[COLS * ROWS];
	private final float[] nx = new float[COLS * ROWS], ny = new float[COLS * ROWS], nz = new float[COLS * ROWS];
	/** Each particle's velocity if it simply moved with the body (body space, m/s) - see {@link #REL_DAMP}. */
	private final float[] fx = new float[COLS * ROWS], fy = new float[COLS * ROWS], fz = new float[COLS * ROWS];
	private long lastFrameNanos;
	private final Vector3f scratchGravity = new Vector3f(), scratchWind = new Vector3f();
	/** What {@link #snapshot} hands out - reused: the draw that reads it runs before the next update of this cloth. */
	private final float[] shape = new float[COLS * ROWS * 6];
	private final float dx = WIDTH / (COLS - 1), dy = LENGTH / (ROWS - 1);
	private final float diag = (float) Math.sqrt(dx * dx + dy * dy);
	private final int seed;

	// The frame of the last update: world position of body-space origin and world-from-body rotation/scale.
	private final Matrix3f worldFromBody = new Matrix3f();
	private final Matrix3f bodyFromWorld = new Matrix3f();
	private double ax, ay, az;
	private boolean framed;
	private long lastNanos;
	private float time;
	/** The previous step's length: Verlet's implied velocity is per that step, so a different next step rescales it. */
	private float lastStep = STEP;

	/** Whether this cloth has a shape yet (it was framed at least once). */
	public boolean ready() {
		return framed;
	}

	/** Last body yaw this cloak was drawn with (picks the right cloak when one player is drawn twice a frame). */
	public float lastYaw;
	public long lastUsedNanos;

	public ClothCape(int seed) {
		this.seed = seed;
		rest();
	}

	/** Hangs straight down, slightly leaning out. */
	private void rest() {
		for (int r = 0; r < ROWS; r++) {
			for (int c = 0; c < COLS; c++) {
				int i = r * COLS + c;
				px[i] = ox[i] = -WIDTH / 2 + c * dx;
				py[i] = oy[i] = r * dy * 0.99f;
				pz[i] = oz[i] = BACK + r * dy * 0.12f;
			}
		}
		normals();
	}

	/**
	 * Moves the body space to where it is now. {@code worldFromBody} maps body-space vectors (blocks) to the world;
	 * {@code ox/oy/oz} is where the body-space origin is in the world. A teleport (or a long pause) resets the cloak.
	 */
	public void frame(Matrix3f worldFromBody, double originX, double originY, double originZ) {
		long now = System.nanoTime();
		double jump = (originX - ax) * (originX - ax) + (originY - ay) * (originY - ay) + (originZ - az) * (originZ - az);
		if (framed && (jump > 16 || now - lastNanos > 1_000_000_000L)) {
			framed = false;
		}
		Matrix3f newBodyFromWorld = new Matrix3f(worldFromBody).invert();
		if (framed) {
			// Particle -> world (old frame) -> body (new frame), for both positions, so they rest in the world.
			Vector3f v = new Vector3f();
			float tx = (float) (ax - originX), ty = (float) (ay - originY), tz = (float) (az - originZ);
			Matrix3f m = new Matrix3f(newBodyFromWorld).mul(this.worldFromBody);
			// Only part of the turn reaches the cloth (see TURN_INERTIA): blend the frame's rotation towards none.
			org.joml.Quaternionf turn = m.getNormalizedRotation(new org.joml.Quaternionf());
			new org.joml.Quaternionf().slerp(turn, TURN_INERTIA).get(m);
			Vector3f t = newBodyFromWorld.transform(new Vector3f(tx, ty, tz));
			Vector3f follow = newBodyFromWorld.transform(new Vector3f(0, (float) (originY - ay) * VERTICAL_FOLLOW, 0));
			float frameDt = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1e9f;
			for (int i = COLS; i < px.length; i++) {
				float bx = px[i], by = py[i], bz = pz[i];
				m.transform(v.set(px[i], py[i], pz[i])).add(t);
				px[i] = v.x;
				py[i] = v.y;
				pz[i] = v.z;
				m.transform(v.set(ox[i], oy[i], oz[i])).add(t);
				ox[i] = v.x;
				oy[i] = v.y;
				oz[i] = v.z;
				// Up and down the cloak mostly just comes along: full inertia there swings it hard on every jump.
				px[i] += follow.x;
				py[i] += follow.y;
				pz[i] += follow.z;
				ox[i] += follow.x;
				oy[i] += follow.y;
				oz[i] += follow.z;
				// A point riding along with the body was shifted by this much in body space: moving with the body is
				// the opposite velocity.
				if (frameDt > 0.0005f && frameDt < 0.25f) {
					fx[i] = (bx - px[i]) / frameDt;
					fy[i] = (by - py[i]) / frameDt;
					fz[i] = (bz - pz[i]) / frameDt;
				}
			}
		} else {
			rest();
			lastNanos = now;
		}
		lastFrameNanos = now;
		this.worldFromBody.set(worldFromBody);
		this.bodyFromWorld.set(newBodyFromWorld);
		ax = originX;
		ay = originY;
		az = originZ;
		framed = true;
	}

	/**
	 * Runs the simulation up to now. {@code legBack} is how far (radians) the rear leg swings back, for the collision
	 * with the legs; {@code still} turns off wind (menus, previews).
	 */
	public void simulate(CapePhysicsPreset preset, float legBack, boolean still, boolean far) {
		long now = System.nanoTime();
		lastUsedNanos = now;
		float elapsed = Math.min(0.1f, (now - lastNanos) / 1e9f);
		if (elapsed < 0.0015f) {
			// A second draw in the same frame (a shadow pass): nothing to step - the time is kept for the next one.
			// Stepping it anyway as a sliver of a step threw away the cloth's speed, a different damping per frame rate.
			return;
		}
		lastNanos = now;
		// Steps follow the frame time (smooth at any frame rate); integrate() corrects for unequal steps. Far away
		// players take half as many, with fewer passes - nobody sees the difference at that distance.
		float target = far ? STEP * 2 : STEP;
		int iterations = far ? 2 : ITERATIONS;
		int steps = Math.max(1, Math.min(10, Math.round(elapsed / target)));
		float h = elapsed / steps;

		float gravityScale = preset.gravityMultiplier();
		float air = 0.5f + preset.windResponsiveness();
		float bend = 0.4f + 0.5f * preset.stiffness();
		float drag = 0.5f + 1.4f * (1 - preset.damping());
		Vector3f gravity = bodyFromWorld.transform(scratchGravity.set(0, -9.8f * gravityScale, 0));
		// The breeze changes over seconds: once per frame is plenty.
		Vector3f wind = still ? scratchWind.set(0, 0, 0) : bodyFromWorld.transform(windAt(time + elapsed, scratchWind));
		for (int s = 0; s < steps; s++) {
			time += h;
			integrate(h, gravity, wind, air, drag);
			for (int it = 0; it < iterations; it++) {
				constrain(bend);
				collide(legBack);
			}
		}
		normals();
	}

	/** The breeze in the world: a slowly turning base wind with gusts (m/s). */
	private Vector3f windAt(float t, Vector3f out) {
		float k = seed * 0.37f;
		float heading = t * 0.07f + k;
		float gust = 0.55f + 0.45f * (float) (Math.sin(t * 0.9f + k) * Math.sin(t * 0.37f + 2 * k));
		float strength = 0.5f * gust * gust;
		return out.set((float) Math.cos(heading) * strength, 0.15f * (float) Math.sin(t * 1.7f + k), (float) Math.sin(heading) * strength);
	}

	private void integrate(float h, Vector3f gravity, Vector3f wind, float air, float drag) {
		float h2 = h * h;
		// Time-corrected Verlet: (x - x_old) moved over the previous step; scaled to this step it's the same velocity.
		float carry = Math.min(2f, h / lastStep) * 0.992f;
		float prevStep = lastStep;
		lastStep = h;
		for (int r = 1; r < ROWS; r++) {
			float hem = r / (float) (ROWS - 1);
			for (int c = 0; c < COLS; c++) {
				int i = r * COLS + c;
				float vx = (px[i] - ox[i]) / prevStep, vy = (py[i] - oy[i]) / prevStep, vz = (pz[i] - oz[i]) / prevStep;
				// Velocity relative to the air (in body space, after the frame update that's world velocity).
				// Up and down counts half: the cloth already follows most of a jump (VERTICAL_FOLLOW).
				float rx = vx - wind.x, ry = (vy - wind.y) * 0.5f, rz = vz - wind.z;
				float speed = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
				float n = rx * nx[i] + ry * ny[i] + rz * nz[i];
				// Pressure drag against the surface, growing with the square of the airspeed like real air (what makes
				// cloth fly: ~35 deg walking, ~45 sprinting, ~65 flying), plus a little skin friction.
				// Saturating: past ~6 m/s through the cloth the pressure stops growing, so falling or sprint-jumping into
				// a turn can't turn the cloak into a sail that flips over.
				float k = air * (0.42f * Math.min(Math.abs(n), 6f) + 0.05f);
				float axx = gravity.x - k * n * nx[i] - drag * rx - REL_DAMP * (vx - fx[i]);
				float ayy = gravity.y - k * n * ny[i] - drag * ry - REL_DAMP * (vy - fy[i]);
				float azz = gravity.z - k * n * nz[i] - drag * rz - REL_DAMP * (vz - fz[i]);
				// Flutter: travelling ripples, stronger with airspeed and toward the hem, along the normal.
				// Kept gentle: real thin cloth flaps much harder, but that reads as jitter on a player model.
				float flutter = air * Math.min(speed, 7f) * hem * hem * 0.35f
						* (float) (Math.sin(time * 9f - r * 0.7f + c * 0.35f + seed) + 0.4 * Math.sin(time * 14f - r * 1.1f - c * 0.6f));
				axx += flutter * nx[i];
				ayy += flutter * ny[i];
				azz += flutter * nz[i];
				float x = px[i], y = py[i], z = pz[i];
				px[i] += (px[i] - ox[i]) * carry + axx * h2;
				py[i] += (py[i] - oy[i]) * carry + ayy * h2;
				pz[i] += (pz[i] - oz[i]) * carry + azz * h2;
				ox[i] = x;
				oy[i] = y;
				oz[i] = z;
			}
		}
		// The top edge is sewn onto the shoulders.
		for (int c = 0; c < COLS; c++) {
			px[c] = ox[c] = -WIDTH / 2 + c * dx;
			py[c] = oy[c] = 0;
			pz[c] = oz[c] = BACK;
		}
	}

	private void constrain(float bend) {
		for (int r = 0; r < ROWS; r++) {
			for (int c = 0; c < COLS; c++) {
				int i = r * COLS + c;
				if (c + 1 < COLS) {
					link(i, i + 1, dx, 1f);
				}
				if (r + 1 < ROWS) {
					link(i, i + COLS, dy, 1f);
					if (c + 1 < COLS) {
						link(i, i + COLS + 1, diag, 0.6f);
						link(i + 1, i + COLS, diag, 0.6f);
					}
				}
				if (c + 2 < COLS) {
					link(i, i + 2, 2 * dx, bend);
				}
				if (r + 2 < ROWS) {
					link(i, i + 2 * COLS, 2 * dy, bend);
				}
			}
		}
	}

	private void link(int a, int b, float rest, float stiffness) {
		float ex = px[b] - px[a], ey = py[b] - py[a], ez = pz[b] - pz[a];
		float d = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
		if (d < 1e-6f) {
			return;
		}
		float diff = stiffness * (d - rest) / d;
		boolean pinA = a < COLS, pinB = b < COLS;
		if (pinA && pinB) {
			return;
		}
		float wa = pinA ? 0 : pinB ? 1 : 0.5f;
		float wb = pinB ? 0 : pinA ? 1 : 0.5f;
		px[a] += ex * diff * wa;
		py[a] += ey * diff * wa;
		pz[a] += ez * diff * wa;
		px[b] -= ex * diff * wb;
		py[b] -= ey * diff * wb;
		pz[b] -= ez * diff * wb;
	}

	/**
	 * Keeps the cloak out of the body: the torso (0..12 px down) and the legs (12..24 px, the rear leg tilting the back
	 * wall as it swings) as a box - back, sides and front. A particle inside is pushed out through the nearest face, so
	 * a cloak blown sideways or round the front wraps around the body instead of going through it.
	 */
	private void collide(float legBack) {
		float lean = (float) Math.sin(Math.max(0, legBack));
		float halfW = 4.4f / 16, front = -2.3f / 16;
		for (int i = COLS; i < px.length; i++) {
			float y = py[i];
			if (y < 0 || y > 24 / 16f || Math.abs(px[i]) >= halfW) {
				continue;
			}
			float back = y <= 12 / 16f ? BACK : BACK + lean * (y - 12 / 16f);
			float z = pz[i];
			if (z >= back || z <= front) {
				continue;
			}
			// Inside: out through the closest face.
			float toBack = back - z, toFront = z - front, toSide = halfW - Math.abs(px[i]);
			if (toBack <= toFront && toBack <= toSide) {
				pz[i] = back;
			} else if (toSide <= toFront) {
				px[i] = Math.copySign(halfW, px[i]);
			} else {
				pz[i] = front;
			}
			// Friction against the body.
			ox[i] += (px[i] - ox[i]) * 0.3f;
			oy[i] += (py[i] - oy[i]) * 0.3f;
			oz[i] += (pz[i] - oz[i]) * 0.3f;
		}
	}

	private void normals() {
		for (int r = 0; r < ROWS; r++) {
			for (int c = 0; c < COLS; c++) {
				int i = r * COLS + c;
				int l = r * COLS + Math.max(0, c - 1), rr = r * COLS + Math.min(COLS - 1, c + 1);
				int u = Math.max(0, r - 1) * COLS + c, d = Math.min(ROWS - 1, r + 1) * COLS + c;
				// across (x) cross down (y) = out of the back (z).
				float ux = px[rr] - px[l], uy = py[rr] - py[l], uz = pz[rr] - pz[l];
				float vx = px[d] - px[u], vy = py[d] - py[u], vz = pz[d] - pz[u];
				float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
				float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
				if (len < 1e-7f) {
					nx[i] = 0;
					ny[i] = 0;
					nz[i] = 1;
				} else {
					nx[i] = cx / len;
					ny[i] = cy / len;
					nz[i] = cz / len;
				}
			}
		}
	}

	/**
	 * Snapshot for drawing: per particle x, y, z, nx, ny, nz. One array per cloth, reused: the draw it's submitted to
	 * runs within the same frame, before this cloth moves again.
	 */
	public float[] snapshot() {
		float[] out = shape;
		for (int i = 0; i < px.length; i++) {
			out[i * 6] = px[i];
			out[i * 6 + 1] = py[i];
			out[i * 6 + 2] = pz[i];
			out[i * 6 + 3] = nx[i];
			out[i * 6 + 4] = ny[i];
			out[i * 6 + 5] = nz[i];
		}
		return out;
	}
}
