package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.gui.title.PauseMenu;
import net.veloclient.velo.client.gui.title.TitleMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The title and pause menus keep the real vanilla buttons (other mods anchor to them, clicks go through them) but paint
 * their own: vanilla never draws those buttons, whoever asks and whenever - they used to show through for a moment
 * when something drew the screen's widgets outside the menu's own render (hovering our buttons flashed the vanilla ones).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.widget.ClickableWidget.class)
public abstract class MenuWidgetDrawMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$skipRestyled(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (PauseMenu.paintsItself(this) || TitleMenu.paintsItself(this)) {
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.components.AbstractWidget.class)
public abstract class MenuWidgetDrawMixin {

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$skipRestyled(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (PauseMenu.paintsItself(this) || TitleMenu.paintsItself(this)) {
			ci.cancel();
		}
	}
}
*///?}
