package net.veloclient.velo.client.clips;

/**
 * What the computer plays right now ("what you hear": Spotify, Discord, the game...) as a plain PCM tap - the same
 * loopback Clips Pro records from (WASAPI on Windows, the PulseAudio / PipeWire monitor on Linux). For analysis only:
 * nothing is stored or sent anywhere. Null from {@link #open()} where there's no loopback (macOS, or it failed).
 */
public final class DesktopAudio {

	public static final int RATE = AudioCapture.RATE;
	private final AudioCapture.Source source;
	private final int[] mix = new int[RATE / 50 * 2];

	private DesktopAudio(AudioCapture.Source source) {
		this.source = source;
	}

	public static DesktopAudio open() {
		AudioCapture.Source source = AudioCapture.desktopSource();
		return source == null ? null : new DesktopAudio(source);
	}

	/**
	 * Up to {@code frames} mono frames into {@code out} (-1..1); frames the source hasn't delivered yet stay silent.
	 * Call on a steady clock (the source is real time).
	 */
	public void read(float[] out, int frames) {
		int n = Math.min(frames, mix.length / 2);
		java.util.Arrays.fill(mix, 0, n * 2, 0);
		source.mixInto(mix, n);
		for (int i = 0; i < n; i++) {
			out[i] = (mix[2 * i] + mix[2 * i + 1]) / 65536f;
		}
	}

	public boolean dead() {
		return source.dead;
	}

	/** Stops listening ("shutdown", not "close": a Stonecutter rule renames close() on 26.x). */
	public void shutdown() {
		source.close();
	}
}
