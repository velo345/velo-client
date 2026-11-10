package net.veloclient.velo.dev.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.veloclient.velo.dev.tweaks.BetterTabListModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Better Tab List's close animation: the Hud only draws the tab list while Tab is held, so after you let go the
 * "held" answer stays true until the list has rolled back up (see {@link BetterTabListModule#holdOpen}).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.hud.InGameHud.class)
public abstract class BetterTabListHoldMixin {

	@ModifyExpressionValue(method = "renderPlayerList", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/KeyBinding;isPressed()Z"))
	private boolean velo$holdOpen(boolean down) {
		return BetterTabListModule.holdOpen(down);
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.gui.Gui.class)
public abstract class BetterTabListHoldMixin {

	@ModifyExpressionValue(method = "extractTabList", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
	private boolean velo$holdOpen(boolean down) {
		return BetterTabListModule.holdOpen(down);
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.gui.Hud.class)
public abstract class BetterTabListHoldMixin {

	@ModifyExpressionValue(method = "extractTabList", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
	private boolean velo$holdOpen(boolean down) {
		return BetterTabListModule.holdOpen(down);
	}
}
*///?}
