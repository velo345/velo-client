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
	private static volatile boolean smoothCameraWhileZoomed;
	/** Mouse look scaled to the zoom: same aim feel at every zoom, stops instantly (no cinematic glide). */
	private static volatile boolean scaleSensitivity = true;
	private static volatile double zoomInSpeed = 1.0;
	private static volatile double zoomOutSpeed = 1.0;
	/** How much one scroll notch zooms (ratio per notch). */
	private static volatile double scrollStep = 0.12;

	private static volatile boolean zooming;
	/** The zoom as a factor on tan(fov/2), animated in log space so every step of the zoom feels equally fast. */
	private static double logZoom;
	private static long lastFrameNanos;
	private static boolean restoreSmoothCamera;
	private static boolean savedSmoothCamera;

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
		if (!overrideActive || !scaleSensitivity) {
			return 1.0;
		}
		return Math.max(0.02, Math.min(1.0, Math.exp(logZoom)));
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
		double rate = (26 - 22 * smoothness) * (zooming ? zoomInSpeed : zoomOutSpeed);   // 26/s (snappy) .. 4/s (very soft)
		logZoom += (target - logZoom) * (1 - Math.exp(-rate * dt));
		if (!zooming && Math.abs(logZoom) < SETTLE_EPSILON * 0.01) {
			// back at the normal FOV: hand it back to vanilla so the sprint FOV kick works again right away
			logZoom = 0;
			overrideActive = false;
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
			setSmoothCamera(false);
		}
	}

	/**
	 * While zoomed the cinematic camera is exactly what the setting says - on only with "Cinematic Camera While Zoomed",
	 * otherwise forced off (even if F8 turned it on) so mouse look stops the instant the mouse stops. The player's own
	 * state comes back when the zoom ends.
	 */
	private static void setSmoothCamera(boolean on) {
		var options = MinecraftClient.getInstance().options;
		if (on) {
			//? if <26.1 {
			savedSmoothCamera = options.smoothCameraEnabled;
			options.smoothCameraEnabled = smoothCameraWhileZoomed;
			//?} else {
			/*savedSmoothCamera = options.smoothCamera;
			options.smoothCamera = smoothCameraWhileZoomed;
			*///?}
			restoreSmoothCamera = true;
		} else if (restoreSmoothCamera) {
			restoreSmoothCamera = false;
			//? if <26.1 {
			options.smoothCameraEnabled = savedSmoothCamera;
			//?} else {
			/*options.smoothCamera = savedSmoothCamera;
			*///?}
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Zoom FOV", MIN_ZOOM_FOV, MAX_ZOOM_FOV,
						() -> targetZoomFov, v -> targetZoomFov = (float) v, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Smoothness", 0, 1, () -> smoothness, v -> smoothness = v,
						v -> v < .05 ? "Instant-ish" : Math.round(v * 100) + "%"),
				new ConfigField.SliderField("Zoom In Speed", 0.2, 3, () -> zoomInSpeed, v -> zoomInSpeed = Math.round(v * 20) / 20.0,
						v -> String.format(java.util.Locale.ROOT, "%.2fx", v)),
				new ConfigField.SliderField("Zoom Out Speed", 0.2, 3, () -> zoomOutSpeed, v -> zoomOutSpeed = Math.round(v * 20) / 20.0,
						v -> String.format(java.util.Locale.ROOT, "%.2fx", v)),
				new ConfigField.SliderField("Scroll Step", 0.03, 0.3, () -> scrollStep, v -> scrollStep = Math.round(v * 100) / 100.0,
						v -> Math.round(v * 100) + "% per notch"),
				new ConfigField.ToggleField("Lower Sensitivity While Zoomed", () -> scaleSensitivity, v -> scaleSensitivity = v),
				new ConfigField.ToggleField("Cinematic Camera While Zoomed (keeps gliding)", () -> smoothCameraWhileZoomed, v -> smoothCameraWhileZoomed = v),
				KeybindConfig.field("Zoom Key", ZOOM_KEY));
	}
}
