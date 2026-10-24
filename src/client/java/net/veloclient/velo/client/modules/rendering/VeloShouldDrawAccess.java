package net.veloclient.velo.client.modules.rendering;

/**
 * Velo Shaders on 26.3: Sodium's chunk renderer (VeloShadowTargetMixin) remembers per pass whether the camera's lists
 * have anything to draw - the shadow pass prepares its own lists, so it puts the camera's flags back afterwards.
 */
public interface VeloShouldDrawAccess {

	boolean[] velo$shouldDraw();
}
