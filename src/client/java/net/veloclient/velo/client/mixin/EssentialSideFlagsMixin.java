package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Essential's side menu on the title and pause screens ({@code RightSideBarNew}) shows little "flags" next to Velo's
 * menu - the beta gem, the news/messages badge and the update badge - which in Essential's minimal layout were all that
 * was left of it: two odd icons floating beside the menu (owner, 1.5.1: "useless, remove it"). Their builders just
 * don't add anything; Essential's real buttons, account menu and modals are untouched. Only applied with Essential
 * installed ({@code @Pseudo}).
 */
@Pseudo
@Mixin(targets = "gg.essential.gui.menu.RightSideBarNew", remap = false)
public abstract class EssentialSideFlagsMixin {

	@Inject(method = "betaFlag", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void velo$noBetaFlag(CallbackInfo ci) {
		ci.cancel();
	}

	@Inject(method = "messageFlag", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void velo$noMessageFlag(CallbackInfo ci) {
		ci.cancel();
	}

	@Inject(method = "updateFlag", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void velo$noUpdateFlag(CallbackInfo ci) {
		ci.cancel();
	}
}
