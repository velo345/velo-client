package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.system.MemoryUtil;
*///?}

/**
 * Velo Turbo (26.2): text glyphs written in one go. Every letter of every text on screen (the whole HUD, chat, menus)
 * went vertex by vertex through the builder - per vertex a begin/validate call, a matrix transform into a new vector
 * and, with Sodium, its copy path with a lock lookup and a thread-local stack per glyph: ~10 % of the render thread
 * with a normal HUD. Here a glyph (with its shadow and bold copies) reserves all its vertices at once and writes them
 * straight into the buffer, in the builder's own layout - the same bytes vanilla writes. Anything unusual (another
 * vertex layout, an unfinished vertex) takes vanilla's path. Off with {@code -Dvelo.noGlyphBulk}.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(targets = "net.minecraft.client.gui.font.glyphs.BakedSheetGlyph$GlyphInstance")
public abstract class TurboGlyphBulkMixin {

	@Unique
	private static final boolean velo$OFF = Boolean.getBoolean("velo.noGlyphBulk");
	// Per vertex format: {size, position, color, uv0, light (-1 = none)}, or empty when it can't be written here.
	@Unique
	private static final java.util.IdentityHashMap<com.mojang.blaze3d.vertex.VertexFormat, int[]> velo$LAYOUTS = new java.util.IdentityHashMap<>();
	@Unique
	private static final int[] velo$NONE = new int[0];

	@Shadow
	@Final
	private float x;
	@Shadow
	@Final
	private float y;
	@Shadow
	@Final
	private int color;
	@Shadow
	@Final
	private int shadowColor;
	@Shadow
	@Final
	private net.minecraft.client.gui.font.glyphs.BakedSheetGlyph glyph;
	@Shadow
	@Final
	private net.minecraft.network.chat.Style style;
	@Shadow
	@Final
	private float boldOffset;
	@Shadow
	@Final
	private float shadowOffset;

	@Shadow
	private boolean hasShadow() {
		return false;
	}

	@Unique
	private static com.mojang.blaze3d.vertex.VertexFormat velo$lastFormat;
	@Unique
	private static int[] velo$lastLayout;

	@Unique
	private static int[] velo$layout(com.mojang.blaze3d.vertex.VertexFormat format) {
		// the text format is the same glyph after glyph: one remembered entry before the map
		if (format == velo$lastFormat) {
			return velo$lastLayout;
		}
		int[] hit = velo$LAYOUTS.get(format);
		if (hit != null) {
			velo$lastFormat = format;
			velo$lastLayout = hit;
			return hit;
		}
		int[] layout = {format.getVertexSize(), -1, -1, -1, -1};
		for (com.mojang.blaze3d.vertex.VertexFormatElement element : format.getElements()) {
			switch (element.name()) {
				case "Position" -> layout[1] = element.offset();
				case "Color" -> layout[2] = element.offset();
				case "UV0" -> layout[3] = element.offset();
				case "UV2" -> layout[4] = element.offset();
				default -> layout = null;
			}
			if (layout == null) {
				break;
			}
		}
		if (layout != null && (layout[1] < 0 || layout[3] < 0)) {
			layout = null;
		}
		velo$LAYOUTS.put(format, layout == null ? velo$NONE : layout);
		velo$lastFormat = format;
		velo$lastLayout = layout == null ? velo$NONE : layout;
		return velo$lastLayout;
	}

	@Inject(method = "render(Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IZ)V", at = @At("HEAD"), cancellable = true)
	private void velo$bulk(org.joml.Matrix4fc pose, com.mojang.blaze3d.vertex.VertexConsumer buffer, int light, boolean flat, CallbackInfo ci) {
		if (velo$OFF || !(buffer instanceof com.mojang.blaze3d.vertex.BufferBuilder builder)
				|| !(buffer instanceof net.veloclient.velo.client.util.BulkVertexSink sink)) {
			return;
		}
		int[] layout = velo$layout(((TurboGlyphFormatAccessor) builder).velo$format());
		if (layout.length == 0) {
			return;
		}
		boolean bold = style.isBold();
		boolean italic = style.isItalic();
		boolean shadow = hasShadow();
		int quads = (bold ? 2 : 1) * (shadow ? 2 : 1);
		long p = sink.velo$reserveRawVertices(quads * 4);
		if (p == -1L) {
			return;
		}
		TurboGlyphInfoAccessor g = (TurboGlyphInfoAccessor) glyph;
		float zFighter = flat ? 0f : 0.001f;
		int stride = layout[0] * 4;
		float depth = 0f;
		if (shadow) {
			velo$quad(p, layout, pose, g, italic, x + shadowOffset, y + shadowOffset, 0f, shadowColor, bold, light);
			p += stride;
			if (bold) {
				velo$quad(p, layout, pose, g, italic, x + boldOffset + shadowOffset, y + shadowOffset, zFighter, shadowColor, true, light);
				p += stride;
			}
			depth = flat ? 0f : 0.03f;
		}
		velo$quad(p, layout, pose, g, italic, x, y, depth, color, bold, light);
		if (bold) {
			p += stride;
			velo$quad(p, layout, pose, g, italic, x + boldOffset, y, depth + zFighter, color, true, light);
		}
		ci.cancel();
	}

	@Unique
	private static void velo$quad(long p, int[] layout, org.joml.Matrix4fc pose, TurboGlyphInfoAccessor g, boolean italic, float x, float y, float z,
			int argb, boolean bold, int light) {
		float x0 = x + g.velo$left();
		float x1 = x + g.velo$right();
		float y0 = y + g.velo$up();
		float y1 = y + g.velo$down();
		float shearTop = italic ? 1f - 0.25f * g.velo$up() : 0f;
		float shearBottom = italic ? 1f - 0.25f * g.velo$down() : 0f;
		float e = bold ? 0.1f : 0f;
		int abgr = argb & 0xFF00FF00 | (argb >>> 16) & 0xFF | (argb & 0xFF) << 16;
		int size = layout[0];
		velo$vertex(p, layout, pose, x0 + shearTop - e, y0 - e, z, abgr, g.velo$u0(), g.velo$v0(), light);
		velo$vertex(p + size, layout, pose, x0 + shearBottom - e, y1 + e, z, abgr, g.velo$u0(), g.velo$v1(), light);
		velo$vertex(p + size * 2L, layout, pose, x1 + shearBottom + e, y1 + e, z, abgr, g.velo$u1(), g.velo$v1(), light);
		velo$vertex(p + size * 3L, layout, pose, x1 + shearTop + e, y0 - e, z, abgr, g.velo$u1(), g.velo$v0(), light);
	}

	@Unique
	private static void velo$vertex(long p, int[] layout, org.joml.Matrix4fc m, float x, float y, float z, int abgr, float u, float v, int light) {
		long at = p + layout[1];
		MemoryUtil.memPutFloat(at, m.m00() * x + m.m10() * y + m.m20() * z + m.m30());
		MemoryUtil.memPutFloat(at + 4, m.m01() * x + m.m11() * y + m.m21() * z + m.m31());
		MemoryUtil.memPutFloat(at + 8, m.m02() * x + m.m12() * y + m.m22() * z + m.m32());
		if (layout[2] >= 0) {
			MemoryUtil.memPutInt(p + layout[2], abgr);
		}
		MemoryUtil.memPutFloat(p + layout[3], u);
		MemoryUtil.memPutFloat(p + layout[3] + 4, v);
		if (layout[4] >= 0) {
			MemoryUtil.memPutInt(p + layout[4], light);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboGlyphBulkMixin {
}
//?}
