package net.veloclient.velo.client.cosmetics.wings;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Lets a GUI preview show a specific pair of wings on the player - each Store card its own pair,
 * each animating on its own - without touching what you actually wear. A preview widget tags the
 * render state it builds for that frame; the wings renderer reads the tag instead of the player's
 * real choice. Tags die with their render state.
 */
public final class WingsPreview {

	/** How a preview moves the wings: still breathing, walking, flying or gliding. */
	public enum Showcase {
		IDLE("Idle", new WingAnimator.Motion(0, 0, true, false, false, false, false)),
		WALK("Walk", new WingAnimator.Motion(0.24, 0, true, false, false, false, false)),
		FLY("Fly", new WingAnimator.Motion(0.3, 0, false, false, true, false, false)),
		GLIDE("Glide", new WingAnimator.Motion(0.9, -0.1, false, true, false, false, false)),
		SNEAK("Sneak", new WingAnimator.Motion(0, 0, true, false, false, true, false)),
		SWIM("Swim", new WingAnimator.Motion(0.1, 0, false, false, false, false, true));

		public final String label;
		public final WingAnimator.Motion motion;

		Showcase(String label, WingAnimator.Motion motion) {
			this.label = label;
			this.motion = motion;
		}
	}

	/** {@code worn} null = show no wings; {@code key} keeps each preview's animation separate. */
	public record Tag(WingsManager.Worn worn, Showcase showcase, int key) {
	}

	private static final Map<Object, Tag> TAGS = Collections.synchronizedMap(new WeakHashMap<>());

	private WingsPreview() {
	}

	public static void tag(Object renderState, Tag tag) {
		TAGS.put(renderState, tag);
	}

	public static Tag get(Object renderState) {
		return TAGS.isEmpty() ? null : TAGS.get(renderState);
	}
}
