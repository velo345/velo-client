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

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom Skybox: a panorama replaces the sky - a few built-in presets, or any picture you import (a 2:1 panorama or a
 * 4:3 cube-map cross; both are turned into a 4096x2048 panorama and kept in .velo-client/skyboxes). It's a screen pass
 * right after the world, before the hand (the depth buffer is cleared for the hand): every pixel nothing was drawn on is
 * the sky, so terrain, clouds, water and entities stay in front at any render distance. The camera axes and settings
 * reach the shader through a 12x1 texture rewritten each frame (post effects can't change uniforms after loading).
 *
 * <p>26.2+ for now (SkyboxFrameMixin hooks the level render there). Fails safe: a render error turns it off for the
 * session.
 */
public final class SkyboxModule extends AbstractModule implements Configurable {

	//? if >=26.3 {
	/*private static final Identifier EFFECT = Identifier.of("velo-client", "skybox_spirv");
	private static final Identifier DIRECT = Identifier.of("velo-client", "skybox_direct_spirv");
	*///?} else {
	private static final Identifier EFFECT = Identifier.of("velo-client", "skybox");
	private static final Identifier DIRECT = Identifier.of("velo-client", "skybox_direct");
	//?}
	private static final Identifier SKY_ID = Identifier.of("velo-client", "textures/effect/skybox.png");
	private static final Identifier PARAMS_ID = Identifier.of("velo-client", "textures/effect/skybox_params.png");
	private static final int W = 4096;
	private static final int H = 2048;
	/** Built in: name -> file under assets/velo-client/textures/skybox. */
	private static final Map<String, String> PRESETS = new LinkedHashMap<>();
	/** How many stars the shader draws over each preset (file -> 0..1); imported pictures get none (they bring their own). */
	private static final Map<String, Double> STARS = new java.util.HashMap<>();

	static {
		PRESETS.put("Starry Night", "starry_night");
		PRESETS.put("Deep Space", "deep_space");
		PRESETS.put("Velo Nebula", "velo_nebula");
		PRESETS.put("Aurora", "aurora");
		PRESETS.put("Crimson Dusk", "crimson_dusk");
		PRESETS.put("Sunset Glow", "sunset_glow");
		PRESETS.put("Cotton Candy", "cotton_candy");
		PRESETS.put("Pastel Day", "pastel_day");
		STARS.put("starry_night", 1.0);
		STARS.put("deep_space", 1.0);
		STARS.put("velo_nebula", 0.8);
		STARS.put("aurora", 0.85);
		STARS.put("crimson_dusk", 0.55);
		STARS.put("sunset_glow", 0.2);
		STARS.put("cotton_candy", 0.25);
	}

	//? if <26.1 {
	//? if <1.21.2 {
	/*private static final Object POOL = null;
	*///?} else {
	private static final net.minecraft.client.util.memory.ObjectPool POOL = new net.minecraft.client.util.memory.ObjectPool(3);
	//?}
	//?} else {
	/*private static final com.mojang.blaze3d.resource.CrossFrameResourcePool POOL = new com.mojang.blaze3d.resource.CrossFrameResourcePool(3);
	*///?}

	private static SkyboxModule instance;
	private static PostEffectProcessor processor;
	private static NativeImageBackedTexture skyTexture;
	private static NativeImageBackedTexture paramsTexture;
	private static volatile boolean broken;
	/** A decoded panorama (ARGB, W x H) waiting to be uploaded on the render thread. */
	private static volatile int[] pending;
	private static String loadedSky;
	private static volatile String importStatus = "";

	private String sky = "Starry Night";
	private double opacity = 1.0;
	private double rotation = 0;
	private double spin = 0;
	private boolean keepSunMoon = true;
	private boolean darkerAtNight = true;
	private boolean overworldOnly = true;
	private double brightness = 1.0;
	private double stars = 1.0;

	public SkyboxModule() {
		super("custom-skybox", "Custom Skybox", "Replace the sky with a panorama: built-in presets or any picture you import. Terrain, clouds and water stay in front.",
				ModuleCategory.RENDERING, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
		//? if >=26.2 {
		/*// Drawn before the world: right after vanilla's sky, at the start of the main pass. Terrain, clouds, water, entities,
		// text (Lyrics) and particles are all drawn over it afterwards - no depth test needed, so nothing can end up behind
		// the sky (clouds did with Fabulous, where they never reach the main depth buffer), and at full opacity it's one
		// pass straight into the frame instead of a full-screen copy there and back.
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.START_MAIN.register(context -> {
			earlyRan = true;
			SkyboxModule m = instance;
			if (m != null && m.isEnabled() && !broken) {
				m.apply(m.opacity >= 0.999);
			}
		});
		*///?} else if >=1.21.10 {
		// 1.21.10 / 1.21.11: Fabric's world render event at the start of the main pass - same spot, same direct pass
		net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.START_MAIN.register(context -> {
			earlyRan = true;
			SkyboxModule m = instance;
			if (m != null && m.isEnabled() && !broken) {
				m.apply(m.opacity >= 0.999);
			}
		});
		//?}
	}

	/** The early pass (start of the main pass) ran this frame - the after-the-world fallback then skips. */
	private static boolean earlyRan;

	/**
	 * After the whole world (SkyboxFrameMixin): only when the early pass didn't run this frame (a renderer that skips the
	 * Fabric terrain events), so there's always a sky.
	 */
	public static void afterLevel() {
		if (earlyRan) {
			earlyRan = false;
			return;
		}
		applyIfActive();
	}

	/** Where a level-render hook exists: 26.2+ and 1.21.10 / 1.21.11 (owner 2026-11-24: "Custom Skybox on 1.21.11"). */
	public static boolean supported() {
		//? if >=26.2 {
		/*return true;
		*///?} else if >=1.21.10 && <26.1 {
		return true;
		//?} else {
		/*return false;
		*///?}
	}

	/** The sun's angle in radians (0 = noon): Velo Shaders captures it from the sky on 26.2+; 1.21.x works it out here. */
	private static double sunAngle() {
		//? if >=26.2 {
		/*return VeloShadersModule.sunAngle();
		*///?} else if >=26.1 {
		/*return 0; // no Custom Skybox on 26.1
		*///?} else {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return 0;
		}
		// vanilla's sky angle (DimensionType.getSkyAngle): eased fraction of the day, 0 = noon
		double d = net.minecraft.util.math.MathHelper.fractionalPart(client.world.getTimeOfDay() / 24000.0 - 0.25);
		double e = 0.5 - Math.cos(d * Math.PI) / 2.0;
		return (d * 2.0 + e) / 3.0 * Math.PI * 2.0;
		//?}
	}

	private static int moonPhase() {
		//? if >=26.2 {
		/*return VeloShadersModule.moonPhase();
		*///?} else if >=26.1 {
		/*return 0;
		*///?} else {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.world == null ? 0 : (int) Math.floorMod(client.world.getTimeOfDay() / 24000L, 8L);
		//?}
	}

	/** Right after the world, before the hand (SkyboxFrameMixin). Render thread. */
	public static void applyIfActive() {
		SkyboxModule m = instance;
		if (m == null || !m.isEnabled() || broken) {
			return;
		}
		m.apply(false);
	}

	/** {@code direct}: before the world, straight into the frame (full opacity); else the depth-tested blend pass. */
	private void apply(boolean direct) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) {
			return;
		}
		if (overworldOnly && VeloShadersModule.dimension() != 0) {
			return;
		}
		try {
			if (!sky.equals(loadedSky)) {
				loadedSky = sky;
				String name = sky;
				Thread.ofVirtual().start(() -> {
					int[] argb = decode(name);
					if (argb != null) {
						pending = argb;
					}
				});
			}
			if (skyTexture == null) {
				skyTexture = new NativeImageBackedTexture(() -> "velo-skybox", new NativeImage(W, H, false));
				client.getTextureManager().registerTexture(SKY_ID, skyTexture);
			}
			int[] next = pending;
			if (next != null) {
				pending = null;
				NativeImageBackedTexture texture = skyTexture;
				NativeImage image = texture.getImage();
				for (int y = 0; y < H; y++) {
					for (int x = 0; x < W; x++) {
						image.setColorArgb(x, y, next[y * W + x]);
					}
				}
				skyTexture.upload();
			}
			if (!uploadParams(client)) {
				return;
			}
			// Fetched every frame (vanilla caches it): a resource reload - e.g. a server resource pack - closes the old one.
			if (net.veloclient.velo.client.util.ClientCompat.reloadingResources()) {
				return; // never mid-reload (see ClientCompat.reloadingResources)
			}
			processor = load(client, direct ? DIRECT : EFFECT);
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
			VeloClient.LOGGER.error("Custom Skybox failed to render - turning it off for this session", t);
		}
	}

	private static PostEffectProcessor load(MinecraftClient client, Identifier id) {
		//? if >=1.21.2 {
		return client.getShaderLoader().loadPostEffect(id, DefaultFramebufferSet.MAIN_ONLY);
		//?} else {
		/*return null;
		*///?}
	}

	/** Camera axes, fov and settings into the 12x1 params texture (decoded in velo_skybox.fsh); false = no camera yet. */
	private boolean uploadParams(MinecraftClient client) {
		double[] cam = net.veloclient.velo.client.pvp.PvpCompat.cameraPos();
		if (cam == null) {
			return false;
		}
		// world axes seen from the camera: row i of the world->view rotation = camera axis i in the world
		float[] ex = net.veloclient.velo.client.util.ClientCompat.cameraView(cam[0] + 1, cam[1], cam[2]);
		float[] ey = net.veloclient.velo.client.util.ClientCompat.cameraView(cam[0], cam[1] + 1, cam[2]);
		float[] ez = net.veloclient.velo.client.util.ClientCompat.cameraView(cam[0], cam[1], cam[2] + 1);
		double aspect = client.getWindow().getWidth() / (double) Math.max(1, client.getWindow().getHeight());
		double turn = (rotation / 360.0 + (System.nanoTime() / 1e9) * spin / 360.0 / 60.0) % 1.0;
		double night = darkerAtNight ? smooth(-0.15, 0.25, Math.cos(sunAngle())) : 1.0;
		double bright = Math.max(0, Math.min(1, brightness * (0.28 + 0.72 * night)));
		double[] v = {
				ex[0], ey[0], ez[0],          // right
				ex[1], ey[1], ez[1],          // up
				-ex[2], -ey[2], -ez[2],       // forward (view space looks down -z)
				ex[3] / 2 - 1, aspect / 2 - 1, // tan(fov/2), aspect: stored as x/2 - 1 so 0..4 fits
				turn * 2 - 1, opacity * 2 - 1, (keepSunMoon ? 1 : 0) * 2 - 1, bright * 2 - 1,
				-1,                           // flipY off
				(System.nanoTime() / 1e9 % 60.0) / 30.0 - 1,   // star twinkle clock
				starAmount() * 2 - 1, stars * 2 - 1,
				-Math.sin(sunAngle()), Math.cos(sunAngle()), // sun direction (x, y)
				moonPhase() / 8.0 * 2 - 1,
				client.getWindow().getHeight() / 2048.0 - 1, -1}; // frame height (the direct pass can't ask its input)
		if (paramsTexture == null) {
			paramsTexture = new NativeImageBackedTexture(() -> "velo-skybox-params", new NativeImage(12, 1, false));
			client.getTextureManager().registerTexture(PARAMS_ID, paramsTexture);
		}
		NativeImageBackedTexture texture = paramsTexture;
		NativeImage image = texture.getImage();
		for (int px = 0; px < 12; px++) {
			int a = s16(v[px * 2]);
			int b = s16(v[px * 2 + 1]);
			// texel = (a hi, a lo, b hi, b lo) as r, g, b, a
			image.setColorArgb(px, 0, (b & 0xFF) << 24 | (a >> 8) << 16 | (a & 0xFF) << 8 | (b >> 8));
		}
		paramsTexture.upload();
		return true;
	}

	/** The current preset's stars (imported pictures: none). */
	private double starAmount() {
		String file = PRESETS.get(sky);
		return file == null ? 0 : STARS.getOrDefault(file, 0.0);
	}

	private static double smooth(double e0, double e1, double x) {
		double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	private static int s16(double v) {
		return Math.max(0, Math.min(65535, (int) Math.round((Math.max(-1, Math.min(1, v)) + 1) / 2 * 65535)));
	}

	// ---- panoramas ----

	private static Path folder() {
		return net.veloclient.velo.config.VeloPaths.root().resolve("skyboxes");
	}

	private static List<String> imported() {
		List<String> names = new ArrayList<>();
		try (var list = Files.list(folder())) {
			list.filter(p -> p.getFileName().toString().endsWith(".png")).sorted()
					.forEach(p -> names.add(p.getFileName().toString().replaceFirst("\\.png$", "")));
		} catch (Exception ignored) {
			// none yet
		}
		return names;
	}

	/** A preset or an imported panorama as W x H ARGB, or null. */
	private static int[] decode(String name) {
		try {
			BufferedImage src;
			String preset = PRESETS.get(name);
			if (preset != null) {
				try (var in = SkyboxModule.class.getResourceAsStream("/assets/velo-client/textures/skybox/" + preset + ".jpg")) {
					src = in == null ? null : javax.imageio.ImageIO.read(in);
				}
			} else {
				Path file = folder().resolve(name + ".png");
				src = Files.exists(file) ? javax.imageio.ImageIO.read(file.toFile()) : null;
			}
			if (src == null) {
				return null;
			}
			return toArgb(scale(src, W, H));
		} catch (Exception e) {
			VeloClient.LOGGER.warn("Custom Skybox couldn't read {}: {}", name, e.toString());
			return null;
		}
	}

	private static BufferedImage scale(BufferedImage src, int w, int h) {
		if (src.getWidth() == w && src.getHeight() == h && src.getType() == BufferedImage.TYPE_INT_ARGB) {
			return src;
		}
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		var g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	private static int[] toArgb(BufferedImage img) {
		int[] argb = img.getRGB(0, 0, W, H, null, 0, W);
		for (int i = 0; i < argb.length; i++) {
			argb[i] |= 0xFF000000;
		}
		return argb;
	}

	/** A picked file into a 2:1 panorama: 4:3 cube-map crosses are unfolded, anything else is used as a panorama. */
	static BufferedImage toPanorama(BufferedImage src) {
		double aspect = src.getWidth() / (double) src.getHeight();
		if (Math.abs(aspect - 4.0 / 3.0) > 0.05) {
			return scale(src, W, H);
		}
		int cell = src.getWidth() / 4;
		BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < H; y++) {
			double lat = (0.5 - (y + 0.5) / H) * Math.PI;
			for (int x = 0; x < W; x++) {
				double lon = ((x + 0.5) / W - 0.5) * 2 * Math.PI;
				double dx = Math.cos(lat) * Math.sin(lon);
				double dy = Math.sin(lat);
				double dz = -Math.cos(lat) * Math.cos(lon);
				double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
				int col, row;
				double u, v;
				if (ay >= ax && ay >= az) {
					col = 1;
					if (dy > 0) {   // top: its lower edge meets the front's upper edge
						row = 0;
						u = (dx / ay + 1) / 2;
						v = (-dz / ay + 1) / 2;
					} else {        // bottom
						row = 2;
						u = (dx / ay + 1) / 2;
						v = (dz / ay + 1) / 2;
					}
				} else if (ax >= az) {
					row = 1;
					if (dx > 0) {   // right
						col = 2;
						u = (dz / ax + 1) / 2;
					} else {        // left
						col = 0;
						u = (-dz / ax + 1) / 2;
					}
					v = (-dy / ax + 1) / 2;
				} else {
					row = 1;
					if (dz < 0) {   // front
						col = 1;
						u = (dx / az + 1) / 2;
					} else {        // back
						col = 3;
						u = (-dx / az + 1) / 2;
					}
					v = (-dy / az + 1) / 2;
				}
				int sx = Math.min(cell - 1, (int) (u * cell)) + col * cell;
				int sy = Math.min(cell - 1, (int) (v * cell)) + row * cell;
				out.setRGB(x, y, src.getRGB(Math.min(src.getWidth() - 1, sx), Math.min(src.getHeight() - 1, sy)));
			}
		}
		return out;
	}

	/** "Import..." - pick a picture, turn it into a panorama, save it and switch to it. */
	private void importPicture() {
		importStatus = "Pick a picture...";
		Thread.ofVirtual().start(() -> {
			try {
				Path file = net.veloclient.velo.client.gui.NativeFileDialog.pickPictureFile("Pick a skybox (2:1 panorama or 4:3 cube-map cross)");
				if (file == null) {
					importStatus = "";
					return;
				}
				BufferedImage src = javax.imageio.ImageIO.read(file.toFile());
				if (src == null) {
					importStatus = "That file isn't a picture Velo can read (PNG / JPG).";
					return;
				}
				BufferedImage pano = toPanorama(src);
				String base = file.getFileName().toString().replaceFirst("\\.[A-Za-z0-9]+$", "").replaceAll("[^A-Za-z0-9 _-]", "").trim();
				if (base.isEmpty() || PRESETS.containsKey(base)) {
					base = "Imported " + System.currentTimeMillis() % 100000;
				}
				Files.createDirectories(folder());
				javax.imageio.ImageIO.write(pano, "png", folder().resolve(base + ".png").toFile());
				String name = base;
				MinecraftClient.getInstance().execute(() -> {
					sky = name;
					importStatus = "Imported \"" + name + "\".";
				});
			} catch (Exception e) {
				importStatus = "Couldn't import: " + e.getMessage();
				VeloClient.LOGGER.warn("Custom Skybox import failed", e);
			}
		});
	}

	@Override
	public void onDisable() {
		//? if >=1.21.2 {
		POOL.clear();
		//?}
	}

	/** The preview-card picker (presets show a rendered preview, imported pictures a thumbnail of themselves). */
	private void openPicker(List<String> options) {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		var parent = client.currentScreen;
		//?} else if <26.2 {
		/*var parent = client.screen;
		*///?} else {
		/*var parent = client.gui.screen();
		*///?}
		var picker = new net.veloclient.velo.client.gui.SkyboxPickerScreen(options,
				name -> PRESETS.containsKey(name) ? Identifier.of("velo-client", "textures/skybox/preview/" + PRESETS.get(name) + ".png") : null,
				SkyboxModule::thumbnailPng, () -> sky, name -> sky = name);
		picker.returnTo(parent);
		client.setScreen(picker);
	}

	/** A 320x160 PNG of an imported panorama, for the picker. */
	private static byte[] thumbnailPng(String name) {
		try {
			Path file = folder().resolve(name + ".png");
			BufferedImage src = Files.exists(file) ? javax.imageio.ImageIO.read(file.toFile()) : null;
			if (src == null) {
				return null;
			}
			BufferedImage small = new BufferedImage(320, 160, BufferedImage.TYPE_INT_ARGB);
			var g = small.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(src, 0, 0, 320, 160, null);
			g.dispose();
			var out = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(small, "png", out);
			return out.toByteArray();
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public List<ConfigField> configFields() {
		List<String> options = new ArrayList<>(PRESETS.keySet());
		options.addAll(imported());
		if (!options.contains(sky)) {
			options.add(sky);
		}
		java.util.function.DoubleFunction<String> pct = v -> Math.round(v * 100) + "%";
		List<ConfigField> fields = new ArrayList<>(List.of(
				new ConfigField.ActionButtonField("Choose Skybox... (" + sky + ")", () -> openPicker(options)),
				new ConfigField.ChoiceField("Skybox", options, () -> sky, v -> sky = v),
				new ConfigField.ActionButtonField(importStatus.isEmpty() ? "Import Picture..." : importStatus, this::importPicture),
				new ConfigField.SliderField("Opacity", 0.1, 1.0, () -> opacity, v -> opacity = Math.round(v * 100) / 100.0, pct),
				new ConfigField.SliderField("Brightness", 0.2, 1.0, () -> brightness, v -> brightness = Math.round(v * 100) / 100.0, pct),
				new ConfigField.SliderField("Rotation", 0, 359, () -> rotation, v -> rotation = Math.round(v), v -> Math.round(v) + "°"),
				new ConfigField.SliderField("Spin (degrees per minute)", 0, 30, () -> spin, v -> spin = Math.round(v * 2) / 2.0, v -> v == 0 ? "Off" : v + "°"),
				new ConfigField.SliderField("Stars", 0, 1, () -> stars, v -> stars = Math.round(v * 100) / 100.0, pct),
				new ConfigField.ToggleField("Sun & Moon", () -> keepSunMoon, v -> keepSunMoon = v),
				new ConfigField.ToggleField("Darker At Night", () -> darkerAtNight, v -> darkerAtNight = v),
				new ConfigField.ToggleField("Overworld Only", () -> overworldOnly, v -> overworldOnly = v)));
		return fields;
	}
}
