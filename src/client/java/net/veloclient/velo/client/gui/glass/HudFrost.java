package net.veloclient.velo.client.gui.glass;

import net.minecraft.client.MinecraftClient;

/**
 * HUD Style "Glass": the frost behind the HUD's panels, put straight into the frame right before the HUD is drawn
 * (2026-11-26). It used to run inside the HUD pass - blur, then a sharp copy drawn back over the whole screen - which a
 * HUD cache (Gnetum) keeps in its own image: the copy landed on top of other HUD parts. Here nothing depends on how the
 * HUD is drawn, so caches stay on.
 *
 * <p>Here the frame is only blurred into a spare picture, at most 60 times a second (behind frosted glass more doesn't
 * show), and each panel draws its own piece of it ({@link #behind}) like any other HUD drawing: no copies in the other
 * frames (copying the panel areas each frame cost most of the FPS). Not for 1.21.1 / 26.1 (no spot before the HUD).
 */
public final class HudFrost {

	private static final long INTERVAL = 1_000_000_000L / 60;

	/** Extra rects (Tier Tagger's ring chips), gui pixels {x, y, w, h[, radius]}. */
	private static java.util.List<int[]> extra = java.util.List.of();
	private static boolean broken;

	private HudFrost() {
	}

	public static boolean supported() {
		//? if >=26.2 {
		/*return true;
		*///?} else if >=1.21.2 && <26.1 {
		return true;
		//?} else {
		/*return false;
		*///?}
	}

	public static void extra(java.util.List<int[]> rects) {
		extra = rects;
	}

	/** Behind a HUD panel while it's drawn (only in play: menus blur themselves). */
	public static void behind(net.minecraft.client.gui.DrawContext context, int x, int y, int w, int h, int radius) {
		if (!supported() || broken || net.veloclient.velo.client.util.ClientCompat.currentScreen() != null) {
			return;
		}
		boolean glass = net.veloclient.velo.client.redstone.RedstoneMode.active() ? net.veloclient.velo.client.gui.widget.VeloStyle.glass()
				: net.veloclient.velo.client.hud.HudLook.glassBlur();
		if (glass) {
			SharpFrame.drawBlurredPanel(context, x, y, w, h, radius);
		}
	}

	//? if >=1.21.2 {
	private static long blurredAt;

	/** Render thread, right before the HUD is drawn into the world picture. */
	public static void apply() {
		//? if >=26.2 || <26.1 {
		if (broken) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || net.veloclient.velo.client.util.ClientCompat.currentScreen() != null || hudHidden(client)) {
			return;
		}
		boolean redstone = net.veloclient.velo.client.redstone.RedstoneMode.active();
		boolean glass = redstone ? net.veloclient.velo.client.gui.widget.VeloStyle.glass() : net.veloclient.velo.client.hud.HudLook.glassBlur();
		java.util.List<int[]> rects = new java.util.ArrayList<>(extra);
		if (glass) {
			rects.addAll(net.veloclient.velo.client.hud.HudLook.rects());
		}
		if (rects.isEmpty()) {
			return;
		}
		long now = System.nanoTime();
		if (now - blurredAt < INTERVAL) {
			return;   // the panels keep drawing the last one
		}
		try {
			if (SharpFrame.blurCopy(() -> blur(client))) {
				blurredAt = now;
			}
		} catch (RuntimeException | LinkageError e) {
			broken = true;
			net.veloclient.velo.VeloClient.LOGGER.warn("HUD frost failed - glass HUD panels stay unblurred this session", e);
		}
		//?}
	}

	//? if >=26.2 || <26.1 {
	private static void blur(MinecraftClient client) {
		//? if >=26.2 {
		/*client.gameRenderer.processBlurEffect();
		*///?} else {
		((net.veloclient.velo.client.mixin.GameRendererBlurInvoker) client.gameRenderer).velo$renderBlur();
		//?}
	}
	//?}

	private static boolean hudHidden(MinecraftClient client) {
		//? if <26.1 {
		return client.options.hudHidden;
		//?} else if <26.2 {
		/*return client.options.hideGui;
		*///?} else {
		/*return client.gui.hud.isHidden();
		*///?}
	}
	//?} else {
	/*public static void apply() {
	}
	*///?}
}
