package net.veloclient.velo.client.pvp;

/**
 * Repaints rows of vanilla's 16x16 overlay texture (the one that tints hurt and creeper-flashing
 * entities). The shader mixes the overlay color over the model by (1 - alpha), so a row's alpha is
 * 255 minus the chosen strength. Rows 0-7 are vanilla's red rows; {@link #restore} puts them back.
 */
public final class HitColorTexture {

	private static final int VANILLA_RED = 0xB2FF0000;

	private HitColorTexture() {
	}

	/** @return false if the overlay texture isn't available yet (try again later) */
	public static boolean paint(int[] rows, int[] colors) {
		try {
			for (int i = 0; i < rows.length; i++) {
				int strength = (colors[i] >>> 24) & 0xFF;
				int pixel = (255 - Math.min(230, strength)) << 24 | (colors[i] & 0xFFFFFF);
				fillRow(rows[i], pixel);
			}
			upload();
			return true;
		} catch (RuntimeException | LinkageError e) {
			// Renderer not ready, or the accessor mixin didn't apply on this setup - stay vanilla.
			return false;
		}
	}

	public static boolean restore() {
		try {
			for (int row = 0; row < 8; row++) {
				fillRow(row, VANILLA_RED);
			}
			upload();
			return true;
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	private static void fillRow(int row, int argb) {
		//? if <26.1 {
		var texture = ((net.veloclient.velo.client.mixin.OverlayTextureAccessor) net.minecraft.client.MinecraftClient.getInstance()
				.gameRenderer.getOverlayTexture()).velo$texture();
		var image = texture.getImage();
		if (image == null) {
			return;
		}
		for (int x = 0; x < 16; x++) {
			image.setColorArgb(x, row, argb);
		}
		//?} else {
		/*var texture = ((net.veloclient.velo.client.mixin.OverlayTextureAccessor) net.minecraft.client.Minecraft.getInstance()
				.gameRenderer.overlayTexture()).velo$texture();
		var image = texture.getPixels();
		if (image == null) {
			return;
		}
		for (int x = 0; x < 16; x++) {
			image.setPixel(x, row, argb);
		}
		*///?}
	}

	private static void upload() {
		//? if <26.1 {
		((net.veloclient.velo.client.mixin.OverlayTextureAccessor) net.minecraft.client.MinecraftClient.getInstance()
				.gameRenderer.getOverlayTexture()).velo$texture().upload();
		//?} else {
		/*((net.veloclient.velo.client.mixin.OverlayTextureAccessor) net.minecraft.client.Minecraft.getInstance()
				.gameRenderer.overlayTexture()).velo$texture().upload();
		*///?}
	}
}
