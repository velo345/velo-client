package net.veloclient.launcher.launch;

/** Progress callback for {@link GameLauncher} - implementations should hop back to the FX thread themselves. */
public interface LaunchProgressListener {

	void onPhase(String phase);

	/** @param fraction 0.0 to 1.0, within the current phase */
	void onProgress(double fraction);
}
