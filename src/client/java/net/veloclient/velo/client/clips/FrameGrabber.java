package net.veloclient.velo.client.clips;

import net.minecraft.client.MinecraftClient;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Reads the finished frame (world + HUD) back from the GPU for the clip recorder - the same
 * texture-to-buffer copy vanilla's screenshot uses, but without its per-pixel loop on the render
 * thread: the mapped bytes are bulk-copied into a pooled direct buffer (about a millisecond for a
 * 1080p frame) and everything else (flip, scale, JPEG) happens on the encoder thread. The copy
 * completes asynchronously a frame or two later, so the GPU is never stalled; with every pooled
 * buffer in flight the frame is just skipped.
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

	/** Frees the pool (recording turned off). Buffers still in flight are dropped when they come back. */
	static synchronized void clear() {
		FREE.clear();
		allocated = 0;
		poolBytes = -1;
	}

	/** Starts an async capture of the current frame; false if skipped (pool busy / not ready). */
	static boolean grab(Sink sink) {
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
		int bytes = width * height * 4;
		ByteBuffer dest = acquire(bytes);
		if (dest == null) {
			return false;
		}
		var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
		// 9 = USAGE_MAP_READ | USAGE_COPY_DST, exactly what vanilla's screenshot uses.
		var buffer = device.createBuffer(() -> "Velo clip frame", 9, (long) bytes);
		var encoder = device.createCommandEncoder();
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
				sink.accept(dest, width, height);
			} catch (Throwable t) {
				if (!handed) {
					release(dest);
				}
			} finally {
				buffer.close();
			}
		}, 0);
		return true;
	}
}
