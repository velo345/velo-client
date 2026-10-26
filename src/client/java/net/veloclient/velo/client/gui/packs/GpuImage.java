package net.veloclient.velo.client.gui.packs;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/**
 * A GUI texture the pack tools draw into from plain ARGB pixels: the editor canvas, the selection overlay,
 * thumbnail pages and the software-rendered 3D preview. Upload after changing pixels; draws scaled with
 * nearest filtering, so pixel art stays crisp. Render thread only; {@link #release()} when done.
 */
public final class GpuImage {

	private static int counter;

	private final Identifier id;
	private final NativeImageBackedTexture texture;
	public final int width;
	public final int height;
	private boolean released;

	public GpuImage(String name, int width, int height) {
		this.width = Math.max(1, width);
		this.height = Math.max(1, height);
		this.id = Identifier.of("velo-client", "pack_editor/" + (counter++));
		NativeImage image = new NativeImage(this.width, this.height, true);
		this.texture = new NativeImageBackedTexture(() -> "velo " + name, image);
		MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
	}

	/** Copies {@code argb} ({@code width * height}, row by row) to the GPU. */
	public void upload(int[] argb) {
		upload(argb, 0, 0, width, height, width);
	}

	/** Copies a region of {@code argb} (row length {@code stride}) into this image at (x, y) and uploads. */
	public void upload(int[] argb, int x, int y, int w, int h, int stride) {
		if (released) {
			return;
		}
		NativeImage image = texture.getImage();
		if (image == null) {
			return;
		}
		for (int row = 0; row < h; row++) {
			for (int col = 0; col < w; col++) {
				image.setColorArgb(x + col, y + row, argb[row * stride + col]);
			}
		}
		texture.upload();
	}

	/** Sets pixels without uploading (call {@link #flush()} after a batch). */
	public void put(int[] argb, int x, int y, int w, int h) {
		if (released) {
			return;
		}
		NativeImage image = texture.getImage();
		if (image == null) {
			return;
		}
		for (int row = 0; row < h; row++) {
			for (int col = 0; col < w; col++) {
				image.setColorArgb(x + col, y + row, argb[row * w + col]);
			}
		}
	}

	public void flush() {
		if (!released) {
			texture.upload();
		}
	}

	/** Draws the whole image into the given GUI rectangle. */
	public void draw(DrawContext context, int x, int y, int w, int h) {
		draw(context, x, y, w, h, 0, 0, width, height);
	}

	/** Draws the region (u, v, regionW, regionH) of the image into the given GUI rectangle. */
	public void draw(DrawContext context, int x, int y, int w, int h, int u, int v, int regionW, int regionH) {
		if (!released) {
			context.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, (float) u, (float) v, w, h, regionW, regionH, width, height);
		}
	}

	/** Draws the whole image multiplied by {@code color} (ARGB - e.g. 0x80FFFFFF for half transparent). */
	public void drawTinted(DrawContext context, int x, int y, int w, int h, int color) {
		if (!released) {
			context.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, width, height, width, height, color);
		}
	}

	public void release() {
		if (!released) {
			released = true;
			MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
		}
	}
}
