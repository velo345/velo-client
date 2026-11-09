package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.veloclient.velo.client.hud.HudFrameCache;
*///?}

/**
 * Velo Turbo whole-HUD cache (see {@link net.veloclient.velo.client.hud.HudFrameCache#wholeHud()}): the entire HUD is
 * built at most 360 times a second and replayed in between, whenever Gnetum isn't caching it. With Gnetum caching, this
 * passes straight through.
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.gui.Hud.class)
public abstract class TurboHudWholeMixin {

	@WrapMethod(method = "extractRenderState")
	private void velo$wholeHud(net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker delta, Operation<Void> original) {
		// Post effects on the world picture: every frame, before any HUD cache (ours or Gnetum's) can skip the HUD -
		// in HudManager they only ran in the frames the HUD was rebuilt and flickered.
		if (!(net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.minecraft.client.gui.screens.TitleScreen)) {
			net.veloclient.velo.client.modules.rendering.ColorGradingModule.applyIfActive();
			net.veloclient.velo.client.modules.performance.PolyBlurModule.applyIfActive();
		}
		if (!HudFrameCache.wholeHud()) {
			original.call(graphics, delta);
			return;
		}
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		if (HudFrameCache.replay(graphics, width, height)) {
			return;
		}
		HudFrameCache.beginRecord(graphics, width, height);
		boolean completed = false;
		try {
			original.call(graphics, delta);
			completed = true;
		} finally {
			HudFrameCache.endRecord(graphics, completed);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboHudWholeMixin {
	// GuiRenderState era only (VeloMixinPlugin skips TurboHud* before 26.2).
}
//?}
