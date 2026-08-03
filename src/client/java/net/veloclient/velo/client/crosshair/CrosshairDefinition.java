package net.veloclient.velo.client.crosshair;

import java.util.Map;

/**
 * One crosshair in the library: an idle image plus an optional "hit" state
 * shown while a reachable entity is targeted, either a wholly separate
 * image or a per-color remap applied to the idle image. {@code colorSwap}
 * maps packed ARGB ints (idle color -> hit color); only used when {@code
 * hitMode == COLOR_SWAP}.
 */
public record CrosshairDefinition(String id, String name, int canvasSize, HitMode hitMode, Map<Integer, Integer> colorSwap) {

	public enum HitMode {
		NONE, SEPARATE_IMAGE, COLOR_SWAP
	}
}
