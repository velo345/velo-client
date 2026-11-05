package net.veloclient.velo.client.compat;

//? if <1.21.2 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/^*
 * 1.21.1 versions of the newer DrawContext texture calls: no pipeline argument and no colour - the colour becomes the
 * shader colour for the one draw. Overloads match the argument lists Velo's GUI uses (x, y, u, v, w, h, region, texture).
 ^/
public final class GuiCompat {

	private GuiCompat() {
	}

	private static void tint(Object pipeline, int argb) {
		RenderSystem.enableBlend();
		if (pipeline == RenderPipelines.CROSSHAIR) {
			RenderSystem.blendFuncSeparate(com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE_MINUS_DST_COLOR,
					com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE_MINUS_SRC_COLOR,
					com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE, com.mojang.blaze3d.platform.GlStateManager.DstFactor.ZERO);
		}
		RenderSystem.setShaderColor((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f);
	}

	private static void untint(Object pipeline) {
		RenderSystem.setShaderColor(1, 1, 1, 1);
		if (pipeline == RenderPipelines.CROSSHAIR) {
			RenderSystem.defaultBlendFunc();
		}
	}

	// drawTexture(pipeline, id, x, y, u, v, w, h, texW, texH[, color])
	public static void drawTexture(DrawContext c, Object p, Identifier id, int x, int y, float u, float v, int w, int h, int tw, int th) {
		drawTexture(c, p, id, x, y, u, v, w, h, w, h, tw, th, 0xFFFFFFFF);
	}

	public static void drawTexture(DrawContext c, Object p, Identifier id, int x, int y, float u, float v, int w, int h, int tw, int th, int color) {
		drawTexture(c, p, id, x, y, u, v, w, h, w, h, tw, th, color);
	}

	// drawTexture(pipeline, id, x, y, u, v, w, h, regionW, regionH, texW, texH[, color])
	public static void drawTexture(DrawContext c, Object p, Identifier id, int x, int y, float u, float v, int w, int h, int rw, int rh, int tw, int th) {
		drawTexture(c, p, id, x, y, u, v, w, h, rw, rh, tw, th, 0xFFFFFFFF);
	}

	public static void drawTexture(DrawContext c, Object p, Identifier id, int x, int y, float u, float v, int w, int h, int rw, int rh, int tw, int th,
			int color) {
		tint(p, color);
		c.drawTexture(id, x, y, w, h, u, v, rw, rh, tw, th);
		untint(p);
	}

	// drawGuiTexture(pipeline, sprite, x, y, w, h[, color])
	public static void drawGuiTexture(DrawContext c, Object p, Identifier sprite, int x, int y, int w, int h) {
		drawGuiTexture(c, p, sprite, x, y, w, h, 0xFFFFFFFF);
	}

	public static void drawGuiTexture(DrawContext c, Object p, Identifier sprite, int x, int y, int w, int h, int color) {
		tint(p, color);
		c.drawGuiTexture(sprite, x, y, w, h);
		untint(p);
	}

	// drawGuiTexture(pipeline, sprite, texW, texH, u, v, x, y, w, h[, color])
	public static void drawGuiTexture(DrawContext c, Object p, Identifier sprite, int tw, int th, int u, int v, int x, int y, int w, int h) {
		drawGuiTexture(c, p, sprite, tw, th, u, v, x, y, w, h, 0xFFFFFFFF);
	}

	public static void drawGuiTexture(DrawContext c, Object p, Identifier sprite, int tw, int th, int u, int v, int x, int y, int w, int h, int color) {
		tint(p, color);
		c.drawGuiTexture(sprite, tw, th, u, v, x, y, w, h);
		untint(p);
	}
}
*///?}
