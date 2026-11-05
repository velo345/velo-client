package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * 1.21.1 has no HUD element registry (Fabric's HudElementRegistry came with 1.21.2+), so the vanilla parts Velo's own
 * modules replace - status effect icons, crosshair, boss bars, scoreboard - are skipped here instead. Newer versions do
 * this in {@link net.veloclient.velo.client.hud.HudManager#register()}; the class is empty there.
 */
@Mixin(net.minecraft.client.gui.hud.InGameHud.class)
public abstract class VanillaHudGateMixin {
	//? if <1.21.2 {
	/*@org.spongepowered.asm.mixin.injection.Inject(method = "renderStatusEffectOverlay", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true, require = 0)
	private void velo$effects(net.minecraft.client.gui.DrawContext context, net.minecraft.client.render.RenderTickCounter tick,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (net.veloclient.velo.client.hud.HudManager.hidesVanillaEffects()) {
			ci.cancel();
		}
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "renderCrosshair", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true, require = 0)
	private void velo$crosshair(net.minecraft.client.gui.DrawContext context, net.minecraft.client.render.RenderTickCounter tick,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (net.veloclient.velo.client.hud.HudManager.drawCustomCrosshair(context)) {
			ci.cancel();
		}
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
			at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true, require = 0)
	private void velo$scoreboard(net.minecraft.client.gui.DrawContext context, net.minecraft.client.render.RenderTickCounter tick,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (net.veloclient.velo.client.hud.HudManager.hidesVanillaScoreboard()) {
			ci.cancel();
		}
	}

	@org.spongepowered.asm.mixin.injection.Redirect(method = "method_55808", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/hud/BossBarHud;render(Lnet/minecraft/client/gui/DrawContext;)V"), require = 0)
	private void velo$bossBar(net.minecraft.client.gui.hud.BossBarHud bars, net.minecraft.client.gui.DrawContext context) {
		if (!net.veloclient.velo.client.hud.HudManager.hidesVanillaBossBar()) {
			bars.render(context);
		}
	}
	*///?}
}
