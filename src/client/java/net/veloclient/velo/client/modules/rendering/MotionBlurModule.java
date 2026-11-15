package net.veloclient.velo.client.modules.rendering;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.render.DefaultFramebufferSet;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Motion Blur (replaced the old "PolyBlur" box blur in 1.5): a real camera motion blur, not a performance filter.
 * Every frame the camera's own motion is measured and turned into a streak - turning smears the picture sideways or
 * up/down, fast travel (sprinting, elytra, falling) adds zoom streaks from the centre, and on 26.1+ the previous blurred
 * frame is blended in for a trail. The amounts reach the shader through a 4x1 texture rewritten each frame (the post
 * effect API can't change uniforms after loading), so the blur is continuous and scales with the speed instead of
 * switching on and off. Standing still costs nothing: the effect is skipped and the trail resets.
 *
 * <p>Default off; the id changed from {@code polyblur} so it starts off for everyone, even players who had PolyBlur on.
 * If the effect fails to load or render it disables itself for the session instead of failing every frame.
 */
public final class MotionBlurModule extends AbstractModule implements Configurable {

	//? if >=26.1 {
	/*private static final Identifier EFFECT = Identifier.of("velo-client", "motion_blur");
	*///?} else {
	private static final Identifier EFFECT = Identifier.of("velo-client", "motion_blur_notrail");
	//?}
	private static final Identifier PARAMS_ID = Identifier.of("velo-client", "textures/effect/motion_params.png");
	private static final List<String> QUALITY = List.of("Fast", "Balanced", "Smooth", "Ultra");
	//? if <26.1 {
	//? if <1.21.2 {
	/*private static final Object POOL = null; // 1.21.1: no post effects
	*///?} else {
	private static final net.minecraft.client.util.memory.ObjectPool POOL = new net.minecraft.client.util.memory.ObjectPool(3);
	//?}
	//?} else {
	/*private static final com.mojang.blaze3d.resource.CrossFrameResourcePool POOL = new com.mojang.blaze3d.resource.CrossFrameResourcePool(3);
	*///?}

	private static MotionBlurModule instance;
	private static PostEffectProcessor processor;
	private static NativeImageBackedTexture texture;
	private static volatile boolean broken;

	private double strength = 1.0;
	private double trail = 0.45;
	private double zoomBlur = 0.6;
	private double maxBlur = 0.6;
	private String quality = "Balanced";
	private boolean whileStill = false;

	private boolean havePrevious;
	private boolean resetTrail = true;
	private float lastYaw;
	private float lastPitch;
	private double lastX;
	private double lastY;
	private double lastZ;
	private long lastNanos;
	private long lastMoveNanos;
	private double speedEma;
	private double yawRate;
	private double pitchRate;

	public MotionBlurModule() {
		super("motion-blur", "Motion Blur", "Real camera motion blur: streaks when you turn, zoom streaks when you go fast, and a trail - scales with your speed.",
				ModuleCategory.RENDERING, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
	}

	/** Called once per frame after the world and before the HUD (PostEffectsFrameMixin on 26.2+, HudManager before). */
	public static void applyIfActive() {
		MotionBlurModule m = instance;
		if (m == null || !m.isEnabled() || broken) {
			return;
		}
		m.apply();
	}

	private void apply() {
		MinecraftClient client = MinecraftClient.getInstance();
		var player = client.player;
		if (player == null || client.world == null) {
			havePrevious = false;
			return;
		}
		long now = System.nanoTime();
		float yaw = player.getYaw();
		float pitch = player.getPitch();
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		if (!havePrevious) {
			lastYaw = yaw;
			lastPitch = pitch;
			lastX = x;
			lastY = y;
			lastZ = z;
			lastNanos = now;
			lastMoveNanos = now;
			speedEma = 0;
			yawRate = 0;
			pitchRate = 0;
			havePrevious = true;
			resetTrail = true;
			return;
		}
		double dt = Math.max(1e-4, Math.min(0.1, (now - lastNanos) / 1e9));
		lastNanos = now;
		double dYaw = wrapDegrees(yaw - lastYaw);
		double dPitch = pitch - lastPitch;
		lastYaw = yaw;
		lastPitch = pitch;
		// degrees per second, lightly smoothed: mouse input arrives in uneven chunks at high FPS
		double k = Math.min(1.0, dt * 40);
		yawRate += (dYaw / dt - yawRate) * k;
		pitchRate += (dPitch / dt - pitchRate) * k;

		// The position only changes once per 50 ms tick, so the speed is a time-smoothed average (blocks per second).
		double moved = Math.sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ));
		if (moved > 0 || now - lastMoveNanos > 120_000_000L) {
			double span = Math.max(0.01, Math.min(0.25, (now - lastMoveNanos) / 1e9));
			double speed = moved > 20 ? 0 : moved / span;   // teleports don't count
			speedEma += (speed - speedEma) * 0.5;
			lastMoveNanos = now;
			lastX = x;
			lastY = y;
			lastZ = z;
		}

		// Streak length = how far the picture travels while a virtual shutter is open (1/50 s at 100% strength).
		double shutter = 0.02 * strength;
		double vfov = Math.max(30, client.options.getFov().getValue().intValue());
		double aspect = client.getWindow().getWidth() / (double) Math.max(1, client.getWindow().getHeight());
		double hfov = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(vfov) / 2) * aspect));
		double dirX = -yawRate * shutter / hfov;
		double dirY = pitchRate * shutter / vfov;
		double len = Math.sqrt(dirX * dirX + dirY * dirY);
		double cap = 0.25 * maxBlur;
		if (len > cap) {
			dirX *= cap / len;
			dirY *= cap / len;
			len = cap;
		}
		// zoom streaks start above sprint speed (~5.6 b/s) and are full at elytra speeds (~30 b/s)
		double zoom = zoomBlur * strength * clamp((speedEma - 6) / 24, 0, 1) * maxBlur;
		double motion = len * 40 + zoom * 4;   // ~1 = clearly moving
		if (motion < 0.02 && !whileStill) {
			resetTrail = true;   // nothing to smear: skip the passes, and don't blend in a stale frame later
			return;
		}
		double trailNow = resetTrail ? 0 : trail * clamp(motion * 1.5, whileStill ? 0.3 : 0, 1);
		resetTrail = false;
		int samples = switch (quality) {
			case "Fast" -> 8;
			case "Smooth" -> 24;
			case "Ultra" -> 40;
			default -> 14;
		};

		try {
			uploadParams(client, dirX / 0.25, dirY / 0.25, zoom, trailNow / 0.95, samples);
			//? if >=1.21.2 {
			if (processor == null) {
				processor = load(client, EFFECT);
			}
			//?}
			if (processor == null) {
				return;
			}
			//? if <26.1 {
			//? if >=1.21.2 {
			processor.render(client.getFramebuffer(), POOL);
			POOL.decrementLifespan();
			//?}
			//?} else if <26.2 {
			/*processor.process(client.getMainRenderTarget(), POOL);
			POOL.endFrame();
			*///?} else {
			/*processor.process(client.gameRenderer.mainRenderTarget(), POOL);
			POOL.endFrame();
			*///?}
		} catch (Throwable t) {
			broken = true;
			VeloClient.LOGGER.error("Motion Blur failed to render - disabling it for this session", t);
		}
	}

	//? if >=1.21.2 {
	private static PostEffectProcessor load(MinecraftClient client, Identifier id) {
		return client.getShaderLoader().loadPostEffect(id, DefaultFramebufferSet.MAIN_ONLY);
	}
	//?}

	/** Packs the frame's values into the 4x1 params texture (decoded in velo_motion_blur.fsh). */
	private static void uploadParams(MinecraftClient client, double dirX, double dirY, double zoom, double trail, int samples) {
		if (texture == null) {
			texture = new NativeImageBackedTexture(() -> "velo-motion-blur", new NativeImage(4, 1, false));
			client.getTextureManager().registerTexture(PARAMS_ID, texture);
		}
		int sx = s16(dirX);
		int sy = s16(dirY);
		NativeImage image = texture.getImage();
		// setColorArgb(a<<24 | r<<16 | g<<8 | b): pixel 0 = (x hi, x lo, y hi, y lo), pixel 1 = (zoom, trail, samples, -)
		image.setColorArgb(0, 0, (sy & 0xFF) << 24 | (sx >> 8) << 16 | (sx & 0xFF) << 8 | (sy >> 8));
		image.setColorArgb(1, 0, 0xFF << 24 | byteOf(zoom / 0.25) << 16 | byteOf(trail) << 8 | byteOf(samples / 64.0));
		texture.upload();
	}

	private static int s16(double v) {
		return Math.max(0, Math.min(65535, (int) Math.round((clamp(v, -1, 1) + 1) / 2 * 65535)));
	}

	private static int byteOf(double v) {
		return Math.max(0, Math.min(255, (int) Math.round(v * 255)));
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static double wrapDegrees(double degrees) {
		double d = degrees % 360;
		if (d < -180) {
			d += 360;
		}
		if (d > 180) {
			d -= 360;
		}
		return d;
	}

	@Override
	public void onEnable() {
		havePrevious = false;
		resetTrail = true;
	}

	@Override
	public void onDisable() {
		havePrevious = false;
		//? if >=1.21.2 {
		POOL.clear();
		//?}
	}

	@Override
	public List<ConfigField> configFields() {
		java.util.function.DoubleFunction<String> pct = v -> Math.round(v * 100) + "%";
		return List.of(
				new ConfigField.SliderField("Strength", 0.2, 3.0, () -> strength, v -> strength = Math.round(v * 20) / 20.0, pct),
				new ConfigField.SliderField("Trail", 0, 0.9, () -> trail, v -> trail = Math.round(v * 100) / 100.0, pct),
				new ConfigField.SliderField("Speed Zoom Blur", 0, 1.5, () -> zoomBlur, v -> zoomBlur = Math.round(v * 100) / 100.0, pct),
				new ConfigField.SliderField("Max Blur", 0.1, 1.0, () -> maxBlur, v -> maxBlur = Math.round(v * 100) / 100.0, pct),
				new ConfigField.ChoiceField("Quality", QUALITY, () -> quality, v -> quality = v),
				new ConfigField.ToggleField("Keep Trail When Still", () -> whileStill, v -> whileStill = v));
	}
}
