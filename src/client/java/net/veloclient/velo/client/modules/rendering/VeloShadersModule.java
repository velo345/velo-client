package net.veloclient.velo.client.modules.rendering;

import net.veloclient.velo.VeloClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Velo Shaders: a shader system built into vanilla's own render pipeline, so it runs on the Vulkan
 * backend (26.2+) where Iris/OptiFine-style packs can't. Not one fixed look - presets plus control
 * over every part of the scenery: water (waves, colour, clarity, reflections), light (sun warmth,
 * directional shading, night, torch colour), fog (distance and ground fog), a procedural sky (sky
 * and horizon colours, sunsets, sun, moon with phases, stars, aurora) and post processing (bloom,
 * exposure, contrast, saturation, tint, vignette). No shadow-map pass, so it costs a few percent
 * instead of halving the FPS.
 *
 * <p>The GLSL lives in a built-in resource pack ({@code resourcepacks/velo_shaders_<version>}),
 * which this module selects/deselects. Everything adjustable reaches the shaders as uniform data
 * appended to vanilla's {@code Globals} block and to Sodium's globals (its terrain has its own
 * shaders), written every frame by {@link #writeGlobals}: moving a slider never needs a reload.
 * Sodium's block only has 64 spare bytes, so the settings travel as 8-bit values packed four to a
 * uint ({@code VeloPack*}, decoded by {@code velo_lib.glsl}).
 */
public final class VeloShadersModule extends AbstractModule implements Configurable {

	private static VeloShadersModule instance;
	/** Read on the render thread every frame. */
	private static volatile boolean active;

	public static final List<String> PRESETS = List.of("Realistic", "Cinematic", "Golden Hour", "Fantasy", "Moody", "Clear", "Vanilla+",
			"Performance");

	private String preset = "Realistic";

	// Water
	private boolean water = true;
	private double waveHeight = 1.0;
	private double reflections = 0.85;
	private double waterClarity = 0.45;
	private int waterTint = 0x2E7FD6;
	// Light
	private double lighting = 1.0;
	private double sunWarmth = 0.6;
	private double wavingPlants = 0.7;
	private double nightBrightness = 0.45;
	private int blockLight = 0xFFC48A;
	private double blockLightStrength = 0.7;
	// Ray-traced light (post pass)
	private boolean shadowMap = true;
	private boolean entityShadows = true;
	private double rtShadows = 1.0;
	private double ambientOcclusion = 0.6;
	private boolean coloredLights = true;
	private double coloredLightStrength = 1.0;
	private boolean handheldLights = true;
	private double sunLight = 1.0;
	private double godRays = 0.8;
	private double volumetricFog = 0.5;
	// Fog
	private double fogDensity = 0.25;
	private boolean groundFog = true;
	private double fogSkyBlend = 0.5;
	// Sky
	private boolean sky = true;
	private int zenithTint = 0x3F7BE0;
	private double zenithTintAmount = 0.35;
	private int horizonTint = 0xC9DDF5;
	private double horizonTintAmount = 0.25;
	private int sunsetColor = 0xFF7A3D;
	private double sunsetIntensity = 0.8;
	private double sunSize = 0.45;
	private double sunGlow = 0.6;
	private double moonSize = 0.45;
	private double starBrightness = 0.7;
	private double starDensity = 0.5;
	private double aurora = 0.0;
	private double milkyWay = 0.8;
	private double shootingStars = 0.6;
	private double skyClouds = 0.7;
	private boolean vanillaClouds = false;
	private double emissiveGlow = 0.6;
	// Post
	private double bloom = 0.5;
	private double exposure = 1.0;
	private double contrast = 1.05;
	private double saturation = 1.06;
	private double vignette = 0.3;
	private int tint = 0xFFF4E6;
	private double tintAmount = 0.06;

	// Captured from the sky renderer each frame (radians, 0 = noon) / ARGB / 0-7.
	private static volatile float sunAngle;
	private static volatile int skyColor = 0xFF78A7FF;
	private static volatile int moonPhase;
	private static final long START = System.nanoTime();

	public VeloShadersModule() {
		super("velo-shaders", "Velo Shaders",
				"Beta: custom Vulkan shaders, still in development. Shaders that run on Vulkan, fully customizable: presets, waving water with reflections, warm light, nicer fog, a custom sky with sun, moon, stars and aurora, bloom and colour grading - at a fraction of the usual shader cost.",
				ModuleCategory.RENDERING, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
		applyPreset(preset);
	}

	@Override
	public boolean beta() {
		return true;
	}

	public static boolean active() {
		return active;
	}

	public static boolean skyActive() {
		VeloShadersModule m = instance;
		return active && m != null && m.sky;
	}

	// Dev: -Dvelo.shaderAB=true cycles through feature sets every 8 s (logged) to measure what each costs.
	private static final boolean AB = Boolean.getBoolean("velo.shaderAB");
	private static final String[] AB_NAMES = {"all", "no clouds", "no shadow map", "no light pass", "no bloom/grade", "chunk shaders only"};
	private static int abLogged = -1;

	private static int abPhase() {
		if (!AB) {
			return 0;
		}
		int phase = (int) ((System.nanoTime() / 8_000_000_000L) % AB_NAMES.length);
		if (phase != abLogged) {
			abLogged = phase;
			VeloClient.LOGGER.info("Velo Shaders A/B: {}", AB_NAMES[phase]);
		}
		return phase;
	}

	/** Whether Sodium's terrain is drawn into the sun shadow map this frame. */
	public static boolean shadowMapActive() {
		VeloShadersModule m = instance;
		int ab = abPhase();
		return lightActive() && m.shadowMap && m.rtShadows > 0.01 && SODIUM && ab != 2;
	}

	/** Entities are drawn into the shadow map (real silhouettes; replaces the capsule shadows). */
	public static boolean entityShadowsActive() {
		VeloShadersModule m = instance;
		return shadowMapActive() && m.entityShadows;
	}

	private static final boolean SODIUM = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");

	/** Whether the light pass (shadows, AO, coloured light) has anything to do this frame. */
	public static boolean lightActive() {
		VeloShadersModule m = instance;
		int ab = abPhase();
		if (ab == 3 || ab == 5) {
			return false;
		}
		return active && m != null && (m.rtShadows > 0.01 || m.ambientOcclusion > 0.01 || m.coloredLights || m.handheldLights
				|| m.sunLight > 0.01 || m.godRays > 0.01 || m.volumetricFog > 0.01 || m.skyClouds > 0.01);
	}

	/** Whether the bloom/grading pass has anything to do this frame. */
	public static boolean postActive() {
		VeloShadersModule m = instance;
		int ab = abPhase();
		if (ab == 4 || ab == 5) {
			return false;
		}
		return active && m != null && (m.bloom > 0.01 || Math.abs(m.exposure - 1) > 0.01 || Math.abs(m.contrast - 1) > 0.01
				|| Math.abs(m.saturation - 1) > 0.01 || m.vignette > 0.01 || m.tintAmount > 0.01);
	}

	public static void captureSky(float angleRadians, int argb, int phase) {
		sunAngle = angleRadians;
		skyColor = argb;
		moonPhase = phase;
	}

	/** This frame's shader data (render thread): written with vanilla's Globals, reused for Sodium's. */
	public static final float[] FRAME_FLOATS = new float[12];
	public static final int[] FRAME_PACKS = new int[16];

	/** Dev: -Dvelo.shaderDebug=1 distance bands, 2 normals, 3 light sum, 4 shadow, 5 AO (post lighting pass). */
	private static final int DEBUG_VIEW = Integer.getInteger("velo.shaderDebug", 0);

	/** This frame's point lights (8 floats each, camera-relative) and how many. */
	public static final float[] FRAME_LIGHTS = new float[VeloShaderLights.MAX * 8];

	/**
	 * Extra bytes the Globals block grows by: six vec4 (writeGlobals), four mat4 (projection, its
	 * inverse, view rotation, its inverse), two vec4 of light settings and two vec4 per light.
	 */
	public static final int GLOBALS_EXTRA = 7 * 16 + 5 * 64 + 4 * 16 + VeloShaderLights.MAX * 2 * 16 + VeloShaderLights.MAX_ENTITIES * 2 * 16 + 64;

	/** This frame's shadow capsules (8 floats each) and how many. */
	public static final float[] FRAME_ENTITIES = new float[VeloShaderLights.MAX_ENTITIES * 8];
	public static int frameEntityCount;

	/**
	 * VeloLightInfo (light count, shadow strength, AO strength, coloured-light strength) and
	 * VeloLightInfo2 (depth range 0..1 = 1, debug view, god rays, volumetric fog) and VeloLightInfo3
	 * (sunlight/shade colours, 0, 0, 0); fills FRAME_LIGHTS. {@code info} holds 16 floats (the last vec4 is VeloShadowInfo).
	 */
	public static void writeLightInfo(float[] info, double cameraX, double cameraY, double cameraZ, boolean zZeroToOne) {
		VeloShadersModule m = instance;
		java.util.Arrays.fill(info, 0f);
		info[4] = zZeroToOne ? 1f : 0f;
		info[5] = DEBUG_VIEW;
		frameEntityCount = 0;
		if (!active || m == null) {
			return;
		}
		int count = m.coloredLights || m.handheldLights
				? VeloShaderLights.collect(FRAME_LIGHTS, cameraX, cameraY, cameraZ, m.handheldLights) : 0;
		if (!m.coloredLights) {
			// handheld only: collect() puts those first
			count = Math.min(count, countHandheld());
		}
		info[0] = count;
		info[1] = (float) m.rtShadows;
		info[2] = (float) m.ambientOcclusion;
		info[3] = (float) m.coloredLightStrength;
		info[6] = (float) m.godRays;
		info[7] = (float) m.volumetricFog;
		info[8] = (float) m.sunLight;
		// VeloShadowInfo: map in use this frame, its half-size in blocks, its size in texels.
		info[12] = shadowMapActive() && VeloShadowMap.valid ? 1f : 0f;
		// VeloLightInfo3.w: entity near map drawn this frame (the light pass then takes entity shadows from it).
		info[11] = entityShadowsActive() && VeloShadowMap.valid && !VeloShadowEntities.disabled ? 1f : 0f;
		// Capsules: entity shadows on the shader clouds, and on the ground when there's no near map.
		frameEntityCount = m.entityShadows ? VeloShaderLights.collectEntities(FRAME_ENTITIES, cameraX, cameraY, cameraZ) : 0;
		info[15] = frameEntityCount;
		info[13] = VeloShadowMap.RADIUS;
		info[14] = VeloShadowMap.SIZE;
		// VeloLightInfo3.y: shader cloud coverage (the light pass draws the volumetric layer).
		info[9] = abPhase() == 1 ? 0f : (float) m.skyClouds;
	}

	private static int countHandheld() {
		return VeloShaderLights.lastHandheld();
	}

	/** Called every client tick (rescans light-emitting blocks now and then). */
	public static void clientTick() {
		//? if >=26.2 {
		/*if (!packChecked && net.minecraft.client.Minecraft.getInstance().getResourcePackRepository() != null) {
			// Once after startup: the pack selection (options.txt) follows the module, so shaders that are off
			// leave nothing loaded - even when options and profile disagree (profile switch, Cloud Sync).
			packChecked = true;
			syncPack(active);
		}
		*///?}
		VeloShaderLights.tick();
	}

	private static boolean packChecked;

	/**
	 * The block appended to Globals - see the pack's globals.glsl. Floats: VeloSun (direction to the
	 * sun, daylight), VeloCam (camera position mod 2048, seconds), VeloSky (vanilla sky colour, rain).
	 * Then four uvec4 of packed 8-bit settings (VeloPack: terrain, VeloPack2: sky/post, VeloPack3:
	 * colours, VeloPack4: night sky and clouds). Sodium's block takes the first three vec4s and VeloPack.
	 */
	public static void writeGlobals(float[] floats, int[] packs, double cameraX, double cameraY, double cameraZ, float rain) {
		VeloShadersModule m = instance;
		double a = sunAngle;
		float sx = (float) -Math.sin(a);
		float sy = (float) Math.cos(a);
		floats[0] = sx;
		floats[1] = sy;
		floats[2] = 0f;
		floats[3] = smoothstep(-0.12f, 0.22f, sy);
		floats[4] = (float) (cameraX - Math.floor(cameraX / 2048.0) * 2048.0);
		floats[5] = (float) cameraY;
		floats[6] = (float) (cameraZ - Math.floor(cameraZ / 2048.0) * 2048.0);
		floats[7] = (float) (((System.nanoTime() - START) / 1e9) % 3600.0);
		floats[8] = ((skyColor >> 16) & 0xFF) / 255f;
		floats[9] = ((skyColor >> 8) & 0xFF) / 255f;
		floats[10] = (skyColor & 0xFF) / 255f;
		floats[11] = rain;
		if (!active || m == null) {
			java.util.Arrays.fill(packs, 0);
			return;
		}
		int flags = (m.water ? 1 : 0) | (m.sky ? 2 : 0) | (m.groundFog ? 4 : 0) | (lightActive() && m.volumetricFog > 0.01 ? 8 : 0)
				| ((int) Math.round(Math.max(0, Math.min(1, m.emissiveGlow)) * 7) << 5); // bits 5-7: emissive glow 0-7
		packs[0] = pack(m.lighting / 1.5, m.sunWarmth, m.nightBrightness, m.wavingPlants);
		packs[1] = rgbA(m.blockLight, m.blockLightStrength);
		packs[2] = rgbA(m.waterTint, m.waterClarity);
		packs[3] = b(m.waveHeight / 2) | (b(m.reflections) << 8) | (b(m.fogDensity) << 16) | (flags << 24);
		packs[4] = pack(m.bloom / 1.5, m.exposure / 2, m.contrast / 2, m.saturation / 2);
		packs[5] = pack(m.vignette, m.sunSize, m.sunGlow, m.moonSize);
		packs[6] = b(m.starBrightness) | (b(m.starDensity) << 8) | (b(m.aurora) << 16) | ((moonPhase & 7) << 24);
		packs[7] = b(m.fogSkyBlend) | (b(m.sunsetIntensity) << 8) | (b(m.tintAmount) << 16);
		packs[8] = rgbA(m.zenithTint, m.zenithTintAmount);
		packs[9] = rgbA(m.horizonTint, m.horizonTintAmount);
		packs[10] = rgbA(m.sunsetColor, 1);
		packs[11] = rgbA(m.tint, m.tintAmount);
		packs[12] = pack(m.milkyWay, m.shootingStars, m.skyClouds, 0);
		packs[13] = 0;
		packs[14] = 0;
		packs[15] = 0;
	}

	/** Vanilla's block clouds are hidden while the shader sky draws its own (unless turned back on). */
	public static boolean hideVanillaClouds() {
		VeloShadersModule m = instance;
		return skyActive() && lightActive() && !m.vanillaClouds && m.skyClouds > 0.01;
	}

	// GLSL reads byte i as (word >> 8*i) & 255: byte 0 = red for colours, written as 0xAABBGGRR.
	private static int rgbA(int rgb, double amount) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int bl = rgb & 0xFF;
		return r | (g << 8) | (bl << 16) | (b(amount) << 24);
	}

	private static int pack(double x, double y, double z, double w) {
		return b(x) | (b(y) << 8) | (b(z) << 16) | (b(w) << 24);
	}

	private static int b(double v) {
		return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
		return t * t * (3f - 2f * t);
	}

	private void applyPreset(String name) {
		// Old preset names (saved configs keep them).
		name = switch (name) {
			case "Balanced" -> "Realistic";
			case "Dreamy" -> "Fantasy";
			case "Crisp" -> "Clear";
			default -> PRESETS.contains(name) ? name : "Realistic";
		};
		// Start from Realistic, then each preset changes what makes it different.
		water = true;
		waveHeight = 1.0;
		reflections = 0.85;
		waterClarity = 0.45;
		waterTint = 0x2E7FD6;
		shadowMap = true;
		entityShadows = true;
		rtShadows = 1.0;
		ambientOcclusion = 0.6;
		coloredLights = true;
		coloredLightStrength = 1.0;
		handheldLights = true;
		sunLight = 1.0;
		godRays = 0.8;
		volumetricFog = 0.5;
		lighting = 1.0;
		sunWarmth = 0.6;
		wavingPlants = 0.7;
		nightBrightness = 0.45;
		blockLight = 0xFFC48A;
		blockLightStrength = 0.7;
		fogDensity = 0.25;
		groundFog = true;
		fogSkyBlend = 0.5;
		sky = true;
		zenithTint = 0x3F7BE0;
		zenithTintAmount = 0.35;
		horizonTint = 0xC9DDF5;
		horizonTintAmount = 0.25;
		sunsetColor = 0xFF7A3D;
		sunsetIntensity = 0.8;
		sunSize = 0.45;
		sunGlow = 0.6;
		moonSize = 0.45;
		starBrightness = 0.7;
		starDensity = 0.5;
		aurora = 0.0;
		milkyWay = 0.8;
		shootingStars = 0.6;
		skyClouds = 0.7;
		vanillaClouds = false;
		emissiveGlow = 0.6;
		bloom = 0.5;
		exposure = 1.0;
		contrast = 1.05;
		saturation = 1.06;
		vignette = 0.3;
		tint = 0xFFF4E6;
		tintAmount = 0.06;
		// Every preset keeps nights readable (night brightness never below the base) and exposure near
		// 1 - they differ in mood, not in how much you can see.
		switch (name) {
			case "Cinematic" -> {
				// Film look: deeper contrast, god rays and haze, a warm grade, a soft vignette.
				contrast = 1.12;
				saturation = 1.02;
				vignette = 0.42;
				bloom = 0.7;
				tint = 0xFFE6CC;
				tintAmount = 0.1;
				godRays = 1.0;
				volumetricFog = 0.7;
				sunGlow = 0.75;
				sunsetIntensity = 0.9;
			}
			case "Golden Hour" -> {
				// Warm sun all day, rich sunsets, glowing horizon.
				sunWarmth = 1.0;
				sunsetIntensity = 1.0;
				sunsetColor = 0xFF8A3D;
				horizonTint = 0xFFD2A6;
				horizonTintAmount = 0.35;
				tint = 0xFFD9A8;
				tintAmount = 0.18;
				bloom = 0.65;
				sunGlow = 0.85;
				sunSize = 0.55;
				godRays = 1.0;
			}
			case "Fantasy" -> {
				// Vivid colours, turquoise water, aurora and a dense starry night.
				saturation = 1.2;
				contrast = 1.0;
				bloom = 0.75;
				zenithTint = 0x6E7BFF;
				zenithTintAmount = 0.35;
				horizonTint = 0xFFC9EC;
				horizonTintAmount = 0.3;
				waterTint = 0x2FC4D2;
				waterClarity = 0.6;
				aurora = 0.6;
				starDensity = 0.8;
				starBrightness = 0.9;
				milkyWay = 1.0;
				shootingStars = 0.9;
				coloredLightStrength = 1.3;
				emissiveGlow = 0.8;
			}
			case "Moody" -> {
				// Overcast and atmospheric: muted colours, heavy clouds, mist - but nights you can still see in.
				saturation = 0.8;
				contrast = 1.1;
				vignette = 0.4;
				fogDensity = 0.5;
				volumetricFog = 0.85;
				skyClouds = 0.95;
				zenithTint = 0x6C7A8A;
				zenithTintAmount = 0.35;
				horizonTint = 0xA9B3BD;
				horizonTintAmount = 0.35;
				waterTint = 0x2E5F72;
				sunWarmth = 0.4;
				tint = 0xDCE8FF;
				tintAmount = 0.08;
				bloom = 0.4;
			}
			case "Clear" -> {
				// Sharp and clean: no haze or fog, clear water, little grading.
				bloom = 0.3;
				vignette = 0.1;
				fogDensity = 0.05;
				groundFog = false;
				volumetricFog = 0.0;
				contrast = 1.06;
				saturation = 1.1;
				waterClarity = 0.7;
				tintAmount = 0.0;
				godRays = 0.5;
				nightBrightness = 0.55;
			}
			case "Vanilla+" -> {
				// Minecraft's own look, just better: vanilla sky colours and clouds, softer shadows, light grading.
				bloom = 0.25;
				contrast = 1.0;
				saturation = 1.03;
				vignette = 0.1;
				tintAmount = 0.0;
				fogDensity = 0.1;
				volumetricFog = 0.0;
				zenithTintAmount = 0.0;
				horizonTintAmount = 0.0;
				sunWarmth = 0.4;
				waveHeight = 0.6;
				rtShadows = 0.75;
				skyClouds = 0.0;
				vanillaClouds = true;
				milkyWay = 0.4;
				shootingStars = 0.3;
				nightBrightness = 0.55;
			}
			case "Performance" -> {
				// The look of Realistic with the costly passes off: no god rays, volumetric fog or shader clouds.
				godRays = 0.0;
				volumetricFog = 0.0;
				skyClouds = 0.0;
				vanillaClouds = true;
				ambientOcclusion = 0.4;
				bloom = 0.35;
			}
			default -> {
			}
		}
		preset = name;
	}

	@Override
	public void onEnable() {
		active = true;
		syncPack(true);
	}

	@Override
	public void onDisable() {
		active = false;
		syncPack(false);
	}

	/** Selects/deselects the built-in shader pack, reloading resources only if that changes anything. */
	private static void syncPack(boolean on) {
		//? if >=26.2 {
		/*try {
			net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
			if (client == null || client.getResourcePackRepository() == null) {
				return;
			}
			client.execute(() -> {
				var repository = client.getResourcePackRepository();
				String id = repository.getAvailableIds().stream().filter(p -> p.contains(PACK_NAME)).findFirst().orElse(null);
				if (id == null) {
					VeloClient.LOGGER.warn("Velo Shaders: built-in pack {} not found", PACK_NAME);
					return;
				}
				boolean selected = repository.getSelectedIds().contains(id);
				if (selected == on) {
					return;
				}
				if (on) {
					repository.addPack(id);
				} else {
					repository.removePack(id);
				}
				client.options.updateResourcePacks(repository);
			});
		} catch (RuntimeException e) {
			VeloClient.LOGGER.error("Velo Shaders: couldn't switch the shader pack", e);
		}
		*///?}
	}

	/** Registers the built-in shader pack ("Velo Shaders" in the pack list; this module selects it) and the light scanner's tick. */
	public static void registerPack() {
		//? if >=26.2 {
		/*net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("velo-client").ifPresent(container ->
				net.fabricmc.fabric.api.resource.v1.ResourceLoader.registerBuiltinPack(
						net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", PACK_NAME), container,
						net.minecraft.network.chat.Component.literal("Velo Shaders"),
						net.fabricmc.fabric.api.resource.v1.pack.PackActivationType.NORMAL));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> clientTick());
		*///?}
	}

	//? if >=26.3 {
	/*public static final String PACK_NAME = "velo_shaders_26_3";
	*///?} else {
	public static final String PACK_NAME = "velo_shaders_26_2";
	//?}

	private static ConfigField percent(String label, double max, java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set) {
		return new ConfigField.SliderField(label, 0, max, get, set, v -> String.format("%.0f%%", v * 100));
	}

	@Override
	public List<ConfigField> configFields() {
		List<ConfigField> fields = new ArrayList<>();
		fields.add(new ConfigField.ChoiceField("Preset", PRESETS, () -> preset, this::applyPreset));
		fields.add(new ConfigField.HeaderField("Water"));
		fields.add(new ConfigField.ToggleField("Shader Water", () -> water, v -> water = v));
		fields.add(percent("Wave Height", 2, () -> waveHeight, v -> waveHeight = v));
		fields.add(percent("Reflections", 1, () -> reflections, v -> reflections = v));
		fields.add(percent("Water Clarity", 1, () -> waterClarity, v -> waterClarity = v));
		fields.add(new ConfigField.ColorField("Water Color", () -> waterTint, v -> waterTint = v & 0xFFFFFF, false));
		fields.add(new ConfigField.HeaderField("Light"));
		fields.add(percent("Lighting", 1.5, () -> lighting, v -> lighting = v));
		fields.add(percent("Sun Warmth", 1, () -> sunWarmth, v -> sunWarmth = v));
		fields.add(percent("Waving Plants", 1, () -> wavingPlants, v -> wavingPlants = v));
		fields.add(percent("Night Brightness", 1, () -> nightBrightness, v -> nightBrightness = v));
		fields.add(new ConfigField.ColorField("Torch Light Color", () -> blockLight, v -> blockLight = v & 0xFFFFFF, false));
		fields.add(percent("Torch Light Tint", 1, () -> blockLightStrength, v -> blockLightStrength = v));
		fields.add(percent("Glowing Light Sources", 1, () -> emissiveGlow, v -> emissiveGlow = v));
		fields.add(new ConfigField.HeaderField("Ray-Traced Light"));
		fields.add(new ConfigField.ToggleField("Sun Shadow Map (Sodium)", () -> shadowMap, v -> shadowMap = v));
		fields.add(new ConfigField.ToggleField("Player & Mob Shadows", () -> entityShadows, v -> entityShadows = v));
		fields.add(percent("Shadow Strength", 1, () -> rtShadows, v -> rtShadows = v));
		fields.add(percent("Ambient Occlusion", 1, () -> ambientOcclusion, v -> ambientOcclusion = v));
		fields.add(new ConfigField.ToggleField("Colored Block Light", () -> coloredLights, v -> coloredLights = v));
		fields.add(percent("Colored Light Strength", 2, () -> coloredLightStrength, v -> coloredLightStrength = v));
		fields.add(new ConfigField.ToggleField("Handheld Light", () -> handheldLights, v -> handheldLights = v));
		fields.add(percent("Sunlight & Shade Colors", 1.5, () -> sunLight, v -> sunLight = v));
		fields.add(percent("God Rays", 1.5, () -> godRays, v -> godRays = v));
		fields.add(percent("Volumetric Fog", 1, () -> volumetricFog, v -> volumetricFog = v));
		fields.add(new ConfigField.HeaderField("Fog"));
		fields.add(percent("Fog Density", 1, () -> fogDensity, v -> fogDensity = v));
		fields.add(new ConfigField.ToggleField("Ground Fog", () -> groundFog, v -> groundFog = v));
		fields.add(percent("Fog Takes Sky Color", 1, () -> fogSkyBlend, v -> fogSkyBlend = v));
		fields.add(new ConfigField.HeaderField("Sky"));
		fields.add(new ConfigField.ToggleField("Shader Sky", () -> sky, v -> sky = v));
		fields.add(new ConfigField.ColorField("Sky Color", () -> zenithTint, v -> zenithTint = v & 0xFFFFFF, false));
		fields.add(percent("Sky Color Strength", 1, () -> zenithTintAmount, v -> zenithTintAmount = v));
		fields.add(new ConfigField.ColorField("Horizon Color", () -> horizonTint, v -> horizonTint = v & 0xFFFFFF, false));
		fields.add(percent("Horizon Color Strength", 1, () -> horizonTintAmount, v -> horizonTintAmount = v));
		fields.add(new ConfigField.ColorField("Sunset Color", () -> sunsetColor, v -> sunsetColor = v & 0xFFFFFF, false));
		fields.add(percent("Sunset Intensity", 1, () -> sunsetIntensity, v -> sunsetIntensity = v));
		fields.add(percent("Sun Size", 1, () -> sunSize, v -> sunSize = v));
		fields.add(percent("Sun Glow", 1, () -> sunGlow, v -> sunGlow = v));
		fields.add(percent("Moon Size", 1, () -> moonSize, v -> moonSize = v));
		fields.add(percent("Star Brightness", 1, () -> starBrightness, v -> starBrightness = v));
		fields.add(percent("Star Density", 1, () -> starDensity, v -> starDensity = v));
		fields.add(percent("Milky Way", 1, () -> milkyWay, v -> milkyWay = v));
		fields.add(percent("Shooting Stars", 1, () -> shootingStars, v -> shootingStars = v));
		fields.add(percent("Aurora", 1, () -> aurora, v -> aurora = v));
		fields.add(percent("Shader Clouds", 1, () -> skyClouds, v -> skyClouds = v));
		fields.add(new ConfigField.ToggleField("Vanilla Block Clouds", () -> vanillaClouds, v -> vanillaClouds = v));
		fields.add(new ConfigField.HeaderField("Post Processing"));
		fields.add(percent("Bloom", 1.5, () -> bloom, v -> bloom = v));
		fields.add(percent("Exposure", 2, () -> exposure, v -> exposure = v));
		fields.add(percent("Contrast", 2, () -> contrast, v -> contrast = v));
		fields.add(percent("Saturation", 2, () -> saturation, v -> saturation = v));
		fields.add(percent("Vignette", 1, () -> vignette, v -> vignette = v));
		fields.add(new ConfigField.ColorField("Color Tint", () -> tint, v -> tint = v & 0xFFFFFF, false));
		fields.add(percent("Tint Strength", 1, () -> tintAmount, v -> tintAmount = v));
		return fields;
	}
}
