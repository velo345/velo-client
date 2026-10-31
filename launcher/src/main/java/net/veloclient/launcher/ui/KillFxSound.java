package net.veloclient.launcher.ui;

/**
 * Plays a kill effect's sound in the launcher (its WAV copy, bundled by tools/cosmetics/killfx/make_sounds.py), off the
 * FX thread with Java Sound - one at a time.
 */
final class KillFxSound {

	private static javax.sound.sampled.Clip playing;
	/** Bumped by every play / stop: a sound still loading when it changes never starts. */
	private static int generation;

	private KillFxSound() {
	}

	/** Stops whatever kill effect sound plays (its preview left the screen). */
	static synchronized void stop() {
		generation++;
		if (playing != null) {
			playing.close();
			playing = null;
		}
	}

	static void play(String id) {
		float volume = net.veloclient.launcher.data.KillFxCatalog.volume();
		int mine;
		synchronized (KillFxSound.class) {
			mine = ++generation;
		}
		Thread thread = new Thread(() -> {
			try (var in = KillFxSound.class.getResourceAsStream("/net/veloclient/launcher/killfx/" + id + ".wav")) {
				if (in == null) {
					return;
				}
				var audio = javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.BufferedInputStream(in));
				var clip = javax.sound.sampled.AudioSystem.getClip();
				clip.open(audio);
				if (clip.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) {
					var gain = (javax.sound.sampled.FloatControl) clip.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN);
					float db = volume <= 0.001f ? gain.getMinimum() : (float) (20 * Math.log10(volume * 0.8));
					gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
				}
				synchronized (KillFxSound.class) {
					if (mine != generation) {
						clip.close();
						return;
					}
					if (playing != null) {
						playing.close();
					}
					playing = clip;
				}
				clip.addLineListener(event -> {
					if (event.getType() == javax.sound.sampled.LineEvent.Type.STOP) {
						clip.close();
					}
				});
				clip.start();
			} catch (Exception ignored) {
				// No sound device - the preview just stays silent.
			}
		}, "kill-effect-sound");
		thread.setDaemon(true);
		thread.start();
	}
}
