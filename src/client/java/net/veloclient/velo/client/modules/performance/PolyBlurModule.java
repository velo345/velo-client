package net.veloclient.velo.client.modules.performance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.render.DefaultFramebufferSet;
import net.minecraft.client.util.memory.ObjectAllocator;
import net.minecraft.util.Identifier;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Motion blur, triggered by how fast the camera is actually turning/moving
 * rather than a constant always-on filter - closer to what "motion blur"
 * means elsewhere than a flat screen tint. Built on vanilla's own real,
 * already-shipped multi-pass box-blur shader (the exact one used to blur the
 * background behind the pause menu - see {@code assets/minecraft/post_effect/blur.json})
 * rather than untested custom GLSL, so it's reusing rendering code Mojang
 * already tests.
 *
 * <p>The post-effect API in this version has no way to change a loaded
 * shader's uniforms afterward, so a continuously variable blur radius isn't
 * possible without reflection into private fields - this instead ships three
 * pre-baked strength variants (Low/Medium/High, fixed blur radius each) as
 * this mod's own post-effect resources and switches between the cached,
 * fully-loaded processor for whichever one is selected.
 *
 * <p>Every GPU call here is wrapped defensively: if loading or rendering the
 * effect throws for any reason (a graphics driver difference, a resource
 * pack conflict, anything), the module logs it once and permanently disables
 * itself rather than risk repeating a crash every frame.
 */
public final class PolyBlurModule extends AbstractModule implements Configurable {

	private static final Map<String, Identifier> STRENGTH_EFFECTS = Map.of(
			"Low", Identifier.of("velo-client", "polyblur_low"),
			"Medium", Identifier.of("velo-client", "polyblur_medium"),
			"High", Identifier.of("velo-client", "polyblur_high"));
	private static final Map<Identifier, PostEffectProcessor> LOADED = new HashMap<>();
	private static volatile boolean broken;

	private String strength = "Low";
	private double sensitivity = 18.0;

	private float lastYaw;
	private float lastPitch;
	private double lastX;
	private double lastY;
	private double lastZ;
	private boolean havePrevious;

	public PolyBlurModule() {
		super("polyblur", "PolyBlur", "Motion blur that responds to how fast you're turning or moving - not a constant filter.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, false);
	}

	/** Called from {@link net.veloclient.velo.client.hud.HudManager} at the top of the frame, after the world (and hotbar) have already drawn but before the rest of the HUD. */
	public static void applyIfActive() {
		var module = net.veloclient.velo.module.ModuleRegistry.get("polyblur").orElse(null);
		if (!(module instanceof PolyBlurModule polyBlur) || !polyBlur.isEnabled() || broken) {
			return;
		}
		polyBlur.applyBlurIfMoving();
	}

	private void applyBlurIfMoving() {
		MinecraftClient client = MinecraftClient.getInstance();
		var player = client.player;
		if (player == null || client.world == null) {
			havePrevious = false;
			return;
		}
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
			havePrevious = true;
			return;
		}

		double angularSpeed = Math.abs(wrapDegrees(yaw - lastYaw)) + Math.abs(pitch - lastPitch);
		double moveSpeed = Math.sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ));
		lastYaw = yaw;
		lastPitch = pitch;
		lastX = x;
		lastY = y;
		lastZ = z;

		// moveSpeed is weighted low on purpose: ordinary walking/sprinting
		// (~0.2-0.3 blocks/tick) must not be enough to trigger this by
		// itself - only genuinely fast motion (a quick camera flick, elytra
		// flight, a big fall/knockback) should. The old weight of 10 made
		// this active almost constantly during normal movement, which read
		// as a permanent screen blur rather than a motion effect.
		boolean moving = angularSpeed + moveSpeed * 3 > sensitivity;
		if (!moving) {
			return;
		}

		try {
			Identifier effectId = STRENGTH_EFFECTS.getOrDefault(strength, STRENGTH_EFFECTS.get("Medium"));
			PostEffectProcessor processor = LOADED.computeIfAbsent(effectId,
					id -> client.getShaderLoader().loadPostEffect(id, DefaultFramebufferSet.MAIN_ONLY));
			if (processor != null) {
				//? if <26.1 {
				processor.render(client.getFramebuffer(), ObjectAllocator.TRIVIAL);
				//?} else if <26.2 {
				/*processor.process(client.getMainRenderTarget(), GraphicsResourceAllocator.UNPOOLED);
				*///?} else {
				/*processor.process(client.gameRenderer.mainRenderTarget(), GraphicsResourceAllocator.UNPOOLED);
				*///?}
			}
		} catch (Throwable t) {
			broken = true;
			VeloClient.LOGGER.error("PolyBlur failed to render - disabling it for this session", t);
		}
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
	public void onDisable() {
		havePrevious = false;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Strength", List.copyOf(STRENGTH_EFFECTS.keySet()), () -> strength, v -> strength = v),
				new ConfigField.SliderField("Motion Sensitivity", 5, 40, () -> sensitivity, v -> sensitivity = v,
						v -> v <= 12 ? "High" : (v <= 25 ? "Medium" : "Low")));
	}
}
