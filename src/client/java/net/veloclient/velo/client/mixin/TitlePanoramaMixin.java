package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
//?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
*///?}
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's rotating-cubemap panorama with {@link
 * TitleScreenTheme#drawPanorama}.
 *
 * <p>{@code TitleScreen.render()} calls {@code renderPanoramaBackground(...)}
 * directly (confirmed by reading the real decompiled bytecode) - a
 * completely different method from {@code renderBackground(...)}, which a
 * previous version of this mixin cancelled instead and which turns out to
 * never actually be invoked for the title screen at all, so that cancel was
 * a no-op the whole time (this is why the panorama previously never visibly
 * moved - vanilla's own cubemap kept drawing underneath, unaffected).
 * {@code renderPanoramaBackground} is declared on {@code Screen}, not
 * overridden by {@code TitleScreen} itself, so this targets {@code Screen}
 * directly (same reasoning as {@link ScreenAccessMixin}'s doc: a method not
 * declared on the exact mixin target class doesn't reliably attach) and
 * gates the replacement to only actually apply when the screen genuinely is
 * a {@code TitleScreen} at runtime - every other panorama-capable vanilla
 * screen keeps its own.
 */
//? if <26.1 {
@Mixin(Screen.class)
public abstract class TitlePanoramaMixin {

	@Inject(method = "renderPanoramaBackground", at = @At("HEAD"), cancellable = true)
	private void velo$panorama(DrawContext context, float delta, CallbackInfo ci) {
		if (!(((Object) this) instanceof TitleScreen self)) {
			return;
		}
		TitleScreenTheme.drawPanorama(context, self.width, self.height);
		ci.cancel();
	}
}
//?} else {
/*@Mixin(Screen.class)
public abstract class TitlePanoramaMixin {

	@Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
	private void velo$panorama(GuiGraphicsExtractor context, float delta, CallbackInfo ci) {
		if (!(((Object) this) instanceof TitleScreen self)) {
			return;
		}
		TitleScreenTheme.drawPanorama(context, self.width, self.height);
		ci.cancel();
	}
}
*///?}
