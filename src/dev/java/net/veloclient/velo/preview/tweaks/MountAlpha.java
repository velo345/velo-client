package net.veloclient.velo.preview.tweaks;

/**
 * Duck on the living-entity render state (RideableAlphaStateMixin): the alpha this entity is drawn with, set while the
 * state is filled in, read while it is submitted. Lives outside the mixin package so normal code can reference it.
 */
public interface MountAlpha {

	float velo$mountAlpha();

	void velo$setMountAlpha(float alpha);
}
