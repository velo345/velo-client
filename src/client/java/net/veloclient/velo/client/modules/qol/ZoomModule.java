package net.veloclient.velo.client.modules.qol;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import net.veloclient.velo.client.util.ModuleProfiler;

import java.util.List;

/**
 * Vanilla-style zoom: holds down a key to smoothly narrow FOV, spyglass-style,
 * with the scroll wheel adjusting how far in it goes while the key is held
 * (see {@link net.veloclient.velo.client.mixin.MouseScrollMixin}, which
 * reroutes scroll input here instead of the hotbar while zooming). Purely a
 * local camera/rendering change - it doesn't touch reach, hit detection, or
 * anything sent to the server.
 *
 * <p>This overrides the rendered FOV directly (see
 * {@link net.veloclient.velo.client.mixin.GameRendererFovMixin}) instead of
 * writing to vanilla's own {@code options.getFov()}, which is what this used
 * to do. That shared state with the player's actual saved FOV setting - it's
 * validated/clamped to vanilla's [30,110] range, so zoom could never go
 * narrower than a normal FOV slider allows, and a scroll-wheel edge case
 * around releasing the key could leave the saved option changed afterward.
 * Overriding the render-time value instead means the real setting is never
 * touched at all, and zoom can go arbitrarily narrow.
 */
public final class ZoomModule extends AbstractModule implements Configurable {

	public static final KeyBinding ZOOM_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.zoom", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.C, VeloKeybinds.CATEGORY));

	private static final float MIN_ZOOM_FOV = 3f;
	private static final float MAX_ZOOM_FOV = 90f;
	private static final float SETTLE_EPSILON = 0.05f;

	private static volatile boolean zoomKeyHeld;
	private static volatile boolean overrideActive;
	/** The setting: where every zoom starts. */
	private static volatile float targetZoomFov = 20f;
	/** This zoom's FOV - starts at the setting each time the key goes down; scrolling changes only this, never the setting. */
	private static volatile float currentZoomFov = 20f;
	/** 0 = snappy, 1 = very soft; how long the zoom takes to settle. */
	private static volatile double smoothness = 0.55;
	/**
	 * Mouse smoothing while zoomed (0 = off .. 1 = softest): every mouse movement is spread over a few milliseconds instead of
	 * landing as one jump - far into a zoom one mouse count is a visible step. Unlike vanilla's cinematic camera it has no
	 * momentum: the camera trails the mouse and settles exactly where it was aimed, never further.
	 * (Replaces "Cinematic Camera While Zoomed", whose glide felt like the camera kept going on its own - owner, 2026-11-06.)
	 */
	private static volatile double mouseSmoothing = 0.5;
	/** Movement not applied yet (x, y) and when each axis was last turned. */
	private static final double[] PENDING = new double[2];
	private static final long[] LAST_TURN = new long[2];
		/** Mouse look scaled to the zoom: same aim feel at every zoom, stops instantly (no cinematic glide). */
	private static volatile boolean scaleSensitivity = true;
	private static volatile double zoomInSpeed = 1.0;
	private static volatile double zoomOutSpeed = 1.0;
	/** How long zooming in / out takes (seconds): an eased glide that lands exactly on the zoom, not a slow exponential creep. */
	private static volatile double zoomInTime = 0.11;
	private static volatile double zoomOutTime = 0.13;
	/** The running glide: where it started (log zoom), when, and whether it's the zoom-in one. */
	private static double glideFrom;
	private static long glideStart;
	private static boolean glideZoomIn;
	private static boolean glideActive;
	/** How much one scroll notch zooms (ratio per notch). */
	private static volatile double scrollStep = 0.12;

	private static volatile boolean zooming;
	/** The zoom as a factor on tan(fov/2), animated in log space so every step of the zoom feels equally fast. */
	private static double logZoom;
	private static long lastFrameNanos;

	public ZoomModule() {
		super("zoom", "Zoom", "Hold the zoom key to smoothly narrow your FOV, spyglass-style. Scroll while held to adjust.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(client ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.TICK, () -> onTick(client)));
	}

	@Override
	public void onDisable() {
		if (zooming) {
			setSmoothCamera(false);
		}
		zooming = false;
		zoomKeyHeld = false;
	}

	public static boolean isZoomKeyHeld() {
		return zoomKeyHeld;
	}

	public static boolean isOverrideActive() {
		return overrideActive;
	}

	/** Factor for mouse look while zoomed (1 = normal): the zoom's own ratio, read by ZoomSensitivityMixin. */
	public static double sensitivityFactor() {
		// Only while the key is held: following the zoom-out animation made the sensitivity climb back over a few
		// hundred ms after letting go, which felt like the camera speeding up on its own.
		if (!overrideActive || !zooming || !scaleSensitivity) {
			return 1.0;
		}
		return Math.max(0.02, Math.min(1.0, Math.exp(logZoom)));
	}

	/**
	 * One axis of mouse look (0 = x, 1 = y) for this frame, from ZoomSensitivityMixin: scaled to the zoom and, while zoomed,
	 * smoothed without momentum (see {@link #mouseSmoothing}).
	 */
	public static double look(int axis, double raw) {
		double scaled = raw * sensitivityFactor();
		long now = System.nanoTime();
		double dt = LAST_TURN[axis] == 0 ? 0 : Math.min(0.05, (now - LAST_TURN[axis]) / 1e9);
		LAST_TURN[axis] = now;
		// Smoothing off: everything at once.
		if (mouseSmoothing < 0.01) {
			double out = scaled + PENDING[axis];
			PENDING[axis] = 0;
			return out;
		}
		// The camera trails the mouse: each frame it covers a share of the way still to go (exponential catch-up), so it
		// glides in and settles exactly where the mouse put it - it never overshoots and never turns further than the
		// mouse moved (vanilla's cinematic camera does both). Not zoomed: new movement goes through directly, and only
		// what is still pending from the zoom finishes its glide, so letting go of the key doesn't jump.
		// 20 .. 400 ms; finer steps at the soft end (owner wanted far more smoothing): 50% = ~155 ms
		double tau = 0.02 + 0.38 * Math.pow(mouseSmoothing, 1.5);
		double catchUp = dt <= 0 ? 0 : 1 - Math.exp(-dt / tau);
		double out;
		if (zooming) {
			PENDING[axis] += scaled;
			out = PENDING[axis] * catchUp;
		} else {
			out = scaled + PENDING[axis] * catchUp;
		}
		PENDING[axis] -= zooming ? out : out - scaled;
		if (Math.abs(PENDING[axis]) < 1e-3) {
			out += PENDING[axis];
			PENDING[axis] = 0;
		}
		return out;
	}

	/** Called from {@link net.veloclient.velo.client.mixin.MouseScrollMixin} - positive amount is scroll-up (zoom in further). */
	public static void adjustZoomOnScroll(double amount) {
		// a scroll notch changes the zoom by a fixed ratio, not a fixed number of degrees
		currentZoomFov = Math.clamp(currentZoomFov * (float) Math.pow(1 - scrollStep, amount), MIN_ZOOM_FOV, MAX_ZOOM_FOV);
	}

	/**
	 * Called by {@link net.veloclient.velo.client.mixin.GameRendererFovMixin} with vanilla's own FOV for this frame
	 * (sprint/speed effects included) - returns the zoomed one. Animated with the real time between frames: the old
	 * version clamped the step to 1/60 s, so at a few hundred FPS it raced to the target in a handful of milliseconds.
	 */
	public static float apply(float vanillaFov) {
		long now = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
		lastFrameNanos = now;
		double target = zooming ? Math.log(Math.tan(Math.toRadians(currentZoomFov) / 2) / Math.tan(Math.toRadians(vanillaFov) / 2)) : 0;
		// Key down / up starts a glide of exactly zoomInTime / zoomOutTime (ease-out cubic: fast start, soft landing on the
		// target); once it has landed, scroll changes follow quickly (60 ms) so scrolling stays smooth too.
		if (!glideActive || glideZoomIn != zooming) {
			glideActive = true;
			glideZoomIn = zooming;
			glideFrom = logZoom;
			glideStart = now;
		}
		double duration = zooming ? zoomInTime : zoomOutTime;
		double t = duration <= 0.001 ? 1 : (now - glideStart) / 1e9 / duration;
		if (t < 1) {
			double eased = 1 - Math.pow(1 - t, 3);
			logZoom = glideFrom + (target - glideFrom) * eased;
		} else {
			logZoom += (target - logZoom) * (1 - Math.exp(-dt / 0.06));
		}
		if (!zooming && Math.abs(logZoom) < SETTLE_EPSILON * 0.01) {
			// back at the normal FOV: hand it back to vanilla so the sprint FOV kick works again right away
			logZoom = 0;
			overrideActive = false;
			glideActive = false;
		}
		double half = Math.atan(Math.tan(Math.toRadians(vanillaFov) / 2) * Math.exp(logZoom));
		return (float) Math.toDegrees(half * 2);
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled() || client.player == null) {
			if (zooming) {
				setSmoothCamera(false);
			}
			zooming = false;
			zoomKeyHeld = false;
			return;
		}
		boolean held = ZOOM_KEY.isPressed();
		zoomKeyHeld = held;
		if (held && !zooming) {
			if (!overrideActive) {
				logZoom = 0;
				lastFrameNanos = 0;
			}
			currentZoomFov = targetZoomFov; // every zoom starts from the setting again
			overrideActive = true;
			zooming = true;
			setSmoothCamera(true);
		} else if (!held && zooming) {
			zooming = false;
		}
		if (zooming) {
			setSmoothCamera(true); // keep vanilla's cinematic off while zoomed, even if its key is pressed meanwhile
		}
	}

	/**
	 * While zoomed the cinematic camera is exactly what the setting says - on only with "Cinematic Camera While Zoomed",
	 * otherwise forced off (even if F8 turned it on) so mouse look stops the instant the mouse stops. The player's own
	 * state comes back when the zoom ends.
	 */
	private static void setSmoothCamera(boolean on) {
		// Only ever turns vanilla's cinematic camera OFF (while zooming, every tick): the zoom has its own momentum-free
		// smoothing. It used to save the player's state on zoom start and restore it on release, and players kept ending up
		// in cinematic mode after every zoom (owner, 2026-11-06) - Velo never turns it on any more.
		if (!on) {
			return;
		}
		var options = MinecraftClient.getInstance().options;
		//? if <26.1 {
		options.smoothCameraEnabled = false;
		//?} else {
		/*options.smoothCamera = false;
		*///?}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Zoom FOV", MIN_ZOOM_FOV, MAX_ZOOM_FOV,
						() -> targetZoomFov, v -> targetZoomFov = (float) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Zoom In Time", 0, 0.4, () -> zoomInTime, v -> zoomInTime = Math.round(v * 100) / 100.0,
						v -> v < .005 ? "Instant" : Math.round(v * 1000) + " ms"),
				new ConfigField.SliderField("Zoom Out Time", 0, 0.4, () -> zoomOutTime, v -> zoomOutTime = Math.round(v * 100) / 100.0,
						v -> v < .005 ? "Instant" : Math.round(v * 1000) + " ms"),
				new ConfigField.SliderField("Scroll Step", 0.03, 0.3, () -> scrollStep, v -> scrollStep = Math.round(v * 100) / 100.0,
						v -> Math.round(v * 100) + "% per notch"),
				new ConfigField.ToggleField("Lower Sensitivity While Zoomed", () -> scaleSensitivity, v -> scaleSensitivity = v),
				new ConfigField.SliderField("Mouse Smoothing While Zoomed", 0, 1, () -> mouseSmoothing, v -> mouseSmoothing = Math.round(v * 50) / 50.0,
						v -> v < .01 ? "Off" : Math.round((0.02 + 0.38 * Math.pow(v, 1.5)) * 1000) + " ms"),
				KeybindConfig.field("Zoom Key", ZOOM_KEY));
	}
}
