package net.veloclient.velo.client.clips;

import net.minecraft.client.MinecraftClient;
//? if >=26.3 {
/*import com.mojang.renderpearl.api.buffers.GpuBuffer;
*///?} else {
import com.mojang.blaze3d.buffers.GpuBuffer;
//?}

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Reads the finished frame (world + HUD) back from the GPU for the clip recorder - the same
 * texture-to-buffer copy vanilla's screenshot uses, but without its per-pixel loop on the render
 * thread: the mapped bytes are bulk-copied into a pooled direct buffer and everything else (flip,
 * JPEG) happens on the encoder thread. The copy completes asynchronously a frame or two later, so
 * the GPU is never stalled; with every pooled buffer in flight the frame is just skipped.
 *
 * <p>Clips Pro measured at -45% FPS on a 1440p screen, from three costs this now avoids: a new
 * 15 MB GPU readback buffer was created and destroyed for every captured frame (they're pooled
 * now), the full-size frame was copied on the render thread, and ffmpeg then scaled 2560x1440 down
 * to 1080p on the CPU (~1.2 cores). On 26.2+ a frame taller than the recording is scaled down on
 * the GPU first (the bilinear blit vanilla's Tracy frame capture uses), so only the recorded size
 * is read back and copied, and the encoder gets it at its final size.
 */
final class FrameGrabber {

	/** Receives RGBA rows, bottom row first; must call {@link #release} when done with the buffer. */
	interface Sink {
		void accept(ByteBuffer rgba, int width, int height);
	}

	private static final int POOL = 3;
	private static final ArrayDeque<ByteBuffer> FREE = new ArrayDeque<>();
	private static int allocated;
	private static int poolBytes;
	/** GPU readback buffers, reused frame to frame (render thread only). */
	private static final ArrayDeque<GpuBuffer> GPU_FREE = new ArrayDeque<>();
	private static long gpuBytes = -1;

	//? if >=26.3 {
	/*private static com.mojang.renderpearl.api.textures.GpuTexture scaled;
	private static com.mojang.renderpearl.api.textures.GpuTextureView scaledView;
	*///?} else if >=26.2 {
	/*private static com.mojang.blaze3d.textures.GpuTexture scaled;
	private static com.mojang.blaze3d.textures.GpuTextureView scaledView;
	*///?}

	private FrameGrabber() {
	}

	static synchronized void release(ByteBuffer buffer) {
		if (buffer.capacity() == poolBytes) {
			FREE.push(buffer);
		} else {
			allocated--;
		}
	}

	private static synchronized ByteBuffer acquire(int bytes) {
		if (bytes != poolBytes) {
			FREE.clear();
			allocated = 0;
			poolBytes = bytes;
		}
		if (!FREE.isEmpty()) {
			return FREE.pop();
		}
		if (allocated >= POOL) {
			return null;
		}
		allocated++;
		return ByteBuffer.allocateDirect(bytes);
	}

	private static GpuBuffer acquireGpu(long bytes) {
		if (bytes != gpuBytes) {
			GPU_FREE.forEach(GpuBuffer::close);
			GPU_FREE.clear();
			gpuBytes = bytes;
		}
		if (!GPU_FREE.isEmpty()) {
			return GPU_FREE.pop();
		}
		// 9 = USAGE_MAP_READ | USAGE_COPY_DST, exactly what vanilla's screenshot uses.
		return com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(() -> "Velo clip frame", 9, bytes);
	}

	private static void releaseGpu(GpuBuffer buffer, long bytes) {
		if (bytes == gpuBytes && GPU_FREE.size() < POOL) {
			GPU_FREE.push(buffer);
		} else {
			buffer.close();
		}
	}

	/** Frees the pool (recording turned off). Buffers still in flight are dropped when they come back. */
	static synchronized void clear() {
		FREE.clear();
		allocated = 0;
		poolBytes = -1;
		GPU_FREE.forEach(GpuBuffer::close);
		GPU_FREE.clear();
		gpuBytes = -1;
		//? if >=26.2 {
		/*if (scaled != null) {
			scaledView.close();
			scaled.close();
			scaled = null;
			scaledView = null;
		}
		*///?}
	}

	/** Starts an async capture of the current frame; false if skipped (pool busy / not ready). */
	static boolean grab(Sink sink) {
		return grab(sink, Integer.MAX_VALUE);
	}

	/**
	 * Like {@link #grab(Sink)}, but a frame taller than {@code maxHeight} may arrive already scaled
	 * down (aspect kept, even size) - the sink gets the size it really has.
	 */
	static boolean grab(Sink sink, int maxHeight) {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		var texture = client.getFramebuffer().getColorAttachment();
		//?} else if <26.2 {
		/*var texture = client.getMainRenderTarget().getColorTexture();
		*///?} else {
		/*var texture = client.gameRenderer.mainRenderTarget().getColorTexture();
		*///?}
		if (texture == null) {
			return false;
		}
		int width = texture.getWidth(0);
		int height = texture.getHeight(0);
		var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
		var encoder = device.createCommandEncoder();
		//? if >=26.3 {
		/*int outH = Math.min(height, maxHeight) & ~1;
		if (outH > 0 && outH < height) {
			int outW = Math.round(width * (outH / (float) height)) & ~1;
			if (scaled == null || scaled.getWidth(0) != outW || scaled.getHeight(0) != outH || scaled.getFormat() != texture.getFormat()) {
				if (scaled != null) {
					scaledView.close();
					scaled.close();
				}
				// 10 = USAGE_COPY_SRC | USAGE_RENDER_ATTACHMENT, like vanilla's Tracy frame capture target.
				scaled = device.createTexture("Velo clip frame (scaled)", 10, texture.getFormat(), outW, outH, 1, 1);
				scaledView = device.createTextureView(scaled);
			}
			try (var pass = encoder.createRenderPass(() -> "Velo clip downscale", scaledView, java.util.Optional.empty())) {
				com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass);
				pass.setPipeline(com.mojang.blaze3d.systems.RenderSystem.getCompiledPipeline(net.minecraft.client.renderer.RenderPipelines.TRACY_BLIT));
				pass.setUniform("InSampler", client.gameRenderer.mainRenderTarget().getColorTextureView(),
						com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR));
				pass.draw(3, 1, 0, 0);
			}
			texture = scaled;
			width = outW;
			height = outH;
		}
		*///?} else if >=26.2 {
		/*int outH = Math.min(height, maxHeight) & ~1;
		if (outH > 0 && outH < height) {
			int outW = Math.round(width * (outH / (float) height)) & ~1;
			if (scaled == null || scaled.getWidth(0) != outW || scaled.getHeight(0) != outH || scaled.getFormat() != texture.getFormat()) {
				if (scaled != null) {
					scaledView.close();
					scaled.close();
				}
				// 10 = USAGE_COPY_SRC | USAGE_RENDER_ATTACHMENT, like vanilla's Tracy frame capture target.
				scaled = device.createTexture("Velo clip frame (scaled)", 10, texture.getFormat(), outW, outH, 1, 1);
				scaledView = device.createTextureView(scaled);
			}
			try (var pass = encoder.createRenderPass(() -> "Velo clip downscale", scaledView, java.util.Optional.empty())) {
				pass.setPipeline(net.minecraft.client.renderer.RenderPipelines.TRACY_BLIT);
				pass.bindTexture("InSampler", client.gameRenderer.mainRenderTarget().getColorTextureView(),
						com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR));
				pass.draw(3, 1, 0, 0);
			}
			texture = scaled;
			width = outW;
			height = outH;
		}
		*///?}
		int bytes = width * height * 4;
		ByteBuffer dest = acquire(bytes);
		if (dest == null) {
			return false;
		}
		final int w = width;
		final int h = height;
		GpuBuffer buffer = acquireGpu(bytes);
		encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
			boolean handed = false;
			//? if <26.2 {
			try (var view = encoder.mapBuffer(buffer, true, false)) {
			//?} else {
			/*try (var view = buffer.map(true, false)) {
			*///?}
				ByteBuffer src = view.data();
				dest.clear();
				dest.put(0, src, 0, Math.min(bytes, src.remaining()));
				handed = true;
				sink.accept(dest, w, h);
			} catch (Throwable t) {
				if (!handed) {
					release(dest);
				}
			} finally {
				releaseGpu(buffer, bytes);
			}
		}, 0);
		return true;
	}
}
