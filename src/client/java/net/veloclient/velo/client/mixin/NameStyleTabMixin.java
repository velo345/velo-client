package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
*///?}
import net.veloclient.velo.client.namestyle.NameStyles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Name Styles in the tab list: the row's player name gets marked (team colours and prefixes stay). */
//? if <26.1 {
@Mixin(PlayerListHud.class)
public abstract class NameStyleTabMixin {

	@Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
	private void velo$nameStyle(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
		cir.setReturnValue(NameStyles.tagName(cir.getReturnValue(), entry.getProfile().name(), NameStyles.TAB));
	}
}
//?} else {
/*@Mixin(PlayerTabOverlay.class)
public abstract class NameStyleTabMixin {

	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void velo$nameStyle(PlayerInfo entry, CallbackInfoReturnable<Component> cir) {
		cir.setReturnValue(NameStyles.tagName(cir.getReturnValue(), entry.getProfile().name(), NameStyles.TAB));
	}
}
*///?}
