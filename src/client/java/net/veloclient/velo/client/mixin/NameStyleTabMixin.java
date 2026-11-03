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

/**
 * Name Styles in the tab list: the row's player name gets marked (team colours and prefixes stay). The finished row
 * name is also kept for a moment ({@link net.veloclient.velo.client.util.TabListCache}): vanilla rebuilds every row's
 * name every frame while Tab is held, which on a big server cost a good chunk of the frame rate.
 */
//? if <26.1 {
@Mixin(PlayerListHud.class)
public abstract class NameStyleTabMixin {

	@Inject(method = "getPlayerName", at = @At("HEAD"), cancellable = true)
	private void velo$cached(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
		if (net.veloclient.velo.client.util.TabListCache.name(entry, entry.getDisplayName()) instanceof Text cached) {
			cir.setReturnValue(cached);
		}
	}

	@Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
	private void velo$nameStyle(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
		Text tagged = NameStyles.tagName(cir.getReturnValue(), entry.getProfile().name(), NameStyles.TAB);
		cir.setReturnValue(tagged);
		net.veloclient.velo.client.util.TabListCache.storeName(entry, entry.getDisplayName(), tagged);
	}
}
//?} else {
/*@Mixin(PlayerTabOverlay.class)
public abstract class NameStyleTabMixin {

	@Inject(method = "getNameForDisplay", at = @At("HEAD"), cancellable = true)
	private void velo$cached(PlayerInfo entry, CallbackInfoReturnable<Component> cir) {
		if (net.veloclient.velo.client.util.TabListCache.name(entry, entry.getTabListDisplayName()) instanceof Component cached) {
			cir.setReturnValue(cached);
		}
	}

	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void velo$nameStyle(PlayerInfo entry, CallbackInfoReturnable<Component> cir) {
		Component tagged = NameStyles.tagName(cir.getReturnValue(), entry.getProfile().name(), NameStyles.TAB);
		cir.setReturnValue(tagged);
		net.veloclient.velo.client.util.TabListCache.storeName(entry, entry.getTabListDisplayName(), tagged);
	}
}
*///?}
