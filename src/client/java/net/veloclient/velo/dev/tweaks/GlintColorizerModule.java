package net.veloclient.velo.dev.tweaks;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.io.InputStream;
import java.util.List;

/**
 * Glint Colorizer (dev build): recolors the enchantment glint of items and armor. No render hook - the two vanilla glint
 * textures are swapped for tinted copies of themselves (resource packs included), so every glint the game draws picks
 * the color up. Rainbow repaints them a few times a second. Turning it off hands the textures back to vanilla.
 */
public final class GlintColorizerModule extends AbstractModule implements Configurable {

	private static final Identifier ITEM_GLINT = Identifier.of("minecraft", "textures/misc/enchanted_glint_item.png");
	private static final Identifier ARMOR_GLINT = Identifier.of("minecraft", "textures/misc/enchanted_glint_armor.png");
	private static final List<String> MODES = List.of("Color", "Rainbow");

	private String mode = "Color";
	private int itemColor = 0xFF49C6FF;
	private boolean armorSameColor = true;
	private int armorColor = 0xFFFF5FD2;
	private double rainbowSpeed = 1.0;
	private double strength = 1.0;

	private final Tinted item = new Tinted(ITEM_GLINT);
	private final Tinted armor = new Tinted(ARMOR_GLINT);
	/** Settings the textures were last painted with ("" = not painted / vanilla). */
	private String painted = "";
	private int tick;

	public GlintColorizerModule() {
		super("glint-colorizer", "Glint Colorizer", "Gives the enchantment glint on your items and armor any color you like - or a rainbow.",
				ModuleCategory.RENDERING, SafetyTag.COSMETIC_ONLY, false);
		ClientTickEvents.END_CLIENT_TICK.register(client -> update());
	}

	private void update() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getTextureManager() == null || client.getResourceManager() == null) {
			return;
		}
		if (!isEnabled()) {
			if (!painted.isEmpty()) {
				item.restore();
				armor.restore();
				painted = "";
			}
			return;
		}
		tick++;
		boolean rainbow = mode.equals("Rainbow");
		String settings = mode + itemColor + armorSameColor + armorColor + strength;
		// A resource reload hands the glint back to a fresh vanilla texture - paint it again then.
		boolean lost = !item.installed() || !armor.installed();
		if (!rainbow && settings.equals(painted) && !lost) {
			return;
		}
		if (rainbow && !lost && settings.equals(painted) && tick % 2 != 0) {
			return;
		}
		float hue = rainbow ? (float) ((System.currentTimeMillis() % 100000L) / 1000.0 * 0.12 * rainbowSpeed % 1.0) : 0f;
		item.paint(rainbow, itemColor, hue, (float) strength);
		armor.paint(rainbow, armorSameColor ? itemColor : armorColor, hue + 0.08f, (float) strength);
		painted = settings;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Mode", MODES, () -> mode, v -> mode = v),
				new ConfigField.ColorField("Glint Color", () -> itemColor, v -> itemColor = v, false),
				new ConfigField.ToggleField("Armor Uses The Same Color", () -> armorSameColor, v -> armorSameColor = v),
				new ConfigField.ColorField("Armor Glint Color", () -> armorColor, v -> armorColor = v, false),
				new ConfigField.SliderField("Rainbow Speed", 0.2, 4, () -> rainbowSpeed, v -> rainbowSpeed = Math.round(v * 10) / 10.0,
						v -> String.format("%.1fx", v)),
				new ConfigField.SliderField("Strength", 0.3, 2, () -> strength, v -> strength = Math.round(v * 10) / 10.0,
						v -> Math.round(v * 100) + "%"));
	}

	/** One glint texture: its untouched pixels, and the tinted copy registered in its place. */
	private static final class Tinted {

		private final Identifier id;
		private int[] source;
		private int width;
		private int height;
		private NativeImageBackedTexture texture;
		private NativeImage image;

		Tinted(Identifier id) {
			this.id = id;
		}

		boolean installed() {
			return texture != null && MinecraftClient.getInstance().getTextureManager().getTexture(id) == texture;
		}

		void paint(boolean rainbow, int color, float hue, float strength) {
			try {
				if (!installed()) {
					texture = null;
					if (!load()) {
						return;
					}
				}
				float baseR = ((color >> 16) & 0xFF) / 255f;
				float baseG = ((color >> 8) & 0xFF) / 255f;
				float baseB = (color & 0xFF) / 255f;
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						int argb = source[y * width + x];
						float lum = (0.3f * ((argb >> 16) & 0xFF) + 0.59f * ((argb >> 8) & 0xFF) + 0.11f * (argb & 0xFF)) / 255f;
						// The vanilla glint is a dim purple: lift it so the chosen color reads at full strength.
						lum = Math.min(1f, lum * 1.6f * strength);
						float r = baseR;
						float g = baseG;
						float b = baseB;
						if (rainbow) {
							int rgb = hsv(hue + (float) x / width * 0.5f, 0.75f);
							r = ((rgb >> 16) & 0xFF) / 255f;
							g = ((rgb >> 8) & 0xFF) / 255f;
							b = (rgb & 0xFF) / 255f;
						}
						int out = (argb & 0xFF000000) | (Math.round(r * lum * 255) << 16) | (Math.round(g * lum * 255) << 8) | Math.round(b * lum * 255);
						image.setColorArgb(x, y, out);
					}
				}
				texture.upload();
			} catch (Exception | LinkageError e) {
				// Renderer not ready yet - try again next tick.
			}
		}

		/** Hue (wraps) and saturation at full brightness -> RGB. */
		private static int hsv(float hue, float sat) {
			float h = (hue - (float) Math.floor(hue)) * 6f;
			int sector = (int) h;
			float f = h - sector;
			float p = 1f - sat;
			float q = 1f - sat * f;
			float t = 1f - sat * (1f - f);
			float r, g, b;
			switch (sector) {
				case 0 -> { r = 1; g = t; b = p; }
				case 1 -> { r = q; g = 1; b = p; }
				case 2 -> { r = p; g = 1; b = t; }
				case 3 -> { r = p; g = q; b = 1; }
				case 4 -> { r = t; g = p; b = 1; }
				default -> { r = 1; g = p; b = q; }
			}
			return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
		}

		private boolean load() throws Exception {
			MinecraftClient client = MinecraftClient.getInstance();
			var resource = client.getResourceManager().getResource(id);
			if (resource.isEmpty()) {
				return false;
			}
			NativeImage original;
			//? if <26.1 {
			try (InputStream in = resource.get().getInputStream()) {
			//?} else {
			/*try (InputStream in = resource.get().open()) {
			*///?}
				original = NativeImage.read(in);
			}
			width = original.getWidth();
			height = original.getHeight();
			source = new int[width * height];
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					source[y * width + x] = original.getColorArgb(x, y);
				}
			}
			image = original;
			texture = new NativeImageBackedTexture(() -> "velo glint", image);
			client.getTextureManager().registerTexture(id, texture);
			return true;
		}

		/** Back to vanilla: drop ours, the game loads the original again the next time it needs it. */
		void restore() {
			if (texture != null) {
				try {
					if (installed()) {
						MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
					}
				} catch (Exception ignored) {
					// already gone
				}
				texture = null;
				image = null;
			}
		}
	}
}
