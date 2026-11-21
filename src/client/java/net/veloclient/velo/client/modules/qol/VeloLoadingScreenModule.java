package net.veloclient.velo.client.modules.qol;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.io.InputStream;

/**
 * Velo Loading Screen: the startup load shows the title screen's panorama, blurred, with the Velo logo and
 * a thin progress bar instead of the red Mojang screen. When loading is done the bar and logo fade away while the title
 * screen slides up into place - quicker than vanilla's two-second fade, so the game is ready no later than before.
 *
 * <p>Nothing here may wait on the game's resources: they're what's loading. The panorama and logo are read straight
 * from the jar and the panorama is shrunk and blurred on a background thread; until it's ready the screen is plain
 * dark. No text either - fonts aren't loaded yet.
 */
public final class VeloLoadingScreenModule extends AbstractModule {

	private static final Identifier BACKGROUND = Identifier.of("velo-client", "loading_background");
	private static final Identifier LOGO = Identifier.of("velo-client", "loading_logo");
	/** The part of the panorama the title screen opens on (TitleScreenTheme: duel on the right of a 16:9 view). */
	private static final int CROP_X = 1458;
	private static final int CROP_W = 2048;
	private static final int SHRINK = 8;
	private static final float OUTRO_SECONDS = 0.7f;
	private static final int DARK = 0xFF0B0B10;

	private static VeloLoadingScreenModule instance;

	private static final Identifier CAPTION = Identifier.of("velo-client", "loading_caption");
	/** Customize (Velo+): the look this screen was prepared for, and the caption picture (drawn with AWT - see prepareCaption). */
	private static volatile ScreenLookSnapshot look = ScreenLookSnapshot.read();
	private static volatile int preparedGeneration = -1;
	private static volatile boolean preparing;
	private static volatile NativeImage preparedCaption;
	private static int captionW;
	private static int captionH;
	private static boolean captionReady;
	private static volatile NativeImage preparedBackground;
	private static volatile NativeImage preparedLogo;
	private static int backgroundW;
	private static int backgroundH;
	private static boolean backgroundReady;
	private static boolean logoReady;
	private static float shownProgress;

	public VeloLoadingScreenModule() {
		super("velo-loading-screen", "Velo Loading Screen", "A clean Velo loading screen at startup instead of the red Mojang one.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
		startPrepare();
	}

	private static void startPrepare() {
		preparing = true;
		Thread prepare = new Thread(() -> {
			try {
				prepare();
			} finally {
				preparing = false;
			}
		}, "Velo loading screen");
		prepare.setDaemon(true);
		prepare.setPriority(Thread.MIN_PRIORITY);
		prepare.start();
	}

	/** The Customize settings the loading screen uses, read once per prepare (it may run before the game is up). */
	private record ScreenLookSnapshot(net.veloclient.velo.client.gui.title.ScreenLook.Loading look, int generation) {
		static ScreenLookSnapshot read() {
			try {
				var l = net.veloclient.velo.client.gui.title.ScreenLook.loading();
				return new ScreenLookSnapshot(l, net.veloclient.velo.client.gui.title.ScreenLook.generation());
			} catch (Throwable t) {
				return new ScreenLookSnapshot(new net.veloclient.velo.client.gui.title.ScreenLook.Loading(), -1);
			}
		}
	}

	/** Set when drawing failed once: vanilla's loading screen takes over for the rest of this start. */
	private static volatile boolean failed;
	private static long lastErrorLog;

	public static boolean active() {
		VeloLoadingScreenModule m = instance;
		// RRLS ("Remove Reloading Screen") draws the overlay inside its own mini renderer - the two fought over it.
		return m != null && m.isEnabled() && !failed && !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("rrls");
	}

	/**
	 * {@link #render} that can't take the start down: any exception (ours, or a mod drawing the title screen behind it)
	 * switches the Velo loading screen off and hands the overlay back to vanilla, which then finishes as normal. Before,
	 * an exception left the pose stack one push deeper every frame until it overflowed - and since the overlay only goes
	 * away when this returns true, the game sat on the loading screen.
	 */
	public static boolean safeRender(DrawContext context, float progress, float doneFor, java.util.function.IntConsumer screen) {
		try {
			return render(context, progress, doneFor, screen) || doneFor > OUTRO_SECONDS + 3f;
		} catch (Throwable t) {
			failed = true;
			long now = System.currentTimeMillis();
			if (now - lastErrorLog > 5000) {
				lastErrorLog = now;
				net.veloclient.velo.VeloClient.LOGGER.error("Velo loading screen failed - using Minecraft's own from here", t);
			}
			return doneFor >= 0f;
		}
	}

	// ---------------------------------------------------------------- background thread

	private static void prepare() {
		ScreenLookSnapshot snapshot = ScreenLookSnapshot.read();
		look = snapshot;
		preparedGeneration = snapshot.generation();
		var custom = snapshot.look();
		if (!logoReady && preparedLogo == null) {
			try (InputStream in = VeloLoadingScreenModule.class.getResourceAsStream("/assets/velo-client/textures/icon/logo.png")) {
				if (in != null) {
					preparedLogo = NativeImage.read(in);
				}
			} catch (Exception | LinkageError ignored) {
				// no logo then
			}
		}
		prepareCaption(custom);
		if (custom.background.equals("color")) {
			return;
		}
		if (custom.background.equals("image") && prepareCustomBackground(custom)) {
			return;
		}
		if (backgroundReady && !customBackground) {
			return; // the panorama is already up
		}
		try (InputStream in = VeloLoadingScreenModule.class.getResourceAsStream("/assets/velo-client/textures/gui/title/pano_base.png")) {
			if (in == null) {
				return;
			}
			NativeImage pano;
			pano = NativeImage.read(in);
			int w = CROP_W / SHRINK;
			int h = pano.getHeight() / SHRINK;
			int[] rgb = new int[w * h * 3];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int r = 0;
					int g = 0;
					int b = 0;
					for (int sy = 0; sy < SHRINK; sy++) {
						for (int sx = 0; sx < SHRINK; sx++) {
							int argb = pano.getColorArgb(Math.min(pano.getWidth() - 1, CROP_X + x * SHRINK + sx), y * SHRINK + sy);
							r += (argb >> 16) & 0xFF;
							g += (argb >> 8) & 0xFF;
							b += argb & 0xFF;
						}
					}
					int i = (y * w + x) * 3;
					rgb[i] = r / (SHRINK * SHRINK);
					rgb[i + 1] = g / (SHRINK * SHRINK);
					rgb[i + 2] = b / (SHRINK * SHRINK);
				}
			}
			pano.close();
			// three box blurs ~ a gaussian, like the title screen's blurred background
			for (int pass = 0; pass < 3; pass++) {
				rgb = blur(rgb, w, h, 5);
			}
			NativeImage out = new NativeImage(w, h, false);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int i = (y * w + x) * 3;
					// a touch darker, so the logo and bar stand out
					out.setColorArgb(x, y, 0xFF000000 | (rgb[i] * 3 / 4 << 16) | (rgb[i + 1] * 3 / 4 << 8) | (rgb[i + 2] * 3 / 4));
				}
			}
			backgroundW = w;
			backgroundH = h;
			customBackground = false;
			preparedBackground = out;
		} catch (Exception | LinkageError ignored) {
			// plain dark background then
		}
	}

	private static volatile boolean customBackground;

	/** Your own picture (Customize, Velo+): shrunk, blurred as chosen; a GIF shows its first frame. */
	private static boolean prepareCustomBackground(net.veloclient.velo.client.gui.title.ScreenLook.Loading custom) {
		java.nio.file.Path file = net.veloclient.velo.client.gui.title.ScreenLook.picture("loading");
		if (file == null) {
			return false;
		}
		try {
			java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(file.toFile());
			if (src == null) {
				return false;
			}
			double k = Math.min(1.0, (custom.blur > 0 ? 480.0 : 1920.0) / src.getWidth());
			int w = Math.max(16, (int) Math.round(src.getWidth() * k));
			int h = Math.max(9, (int) Math.round(src.getHeight() * k));
			java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
			var g = scaled.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(src, 0, 0, w, h, null);
			g.dispose();
			int[] px = scaled.getRGB(0, 0, w, h, null, 0, w);
			int[] rgb = new int[w * h * 3];
			for (int i = 0; i < px.length; i++) {
				rgb[i * 3] = (px[i] >> 16) & 0xFF;
				rgb[i * 3 + 1] = (px[i] >> 8) & 0xFF;
				rgb[i * 3 + 2] = px[i] & 0xFF;
			}
			int radius = Math.round(custom.blur / 100f * w / 40f);
			if (radius > 0) {
				for (int pass = 0; pass < 3; pass++) {
					rgb = blur(rgb, w, h, radius);
				}
			}
			NativeImage out = new NativeImage(w, h, false);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int i = (y * w + x) * 3;
					out.setColorArgb(x, y, 0xFF000000 | (rgb[i] << 16) | (rgb[i + 1] << 8) | rgb[i + 2]);
				}
			}
			backgroundW = w;
			backgroundH = h;
			customBackground = true;
			preparedBackground = out;
			return true;
		} catch (Exception | LinkageError e) {
			return false;
		}
	}

	/**
	 * The caption under the logo, drawn with Java's own text renderer from Velo's font files: the game's fonts load as
	 * part of what this screen waits for, so they can't draw it. Rendered large and drawn scaled down - sharp at any GUI
	 * scale.
	 */
	private static void prepareCaption(net.veloclient.velo.client.gui.title.ScreenLook.Loading custom) {
		String caption = custom.caption == null ? "" : custom.caption.strip();
		if (caption.isEmpty()) {
			preparedCaption = null;
			captionW = 0;
			return;
		}
		String file = switch (custom.captionFont) {
			case "Sora" -> "sora_bold.ttf";
			case "Archivo" -> "archivo_semiexp_extrabold.ttf";
			case "Poppins" -> "poppins_regular.ttf";
			case "Space Grotesk" -> "space_grotesk_bold.ttf";
			case "Mono" -> "jetbrains_mono_medium.ttf";
			case "Audiowide" -> "audiowide.ttf";
			default -> "inter_semibold.ttf";
		};
		try (InputStream in = VeloLoadingScreenModule.class.getResourceAsStream("/assets/velo-client/font/" + file)) {
			java.awt.Font font = in != null ? java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, in).deriveFont(64f)
					: new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 64);
			java.awt.image.BufferedImage probe = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			var pg = probe.createGraphics();
			pg.setFont(font);
			var metrics = pg.getFontMetrics();
			int w = Math.min(4096, metrics.stringWidth(caption) + 16);
			int h = metrics.getAscent() + metrics.getDescent() + 8;
			pg.dispose();
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			var g = img.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			g.setFont(font);
			g.setColor(java.awt.Color.WHITE);
			g.drawString(caption, 8, 4 + metrics.getAscent());
			g.dispose();
			NativeImage out = new NativeImage(w, h, false);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					out.setColorArgb(x, y, img.getRGB(x, y));
				}
			}
			captionW = w;
			captionH = h;
			preparedCaption = out;
		} catch (Exception | LinkageError e) {
			preparedCaption = null;
		}
	}

	private static int[] blur(int[] src, int w, int h, int radius) {
		int[] tmp = new int[src.length];
		int[] dst = new int[src.length];
		int span = radius * 2 + 1;
		for (int y = 0; y < h; y++) {
			for (int c = 0; c < 3; c++) {
				int sum = 0;
				for (int k = -radius; k <= radius; k++) {
					sum += src[(y * w + Math.max(0, Math.min(w - 1, k))) * 3 + c];
				}
				for (int x = 0; x < w; x++) {
					tmp[(y * w + x) * 3 + c] = sum / span;
					sum += src[(y * w + Math.min(w - 1, x + radius + 1)) * 3 + c] - src[(y * w + Math.max(0, x - radius)) * 3 + c];
				}
			}
		}
		for (int x = 0; x < w; x++) {
			for (int c = 0; c < 3; c++) {
				int sum = 0;
				for (int k = -radius; k <= radius; k++) {
					sum += tmp[(Math.max(0, Math.min(h - 1, k)) * w + x) * 3 + c];
				}
				for (int y = 0; y < h; y++) {
					dst[(y * w + x) * 3 + c] = sum / span;
					sum += tmp[(Math.min(h - 1, y + radius + 1) * w + x) * 3 + c] - tmp[(Math.max(0, y - radius) * w + x) * 3 + c];
				}
			}
		}
		return dst;
	}

	// ---------------------------------------------------------------- render thread

	private static void upload() {
		var textures = MinecraftClient.getInstance().getTextureManager();
		NativeImage background = preparedBackground;
		if (background != null) {
			preparedBackground = null;
			textures.registerTexture(BACKGROUND, new NativeImageBackedTexture(() -> "velo loading background", background));
			backgroundReady = true;
		}
		NativeImage caption = preparedCaption;
		if (caption != null) {
			preparedCaption = null;
			textures.registerTexture(CAPTION, new NativeImageBackedTexture(() -> "velo loading caption", caption));
			captionReady = true;
		} else if (captionW == 0) {
			captionReady = false;
		}
		if (!logoReady && preparedLogo != null) {
			textures.registerTexture(LOGO, new NativeImageBackedTexture(() -> "velo loading logo", preparedLogo));
			logoReady = true;
		}
	}

	private static float ease(float t) {
		t = Math.max(0f, Math.min(1f, t));
		return 1f - (1f - t) * (1f - t) * (1f - t);
	}

	/**
	 * Draws the loading screen in place of vanilla's startup overlay.
	 *
	 * @param progress  the load's progress (0..1)
	 * @param doneFor   seconds since loading finished, or -1 while it's still loading
	 * @param screen    draws the screen behind (the title screen once it's there), with a vertical offset
	 * @return true when the outro is over and the overlay should go away
	 */
	public static boolean render(DrawContext context, float progress, float doneFor, java.util.function.IntConsumer screen) {
		try {
			upload();
		} catch (Exception | LinkageError ignored) {
			// try again next frame
		}
		int width = context.getScaledWindowWidth();
		int height = context.getScaledWindowHeight();
		float outro = doneFor < 0 ? 0f : ease(doneFor / OUTRO_SECONDS);
		if (outro > 0f) {
			// the title screen slides up into place under the fading loading screen
			screen.accept(Math.round((1f - outro) * 28f));
		}
		// Customize changed since this screen was prepared (next resource reload): prepare again in the background
		if (!preparing && net.veloclient.velo.client.gui.title.ScreenLook.generation() != preparedGeneration) {
			startPrepare();
		}
		var custom = look.look();
		int coverAlpha = Math.round((1f - outro) * 255);
		if (coverAlpha > 0) {
			if (custom.background.equals("color")) {
				context.fill(0, 0, width, height, (coverAlpha << 24) | (custom.color & 0xFFFFFF));
			} else if (backgroundReady) {
				// cover the screen, keeping the panorama's aspect
				float scale = Math.max(width / (float) backgroundW, height / (float) backgroundH);
				int drawW = Math.round(backgroundW * scale);
				int drawH = Math.round(backgroundH * scale);
				context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND, (width - drawW) / 2, (height - drawH) / 2, 0f, 0f, drawW, drawH,
						backgroundW, backgroundH, backgroundW, backgroundH, (coverAlpha << 24) | 0xFFFFFF);
			} else {
				context.fill(0, 0, width, height, (coverAlpha << 24) | (DARK & 0xFFFFFF));
			}
			// the default look has its darkening baked into the panorama; the Customize dim goes on top
			int dim = custom.dim - 25;
			if (customBackground || custom.background.equals("color") ? custom.dim > 0 : dim > 0) {
				int d = customBackground || custom.background.equals("color") ? custom.dim : dim;
				context.fill(0, 0, width, height, (Math.round(d * 2.4f * coverAlpha / 255f) << 24));
			}
		}
		shownProgress += (Math.max(shownProgress, progress) - shownProgress) * 0.12f;
		if (doneFor >= 0) {
			shownProgress += (1f - shownProgress) * 0.35f;
		}
		// logo and bar: drift down and fade out a little ahead of the background
		float out = doneFor < 0 ? 0f : ease(doneFor / (OUTRO_SECONDS * 0.6f));
		int alpha = Math.round((1f - out) * 255);
		if (alpha > 2) {
			int drift = Math.round(out * 10f);
			int size = Math.round(Math.max(40, Math.min(110, height / 5)) * custom.logoSize);
			int cy = height / 2 - size / 3 + drift;
			if (!custom.logo) {
				size = 0;
				cy = height / 2 + drift;
			}
			if (logoReady && custom.logo) {
				float pulse = doneFor < 0 ? 0.94f + 0.06f * (float) Math.sin(System.nanoTime() / 4e8) : 1f;
				int s = Math.round(size * pulse);
				context.drawTexture(RenderPipelines.GUI_TEXTURED, LOGO, width / 2 - s / 2, cy - s / 2, 0f, 0f, s, s, 500, 500, 500, 500,
						(alpha << 24) | 0xFFFFFF);
			}
			int barW = Math.max(120, Math.min(220, width / 4));
			int barX = width / 2 - barW / 2;
			int barY = cy + size / 2 + 18;
			int captionBottom = barY - 6;
			if (captionReady && captionW > 0) {
				// the caption sits between the logo and the bar
				float k = 12f * custom.captionSize / captionH * 1.4f;
				int dw = Math.round(captionW * k);
				int dh = Math.round(captionH * k);
				int capY = cy + size / 2 + 8;
				context.drawTexture(RenderPipelines.GUI_TEXTURED, CAPTION, width / 2 - dw / 2, capY, 0f, 0f, dw, dh, captionW, captionH,
						captionW, captionH, (alpha << 24) | (custom.captionColor & 0xFFFFFF));
				captionBottom = capY + dh;
				barY = Math.max(barY, captionBottom + 8);
			}
			int barRgb = custom.barColor & 0xFFFFFF;
			int thick = custom.bar.equals("thick") ? 4 : 2;
			if (!custom.bar.equals("hidden")) {
				context.fill(barX, barY, barX + barW, barY + thick, (alpha * 0x30 / 255 << 24) | barRgb);
				int filled = Math.round(barW * Math.max(0f, Math.min(1f, shownProgress)));
				if (filled > 0) {
					context.fill(barX, barY - 1, barX + filled, barY + thick + 1, (alpha * 0x28 / 255 << 24) | barRgb); // soft glow
					context.fill(barX, barY, barX + filled, barY + thick, (alpha << 24) | barRgb);
				}
			}
		}
		return doneFor >= OUTRO_SECONDS;
	}
}
