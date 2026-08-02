package net.veloclient.velo.client.cosmetics;

/**
 * Per-cape cloth simulation parameters (design spec section 6.5): stiffness,
 * gravity multiplier, damping, wind responsiveness, and segment resolution
 * (perf vs fidelity slider).
 */
public record CapePhysicsPreset(
		float stiffness,
		float gravityMultiplier,
		float damping,
		float windResponsiveness,
		int segments) {

	public static CapePhysicsPreset defaults() {
		return new CapePhysicsPreset(0.6f, 1.0f, 0.9f, 0.5f, 6);
	}

	public CapePhysicsPreset {
		stiffness = Math.clamp(stiffness, 0f, 1f);
		gravityMultiplier = Math.clamp(gravityMultiplier, 0f, 3f);
		damping = Math.clamp(damping, 0f, 1f);
		windResponsiveness = Math.clamp(windResponsiveness, 0f, 2f);
		segments = Math.clamp(segments, 2, 16);
	}
}
