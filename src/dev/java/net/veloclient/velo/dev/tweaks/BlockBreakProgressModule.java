package net.veloclient.velo.dev.tweaks;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.dev.mixin.BreakProgressAccessor;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Block Break Progress (dev build): how far along the block you're mining is - as a percentage, a bar or a ring at the
 * crosshair, and as a different break texture on the block itself, like resource packs do: vanilla's ten crack stages
 * ({@code textures/block/destroy_stage_N.png}) are swapped for generated ones. The crumbling pass multiplies the block by
 * twice the texture, so mid-grey leaves it alone and darker greys shade it like vanilla's cracks; transparent pixels
 * aren't drawn at all.
 */
public final class BlockBreakProgressModule extends AbstractModule implements Configurable {

	private static final List<String> STYLES = List.of("Percent", "Bar", "Percent + Bar", "Ring", "Ring + Percent", "None");
	private static final List<String> BLOCK_STYLES = List.of("Grow From Center", "Fill Up", "Frame", "Dissolve", "Shade", "Vanilla Cracks");
	private static final List<String> COLOR_MODES = List.of("One Color", "Red To Green");
	private static final int STAGES = 10;
	private static final int SIZE = 64;

	private String style = "Percent + Bar";
	private String blockStyle = "Grow From Center";
	private String colorMode = "One Color";
	private int color = 0xFFFFFFFF;
	private int blockColor = 0xFFFFFFFF;
	private boolean shadow = true;
	private int offset = 12;
	private int barWidth = 30;
	private double ringRadius = 9;

	private float shown;
	/** The generated stage textures (vanilla's and, on 26.3, the order-independent-transparency set). */
	private final NativeImageBackedTexture[] textures = new NativeImageBackedTexture[STAGES * 2];
	/** Block settings the stage textures were painted with ("" = vanilla). */
	private String painted = "";

	public BlockBreakProgressModule() {
		super("block-break-progress", "Block Break Progress", "Shows how far you've mined a block - at the crosshair and on the block itself.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.of("velo-client", "dev_break_progress"),
				(context, tickCounter) -> drawHud(context));
		ClientTickEvents.END_CLIENT_TICK.register(client -> updateTextures());
	}

	/** The block being mined and how far (0..1), or null. */
	private static BlockPos minedBlock(float[] progressOut) {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		Object gameMode = client.interactionManager;
		//?} else {
		/*Object gameMode = client.gameMode;
		*///?}
		if (gameMode == null || client.player == null) {
			return null;
		}
		BreakProgressAccessor access = (BreakProgressAccessor) gameMode;
		if (!access.velo$breaking() || access.velo$pos() == null) {
			return null;
		}
		progressOut[0] = Math.max(0f, Math.min(1f, access.velo$progress()));
		return access.velo$pos();
	}

	private int colorAt(float progress, int alpha) {
		int rgb;
		if (colorMode.equals("One Color")) {
			rgb = color & 0xFFFFFF;
		} else {
			// red -> yellow -> green
			int r = progress < 0.5f ? 255 : Math.round(255 * (1f - (progress - 0.5f) * 2f));
			int g = progress < 0.5f ? Math.round(255 * progress * 2f) : 255;
			rgb = (r << 16) | (g << 8) | 0x40;
		}
		return (alpha << 24) | rgb;
	}

	private void drawHud(DrawContext context) {
		float[] p = new float[1];
		if (!isEnabled() || style.equals("None") || minedBlock(p) == null) {
			shown = 0f;
			return;
		}
		// Eased a little: the server-side progress moves in steps, the bar shouldn't.
		shown += (p[0] - shown) * 0.5f;
		if (p[0] < shown) {
			shown = p[0];
		}
		float progress = shown;
		MinecraftClient client = MinecraftClient.getInstance();
		int cx = context.getScaledWindowWidth() / 2;
		int cy = context.getScaledWindowHeight() / 2;
		int fg = colorAt(progress, 0xFF);
		var renderer = client.textRenderer;
		String percent = Math.round(progress * 100) + "%";
		boolean ring = style.startsWith("Ring");
		if (ring) {
			float r = (float) ringRadius;
			VeloDraw.strokeArc(context, cx, cy, r, 2f, 0f, 360f, 0x50000000);
			VeloDraw.strokeArc(context, cx, cy, r, 2f, 0f, 360f * progress, fg);
		}
		int y = cy + (ring ? (int) Math.ceil(ringRadius) + 3 : offset);
		if (style.contains("Percent")) {
			int x = cx - renderer.getWidth(percent) / 2;
			if (shadow) {
				context.drawTextWithShadow(renderer, percent, x, y, fg);
			} else {
				context.drawText(renderer, percent, x, y, fg, false);
			}
			y += 10;
		}
		if (style.contains("Bar")) {
			int x = cx - barWidth / 2;
			VeloDraw.fillRounded(context, x, y, barWidth, 3, 1, 0x80000000);
			int filled = Math.round(barWidth * progress);
			if (filled > 0) {
				VeloDraw.fillRounded(context, x, y, filled, 3, 1, fg);
			}
		}
	}

	private static Identifier stageId(int index) {
		String name = index < STAGES ? "destroy_stage_" + index : "destroy_oit_stage_" + (index - STAGES);
		return Identifier.of("minecraft", "textures/block/" + name + ".png");
	}

	private boolean installed(int index) {
		return textures[index] != null && MinecraftClient.getInstance().getTextureManager().getTexture(stageId(index)) == textures[index];
	}

	/** Paints (or hands back) the ten stage textures when the block style changes or a resource reload replaced them. */
	private void updateTextures() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getTextureManager() == null || client.getResourceManager() == null) {
			return;
		}
		boolean custom = isEnabled() && !blockStyle.equals("Vanilla Cracks");
		String settings = custom ? blockStyle + blockColor : "";
		if (!custom) {
			if (!painted.isEmpty()) {
				for (int i = 0; i < textures.length; i++) {
					if (installed(i)) {
						client.getTextureManager().destroyTexture(stageId(i));
					}
					textures[i] = null;
				}
				painted = "";
			}
			return;
		}
		boolean lost = !installed(0);
		if (settings.equals(painted) && !lost) {
			return;
		}
		try {
			for (int i = 0; i < textures.length; i++) {
				// 26.3's second set only exists there; elsewhere there's no such resource to replace.
				if (i >= STAGES && client.getResourceManager().getResource(stageId(i)).isEmpty()) {
					continue;
				}
				NativeImage image = new NativeImage(SIZE, SIZE, true);
				paintStage(image, (i % STAGES + 1) / (float) STAGES);
				NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "velo break stage", image);
				client.getTextureManager().registerTexture(stageId(i), texture);
				textures[i] = texture;
			}
			painted = settings;
		} catch (Exception | LinkageError e) {
			// Renderer not ready yet - next tick.
		}
	}

	private void paintStage(NativeImage image, float progress) {
		int r = (blockColor >> 16) & 0xFF;
		int g = (blockColor >> 8) & 0xFF;
		int b = blockColor & 0xFF;
		// Darkens like vanilla's cracks: mid-grey (128) is "no change", so the mark is the chosen color at under half of
		// that - white gives a neutral shade, a color tints the shade. The rim is a little darker still.
		int lit = 0xFF000000 | (r * 50 / 255 << 16) | (g * 50 / 255 << 8) | (b * 50 / 255);
		int rim = 0xFF000000 | (r * 30 / 255 << 16) | (g * 30 / 255 << 8) | (b * 30 / 255);
		java.util.Random noise = new java.util.Random(1337);
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				float u = (x + 0.5f) / SIZE;
				float v = (y + 0.5f) / SIZE;
				float edge = Math.min(Math.min(u, 1 - u), Math.min(v, 1 - v)); // 0 at the border, 0.5 in the middle
				float center = Math.max(Math.abs(u - 0.5f), Math.abs(v - 0.5f)); // 0 in the middle, 0.5 at the border
				float rimWidth = 1.5f / SIZE;
				int argb = 0;
				switch (blockStyle) {
					case "Fill Up" -> {
						float level = 1 - progress;
						if (v >= level) {
							argb = v < level + rimWidth * 1.5f ? rim : lit;
						}
					}
					case "Frame" -> {
						float depth = progress * 0.5f;
						if (edge <= depth) {
							argb = edge > depth - rimWidth * 1.5f ? rim : lit;
						}
					}
					case "Dissolve" -> argb = noise.nextFloat() < progress ? lit : 0;
					case "Shade" -> {
						// From untouched grey to the full shade, darkest in the middle of the face.
						float k = progress * (1f - center);
						argb = 0xFF000000 | (Math.round(128 + (r * 50 / 255f - 128) * k) << 16) | (Math.round(128 + (g * 50 / 255f - 128) * k) << 8)
								| Math.round(128 + (b * 50 / 255f - 128) * k);
					}
					default -> { // Grow From Center
						float half = progress * 0.5f;
						if (center <= half) {
							argb = center > half - rimWidth * 1.5f ? rim : lit;
						}
					}
				}
				image.setColorArgb(x, y, argb);
			}
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.HeaderField("At The Crosshair"),
				new ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = v),
				new ConfigField.ChoiceField("Color", COLOR_MODES, () -> colorMode, v -> colorMode = v),
				new ConfigField.ColorField("Custom Color", () -> color, v -> color = v, false),
				new ConfigField.ToggleField("Text Shadow", () -> shadow, v -> shadow = v),
				new ConfigField.SliderField("Distance Below Crosshair", 4, 40, () -> offset, v -> offset = (int) Math.round(v), v -> (int) Math.round(v) + "px"),
				new ConfigField.SliderField("Bar Width", 12, 80, () -> barWidth, v -> barWidth = (int) Math.round(v), v -> (int) Math.round(v) + "px"),
				new ConfigField.SliderField("Ring Size", 5, 20, () -> ringRadius, v -> ringRadius = Math.round(v), v -> (int) Math.round(v) + "px"),
				new ConfigField.HeaderField("On The Block"),
				new ConfigField.ChoiceField("Break Texture", BLOCK_STYLES, () -> blockStyle, v -> blockStyle = v),
				new ConfigField.ColorField("Break Texture Color", () -> blockColor, v -> blockColor = v, false));
	}
}
