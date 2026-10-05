package net.veloclient.velo.profile;

/**
 * Plain, client-independent snapshot of one HUD element's position/scale
 * (mirrors the client-only {@code HudPosition}) - kept in the common
 * sourceset so {@link VeloProfile} doesn't need a dependency on client-only
 * classes; the client module converts between the two when saving/loading.
 */
public record HudLayoutEntry(float xFraction, float yFraction, float scale, Boolean placed) {
	// placed: true = the player positioned it in the HUD editor; false = automatic corner layout;
	// null = saved before this existed (decided by whether it still sits at its old default).
}
