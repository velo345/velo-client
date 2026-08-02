package net.veloclient.launcher.data;

/** Mirrors the mod's {@code CapePhysicsPreset} record/JSON shape (design spec section 6.5). */
public record CapePhysicsPresetData(float stiffness, float gravityMultiplier, float damping, float windResponsiveness, int segments) {

	public static CapePhysicsPresetData defaults() {
		return new CapePhysicsPresetData(0.6f, 1.0f, 0.9f, 0.5f, 6);
	}
}
