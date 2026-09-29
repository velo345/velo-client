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
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * A free-flying "director's" camera for singleplayer cinematic shots, in the same spirit as
 * Replay Mod's own free camera (recording itself is explicitly out of scope for now - this is
 * just the camera). Toggling it on detaches the render camera from the player entirely: the
 * player stays exactly where they were (see {@code CinematicFreezePlayerMixin}, which cancels the
 * player's own movement/physics tick outright while this is active, so there's nothing to record
 * standing still and drifting or falling), while mouse look and the normal movement keys
 * (forward/back/strafe/jump/sneak, held sprint to move faster) instead fly this virtual camera
 * anywhere - including straight through terrain, since it's not a real entity with collision.
 *
 * <p>Since the player never actually moves, chunks are only ever loaded/sent to the client around
 * their stationary position, not wherever the camera flies off to - two things fight that:
 * activating temporarily maxes out view distance around the player (restored on deactivate,
 * see {@link #chunkLoadRadius}), and {@code CinematicChunkCacheMixin} keeps already-seen chunks
 * rendered (a scoped, in-memory, Bobby-mod-style cache) instead of letting them unload once
 * they're outside the player's own view radius, so previously-explored terrain stays visible
 * while the camera roams beyond it.
 *
 * <p>Deliberately restricted to singleplayer ({@link #toggle} refuses to activate otherwise): the
 * mechanism here - camera detached from an entity that appears to stand still - is exactly what a
 * multiplayer scouting exploit looks like (see the earlier, explicitly declined "freecam for
 * exploring a live server" request in this project's own history), and singleplayer is the one
 * context where that concern doesn't apply at all - there's no other real player to gain an
 * advantage over.
 */
public final class CinematicCameraModule extends AbstractModule implements Configurable {

	public static final KeyBinding TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.cinematic_camera_toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));

	private static final double MIN_SPEED = 0.5;
	private static final double MAX_SPEED = 200.0;
	private static final double SPRINT_MULTIPLIER = 4.0;

	private static volatile boolean active;
	private static volatile double camX;
	private static volatile double camY;
	private static volatile double camZ;
	private static volatile float camYaw;
	private static volatile float camPitch;
	private static volatile long lastFrameNanos = -1;
	private static volatile double flySpeed = 10.0;
	private static volatile int chunkLoadRadius = 32;
	private static volatile boolean cacheChunks = true;
	private static Integer previousViewDistance;

	public CinematicCameraModule() {
		super("cinematic-camera", "Cinematic Camera",
				"Free-flying camera for singleplayer cinematic shots (Replay-Mod-style, camera only - no "
						+ "recording yet). Your player stays frozen exactly where they are while the camera flies "
						+ "anywhere, including through blocks. Singleplayer only.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(client ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.TICK, () -> onTick(client)));
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled()) {
			deactivate();
			return;
		}
		while (TOGGLE_KEY.wasPressed()) {
			toggle(client);
		}
	}

	private void toggle(MinecraftClient client) {
		if (active) {
			deactivate();
			return;
		}
		if (client.player == null || client.world == null) {
			return;
		}
		camX = client.player.getX();
		camY = client.player.getEyeY();
		camZ = client.player.getZ();
		camYaw = client.player.getYaw();
		camPitch = client.player.getPitch();
		lastFrameNanos = -1;
		active = true;
		// The player never moves while this is active (by design - see the class javadoc), but
		// chunks are only ever loaded/sent to the client around the player's actual position, not
		// wherever this virtual camera flies off to - without this, flying more than a
		// render-distance's worth of blocks away just shows void. Temporarily maxing out view
		// distance around the (stationary) player instead covers a much larger area up front, so
		// the camera has real terrain to fly through for typical base-flythrough-sized shots.
		// This doesn't help a camera flown arbitrarily far from the player - that would need the
		// server to load chunks around the camera itself, which isn't implemented here.
		previousViewDistance = client.options.getViewDistance().getValue();
		client.options.getViewDistance().setValue(chunkLoadRadius);
		refreshRendering();
	}

	private void deactivate() {
		active = false;
		lastFrameNanos = -1;
		if (previousViewDistance != null) {
			MinecraftClient client = MinecraftClient.getInstance();
			client.options.getViewDistance().setValue(previousViewDistance);
			refreshRendering();
			previousViewDistance = null;
		}
	}

	/** Same verified per-version branches as {@code PerformanceBoostModule#refreshRendering} - a direct setValue() on the view distance option doesn't by itself re-queue terrain the way vanilla's own video settings screen does. */
	private static void refreshRendering() {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		if (client.worldRenderer != null) {
			client.worldRenderer.scheduleTerrainUpdate();
		}
		//?} else if <26.2 {
		/*if (client.levelRenderer != null) {
			client.levelRenderer.needsUpdate();
		}
		*///?} else {
		/*if (client.levelExtractor != null) {
			client.levelExtractor.allChanged();
		}
		*///?}
	}

	@Override
	public void onDisable() {
		deactivate();
	}

	public static boolean isActive() {
		return active && MinecraftClient.getInstance().player != null;
	}

	/** Whether {@code CinematicChunkCacheMixin} should keep already-seen chunks rendered instead of letting them unload as the camera flies away from the stationary player. */
	public static boolean isChunkCachingEnabled() {
		return isActive() && cacheChunks;
	}

	private static boolean isSingleplayer(MinecraftClient client) {
		//? if <26.1 {
		return client.isInSingleplayer();
		//?} else {
		/*return client.hasSingleplayerServer();
		*///?}
	}

	/** Called from {@code CinematicLookInputMixin} with the raw, unscaled cursor delta - same 0.15-per-pixel scale vanilla itself applies. */
	public static void accumulateLookDelta(double cursorDeltaX, double cursorDeltaY) {
		camYaw += (float) cursorDeltaX * 0.15f;
		camPitch = Math.clamp(camPitch + (float) cursorDeltaY * 0.15f, -90f, 90f);
	}

	/** Multiplies the fly speed on scroll (from the extended {@code MouseScrollMixin}), same idea as {@link ZoomModule}'s scroll-adjusted zoom level. */
	public static void adjustSpeedOnScroll(double vertical) {
		double factor = vertical > 0 ? 1.1 : 1 / 1.1;
		flySpeed = Math.clamp(flySpeed * factor, MIN_SPEED, MAX_SPEED);
	}

	/**
	 * Called once per rendered frame from {@code CinematicCameraMixin}, before it reads {@link
	 * #x()}/{@link #y()}/{@link #z()}/{@link #yaw()}/{@link #pitch()} to place the camera. Movement
	 * is integrated here (against a real elapsed-wall-clock-time delta, not the tick-interpolation
	 * fraction vanilla's own camera update receives) rather than once per client tick, so panning
	 * stays smooth at whatever frame rate is actually being rendered instead of visibly stepping at
	 * 20Hz.
	 */
	public static void updateFrame() {
		long now = System.nanoTime();
		if (lastFrameNanos < 0) {
			lastFrameNanos = now;
			return;
		}
		double dt = (now - lastFrameNanos) / 1_000_000_000.0;
		lastFrameNanos = now;
		// A stall (alt-tab, GC pause, ...) shouldn't fling the camera across the map once the
		// next frame finally renders.
		dt = Math.min(dt, 0.25);

		MinecraftClient client = MinecraftClient.getInstance();
		var options = client.options;
		double moveForward = (options.forwardKey.isPressed() ? 1 : 0) - (options.backKey.isPressed() ? 1 : 0);
		double moveRight = (options.rightKey.isPressed() ? 1 : 0) - (options.leftKey.isPressed() ? 1 : 0);
		double moveUp = (options.jumpKey.isPressed() ? 1 : 0) - (options.sneakKey.isPressed() ? 1 : 0);
		if (moveForward == 0 && moveRight == 0 && moveUp == 0) {
			return;
		}

		double yawRad = Math.toRadians(camYaw);
		double pitchRad = Math.toRadians(camPitch);
		// Same forward-direction convention as FreeLookModule.cameraOffset - proven correct
		// against the real renderer there.
		double forwardX = -Math.sin(yawRad) * Math.cos(pitchRad);
		double forwardY = -Math.sin(pitchRad);
		double forwardZ = Math.cos(yawRad) * Math.cos(pitchRad);
		// "Right" is just that same forward direction turned 90 degrees in yaw, kept horizontal
		// (pitch dropped) so strafing never drifts up/down while looking up or down.
		double rightYawRad = Math.toRadians(camYaw + 90);
		double rightX = -Math.sin(rightYawRad);
		double rightZ = Math.cos(rightYawRad);

		double dx = forwardX * moveForward + rightX * moveRight;
		double dy = forwardY * moveForward + moveUp;
		double dz = forwardZ * moveForward + rightZ * moveRight;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length < 1.0e-6) {
			return;
		}
		double speed = flySpeed * (options.sprintKey.isPressed() ? SPRINT_MULTIPLIER : 1.0) * dt / length;
		camX += dx * speed;
		camY += dy * speed;
		camZ += dz * speed;
	}

	public static double x() {
		return camX;
	}

	public static double y() {
		return camY;
	}

	public static double z() {
		return camZ;
	}

	public static float yaw() {
		return camYaw;
	}

	public static float pitch() {
		return camPitch;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Toggle Cinematic Camera", TOGGLE_KEY),
				new ConfigField.SliderField("Fly Speed (blocks/s)", MIN_SPEED, MAX_SPEED,
						() -> flySpeed, v -> flySpeed = v, v -> String.format(Locale.ROOT, "%.1f", v)),
				new ConfigField.SliderField("Chunk Load Radius While Active", 8, 32,
						() -> chunkLoadRadius, v -> chunkLoadRadius = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.ToggleField("Cache Already-Seen Chunks (Bobby-style)", () -> cacheChunks, v -> cacheChunks = v));
	}
}
