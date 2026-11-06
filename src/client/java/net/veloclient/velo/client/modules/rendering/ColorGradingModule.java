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
 * Color Grading: post effects on the game picture (not the HUD) - saturation, vibrance, contrast, brightness, gamma,
 * warmth, vignette and sharpening, all in one full-screen pass ({@code velo-client:post/velo_grade}). The slider values
 * reach the shader through an 8x1 texture ({@code velo-client:grade_params}) rewritten only when a value changes, so
 * there's no reload and almost no cost. Applied from HudManager right before the HUD draws, like PolyBlur.
 */
public final class ColorGradingModule extends AbstractModule implements Configurable {

	private static final Identifier EFFECT = Identifier.of("velo-client", "color_grade");
	private static final Identifier PARAMS_ID = Identifier.of("velo-client", "textures/effect/grade_params.png");
	private static final List<String> PRESETS = List.of("Custom", "Vivid", "Cinematic", "Warm", "Cold", "Black & White", "Crisp PvP");

	//? if <26.1 {
	//? if <1.21.2 {
	/*private static final Object POOL = null;
	*///?} else {
	private static final net.minecraft.client.util.memory.ObjectPool POOL = new net.minecraft.client.util.memory.ObjectPool(3);
	//?}
	//?} else {
	/*private static final com.mojang.blaze3d.resource.CrossFrameResourcePool POOL = new com.mojang.blaze3d.resource.CrossFrameResourcePool(3);
	*///?}

	private static ColorGradingModule instance;
	private static PostEffectProcessor processor;
	private static NativeImageBackedTexture texture;
	private static volatile boolean broken;
	private static long uploadedHash = Long.MIN_VALUE;

	private String preset = "Vivid";
	private double saturation = 1.25;
	private double vibrance = 0.2;
	private double contrast = 1.05;
	private double brightness = 0;
	private double gamma = 1.0;
	private double warmth = 0;
	private double vignette = 0;
	private double sharpen = 0;

	public ColorGradingModule() {
		super("color-grading", "Color Grading", "Post effects on the picture: saturation, vibrance, contrast, brightness, warmth, vignette and sharpening.",
				ModuleCategory.RENDERING, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
	}

	/** Called from HudManager right before the HUD: grades the world picture (the HUD stays untouched). */
	public static void applyIfActive() {
		ColorGradingModule m = instance;
		if (m == null || !m.isEnabled() || broken) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return;
		}
		try {
			m.uploadParams(client);
			//? if <1.21.2 {
			/*//
			*///?} else {
			if (processor == null) {
				processor = load(client, EFFECT);
			}
			//?}
			if (processor == null) {
				return;
			}
			//? if <26.1 {
			//? if <1.21.2 {
			/*//
			*///?} else {
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
			VeloClient.LOGGER.error("Color Grading failed to render - disabling it for this session", t);
		}
	}

	//? if >=1.21.2 {
	private static PostEffectProcessor load(MinecraftClient client, Identifier id) {
		return client.getShaderLoader().loadPostEffect(id, DefaultFramebufferSet.MAIN_ONLY);
	}
	//?}

	/** Packs the settings into the 8x1 params texture (bytes, decoded in velo_grade.fsh) - only when they changed. */
	private void uploadParams(MinecraftClient client) {
		int[] b = {
				byteOf(saturation / 2), byteOf(contrast / 2), byteOf(brightness + 0.5), byteOf((vibrance + 1) / 2),
				byteOf((warmth + 1) / 2), byteOf(vignette), byteOf(sharpen), byteOf(gamma - 0.5)};
		long hash = 0;
		for (int v : b) {
			hash = hash * 257 + v;
		}
		if (texture == null) {
			texture = new NativeImageBackedTexture(() -> "velo-color-grading", new NativeImage(8, 1, false));
			client.getTextureManager().registerTexture(PARAMS_ID, texture);
			uploadedHash = Long.MIN_VALUE;
		}
		if (hash == uploadedHash) {
			return;
		}
		NativeImage image = texture.getImage();
		image.setColorArgb(0, 0, b[3] << 24 | b[0] << 16 | b[1] << 8 | b[2]);
		image.setColorArgb(1, 0, b[7] << 24 | b[4] << 16 | b[5] << 8 | b[6]);
		texture.upload();
		uploadedHash = hash;
	}

	private static int byteOf(double v) {
		return Math.max(0, Math.min(255, (int) Math.round(v * 255)));
	}

	private void applyPreset(String name) {
		preset = name;
		switch (name) {
			case "Vivid" -> set(1.3, 0.25, 1.06, 0, 1.0, 0, 0, 0.15);
			case "Cinematic" -> set(0.9, 0.1, 1.15, -0.03, 1.0, 0.25, 0.45, 0);
			case "Warm" -> set(1.1, 0.1, 1.04, 0.02, 1.0, 0.45, 0.15, 0);
			case "Cold" -> set(1.0, 0.05, 1.06, 0, 1.0, -0.45, 0.15, 0);
			case "Black & White" -> set(0, 0, 1.15, 0, 1.0, 0, 0.3, 0.1);
			case "Crisp PvP" -> set(1.15, 0.3, 1.08, 0.03, 1.1, 0, 0, 0.45);
			default -> {
			}
		}
	}

	private void set(double sat, double vib, double con, double bri, double gam, double warm, double vig, double sharp) {
		saturation = sat;
		vibrance = vib;
		contrast = con;
		brightness = bri;
		gamma = gam;
		warmth = warm;
		vignette = vig;
		sharpen = sharp;
	}

	private ConfigField slider(String label, double min, double max, java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set,
			java.util.function.DoubleFunction<String> format) {
		return new ConfigField.SliderField(label, min, max, get, v -> {
			set.accept(Math.round(v * 100) / 100.0);
			preset = "Custom";
		}, format);
	}

	@Override
	public List<ConfigField> configFields() {
		java.util.function.DoubleFunction<String> pct = v -> Math.round(v * 100) + "%";
		java.util.function.DoubleFunction<String> signed = v -> (v > 0 ? "+" : "") + Math.round(v * 100);
		return List.of(
				new ConfigField.ChoiceField("Preset", PRESETS, () -> preset, this::applyPreset),
				slider("Saturation", 0, 2, () -> saturation, v -> saturation = v, pct),
				slider("Vibrance", -1, 1, () -> vibrance, v -> vibrance = v, signed),
				slider("Contrast", 0.5, 1.6, () -> contrast, v -> contrast = v, pct),
				slider("Brightness", -0.4, 0.4, () -> brightness, v -> brightness = v, signed),
				slider("Gamma", 0.6, 1.5, () -> gamma, v -> gamma = v, v -> String.format(java.util.Locale.ROOT, "%.2f", v)),
				slider("Warmth", -1, 1, () -> warmth, v -> warmth = v, signed),
				slider("Vignette", 0, 1, () -> vignette, v -> vignette = v, pct),
				slider("Sharpen", 0, 1, () -> sharpen, v -> sharpen = v, pct));
	}
}
