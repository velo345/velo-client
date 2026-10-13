package net.veloclient.velo.client.cosmetics.wings;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Procedural wing animation, per player: how far the wings are swept back, how fast and how wide
 * they flap and how high they're held follow what the player is doing - a slow "breathing" open and
 * close while standing, wider strokes the faster you walk or sprint, strong slow strokes in the air,
 * spread wide with a light flutter while gliding, folded back while sneaking, tucked in with small
 * strokes while swimming. Every value eases towards its target and the flap phase only ever moves
 * forward, so speed changes never make the wings jump; it runs per rendered frame, so it's smooth
 * at any frame rate. The outer part of each wing lags behind the inner part (it bends with the
 * flap), which is what makes them look soft instead of like two rotating boards.
 */
public final class WingAnimator {

	/** What the animation needs to know about a player this frame. */
	public record Motion(double horizontalSpeed, double verticalSpeed, boolean onGround, boolean gliding, boolean flying,
			boolean sneaking, boolean swimming) {
	}

	/** Where to put the hinges on the body (blocks, body space: y down, z backwards). */
	private static final float ATTACH_Y = 0.30f;
	private static final float ATTACH_Z = 0.17f;
	private static final float SHOULDER_X = 0.05f;

	private static final class State {
		double phase;
		float sweep = 0.35f;
		float amplitude = 0.1f;
		float frequency = 0.5f;
		float lift = 0.08f;
		long lastNanos;
		long lastSeen;
	}

	private static final Map<Integer, State> STATES = new HashMap<>();

	private WingAnimator() {
	}

	/** Current pose: sweep back (rad), lift of the tips (rad, positive = up) and the bend of the outer part (rad). */
	public record Pose(float sweep, float lift, float bend) {
	}

	/** Advances the animation of entity {@code id} to now and returns its pose. Render thread. */
	public static Pose pose(int id, Motion motion, WingsManager.Style style, WingsManager.Settings settings) {
		long now = System.nanoTime();
		State s = STATES.computeIfAbsent(id, k -> new State());
		float dt = s.lastNanos == 0 ? 0f : Math.min(0.1f, (now - s.lastNanos) / 1e9f);
		s.lastNanos = now;
		s.lastSeen = now;
		if (STATES.size() > 64) {
			cleanUp(now);
		}

		// Targets for this moment.
		double speed = Math.min(1.0, motion.horizontalSpeed() / 0.28);
		float sweep;
		float amplitude;
		float frequency;
		float lift;
		// The flap is mostly a slow opening and closing of the wings (the same motion as the Spread
		// setting), with only a little lift and bend - calm and majestic rather than fluttery.
		if (motion.swimming()) {
			// Tucked in, a small slow stroke.
			sweep = 1.0f;
			amplitude = 0.2f;
			frequency = 0.55f;
			lift = -0.12f;
		} else if (motion.gliding()) {
			// Spread wide and nearly still, a light flutter that quickens with speed.
			sweep = -0.08f;
			amplitude = 0.07f;
			frequency = 0.7f + (float) Math.min(1.0, motion.horizontalSpeed());
			lift = 0.06f;
		} else if (motion.sneaking()) {
			sweep = 0.95f;
			amplitude = 0.08f;
			frequency = 0.3f;
			lift = -0.2f;
		} else if (motion.flying()) {
			sweep = 0.1f;
			amplitude = (float) (0.5 + 0.2 * speed);
			frequency = (float) (0.65 + 0.45 * speed);
			lift = 0.1f;
		} else if (!motion.onGround()) {
			boolean fallingFast = motion.verticalSpeed() < -0.5;
			sweep = fallingFast ? -0.05f : 0.1f;
			amplitude = fallingFast ? 0.12f : 0.6f;
			frequency = fallingFast ? 1.4f : 1.05f;
			lift = fallingFast ? 0.3f : 0.12f;
		} else {
			// On the ground: a slow breath when still, wider and a bit quicker strokes when moving.
			sweep = (float) (0.45 - 0.15 * speed);
			amplitude = (float) (0.24 + 0.24 * speed);
			frequency = (float) (0.28 + 0.5 * speed);
			lift = 0.05f;
		}
		amplitude *= style.amplitude * settings.flapStrength();
		frequency *= style.speed * settings.flapSpeed();
		sweep += settings.spreadRadians();

		// Ease towards them (about a third of a second), the phase always moving forward.
		float k = 1f - (float) Math.exp(-dt / 0.35f);
		s.sweep += (sweep - s.sweep) * k;
		s.amplitude += (amplitude - s.amplitude) * k;
		s.frequency += (frequency - s.frequency) * k;
		s.lift += (lift - s.lift) * k;
		s.phase = (s.phase + dt * s.frequency * Math.PI * 2) % (Math.PI * 2);

		// One flap: open out past the rest pose, then sweep back behind it, easing at both ends.
		float flap = (float) (0.5 - 0.5 * Math.cos(s.phase));
		float flapSweep = s.sweep + s.amplitude * (flap - 0.35f);
		float flapLift = s.lift - s.amplitude * 0.12f * (float) Math.sin(s.phase);
		// The outer part trails the inner part a little: proportional to how fast the flap moves.
		float bend = -s.amplitude * 0.25f * style.flex * (float) Math.sin(s.phase);
		return new Pose(flapSweep, flapLift, bend);
	}

	private static void cleanUp(long now) {
		Iterator<State> it = STATES.values().iterator();
		while (it.hasNext()) {
			if (now - it.next().lastSeen > 10_000_000_000L) {
				it.remove();
			}
		}
	}

	/**
	 * The deformed wing geometry for a pose, in body space: per half, {x, y, z, nx, ny, nz} per
	 * vertex (same order as the model). Allocation-free when {@code out} is big enough.
	 */
	public static float[][] deform(WingModel model, Pose pose, WingsManager.Settings settings, float[][] out) {
		return deform(model, pose, settings, 0f, out);
	}

	/** {@code baseOffsetY}: the pair's own height adjustment (blocks, down), on top of the wearer's. */
	public static float[][] deform(WingModel model, Pose pose, WingsManager.Settings settings, float baseOffsetY, float[][] out) {
		float scale = settings.scale();
		float attachY = ATTACH_Y + baseOffsetY + settings.offsetY();
		float attachZ = ATTACH_Z + settings.offsetZ();
		if (out == null || out.length != model.halves.length) {
			out = new float[model.halves.length][];
		}
		for (int h = 0; h < model.halves.length; h++) {
			WingModel.Half half = model.halves[h];
			float[] src = half.geometry;
			float[] dst = out[h];
			if (dst == null || dst.length != src.length) {
				dst = new float[src.length];
				out[h] = dst;
			}
			int s = half.side;
			// Raising the tip means -y (body y points down): for the +x wing that's a negative turn
			// around z. Sweeping back moves the tip towards +z: for the +x wing a negative turn around y.
			float lift = -s * pose.lift();
			float cosL = (float) Math.cos(lift);
			float sinL = (float) Math.sin(lift);
			float hingeX = s * SHOULDER_X;
			int n = half.vertexCount();
			// The bend grows with the square of the distance from the shoulder; quantised into a few
			// bands so the trig isn't computed per vertex.
			float[] cosY = new float[9];
			float[] sinY = new float[9];
			for (int b = 0; b <= 8; b++) {
				float w = b / 8f;
				float yaw = -s * (pose.sweep() + pose.bend() * w * w);
				cosY[b] = (float) Math.cos(yaw);
				sinY[b] = (float) Math.sin(yaw);
			}
			for (int v = 0; v < n; v++) {
				int i = v * 6;
				int band = Math.round(half.weight[v] * 8);
				float cy = cosY[band];
				float sy = sinY[band];
				// Lift: turn around z (the axis pointing backwards).
				float sx = src[i] * scale;
				float sy0 = src[i + 1] * scale;
				float x = sx * cosL - sy0 * sinL;
				float y = sx * sinL + sy0 * cosL;
				float z = src[i + 2] * scale;
				float nx = src[i + 3] * cosL - src[i + 4] * sinL;
				float ny = src[i + 3] * sinL + src[i + 4] * cosL;
				float nz = src[i + 5];
				// Sweep: turn around y (vertical).
				dst[i] = x * cy + z * sy + hingeX;
				dst[i + 1] = y + attachY;
				dst[i + 2] = -x * sy + z * cy + attachZ;
				dst[i + 3] = nx * cy + nz * sy;
				dst[i + 4] = ny;
				dst[i + 5] = -nx * sy + nz * cy;
			}
		}
		return out;
	}
}
