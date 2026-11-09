package net.veloclient.velo.devbuild;

/**
 * Hand-over between the item model (knows who holds the shield) and the shield renderer (only sees the item): the
 * model's update stores the holder here while the renderer extracts its data, the extracted data carries the tint, and
 * while that data is drawn {@link #drawing} holds it for the tint argument. Render thread only.
 */
public final class ShieldTint {

	/** The entity whose item model is being updated right now, or null. */
	public static Object holder;
	/** Packed tint of the shield being drawn right now (see ShieldIndicatorModule#tintFor), 0 = none. */
	public static int drawing;

	private ShieldTint() {
	}

	/** The tint argument: the indicator colour (opaque) while a tinted shield is drawn, else what vanilla passed. */
	public static int tintArg(int vanilla) {
		return ShieldIndicatorModule.hasTint(drawing) ? 0xFF000000 | (drawing & 0xFFFFFF) : vanilla;
	}

	/** Flash strength 0-255 of the shield being drawn. */
	public static int flash() {
		return drawing >>> 24;
	}

	/** Full-bright light while the "ready" flash runs. */
	public static int light(int vanilla) {
		return flash() > 40 ? 0xF000F0 : vanilla;
	}
}
