package net.veloclient.velo.dev.mixin;

import net.veloclient.velo.dev.tweaks.SeamlessReloadModule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Seamless Resource Reload: a reload's overlay draws the open screen and a small progress bar instead of the red screen,
 * skips vanilla's one-second grace period and goes away as soon as the reload is done. The first load at startup is the
 * Velo Loading Screen's instead (VeloLoadingScreenModule).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.screen.SplashOverlay.class)
public abstract class SeamlessReloadMixin {

	@Shadow @Final private net.minecraft.client.MinecraftClient client;
	@Shadow @Final private net.minecraft.resource.ResourceReload reload;
	@Shadow @Final private boolean reloading;
	@Shadow private long reloadCompleteTime;
	@Shadow private long reloadStartTime;

	@Unique
	private float velo$progress;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$seamless(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (!reloading) {
			if (net.veloclient.velo.dev.tweaks.VeloLoadingScreenModule.active()) {
				ci.cancel();
				float doneFor = reloadCompleteTime > -1L ? (net.minecraft.util.Util.getMeasuringTimeMs() - reloadCompleteTime) / 1000f : -1f;
				if (net.veloclient.velo.dev.tweaks.VeloLoadingScreenModule.render(context, reload.getProgress(), doneFor, offset -> {
					if (client.currentScreen != null) {
						context.getMatrices().pushMatrix();
						context.getMatrices().translate(0f, offset);
						client.currentScreen.renderWithTooltip(context, mouseX, mouseY, delta);
						context.getMatrices().popMatrix();
					}
				})) {
					client.setOverlay(null);
				}
			}
			return;
		}
		if (!SeamlessReloadModule.active()) {
			return;
		}
		ci.cancel();
		if (reloadStartTime == -1L) {
			reloadStartTime = net.minecraft.util.Util.getMeasuringTimeMs() - 1000L; // no grace period
		}
		if (reloadCompleteTime > -1L) {
			client.setOverlay(null);
			return;
		}
		if (client.currentScreen != null) {
			client.currentScreen.renderWithTooltip(context, mouseX, mouseY, delta);
		}
		velo$progress = Math.max(velo$progress, reload.getProgress());
		SeamlessReloadModule.drawProgress(context, velo$progress);
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.gui.screens.LoadingOverlay.class)
public abstract class SeamlessReloadMixin {

	@Shadow @Final private net.minecraft.client.Minecraft minecraft;
	@Shadow @Final private net.minecraft.server.packs.resources.ReloadInstance reload;
	@Shadow @Final private boolean fadeIn;
	@Shadow private long fadeOutStart;
	@Shadow private long fadeInStart;

	@Unique
	private float velo$progress;

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$seamless(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if (!fadeIn) {
			if (net.veloclient.velo.dev.tweaks.VeloLoadingScreenModule.active()) {
				ci.cancel();
				float doneFor = fadeOutStart > -1L ? (net.minecraft.util.Util.getMillis() - fadeOutStart) / 1000f : -1f;
				if (net.veloclient.velo.dev.tweaks.VeloLoadingScreenModule.render(graphics, reload.getActualProgress(), doneFor, offset -> {
					if (minecraft.screen != null) {
						graphics.pose().pushMatrix();
						graphics.pose().translate(0f, offset);
						minecraft.screen.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, a);
						graphics.pose().popMatrix();
					}
				})) {
					minecraft.setOverlay(null);
				}
			}
			return;
		}
		if (!SeamlessReloadModule.active()) {
			return;
		}
		ci.cancel();
		if (fadeInStart == -1L) {
			fadeInStart = net.minecraft.util.Util.getMillis() - 1000L;
		}
		if (fadeOutStart > -1L) {
			minecraft.setOverlay(null);
			return;
		}
		if (minecraft.screen != null) {
			minecraft.screen.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, a);
		}
		velo$progress = Math.max(velo$progress, reload.getActualProgress());
		SeamlessReloadModule.drawProgress(graphics, velo$progress);
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.gui.screens.LoadingOverlay.class)
public abstract class SeamlessReloadMixin {

	@Shadow @Final private net.minecraft.client.Minecraft minecraft;
	@Shadow @Final private net.minecraft.server.packs.resources.ReloadInstance reload;
	@Shadow @Final private boolean fadeIn;
	@Shadow private long fadeOutStart;
	@Shadow private long fadeInStart;

	@Unique
	private float velo$progress;

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$seamless(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if (!fadeIn) {
			if (net.veloclient.velo.dev.tweaks.VeloLoadingScreenModule.active()) {
				ci.cancel();
				float doneFor = fadeOutStart > -1L ? (net.minecraft.util.Util.getMillis() - fadeOutStart) / 1000f : -1f;
				if (net.veloclient.velo.dev.tweaks.VeloLoadingScreenModule.render(graphics, reload.getActualProgress(), doneFor, offset -> {
					if (minecraft.gui.screen() != null) {
						graphics.pose().pushMatrix();
						graphics.pose().translate(0f, offset);
						minecraft.gui.screen().extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, a);
						graphics.pose().popMatrix();
					}
				})) {
					minecraft.gui.setOverlay(null);
				}
			}
			return;
		}
		if (!SeamlessReloadModule.active()) {
			return;
		}
		ci.cancel();
		if (fadeInStart == -1L) {
			fadeInStart = net.minecraft.util.Util.getMillis() - 1000L;
		}
		if (fadeOutStart > -1L) {
			minecraft.gui.setOverlay(null);
			return;
		}
		if (minecraft.gui.screen() != null) {
			minecraft.gui.screen().extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, a);
		}
		velo$progress = Math.max(velo$progress, reload.getActualProgress());
		SeamlessReloadModule.drawProgress(graphics, velo$progress);
	}
}
*///?}
