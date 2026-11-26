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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The launcher's interface sounds (same set as the game's, made by tools/sounds/make_ui_sounds.py): a soft swell when it
 * opens, a deep bloom when you press Play, clicks and switch ticks. On by default, off with Settings >
 * Interface sounds. Every press sounds: buttons, switches, tiles, rows and links. Clips are decoded once, off the FX thread; a sound that isn't ready yet is simply skipped.
 */
public final class UiSounds {

	private static final String[] NAMES = {"launcher_start", "hover", "click", "back", "toggle_on", "toggle_off", "slider", "tab", "page", "menu_open", "menu_close", "confirm", "error", "notify", "drag", "drop", "launch", "launcher_start"};
	private static final Map<String, Clip> CLIPS = new ConcurrentHashMap<>();
	/** The start sound was asked for before its clip had loaded (the first frame usually beats the loader). */
	private static volatile long startupWanted;
	private static volatile boolean loading;
	private static long lastClick;

	private UiSounds() {
	}

	private static boolean on() {
		return InterfacePrefs.get().sounds;
	}

	/** Loads the clips in the background (call once at startup). */
	public static void preload() {
		if (loading) {
			return;
		}
		loading = true;
		Thread t = new Thread(() -> {
			for (String file : files()) {
				String name = file.replaceAll("_\\d$", "");
				try (InputStream raw = UiSounds.class.getResourceAsStream("/net/veloclient/launcher/sounds/" + file + ".wav")) {
					if (raw == null) {
						continue;
					}
					AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(raw));
					Clip clip = AudioSystem.getClip();
					clip.open(in);
					// quiet by default (2026-11-26): about -9 dB under the files' own level
					if (clip.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) {
						javax.sound.sampled.FloatControl gain = (javax.sound.sampled.FloatControl) clip.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN);
						gain.setValue(Math.max(gain.getMinimum(), -9f));
					}
					if (!file.equals(name)) {
						TAKES.computeIfAbsent(name, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(clip);
						continue;
					}
					CLIPS.put(name, clip);
					// loaded first: if the window is already up (within the last 4 s), the opening bloom plays now
					if (name.equals("launcher_start") && startupWanted > 0 && System.nanoTime() - startupWanted < 4_000_000_000L) {
						startupWanted = 0;
						play(name);
					}
				} catch (Exception | LinkageError e) {
					// no audio device / headless: the launcher just stays quiet
				}
			}
		}, "velo-ui-sounds");
		t.setDaemon(true);
		t.start();
	}

	/** Every file: the main take of each sound, then its extra takes (name_1..name_4) where they exist. */
	private static List<String> files() {
		List<String> out = new java.util.ArrayList<>(List.of(NAMES));
		for (String name : NAMES) {
			for (int i = 1; i <= 4; i++) {
				if (UiSounds.class.getResource("/net/veloclient/launcher/sounds/" + name + "_" + i + ".wav") != null) {
					out.add(name + "_" + i);
				}
			}
		}
		return out;
	}

	/** Extra takes of the small sounds (a touch higher / lower): a different one each time, never the same twice in a row. */
	private static final Map<String, List<Clip>> TAKES = new ConcurrentHashMap<>();
	private static final Map<String, Clip> LAST_TAKE = new ConcurrentHashMap<>();

	private static Clip pick(String name) {
		Clip main = CLIPS.get(name);
		List<Clip> extra = TAKES.get(name);
		if (main == null || extra == null || extra.isEmpty()) {
			return main;
		}
		List<Clip> all = new java.util.ArrayList<>(extra);
		all.add(main);
		all.remove(LAST_TAKE.get(name));
		Clip clip = all.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(all.size()));
		LAST_TAKE.put(name, clip);
		return clip;
	}

	private static void play(String name) {
		if (!on()) {
			return;
		}
		Clip clip = pick(name);
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
		if (CLIPS.containsKey("launcher_start")) {
			play("launcher_start");
		} else {
			startupWanted = System.nanoTime();   // it was always skipped here before: the clips were still loading
		}
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
	/** Soft feedback the rest of the launcher can call (2026-11-25 sound set). */
	public static void confirm() {
		play("confirm");
	}

	public static void error() {
		play("error");
	}

	public static void notifySoft() {
		play("notify");
	}

	public static void menuOpen() {
		play("menu_open");
	}

	public static void menuClose() {
		play("menu_close");
	}

	public static void drop() {
		play("drop");
	}

	private static long lastHover;

	public static void install(Scene scene) {
		// hovering anything you can press: a barely-there tick (never twice within 70 ms)
		scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_ENTERED_TARGET, e -> {
			if (!(e.getTarget() instanceof javafx.scene.Node node) || node.isDisabled()) {
				return;
			}
			boolean pressable = node instanceof ButtonBase || node instanceof javafx.scene.control.ComboBoxBase<?>
					|| node.getOnMouseClicked() != null && node.getCursor() == javafx.scene.Cursor.HAND;
			long now = System.nanoTime();
			if (pressable && now - lastHover > 70_000_000L) {
				lastHover = now;
				play("hover");
			}
		});
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
			} else if (target instanceof ButtonBase button && (button.getStyleClass().contains("sidebar-nav") || button.getStyleClass().contains("nav-item"))) {
				play("page"); // another page of the launcher
			} else if (target instanceof ButtonBase button && (button.getStyleClass().contains("topnav-tab")
					|| button.getStyleClass().contains("segmented-button") || button.getStyleClass().contains("tw-nav-item"))) {
				play("tab"); // a tab inside a page
			} else if (target instanceof ButtonBase) {
				play("click");
			}
		});
		// Everything else you can press clicks too (2026-11): tiles, cards, rows, links and dropdowns - anything with its
		// own click handler that isn't a button (those already sounded above).
		scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
			if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
				return;
			}
			for (Object n = e.getTarget(); n instanceof javafx.scene.Node node; n = node.getParent()) {
				if (node instanceof ButtonBase && !(node instanceof javafx.scene.control.MenuButton)) {
					return;
				}
				if (node.isDisabled()) {
					return;
				}
				if (node instanceof javafx.scene.control.ComboBoxBase<?> || node instanceof javafx.scene.control.MenuButton
						|| node.getOnMouseClicked() != null) {
					long now = System.nanoTime();
					if (now - lastClick >= 40_000_000L) {
						lastClick = now;
						play("click");
					}
					return;
				}
			}
		});
	}
}
