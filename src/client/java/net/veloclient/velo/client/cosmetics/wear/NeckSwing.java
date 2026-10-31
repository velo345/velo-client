package net.veloclient.velo.client.cosmetics.wear;

import java.util.HashMap;
import java.util.Map;

/**
 * The little swing of a worn chain: a damped spring per player, nudged by how they speed up, stop, turn, jump and land,
 * plus a soft sway while walking. Only away from the chest (it can't swing into the body) and never more than a few
 * degrees - it should feel like a real chain, not wobble about.
 */
public final class NeckSwing {

	private static final float STIFFNESS = 70f;
	private static final float DAMPING = 7f;
	/** Most it swings out from the chest / to a side, radians. */
	private static final float MAX_OUT = 0.3f;
	private static final float MAX_SIDE = 0.2f;

	private static final class State {
		double x;
		double y;
		double z;
		float yaw;
		float vFwd;
		float vSide;
		float vUp;
		float yawRate;
		float out;
		float outVel;
		float side;
		float sideVel;
		/** Walking: the step cycle, advanced by the distance covered. */
		float stride;
		long nanos;
	}

	private static final Map<Integer, State> STATES = new HashMap<>();

	private NeckSwing() {
	}

	/**
	 * Steps player {@code id}'s spring to now and returns {out, side} in radians: how far the chain hangs out in front
	 * (always >= 0) and to the player's left (+) or right (-).
	 */
	public static synchronized float[] update(int id, double x, double y, double z, float bodyYawDegrees) {
		long now = System.nanoTime();
		State s = STATES.get(id);
		if (s == null) {
			if (STATES.size() > 64) {
				STATES.values().removeIf(old -> now - old.nanos > 5_000_000_000L);
			}
			s = new State();
			s.x = x;
			s.y = y;
			s.z = z;
			s.yaw = bodyYawDegrees;
			s.nanos = now;
			STATES.put(id, s);
		}
		float dt = (now - s.nanos) / 1e9f;
		if (dt > 0.002f) {
			// A long gap (out of sight, a teleport): start again calmly.
			if (dt > 0.25f) {
				s.out = s.outVel = s.side = s.sideVel = 0;
				s.vFwd = s.vSide = s.vUp = s.yawRate = 0;
			} else {
				float yaw = (float) Math.toRadians(bodyYawDegrees);
				float fx = (float) -Math.sin(yaw);
				float fz = (float) Math.cos(yaw);
				float vx = (float) (x - s.x) / dt;
				float vz = (float) (z - s.z) / dt;
				float vy = (float) (y - s.y) / dt;
				float dyaw = bodyYawDegrees - s.yaw;
				dyaw -= 360f * Math.round(dyaw / 360f);
				// smoothed velocities, so the frame-to-frame jitter of positions doesn't shake the chain
				float k = Math.min(1f, dt / 0.08f);
				float fwd = s.vFwd + ((vx * fx + vz * fz) - s.vFwd) * k;
				float side = s.vSide + ((vx * fz - vz * fx) - s.vSide) * k;
				float up = s.vUp + (vy - s.vUp) * k;
				float rate = s.yawRate + ((float) Math.toRadians(dyaw) / dt - s.yawRate) * k;
				float aFwd = clamp((fwd - s.vFwd) / dt, 40f);
				float aSide = clamp((side - s.vSide) / dt, 40f);
				float aUp = clamp((up - s.vUp) / dt, 120f);
				float aYaw = clamp((rate - s.yawRate) / dt, 80f);
				s.vFwd = fwd;
				s.stride += (float) Math.hypot(fwd, side) * dt * 2.2f;
				s.vSide = side;
				s.vUp = up;
				s.yawRate = rate;
				// Stopping throws it forwards, landing bounces it; turning and side steps swing it sideways.
				float pushOut = -aFwd * 0.9f + Math.abs(aUp) * 0.02f;
				float pushSide = aSide * 0.7f - aYaw * 0.18f;
				s.outVel += (-STIFFNESS * s.out - DAMPING * s.outVel + pushOut) * dt;
				s.out += s.outVel * dt;
				s.sideVel += (-STIFFNESS * s.side - DAMPING * s.sideVel + pushSide) * dt;
				s.side += s.sideVel * dt;
				if (s.out < 0) {
					// against the chest: it stops there
					s.out = 0;
					s.outVel = Math.max(0, s.outVel);
				}
				s.out = Math.min(MAX_OUT, s.out);
				s.side = Math.max(-MAX_SIDE, Math.min(MAX_SIDE, s.side));
			}
			s.x = x;
			s.y = y;
			s.z = z;
			s.yaw = bodyYawDegrees;
			s.nanos = now;
		}
		// Walking: a soft sway with the steps.
		float walk = Math.min(1f, (float) Math.hypot(s.vFwd, s.vSide) / 4.3f);
		float step = (float) Math.sin(s.stride * Math.PI);
		return new float[] {s.out + Math.abs(step) * walk * 0.035f, s.side + step * walk * 0.05f};
	}

	private static float clamp(float v, float limit) {
		return Math.max(-limit, Math.min(limit, v));
	}
}
