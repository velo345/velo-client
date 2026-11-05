package net.veloclient.launcher.launch;

/** Progress callback for {@link GameLauncher} - implementations should hop back to the FX thread themselves. */
public interface LaunchProgressListener {

	void onPhase(String phase);

	/** @param fraction 0.0 to 1.0, within the current phase */
	void onProgress(double fraction);

	/** Bytes of the current phase's download so far, out of {@code total} (for "23 / 61 MB · 4 MB/s"). */
	default void onBytes(long done, long total) {
	}
}
