package net.veloclient.launcher.ui;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import javafx.animation.Animation;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.stage.Stage;

/**
 * Pauses the launcher's endless decorative animations (the Home island and player, auras, wings,
 * name previews, loading shimmer...) while its window is unfocused or minimized - most of the time
 * the game is running in front of it - or nobody has touched it for {@link #IDLE_MILLIS}.
 *
 * <p>Besides the CPU/GPU, this is a memory fix: with the NVIDIA driver under XWayland, every frame
 * JavaFX presents leaks native memory inside the GL driver (measured ~2.9 MB/s at 60 FPS, linear and
 * unbounded - over 10 GB an hour with Home open, even for a single spinning 2D rectangle; software
 * rendering doesn't leak, newer JavaFX doesn't help). A window that renders nothing presents nothing.
 *
 * <p>AnimationTimers check {@link #active()} at the top of {@code handle}; Timelines/Transitions are
 * registered with {@link #gate(Animation)}. Things the user watches on purpose (clip and media
 * playback) and slow functional timers (countdowns, polls) are not gated.
 */
public final class RenderGate {

	private static final long IDLE_MILLIS = 30_000;
	private static final ReadOnlyBooleanWrapper ACTIVE = new ReadOnlyBooleanWrapper(true);
	private static final javafx.beans.property.BooleanProperty RECENT_INPUT = new javafx.beans.property.SimpleBooleanProperty(true);
	private static volatile long lastInput = System.currentTimeMillis();
	// Animations this gate paused, to resume them; weak so removed views don't stay reachable.
	private static final Map<Animation, Boolean> GATED = Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<Animation, Boolean> PAUSED_BY_GATE = new WeakHashMap<>();

	private RenderGate() {
	}

	public static void install(Stage stage) {
		if (Boolean.getBoolean("velo.launcherTour")) {
			return; // screenshot tours run unfocused
		}
		// Any mouse or key event in the window counts as use; a 1 s check turns idle on/off.
		stage.addEventFilter(javafx.event.Event.ANY, e -> {
			if (e instanceof javafx.scene.input.MouseEvent || e instanceof javafx.scene.input.KeyEvent
					|| e instanceof javafx.scene.input.ScrollEvent) {
				lastInput = System.currentTimeMillis();
				if (!RECENT_INPUT.get()) {
					RECENT_INPUT.set(true);
				}
			}
		});
		javafx.animation.Timeline idleCheck = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1),
				e -> RECENT_INPUT.set(System.currentTimeMillis() - lastInput < IDLE_MILLIS)));
		idleCheck.setCycleCount(javafx.animation.Animation.INDEFINITE);
		idleCheck.play();
		ACTIVE.bind(stage.focusedProperty().and(stage.iconifiedProperty().not()).and(RECENT_INPUT));
		stage.focusedProperty().addListener((obs, was, now) -> {
			if (now) {
				lastInput = System.currentTimeMillis();
				RECENT_INPUT.set(true);
			}
		});
		ACTIVE.addListener((obs, was, now) -> {
			if (now) {
				for (Animation animation : PAUSED_BY_GATE.keySet().toArray(Animation[]::new)) {
					if (animation.getStatus() == Animation.Status.PAUSED) {
						animation.play();
					}
				}
				PAUSED_BY_GATE.clear();
			} else {
				for (Animation animation : GATED.keySet().toArray(Animation[]::new)) {
					pauseIfRunning(animation);
				}
			}
		});
	}

	public static boolean active() {
		return ACTIVE.get();
	}

	public static ReadOnlyBooleanProperty activeProperty() {
		return ACTIVE.getReadOnlyProperty();
	}

	/** Pauses {@code animation} whenever the window is inactive (and resumes it after), on top of its own play/stop. */
	public static <A extends Animation> A gate(A animation) {
		GATED.put(animation, Boolean.TRUE);
		// Started while inactive (a page rebuilt in the background): hold it until the window is back.
		animation.statusProperty().addListener((obs, was, status) -> {
			if (status == Animation.Status.RUNNING && !ACTIVE.get()) {
				pauseIfRunning(animation);
			}
		});
		return animation;
	}

	private static void pauseIfRunning(Animation animation) {
		if (animation.getStatus() == Animation.Status.RUNNING) {
			animation.pause();
			PAUSED_BY_GATE.put(animation, Boolean.TRUE);
		}
	}
}
