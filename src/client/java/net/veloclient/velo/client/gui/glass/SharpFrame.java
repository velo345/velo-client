package net.veloclient.velo.client.gui.glass;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
//? if >=26.3 {
/*import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
*///?} else if >=1.21.2 {
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
//?}

/**
 * Glass blur only behind a menu (2026-11-25). Minecraft blurs the whole screen; right before its blur runs this copies
 * the still-sharp frame ({@link #capture}, from GameRenderer's blur), and the menu then draws that sharp copy back
 * everywhere except behind itself ({@link #drawOutside}) - so the game around the menu stays clear and only the glass
 * is frosted. 1.21.1 blurs immediately (no copy point): there the whole screen stays blurred as before.
 */
public final class SharpFrame {

	private static final Identifier ID = Identifier.of("velo-client", "glass/sharp_frame");
	private static boolean wanted;
	private static boolean wantedBlurred;
	private static final Identifier BLURRED_ID = Identifier.of("velo-client", "glass/blurred_frame");
	private static int texW;
	private static int texH;

	private SharpFrame() {
	}

	private static Object tex;
	private static Object blurredTex;

	//? if >=1.21.11 && <26.1 {
	private static final class Tex extends net.minecraft.client.texture.AbstractTexture {
		Tex(GpuTexture texture) {
			this.glTexture = texture;
			this.glTextureView = com.mojang.blaze3d.systems.RenderSystem.getDevice().createTextureView(texture);
			this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
		}

		GpuTexture gpu() {
			return this.glTexture;
		}
	}
	//?} else if >=1.21.2 && <1.21.11 {
	/*private static final class Tex extends net.minecraft.client.texture.AbstractTexture {
		Tex(GpuTexture texture) {
			this.glTexture = texture;
			this.glTextureView = com.mojang.blaze3d.systems.RenderSystem.getDevice().createTextureView(texture);
			setFilter(true, false);
			setClamp(true);
		}

		GpuTexture gpu() {
			return this.glTexture;
		}
	}
	*///?} else if >=26.1 {
	/*private static final class Tex extends net.minecraft.client.renderer.texture.AbstractTexture {
		Tex(GpuTexture texture) {
			this.texture = texture;
			this.textureView = com.mojang.blaze3d.systems.RenderSystem.getDevice().createTextureView(texture);
			this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}

		GpuTexture gpu() {
			return this.texture;
		}
	}
	*///?}

	//? if >=1.21.2 && <26.1 {
	private static GpuTexture main() {
		return MinecraftClient.getInstance().getFramebuffer().getColorAttachment();
	}
	//?} else if >=26.1 && <26.2 {
	/*private static GpuTexture main() {
		return net.minecraft.client.Minecraft.getInstance().getMainRenderTarget().getColorTexture();
	}
	*///?} else if >=26.2 {
	/*private static GpuTexture main() {
		return net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
	}
	*///?}

	//? if <1.21.2 {
	/*public static boolean prepare() {
		return false;
	}

	public static void capture() {
	}

	public static void captureBlurred() {
	}

	public static boolean prepareBoth() {
		return false;
	}
	*///?} else {
	/** Called while a menu records its frame: makes sure the copy target matches the screen. False when not possible. */
	public static boolean prepare() {
		try {
			var main = main();
			if (main == null) {
				return false;
			}
			int w = main.getWidth(0);
			int h = main.getHeight(0);
			if (tex == null || w != texW || h != texH) {
				var gpu = com.mojang.blaze3d.systems.RenderSystem.getDevice().createTexture(() -> "Velo glass sharp frame",
						GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
						main.getFormat(), w, h, 1, 1);
				Tex made = new Tex(gpu);
				MinecraftClient.getInstance().getTextureManager().registerTexture(ID, made);
				tex = made;
				var gpu2 = com.mojang.blaze3d.systems.RenderSystem.getDevice().createTexture(() -> "Velo glass blurred frame",
						GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
						main.getFormat(), w, h, 1, 1);
				Tex made2 = new Tex(gpu2);
				MinecraftClient.getInstance().getTextureManager().registerTexture(BLURRED_ID, made2);
				blurredTex = made2;
				texW = w;
				texH = h;
			}
			wanted = true;
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Like {@link #prepare}, and also keeps the blurred frame (for menus with several glass panels: {@link #drawSharpExcept}). */
	public static boolean prepareBoth() {
		if (!prepare()) {
			return false;
		}
		wantedBlurred = true;
		return true;
	}

	/** GameRenderer, right after the menu blur: copy the blurred frame. */
	public static void captureBlurred() {
		if (!wantedBlurred || blurredTex == null) {
			return;
		}
		wantedBlurred = false;
		var main = main();
		if (main == null || main.getWidth(0) != texW || main.getHeight(0) != texH) {
			return;
		}
		com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main, ((Tex) blurredTex).gpu(), 0, 0, 0, 0, 0, texW, texH);
	}

	/** GameRenderer, right before the menu blur: copy the frame as it is (sharp). */
	public static void capture() {
		if (!wanted || tex == null) {
			return;
		}
		wanted = false;
		var main = main();
		if (main == null || main.getWidth(0) != texW || main.getHeight(0) != texH) {
			return;
		}
		com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main, ((Tex) tex).gpu(), 0, 0, 0, 0, 0, texW, texH);
	}
	//?}

	/**
	 * Draws the sharp frame over the whole screen except the rect {x, y, w, h} (gui pixels) - the menu sits there on
	 * the blurred game. Call right after the background, before anything else of the menu.
	 */
	public static void drawOutside(DrawContext context, int screenW, int screenH, int x, int y, int w, int h) {
		drawOutside(context, screenW, screenH, x, y, w, h, 0);
	}

	/** Like {@link #drawOutside(DrawContext, int, int, int, int, int, int)} for a rounded menu: the corners outside its curve stay sharp too. */
	public static void drawOutside(DrawContext context, int screenW, int screenH, int x, int y, int w, int h, int radius) {
		if (texW == 0 || screenW <= 0 || screenH <= 0) {
			return;
		}
		corners(context, ID, screenW, screenH, x, y, w, h, radius);
		x = Math.max(0, Math.min(screenW, x));
		y = Math.max(0, Math.min(screenH, y));
		int x2 = Math.max(x, Math.min(screenW, x + w));
		int y2 = Math.max(y, Math.min(screenH, y + h));
		strip(context, screenW, screenH, 0, 0, screenW, y);
		strip(context, screenW, screenH, 0, y2, screenW, screenH - y2);
		strip(context, screenW, screenH, 0, y, x, y2 - y);
		strip(context, screenW, screenH, x2, y, screenW - x2, y2 - y);
	}

	/** Runs the menu blur at this point of the GUI (everything drawn before gets blurred). */
	public static void blurHere(DrawContext context) {
		newLayer(context);
		//? if >=1.21.2 && <26.1 {
		context.applyBlur();
		//?} else if >=26.1 {
		/*context.blurBeforeThisStratum();
		*///?}
	}

	/**
	 * Starts a new GUI layer: the blur only takes what's in the layers before the current one, so whatever must be
	 * blurred (and be in the sharp copy) has to be drawn before this.
	 */
	public static void newLayer(DrawContext context) {
		//? if >=1.21.2 && <26.1 {
		context.createNewRootLayer();
		//?} else if >=26.1 {
		/*context.nextStratum();
		*///?}
	}

	/**
	 * Several glass panels (the pause menu): the sharp frame everywhere, then the blurred one back behind each rect
	 * {x, y, w, h} (gui pixels). Needs {@link #prepareBoth} this frame.
	 */
	public static void drawSharpExcept(DrawContext context, int screenW, int screenH, java.util.List<int[]> rects) {
		if (texW == 0 || screenW <= 0 || screenH <= 0) {
			return;
		}
		strip(context, ID, screenW, screenH, 0, 0, screenW, screenH);
		for (int[] r : rects) {
			int x = Math.max(0, r[0]);
			int y = Math.max(0, r[1]);
			int x2 = Math.min(screenW, r[0] + r[2]);
			int y2 = Math.min(screenH, r[1] + r[3]);
			strip(context, BLURRED_ID, screenW, screenH, x, y, x2 - x, y2 - y);
			if (r.length > 4) {
				corners(context, ID, screenW, screenH, r[0], r[1], r[2], r[3], r[4]);   // rounded: sharp again outside the curve
			}
		}
	}

	/** The four corner areas outside a rounded rect's curve, row by row, from texture {@code id}. */
	private static void corners(DrawContext context, Identifier id, int screenW, int screenH, int x, int y, int w, int h, int radius) {
		int r = Math.min(radius, Math.min(w, h) / 2);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(Math.max(0, (double) r * r - dy * dy)));
			if (inset <= 0) {
				continue;
			}
			strip(context, id, screenW, screenH, x, y + i, inset, 1);
			strip(context, id, screenW, screenH, x + w - inset, y + i, inset, 1);
			strip(context, id, screenW, screenH, x, y + h - 1 - i, inset, 1);
			strip(context, id, screenW, screenH, x + w - inset, y + h - 1 - i, inset, 1);
		}
	}

	private static void strip(DrawContext context, int screenW, int screenH, int gx, int gy, int gw, int gh) {
		strip(context, ID, screenW, screenH, gx, gy, gw, gh);
	}

	private static void strip(DrawContext context, Identifier id, int screenW, int screenH, int gx, int gy, int gw, int gh) {
		if (gw <= 0 || gh <= 0) {
			return;
		}
		float sx = texW / (float) screenW;
		float sy = texH / (float) screenH;
		int rw = Math.round(gw * sx);
		int rh = Math.round(gh * sy);
		// the copied render target is stored bottom-up: sample it flipped
		context.drawTexture(RenderPipelines.GUI_TEXTURED, id, gx, gy, gx * sx, texH - gy * sy, gw, gh, rw, -rh, texW, texH, 0xFFFFFFFF);
	}
}
