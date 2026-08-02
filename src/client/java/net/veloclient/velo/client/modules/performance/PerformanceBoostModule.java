package net.veloclient.velo.client.modules.performance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.option.InactivityFpsLimit;
import net.minecraft.particle.ParticlesMode;
import org.lwjgl.glfw.GLFW;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Presets plus individually-tunable video settings for FPS, replacing the
 * old single-preset "Performance Mode". The granular sliders/toggles below
 * the preset picker are live views straight onto vanilla's own options -
 * always editable, not gated behind this module's own enable switch - so
 * picking "Performance" and then nudging just the view distance back up
 * doesn't require re-toggling anything.
 *
 * <p>A few things worth being upfront about, since some of what's marketed
 * for other clients as distinct named features aren't independently
 * controllable through anything Minecraft actually exposes:
 * <ul>
 *   <li><b>Turbo Entities</b> maps to vanilla's own entity distance scaling -
 *       a real, working lever.</li>
 *   <li><b>Smart culling/occlusion</b> - vanilla already does frustum/chunk
 *       visibility culling itself; there's no further public hook to extend
 *       it (that's what dedicated rendering-engine mods like Sodium rewrite
 *       from scratch), so this doesn't add a separate toggle that would just
 *       do nothing.</li>
 *   <li><b>Animate only visible textures</b> - no vanilla API exposes
 *       per-texture visibility for animation; not implemented rather than
 *       faked.</li>
 *   <li><b>HUD Caching</b> is real and implemented here (see
 *       {@link #hudCachingEnabled}) - it throttles this mod's own expensive
 *       per-frame overlay work (e.g. counting every loaded entity) to about
 *       20 times/second instead of every frame.</li>
 *   <li><b>Dynamic FPS/unfocused capping</b> maps to vanilla's real
 *       inactivity FPS limiter.</li>
 *   <li><b>Memory allocation</b> is a JVM heap size (-Xmx), fixed at launch -
 *       nothing running inside the game can change it live, so it belongs in
 *       the launcher, not here.</li>
 *   <li><b>"Poly Patcher"</b> is another client's own closed-source internal
 *       system - not something that can be integrated.</li>
 * </ul>
 */
public final class PerformanceBoostModule extends AbstractModule implements Configurable {

	/** Read by other modules to throttle expensive per-frame work to ~20Hz instead of every frame. Default off - opt-in, not a silent behavior change. */
	public static volatile boolean hudCachingEnabled = false;

	private static final List<String> PRESETS = List.of("Default", "Performance", "Medium Quality", "High Quality");

	private GraphicsMode previousGraphics;
	private ParticlesMode previousParticles;
	private Boolean previousEntityShadows;
	private Integer previousViewDistance;
	private Boolean previousVsync;
	private CloudRenderMode previousClouds;
	private Integer previousBiomeBlend;
	private InactivityFpsLimit previousInactivityLimit;

	private String preset = "Default";
	private boolean dynamicFps = true;
	private String vsyncMode = "Off";

	public PerformanceBoostModule() {
		super("performance-boost", "Performance Boost",
				"Presets and individually-tunable settings for maximum FPS - view distance, entities, particles, VSync, HUD caching and more.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, true);
	}

	@Override
	public void onEnable() {
		GameOptions options = MinecraftClient.getInstance().options;
		previousGraphics = options.getPreset().getValue();
		previousParticles = options.getParticles().getValue();
		previousEntityShadows = options.getEntityShadows().getValue();
		previousViewDistance = options.getViewDistance().getValue();
		previousVsync = options.getEnableVsync().getValue();
		previousClouds = options.getCloudRenderMode().getValue();
		previousBiomeBlend = options.getBiomeBlendRadius().getValue();
		previousInactivityLimit = options.getInactivityFpsLimit().getValue();

		applyPreset(preset);
		if (dynamicFps) {
			options.getInactivityFpsLimit().setValue(InactivityFpsLimit.AFK);
		}
	}

	@Override
	public void onDisable() {
		if (previousGraphics == null) {
			return;
		}
		GameOptions options = MinecraftClient.getInstance().options;
		options.getPreset().setValue(previousGraphics);
		options.getParticles().setValue(previousParticles);
		options.getEntityShadows().setValue(previousEntityShadows);
		options.getViewDistance().setValue(previousViewDistance);
		options.getEnableVsync().setValue(previousVsync);
		options.getCloudRenderMode().setValue(previousClouds);
		options.getBiomeBlendRadius().setValue(previousBiomeBlend);
		options.getInactivityFpsLimit().setValue(previousInactivityLimit);
		previousGraphics = null;
	}

	private void applyPreset(String name) {
		GameOptions options = MinecraftClient.getInstance().options;
		switch (name) {
			case "Performance" -> {
				options.getPreset().setValue(GraphicsMode.FAST);
				options.getParticles().setValue(ParticlesMode.MINIMAL);
				options.getEntityShadows().setValue(false);
				options.getViewDistance().setValue(Math.min(options.getViewDistance().getValue(), 8));
				applyVsyncMode("Off");
				options.getCloudRenderMode().setValue(CloudRenderMode.OFF);
				options.getBiomeBlendRadius().setValue(0);
				options.getEntityDistanceScaling().setValue(0.75);
				options.getMipmapLevels().setValue(0);
			}
			case "Medium Quality" -> {
				options.getPreset().setValue(GraphicsMode.FAST);
				options.getParticles().setValue(ParticlesMode.DECREASED);
				options.getEntityShadows().setValue(false);
				options.getViewDistance().setValue(Math.max(options.getViewDistance().getValue(), 12));
				applyVsyncMode("Off");
				options.getCloudRenderMode().setValue(CloudRenderMode.FAST);
				options.getBiomeBlendRadius().setValue(2);
				options.getEntityDistanceScaling().setValue(1.0);
				options.getMipmapLevels().setValue(2);
			}
			case "High Quality" -> {
				options.getPreset().setValue(GraphicsMode.FANCY);
				options.getParticles().setValue(ParticlesMode.ALL);
				options.getEntityShadows().setValue(true);
				options.getViewDistance().setValue(Math.max(options.getViewDistance().getValue(), 16));
				applyVsyncMode("Off");
				options.getCloudRenderMode().setValue(CloudRenderMode.FANCY);
				options.getBiomeBlendRadius().setValue(5);
				options.getEntityDistanceScaling().setValue(1.5);
				options.getMipmapLevels().setValue(4);
			}
			default -> {
				// "Default" - since this module is enabled out of the box,
				// this preset must not touch graphics mode, particles,
				// shadows, clouds, biome blend, entity distance or mipmaps
				// at all: forcing those to Fancy/All/On unconditionally on
				// every fresh install, regardless of what the player already
				// had configured for their own hardware, was a real bug -
				// it made performance dramatically WORSE than plain vanilla
				// for anyone who had already turned those down. VSync off is
				// the only thing this preset applies, since it's a pure
				// performance lever with no visual-quality tradeoff of its
				// own that every preset (including this one) should have.
				applyVsyncMode("Off");
			}
		}
	}

	@Override
	public List<ConfigField> configFields() {
		GameOptions options = MinecraftClient.getInstance().options;
		return List.of(
				new ConfigField.ChoiceField("Preset", PRESETS, () -> preset, v -> {
					preset = v;
					if (isEnabled()) {
						applyPreset(v);
						refreshRendering();
					}
				}),
				new ConfigField.ToggleField("Dynamic FPS (throttle when unfocused/AFK)", () -> dynamicFps, v -> {
					dynamicFps = v;
					if (isEnabled()) {
						options.getInactivityFpsLimit().setValue(v ? InactivityFpsLimit.AFK : InactivityFpsLimit.MINIMIZED);
					}
				}),
				new ConfigField.ToggleField("HUD Caching (~20Hz overlay updates)", () -> hudCachingEnabled, v -> hudCachingEnabled = v),
				new ConfigField.ChoiceField("VSync Mode", List.of("Off", "On", "Adaptive"), () -> vsyncMode, this::applyVsyncMode),
				new ConfigField.SliderField("Max FPS", 10, 260,
						() -> options.getMaxFps().getValue(), v -> options.getMaxFps().setValue((int) v),
						v -> (int) v >= 260 ? "Unlimited" : String.valueOf((int) v)),
				new ConfigField.SliderField("View Distance", 2, 32,
						() -> options.getViewDistance().getValue(), v -> { options.getViewDistance().setValue((int) v); refreshRendering(); }, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Simulation Distance", 5, 32,
						() -> options.getSimulationDistance().getValue(), v -> options.getSimulationDistance().setValue((int) v), v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Turbo Entities (entity distance)", 0.5, 5.0,
						() -> options.getEntityDistanceScaling().getValue(),
						v -> { options.getEntityDistanceScaling().setValue(v); refreshRendering(); },
						v -> Math.round(v * 100) + "%"),
				new ConfigField.ToggleField("Entity Shadows", () -> options.getEntityShadows().getValue(),
						v -> { options.getEntityShadows().setValue(v); refreshRendering(); }),
				new ConfigField.ToggleField("Clouds", () -> options.getCloudRenderMode().getValue() != CloudRenderMode.OFF,
						v -> { options.getCloudRenderMode().setValue(v ? CloudRenderMode.FAST : CloudRenderMode.OFF); refreshRendering(); }),
				new ConfigField.SliderField("Biome Blend", 0, 7,
						() -> options.getBiomeBlendRadius().getValue(),
						v -> { options.getBiomeBlendRadius().setValue((int) v); refreshRendering(); }, v -> String.valueOf((int) v)),
				new ConfigField.SliderField("Mipmap Levels", 0, 4,
						() -> options.getMipmapLevels().getValue(),
						v -> { options.getMipmapLevels().setValue((int) v); reloadRendering(); }, v -> String.valueOf((int) v)),
				new ConfigField.ChoiceField("Particles", List.of("Minimal", "Decreased", "All"),
						() -> particlesLabel(options.getParticles().getValue()),
						v -> { options.getParticles().setValue(particlesFromLabel(v)); refreshRendering(); }));
	}

	/**
	 * Vanilla's own video settings screen doesn't just write the new value -
	 * it also pokes the world renderer to actually pick the change up, which
	 * these sliders weren't doing, so a dragged value only visibly took
	 * effect once something else (like re-enabling this module) happened to
	 * trigger a refresh incidentally.
	 */
	private static void refreshRendering() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.worldRenderer != null) {
			client.worldRenderer.scheduleTerrainUpdate();
		}
	}

	/** Mipmap levels change the texture atlas itself, not just how it's rendered - that needs the heavier full reload, not just a terrain re-queue. */
	private static void reloadRendering() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.worldRenderer != null) {
			client.worldRenderer.reload();
		}
	}

	/**
	 * Full VSync (swap interval 1) hard-caps FPS to the display's refresh
	 * rate, which is the "too harsh" tradeoff for tearing that pushes people
	 * to just leave it off. Adaptive VSync (interval -1) still syncs when a
	 * frame is ready in time - avoiding tearing the same way - but stops
	 * syncing instead of stalling when a frame runs late, so it doesn't
	 * impose the same hard ceiling. Vanilla's own VSync option is a plain
	 * on/off that only ever calls {@code glfwSwapInterval} with 0 or 1, so
	 * reaching -1 means calling it directly instead of going through that
	 * option. If the platform/driver doesn't actually support adaptive sync,
	 * GLFW itself falls back to regular VSync rather than erroring.
	 */
	private void applyVsyncMode(String mode) {
		vsyncMode = mode;
		GameOptions options = MinecraftClient.getInstance().options;
		switch (mode) {
			case "On" -> {
				GLFW.glfwSwapInterval(1);
				options.getEnableVsync().setValue(true);
			}
			case "Adaptive" -> {
				GLFW.glfwSwapInterval(-1);
				options.getEnableVsync().setValue(true);
			}
			default -> {
				GLFW.glfwSwapInterval(0);
				options.getEnableVsync().setValue(false);
			}
		}
	}

	private static String particlesLabel(ParticlesMode mode) {
		return switch (mode) {
			case MINIMAL -> "Minimal";
			case DECREASED -> "Decreased";
			case ALL -> "All";
		};
	}

	private static ParticlesMode particlesFromLabel(String label) {
		return switch (label) {
			case "Minimal" -> ParticlesMode.MINIMAL;
			case "Decreased" -> ParticlesMode.DECREASED;
			default -> ParticlesMode.ALL;
		};
	}
}
