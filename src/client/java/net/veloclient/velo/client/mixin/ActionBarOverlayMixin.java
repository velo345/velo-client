package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.hud.InGameHud;
//?} else if <26.2 {
/*import net.minecraft.client.gui.Gui;
*///?} else {
/*import net.minecraft.client.gui.Hud;
*///?}
import net.minecraft.text.Text;
import net.veloclient.velo.client.modules.hud.ActionBarLogModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link InGameHud#setOverlayMessage} is the single choke point every
 * action-bar message actually passes through (record-playing notices, the
 * dedicated {@code OverlayMessageS2CPacket} plugins/commands use for
 * {@code /title @s actionbar ...}, and the rarer overlay-flagged system
 * messages) - {@link ActionBarLogModule} previously listened to Fabric API's
 * {@code ClientReceiveMessageEvents.GAME} instead, which only covers that
 * last, uncommon case and never fires for the dedicated packet, which is why
 * the log stayed empty in practice.
 */
//? if <26.1 {
@Mixin(InGameHud.class)
//?} else if <26.2 {
/*@Mixin(Gui.class)
*///?} else {
/*@Mixin(Hud.class)
*///?}
public abstract class ActionBarOverlayMixin {

	@Inject(method = "setOverlayMessage", at = @At("HEAD"))
	private void velo$captureActionBar(Text message, boolean tinted, CallbackInfo ci) {
		ActionBarLogModule.capture(message);
	}
}
