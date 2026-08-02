package net.veloclient.velo.client.cosmetics.physics;

import net.veloclient.velo.client.cosmetics.CapePhysicsPreset;
import org.joml.Vector3f;

/**
 * Verlet-integration cloth simulation for a single vertical chain of points
 * (design spec section 6.5). Point 0 is pinned to the wearer's back; each
 * subsequent point is free, connected to its neighbor by a distance
 * constraint, and pushed by gravity and a wind vector supplied by the caller.
 *
 * <p>This is intentionally a 1D chain (not a full 2D cloth grid) - it's
 * enough to drive a physically-simulated sway/droop angle per cape segment.
 * A full width-wise grid (for cloth that also billows sideways) is a natural
 * follow-up once this chain is proven out in-game.
 */
public final class ClothSimulator {

	private final Vector3f[] current;
	private final Vector3f[] previous;
	private final float segmentLength;
	private CapePhysicsPreset preset;

	public ClothSimulator(CapePhysicsPreset preset, float segmentLength) {
		this.preset = preset;
		this.segmentLength = segmentLength;
		int pointCount = preset.segments() + 1;
		this.current = new Vector3f[pointCount];
		this.previous = new Vector3f[pointCount];
		for (int i = 0; i < pointCount; i++) {
			current[i] = new Vector3f(0, -i * segmentLength, 0);
			previous[i] = new Vector3f(current[i]);
		}
	}

	public void setPreset(CapePhysicsPreset preset) {
		this.preset = preset;
	}

	/**
     * Advances the simulation by one step.
     *
     * @param pinPosition world-space (or entity-local-space) position of the pin point (point 0)
     * @param windAndMotion combined wind + "opposing player velocity" force for this step
     * @param deltaSeconds time step, typically one client tick's worth
     */
	public void step(Vector3f pinPosition, Vector3f windAndMotion, float deltaSeconds) {
		current[0].set(pinPosition);
		previous[0].set(pinPosition);

		Vector3f gravity = new Vector3f(0, -9.8f * preset.gravityMultiplier() * deltaSeconds * deltaSeconds, 0);
		Vector3f wind = new Vector3f(windAndMotion).mul(preset.windResponsiveness() * deltaSeconds * deltaSeconds);

		for (int i = 1; i < current.length; i++) {
			Vector3f velocity = new Vector3f(current[i]).sub(previous[i]).mul(preset.damping());
			Vector3f next = new Vector3f(current[i]).add(velocity).add(gravity).add(wind);
			previous[i].set(current[i]);
			current[i].set(next);
		}

		int iterations = 1 + Math.round(preset.stiffness() * 4);
		for (int iter = 0; iter < iterations; iter++) {
			for (int i = 0; i < current.length - 1; i++) {
				satisfyDistanceConstraint(i, i + 1);
			}
		}
	}

	private void satisfyDistanceConstraint(int a, int b) {
		Vector3f delta = new Vector3f(current[b]).sub(current[a]);
		float distance = delta.length();
		if (distance < 1.0e-5f) {
			return;
		}
		float diff = (distance - segmentLength) / distance;
		Vector3f correction = new Vector3f(delta).mul(0.5f * diff);
		if (a > 0) {
			current[a].add(correction);
		} else {
			correction.mul(2f);
		}
		current[b].sub(correction);
	}

	/** Point positions in the simulator's local space, index 0 = pinned point. */
	public Vector3f[] points() {
		return current;
	}

	/**
	 * The angle (radians) between the rest-pose direction (straight down) and
	 * the direction from point 0 to point 1 - a single number a simple
	 * rigid-segment renderer can use to approximate the chain's overall droop
	 * until a full per-segment mesh renderer is built.
	 */
	public float firstSegmentPitchRadians() {
		Vector3f direction = new Vector3f(current[1]).sub(current[0]);
		if (direction.lengthSquared() < 1.0e-6f) {
			return 0f;
		}
		direction.normalize();
		return (float) Math.atan2(direction.z, -direction.y);
	}

	public float firstSegmentRollRadians() {
		Vector3f direction = new Vector3f(current[1]).sub(current[0]);
		if (direction.lengthSquared() < 1.0e-6f) {
			return 0f;
		}
		direction.normalize();
		return (float) Math.atan2(direction.x, -direction.y);
	}
}
