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
	private static int texW;
	private static int texH;

	private SharpFrame() {
	}

	private static Object tex;

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
				texW = w;
				texH = h;
			}
			wanted = true;
			return true;
		} catch (RuntimeException e) {
			return false;
		}
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
		if (texW == 0 || screenW <= 0 || screenH <= 0) {
			return;
		}
		x = Math.max(0, Math.min(screenW, x));
		y = Math.max(0, Math.min(screenH, y));
		int x2 = Math.max(x, Math.min(screenW, x + w));
		int y2 = Math.max(y, Math.min(screenH, y + h));
		strip(context, screenW, screenH, 0, 0, screenW, y);
		strip(context, screenW, screenH, 0, y2, screenW, screenH - y2);
		strip(context, screenW, screenH, 0, y, x, y2 - y);
		strip(context, screenW, screenH, x2, y, screenW - x2, y2 - y);
	}

	private static void strip(DrawContext context, int screenW, int screenH, int gx, int gy, int gw, int gh) {
		if (gw <= 0 || gh <= 0) {
			return;
		}
		float sx = texW / (float) screenW;
		float sy = texH / (float) screenH;
		int rw = Math.round(gw * sx);
		int rh = Math.round(gh * sy);
		// the copied render target is stored bottom-up: sample it flipped
		context.drawTexture(RenderPipelines.GUI_TEXTURED, ID, gx, gy, gx * sx, texH - gy * sy, gw, gh, rw, -rh, texW, texH, 0xFFFFFFFF);
	}
}
