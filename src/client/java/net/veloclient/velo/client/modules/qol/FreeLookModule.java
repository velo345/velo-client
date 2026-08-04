package net.veloclient.velo.client.modules.qol;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
//? if <26.1 {
import net.minecraft.client.option.Perspective;
//?} else {
/*import net.minecraft.client.CameraType;
*///?}
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
 * Hold a key to orbit the camera freely around the player - third person,
 * like a spectator circling them - without turning the player itself or
 * moving. While held, mouse movement is intercepted before it ever reaches
 * the player entity's own yaw/pitch (see {@code FreeLookInputMixin}) and
 * instead accumulates into a local orbit angle that {@code
 * CameraFreeLookMixin} uses to place and rotate the camera on a sphere
 * around the player's (interpolated) eye position each frame - the
 * player's body/head model, movement direction and whatever's sent to the
 * server never change, only what's rendered on this client does.
 *
 * <p>The perspective is temporarily forced to third-person while held (and
 * restored on release) so there's actually a player model to orbit around
 * in the first place - vanilla's own real "third person" option/rendering
 * does the model/camera-distance work, this only overrides where in space
 * the camera sits and which way it looks.
 */
public final class FreeLookModule extends AbstractModule implements Configurable {

	public static final KeyBinding FREE_LOOK_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.free_look", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));

	private static volatile boolean active;
	private static volatile boolean needsBaseCapture;
	private static volatile float baseYaw;
	private static volatile float basePitch;
	private static volatile float yawOffset;
	private static volatile float pitchOffset;
	private static volatile double orbitDistance = 4.5;
	private static volatile boolean invertY = false;

	//? if <26.1 {
	private static Perspective previousPerspective;
	//?} else {
	/*private static CameraType previousPerspective;
	*///?}

	public FreeLookModule() {
		super("free-look", "Free Look",
				"Hold the free look key to orbit the camera freely around your player in third person, without "
						+ "turning your player or moving.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled()) {
			deactivate(client);
			return;
		}
		boolean shouldBeActive = client.player != null && FREE_LOOK_KEY.isPressed();
		if (shouldBeActive == active) {
			return;
		}
		if (shouldBeActive) {
			active = true;
			needsBaseCapture = true;
			yawOffset = 0f;
			pitchOffset = 0f;
			forceThirdPerson(client);
		} else {
			deactivate(client);
		}
	}

	private void deactivate(MinecraftClient client) {
		if (!active) {
			return;
		}
		active = false;
		restorePerspective(client);
	}

	@Override
	public void onDisable() {
		deactivate(MinecraftClient.getInstance());
	}

	public static boolean isActive() {
		return active && MinecraftClient.getInstance().player != null;
	}

	/** Called from the Entity changeLookDirection/turn mixin with the raw, unscaled cursor delta. */
	public static void accumulateLookDelta(double cursorDeltaX, double cursorDeltaY) {
		float deltaYaw = (float) cursorDeltaX * 0.15f;
		float deltaPitch = (float) cursorDeltaY * 0.15f * (invertY ? -1f : 1f);
		yawOffset += deltaYaw;
		// A full orbit range (not vanilla's +-90 head-pitch clamp) - this is
		// a camera circling the player, not a neck craning, so looking from
		// directly above or below is valid.
		pitchOffset = Math.clamp(pitchOffset + deltaPitch, -89f, 89f);
	}

	/** Called once per frame from {@code CameraFreeLookMixin} with the camera's own vanilla-computed (yaw, pitch) for this frame, before any override - captured once as the fixed orbit "center" the instant the key was pressed. */
	public static void captureBaseIfNeeded(float cameraYaw, float cameraPitch) {
		if (needsBaseCapture) {
			baseYaw = cameraYaw;
			basePitch = cameraPitch;
			needsBaseCapture = false;
		}
	}

	public static float effectiveYaw() {
		return baseYaw + yawOffset;
	}

	public static float effectivePitch() {
		return Math.clamp(basePitch + pitchOffset, -89f, 89f);
	}

	public static double orbitDistance() {
		return orbitDistance;
	}

	/**
	 * The (x, y, z) offset from the orbit target to where the camera should
	 * sit for the given (yaw, pitch) angle and distance - pure trigonometry
	 * (vanilla's own yaw/pitch-to-direction convention), no version-specific
	 * API needed.
	 */
	public static double[] cameraOffset(float yaw, float pitch, double distance) {
		double yawRad = Math.toRadians(yaw);
		double pitchRad = Math.toRadians(pitch);
		double forwardX = -Math.sin(yawRad) * Math.cos(pitchRad);
		double forwardY = -Math.sin(pitchRad);
		double forwardZ = Math.cos(yawRad) * Math.cos(pitchRad);
		// Behind the look direction, not ahead of it, so looking "forward"
		// from the camera's own rotation faces back toward the target.
		return new double[] {-forwardX * distance, -forwardY * distance, -forwardZ * distance};
	}

	//? if <26.1 {
	private static void forceThirdPerson(MinecraftClient client) {
		if (previousPerspective == null) {
			previousPerspective = client.options.getPerspective();
		}
		client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
	}

	private static void restorePerspective(MinecraftClient client) {
		if (previousPerspective != null) {
			client.options.setPerspective(previousPerspective);
			previousPerspective = null;
		}
	}
	//?} else {
	/*private static void forceThirdPerson(MinecraftClient client) {
		if (previousPerspective == null) {
			previousPerspective = client.options.getCameraType();
		}
		client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
	}

	private static void restorePerspective(MinecraftClient client) {
		if (previousPerspective != null) {
			client.options.setCameraType(previousPerspective);
			previousPerspective = null;
		}
	}
	*///?}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Free Look Key", FREE_LOOK_KEY),
				new ConfigField.SliderField("Orbit Distance", 1.5, 10.0, () -> orbitDistance,
						v -> orbitDistance = v, v -> String.format(java.util.Locale.ROOT, "%.1f", v)),
				new ConfigField.ToggleField("Invert vertical look", () -> invertY, v -> invertY = v));
	}
}
