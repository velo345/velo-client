package net.veloclient.velo.dev.bridge;

/** The current camera shake offset {yaw, pitch} in degrees, or null - smooth noise that dies down over its time. */
public final class BridgeShake {

	private BridgeShake() {
	}

	public static float[] offset() {
		VeloBridgeClient.Effect e = VeloBridgeClient.shake;
		if (e == null) {
			return null;
		}
		long age = System.currentTimeMillis() - e.startedAt();
		if (age > e.millis()) {
			VeloBridgeClient.shake = null;
			return null;
		}
		float fade = 1f - age / (float) e.millis();
		float amount = (float) e.strength() * 3.2f * fade * fade;
		double t = age / 1000.0;
		// A few sines at odd frequencies: reads as a rumble, not a wobble.
		float yaw = (float) (Math.sin(t * 47) * 0.6 + Math.sin(t * 83 + 1.3) * 0.4) * amount;
		float pitch = (float) (Math.sin(t * 59 + 0.7) * 0.6 + Math.sin(t * 101 + 2.1) * 0.4) * amount;
		return new float[] {yaw, pitch};
	}
}
