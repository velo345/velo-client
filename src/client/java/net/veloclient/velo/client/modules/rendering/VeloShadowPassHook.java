package net.veloclient.velo.client.modules.rendering;

/**
 * Velo Shaders on 26.3: Sodium's world renderer (VeloShadowSodiumMixin) draws the shadow map when asked - 26.3 opens
 * the frame's terrain pass itself, so the shadow pass runs before that, from VeloShadowTriggerMixin.
 */
public interface VeloShadowPassHook {

	/** Draws the solid and cutout terrain from the sun into the shadow map (camera position in world space). */
	void velo$shadowPass(double cameraX, double cameraY, double cameraZ);
}
