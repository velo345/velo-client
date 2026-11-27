package net.veloclient.velo.client.util;

/** Velo's rules on top of vanilla's frame limit (FrameCapMixin), shared by every version's hook. */
public final class FrameCaps {

	private FrameCaps() {
	}

	public static int apply(int vanilla) {
		if (ParkedGame.waiting()) {
			return ParkedGame.frameCap(vanilla);
		}
		if (WindowOcclusion.covered()) {
			return Math.min(vanilla, WindowOcclusion.HIDDEN_FPS);
		}
		return net.veloclient.velo.client.modules.performance.LowLatencyModule.vanillaLimit(vanilla);
	}
}
