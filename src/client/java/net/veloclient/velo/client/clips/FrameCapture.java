package net.veloclient.velo.client.clips;

import java.nio.ByteBuffer;

/** Public door to the clip recorder's frame readback (bottom-up RGBA, asynchronous) - Velo Rewind's video export uses it. */
public final class FrameCapture {

	public interface Sink {
		void accept(ByteBuffer rgba, int width, int height);
	}

	private FrameCapture() {
	}

	/** Reads this frame back (scaled down on the GPU to {@code maxHeight} where it can); false if every buffer is busy. */
	public static boolean grab(Sink sink, int maxHeight) {
		return FrameGrabber.grab(sink::accept, maxHeight);
	}

	public static void release(ByteBuffer buffer) {
		FrameGrabber.release(buffer);
	}
}
