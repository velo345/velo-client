package net.veloclient.legacy.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.GameGui;
import net.veloclient.legacy.dev.LegacyTour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Dev tour: shows the tab list in singleplayer without holding the key. */
@Mixin(GameGui.class)
public abstract class TourGameGuiMixin {

	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/options/KeyBinding;isPressed()Z"))
	private boolean velo$tabKey(boolean pressed) {
		return pressed || LegacyTour.forceTab;
	}

	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isIntegratedServerRunning()Z"))
	private boolean velo$integrated(boolean integrated) {
		return integrated && !LegacyTour.forceTab;
	}
}
