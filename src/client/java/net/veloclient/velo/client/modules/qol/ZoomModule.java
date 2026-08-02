package net.veloclient.velo.client.modules.qol;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import org.lwjgl.glfw.GLFW;

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
			"key.velo-client.zoom", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_C, VeloKeybinds.CATEGORY));

	private static final float MIN_ZOOM_FOV = 3f;
	private static final float MAX_ZOOM_FOV = 90f;
	private static final float SETTLE_EPSILON = 0.25f;

	private static volatile boolean zoomKeyHeld;
	private static volatile boolean overrideActive;
	private static volatile float overrideFov;
	private static volatile float targetZoomFov = 20f;

	private boolean zooming;

	public ZoomModule() {
		super("zoom", "Zoom", "Hold the zoom key to smoothly narrow your FOV, spyglass-style. Scroll while held to adjust.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		WorldRenderEvents.BEFORE_ENTITIES.register(this::onFrame);
	}

	@Override
	public void onDisable() {
		zooming = false;
		zoomKeyHeld = false;
	}

	public static boolean isZoomKeyHeld() {
		return zoomKeyHeld;
	}

	public static boolean isOverrideActive() {
		return overrideActive;
	}

	public static float currentOverrideFov() {
		return overrideFov;
	}

	/** Called from {@link net.veloclient.velo.client.mixin.MouseScrollMixin} - positive amount is scroll-up (zoom in further). */
	public static void adjustZoomOnScroll(double amount) {
		targetZoomFov = Math.clamp(targetZoomFov - (float) amount * 2f, MIN_ZOOM_FOV, MAX_ZOOM_FOV);
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled() || client.player == null) {
			zooming = false;
			zoomKeyHeld = false;
			return;
		}
		boolean held = ZOOM_KEY.isPressed();
		zoomKeyHeld = held;
		if (held && !zooming) {
			overrideFov = client.options.getFov().getValue();
			overrideActive = true;
			zooming = true;
		} else if (!held && zooming) {
			zooming = false;
		}
	}

	private void onFrame(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext context) {
		if (!overrideActive) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		float restingFov = client.options.getFov().getValue();
		float target = zooming ? targetZoomFov : restingFov;
		float deltaSeconds = client.getRenderTickCounter().getDynamicDeltaTicks() / 20f;
		overrideFov = VeloAnim.step(overrideFov, target, Math.max(deltaSeconds, 1 / 60f));

		if (!zooming && Math.abs(overrideFov - restingFov) < SETTLE_EPSILON) {
			// Animated back to whatever the resting FOV naturally is -
			// hand rendering back to vanilla's own unmodified computation
			// instead of continuing to force this exact value, so effects
			// like the sprint FOV kick work normally again immediately.
			overrideActive = false;
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Zoom FOV", MIN_ZOOM_FOV, MAX_ZOOM_FOV,
						() -> targetZoomFov, v -> targetZoomFov = (float) v, v -> String.valueOf((int) v)),
				KeybindConfig.field("Zoom Key", ZOOM_KEY));
	}
}
