package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.hud.InGameHud;
//?} else if <26.2 {
/*import net.minecraft.client.gui.Gui;
*///?} else {
/*import net.minecraft.client.gui.Hud;
*///?}
import net.veloclient.velo.client.gui.VeloFonts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps chat/hotbar/scoreboard in the vanilla font while a Velo menu (Inter) is open on top. */
//? if <26.1 {
@Mixin(InGameHud.class)
//?} else if <26.2 {
/*@Mixin(Gui.class)
*///?} else {
/*@Mixin(Hud.class)
*///?}
public abstract class HudFontScopeMixin {

	//? if <26.1 {
	@Inject(method = "render", at = @At("HEAD"))
	//?} else {
	/*@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
	*///?}
	private void velo$hudFontStart(CallbackInfo ci) {
		VeloFonts.beginHud();
	}

	//? if <26.1 {
	@Inject(method = "render", at = @At("RETURN"))
	//?} else {
	/*@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"))
	*///?}
	private void velo$hudFontEnd(CallbackInfo ci) {
		VeloFonts.endHud();
	}
}
