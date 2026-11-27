package net.veloclient.velo.client.hud;

/** Version differences of the HUD canvas (render target constructor, 26.3 moved GpuFormat into renderpearl). */
final class HudCanvasCompat {

	private HudCanvasCompat() {
	}

	//? if >=26.3 {
	/*static com.mojang.blaze3d.pipeline.TextureTarget newTarget(int width, int height) {
		return new com.mojang.blaze3d.pipeline.TextureTarget("Velo HUD canvas", width, height, com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM, null);
	}
	*///?} else if >=26.2 {
	/*static com.mojang.blaze3d.pipeline.TextureTarget newTarget(int width, int height) {
		return new com.mojang.blaze3d.pipeline.TextureTarget("Velo HUD canvas", width, height, false, com.mojang.blaze3d.GpuFormat.RGBA8_UNORM);
	}
	*///?}
}
