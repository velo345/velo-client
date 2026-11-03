package net.veloclient.velo.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Holding Tab on a big server: the sorted player list is reused for a quarter second and each name's width is
 * measured once instead of every frame (see {@link net.veloclient.velo.client.util.TabListCache}; the names
 * themselves are kept in NameStyleTabMixin). WrapOperation, not Redirect, so other mods' hooks there still run.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.hud.PlayerListHud.class)
public abstract class TabListCacheMixin {

	@Inject(method = "collectPlayerEntries", at = @At("HEAD"), cancellable = true, require = 0)
	@SuppressWarnings("unchecked")
	private void velo$cachedList(CallbackInfoReturnable<java.util.List<net.minecraft.client.network.PlayerListEntry>> cir) {
		java.util.List<?> cached = net.veloclient.velo.client.util.TabListCache.entries();
		if (cached != null) {
			cir.setReturnValue((java.util.List<net.minecraft.client.network.PlayerListEntry>) cached);
		}
	}

	@Inject(method = "collectPlayerEntries", at = @At("RETURN"), require = 0)
	private void velo$storeList(CallbackInfoReturnable<java.util.List<net.minecraft.client.network.PlayerListEntry>> cir) {
		net.veloclient.velo.client.util.TabListCache.storeEntries(cir.getReturnValue());
	}

	@WrapOperation(method = "render", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/font/TextRenderer;getWidth(Lnet/minecraft/text/StringVisitable;)I"))
	private int velo$cachedWidth(net.minecraft.client.font.TextRenderer renderer, net.minecraft.text.StringVisitable text, Operation<Integer> original) {
		int known = net.veloclient.velo.client.util.TabListCache.width(text);
		if (known >= 0) {
			return known;
		}
		int width = original.call(renderer, text);
		net.veloclient.velo.client.util.TabListCache.storeWidth(text, width);
		return width;
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.components.PlayerTabOverlay.class)
public abstract class TabListCacheMixin {

	@Inject(method = "getPlayerInfos", at = @At("HEAD"), cancellable = true, require = 0)
	@SuppressWarnings("unchecked")
	private void velo$cachedList(CallbackInfoReturnable<java.util.List<net.minecraft.client.multiplayer.PlayerInfo>> cir) {
		java.util.List<?> cached = net.veloclient.velo.client.util.TabListCache.entries();
		if (cached != null) {
			cir.setReturnValue((java.util.List<net.minecraft.client.multiplayer.PlayerInfo>) cached);
		}
	}

	@Inject(method = "getPlayerInfos", at = @At("RETURN"), require = 0)
	private void velo$storeList(CallbackInfoReturnable<java.util.List<net.minecraft.client.multiplayer.PlayerInfo>> cir) {
		net.veloclient.velo.client.util.TabListCache.storeEntries(cir.getReturnValue());
	}

	@WrapOperation(method = "extractRenderState", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"))
	private int velo$cachedWidth(net.minecraft.client.gui.Font font, net.minecraft.network.chat.FormattedText text, Operation<Integer> original) {
		int known = net.veloclient.velo.client.util.TabListCache.width(text);
		if (known >= 0) {
			return known;
		}
		int width = original.call(font, text);
		net.veloclient.velo.client.util.TabListCache.storeWidth(text, width);
		return width;
	}
}
*///?}
