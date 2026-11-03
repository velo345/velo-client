package net.veloclient.launcher.ui;

import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ToggleButton;
import net.veloclient.launcher.AppVersion;
import net.veloclient.launcher.data.InterfacePrefs;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The launcher's interface sounds (same set as the game's, made by tools/sounds/make_ui_sounds.py): a soft swell when it
 * opens, a deep bloom when you press Play, clicks and switch ticks. Dev build only for now, and off with Settings >
 * Interface sounds. Clips are decoded once, off the FX thread; a sound that isn't ready yet is simply skipped.
 */
public final class UiSounds {

	private static final String[] NAMES = {"click", "toggle_on", "toggle_off", "tab", "launcher_start", "launch"};
	private static final Map<String, Clip> CLIPS = new ConcurrentHashMap<>();
	private static volatile boolean loading;
	private static long lastClick;

	private UiSounds() {
	}

	private static boolean on() {
		return AppVersion.DEV && InterfacePrefs.get().sounds;
	}

	/** Loads the clips in the background (call once at startup). */
	public static void preload() {
		if (!AppVersion.DEV || loading) {
			return;
		}
		loading = true;
		Thread t = new Thread(() -> {
			for (String name : NAMES) {
				try (InputStream raw = UiSounds.class.getResourceAsStream("/net/veloclient/launcher/sounds/" + name + ".wav")) {
					if (raw == null) {
						continue;
					}
					AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(raw));
					Clip clip = AudioSystem.getClip();
					clip.open(in);
					CLIPS.put(name, clip);
				} catch (Exception | LinkageError e) {
					// no audio device / headless: the launcher just stays quiet
				}
			}
		}, "velo-ui-sounds");
		t.setDaemon(true);
		t.start();
	}

	private static void play(String name) {
		if (!on()) {
			return;
		}
		Clip clip = CLIPS.get(name);
		if (clip == null) {
			return;
		}
		try {
			clip.stop();
			clip.setFramePosition(0);
			clip.start();
		} catch (Exception ignored) {
			// device went away
		}
	}

	public static void startup() {
		play("launcher_start");
	}

	public static void launch() {
		play("launch");
	}

	public static void tab() {
		play("tab");
	}

	/**
	 * Every button and switch in the window clicks: a plain click for buttons, an on/off tick for check boxes and toggle
	 * buttons. One filter on the scene instead of a call in every handler.
	 */
	public static void install(Scene scene) {
		if (!AppVersion.DEV) {
			return;
		}
		scene.addEventFilter(ActionEvent.ACTION, e -> {
			Object target = e.getTarget();
			long now = System.nanoTime();
			if (now - lastClick < 40_000_000L) {
				return; // one action, one sound
			}
			lastClick = now;
			if (target instanceof CheckBox box) {
				play(box.isSelected() ? "toggle_on" : "toggle_off");
			} else if (target instanceof ToggleButton toggle) {
				play(toggle.isSelected() ? "toggle_on" : "toggle_off");
			} else if (target instanceof ButtonBase button && button.getStyleClass().contains("topnav-tab")) {
				play("tab"); // switching pages in the top bar
			} else if (target instanceof ButtonBase) {
				play("click");
			}
		});
	}
}
